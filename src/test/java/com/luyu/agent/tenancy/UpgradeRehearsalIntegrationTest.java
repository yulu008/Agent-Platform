package com.luyu.agent.tenancy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.jdbc.H2JdbcSessionRepositoryDialect;
import org.springframework.ai.session.jdbc.JdbcSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.service.AuthService;
import com.luyu.agent.service.MemoryService;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 升级演练（tasks 6.2）：在"存量库"（升级前形态的数据）上模拟升级部署后的启动链——
 * <ol>
 *   <li>schema-h2.sql 幂等段重跑（ALTER ADD COLUMN + 存量行回填 default 租户）</li>
 *   <li>AiSessionTenantBackfill 存量会话回填（default-user → default）</li>
 *   <li>TenantFileMigration 存量文件搬迁（~/.agent/memories → tenants/default/memories）</li>
 * </ol>
 * 验收：default 租户登录后功能与升级前等价（存量会话可见、可继续对话、存量世界可见、
 * 存量记忆可读）；新租户对存量数据是跨租户不可见；全部步骤重跑幂等。
 * <p>
 * 存量 RPG 行的写入走直连 Connection：运行时守卫拦截的是新代码路径，
 * 存量数据来自升级前的库文件（与 DDL 走 populator 直连同理由）。
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        RpgWorldSettingRepository.class})
class UpgradeRehearsalIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @TempDir
    Path temp;

    private AuthService authService;
    private AiSessionTenantBackfill backfill;
    private TenantFileMigration migration;
    private JdbcSessionRepository sessionRepository;
    private SessionService sessionService;
    private SessionTenantGuard guard;
    private MemoryService memoryService;
    private Path legacyMemories;
    private Path legacyRpgSaves;
    private Path tenantsRoot;

    @BeforeEach
    void setUp() throws Exception {
        // AI_SESSION 两表建表（jar 内置 schema，幂等）
        Resource schema = new DefaultResourceLoader()
                .getResource("classpath:org/springframework/ai/session/jdbc/schema-h2.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(schema);
        try (Connection conn = dataSource.getConnection()) {
            populator.populate(conn);
            // 直连清理：兜住上一测试经独立事务提交的残留，保证每测干净起点
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM AI_SESSION");
                st.execute("DELETE FROM app_user");
                st.execute("DELETE FROM tenant");
                st.execute("DELETE FROM rpg_world_setting");
            }
        }

        authService = new AuthService(jdbcTemplate, transactionManager);
        backfill = new AiSessionTenantBackfill(jdbcTemplate);
        sessionRepository = JdbcSessionRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .dialect(new H2JdbcSessionRepositoryDialect())
                .transactionManager(transactionManager)
                .jsonMapper(JsonMapper.builder().build())
                .build();
        sessionService = DefaultSessionService.builder()
                .sessionRepository(sessionRepository)
                .build();
        guard = new SessionTenantGuard(sessionRepository);

        // 迁移源与目标都指向临时目录（TenantFileMigration 测试构造器）
        legacyMemories = temp.resolve("legacy-memories");
        legacyRpgSaves = temp.resolve("legacy-rpg-saves");
        tenantsRoot = temp.resolve("tenants");
        migration = new TenantFileMigration(legacyMemories, legacyRpgSaves,
                tenantsRoot.resolve(TenantPaths.DEFAULT_TENANT));

        // 记忆根指向搬迁后的租户目录（<tid>/memories，与 TenantPaths 层级同构）
        memoryService = new MemoryService() {
            @Override
            protected Path memoriesDirOf() {
                return tenantsRoot.resolve(TenantContext.requireTenantId()).resolve("memories");
            }
        };
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 存量库造数与"升级后启动"模拟 ====================

    /** 升级前的库形态：会话 user_id='default-user'、RPG 行无租户列数据、记忆在旧目录。 */
    private void seedLegacyDb() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO AI_SESSION (id, user_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                "legacy-session", AiSessionTenantBackfill.LEGACY_USER_ID);
        jdbcTemplate.update(
                "INSERT INTO AI_SESSION_EVENT (id, session_id, timestamp, message_type, message_content) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?)",
                "legacy-e1", "legacy-session", "USER", "存量的玩家消息");
        jdbcTemplate.update(
                "INSERT INTO AI_SESSION_EVENT (id, session_id, timestamp, message_type, message_content) "
                        + "VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?)",
                "legacy-e2", "legacy-session", "ASSISTANT", "存量的GM回复");

        // RPG 存量行（tenant_id NULL）直连写入：绕过守卫，等价于升级前的库文件内容
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO rpg_world_setting (id, name) VALUES (?, ?)")) {
            ps.setString(1, "legacy-world");
            ps.setString(2, "存量世界");
            ps.executeUpdate();
        }

        // 存量记忆文件（迁移源 ~/.agent/memories 的替身）
        Path file = legacyMemories.resolve("user").resolve("profile.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file,
                "---\nname: 存量记忆\ndescription: 升级前的全局记忆\ntype: user\n---\n存量内容\n");
    }

    /** 升级部署后的启动链（与生产组件一致）：DDL/回填脚本 + 会话回填 + 文件搬迁。 */
    private void simulateStartupOnUpgradedDb() throws Exception {
        Resource schema = new DefaultResourceLoader().getResource("classpath:rpg/schema-h2.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(true);
        populator.addScript(schema);
        try (Connection conn = dataSource.getConnection()) {
            populator.populate(conn);
        }
        backfill.backfill();
        migration.migrateAll();
    }

    private void runAsTenant(String userId, String tenantId, Runnable action) {
        TenantContext.set(userId, tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    // ==================== 演练主场景 ====================

    @Test
    void 存量库升级_default租户功能等价且全部步骤幂等() throws Exception {
        seedLegacyDb();

        // 升级部署后首次启动 + 立即重启一次（两连跑即幂等性演练）
        simulateStartupOnUpgradedDb();
        simulateStartupOnUpgradedDb();

        // ── default 租户登录后：功能与升级前等价 ──
        runAsTenant("u-legacy", TenantPaths.DEFAULT_TENANT, () -> {
            // 存量会话可见
            assertThat(sessionRepository.findByUserId(TenantPaths.DEFAULT_TENANT))
                    .extracting(Session::id).containsExactly("legacy-session");
            assertThat(guard.isOwned("legacy-session")).isTrue();
            // 存量历史事件完整
            assertThat(sessionService.getEvents("legacy-session")).hasSize(2);
            // 可继续对话：新事件写入并可见（升级前后行为等价的核心断言）
            sessionService.appendEvent(SessionEvent.builder()
                    .id("post-upgrade-e").sessionId("legacy-session")
                    .message(new UserMessage("升级后继续对话"))
                    .timestamp(Instant.now()).build());
            assertThat(sessionService.getEvents("legacy-session")).hasSize(3);
            // 存量世界归入 default 后可见
            assertThat(worldRepo.findAll())
                    .extracting(WorldSetting::getId).containsExactly("legacy-world");
            // 搬迁后的存量记忆可读
            assertThat(memoryService.listMemories())
                    .extracting(m -> m.get("name")).containsExactly("存量记忆");
            assertThat(memoryService.readMemory("user/profile.md").get("content")).contains("存量内容");
        });

        // ── 新租户对存量数据是跨租户（不可见）──
        AuthService.AuthUser newcomer = authService.register("newcomer@example.com", "password123");
        TenantContext.set(newcomer.userId(), newcomer.tenantId());
        try {
            assertThat(guard.isForeign("legacy-session")).isTrue();
            assertThat(sessionRepository.findByUserId(newcomer.tenantId())).isEmpty();
            assertThat(worldRepo.findAll()).isEmpty();
        } finally {
            TenantContext.clear();
        }

        // ── 幂等：第三次启动零变化 ──
        Integer defaultSessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_SESSION WHERE user_id = ?", Integer.class,
                TenantPaths.DEFAULT_TENANT);
        simulateStartupOnUpgradedDb();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_SESSION WHERE user_id = ?",
                Integer.class, AiSessionTenantBackfill.LEGACY_USER_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_SESSION WHERE user_id = ?", Integer.class,
                TenantPaths.DEFAULT_TENANT)).isEqualTo(defaultSessions);
        // 文件不被重复搬动，内容原样
        assertThat(Files.readString(tenantsRoot.resolve(
                TenantPaths.DEFAULT_TENANT + "/memories/user/profile.md"))).contains("存量内容");
        assertThat(Files.exists(legacyMemories)).isFalse();
    }
}
