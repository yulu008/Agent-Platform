package com.luyu.agent.tenancy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.SessionController;
import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.service.AuthService;
import com.luyu.agent.service.JwtService;
import com.luyu.agent.service.MemoryService;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双租户端到端回归（tasks 6.1）：真实 H2 + 真实 spring-ai-session JDBC 仓库 +
 * 真实 Repository/Service/Controller 组件，模拟 JwtTenantFilter 的请求生命周期
 * （set → 业务 → finally clear）跑通两条完整业务线：
 * <ul>
 *   <li>主聊天：注册 → 登录 → JWT 验签 → 建会话 → 回合事件写入 → 列表隔离 → 跨租户删除 404 零副作用</li>
 *   <li>RPG：世界 → 角色卡 → 存档（绑定各自 session）→ 列表/主键跨租户互不可见</li>
 *   <li>记忆：按租户目录隔离（&lt;tid&gt;/memories/），跨租户读同名义返回不存在</li>
 * </ul>
 * LLM 流式环节不进测试（无真实模型）：回合事件按 advisor 落库形态直接写入
 * AI_SESSION_EVENT，归属断言与真实链路一致（同一张表、同一 session 键）。
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        RpgWorldSettingRepository.class, RpgCharacterCardRepository.class,
        RpgGameStateRepository.class})
class MultiTenantEndToEndTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @Autowired
    private RpgCharacterCardRepository cardRepo;

    @Autowired
    private RpgGameStateRepository stateRepo;

    @TempDir
    Path temp;

    private AuthService authService;
    private JwtService jwtService;
    private JdbcSessionRepository sessionRepository;
    private SessionService sessionService;
    private SessionTenantGuard guard;
    private SessionController sessionController;
    private MemoryService memoryService;

    @BeforeEach
    void setUp() throws Exception {
        // AI_SESSION 两表建表（jar 内置 schema，幂等；@JdbcTest 不含 spring-ai-session 自动配置）
        Resource schema = new DefaultResourceLoader()
                .getResource("classpath:org/springframework/ai/session/jdbc/schema-h2.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(schema);
        try (java.sql.Connection conn = dataSource.getConnection()) {
            populator.populate(conn);
            // 直连清理：兜住上一测试经独立事务提交的残留，保证每测干净起点
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("DELETE FROM AI_SESSION");
                st.execute("DELETE FROM app_user");
                st.execute("DELETE FROM tenant");
                st.execute("DELETE FROM rpg_world_setting");
            }
        }

        authService = new AuthService(jdbcTemplate, transactionManager);
        jwtService = new JwtService(new AuthProperties());
        // 真实 JDBC 会话仓库：守卫版 JdbcTemplate + H2 方言 + 测试事务管理器
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
        sessionController = new SessionController(sessionService, sessionRepository, guard);
        // 记忆根指向临时目录下 <tid>/memories（替换 TenantPaths 的 ~/.agent 层，隔离断言与生产同构）
        memoryService = new MemoryService() {
            @Override
            protected java.nio.file.Path memoriesDirOf() {
                return temp.resolve(TenantContext.requireTenantId()).resolve("memories");
            }
        };
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 辅助：请求生命周期与业务动作 ====================

    /** 模拟 JwtTenantFilter：验签通过后 set → 业务 → finally clear。 */
    private void runAs(AuthService.AuthUser user, Runnable action) {
        TenantContext.set(user.userId(), user.tenantId());
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private AuthService.AuthUser register(String email) {
        return authService.register(email, "password123");
    }

    /** 走真实 Controller 建会话（AI_SESSION.user_id = 当前租户）。 */
    private String createSession(AuthService.AuthUser user) {
        return runAndReturn(user, () -> sessionController.createSession().get("id"));
    }

    private <T> T runAndReturn(AuthService.AuthUser user, java.util.function.Supplier<T> action) {
        TenantContext.set(user.userId(), user.tenantId());
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** 一轮对话事件落库（user → assistant，与 SessionMemoryAdvisor 的写入形态一致）。 */
    private void appendTurn(String sessionId, String eventIdPrefix, String userText, String assistantText) {
        appendEvent(sessionId, eventIdPrefix + "-u", new UserMessage(userText));
        appendEvent(sessionId, eventIdPrefix + "-a", new AssistantMessage(assistantText));
    }

    private void appendEvent(String sessionId, String eventId, Message message) {
        sessionService.appendEvent(SessionEvent.builder()
                .id(eventId).sessionId(sessionId).message(message)
                .timestamp(Instant.now()).build());
    }

    private static WorldSetting world(String id, String name) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName(name);
        w.setSettingDesc("desc");
        return w;
    }

    private static CharacterCard card(String id, String worldId, String name) {
        CharacterCard c = new CharacterCard();
        c.setId(id);
        c.setWorldId(worldId);
        c.setName(name);
        c.setType("player");
        return c;
    }

    private static GameState gameState(String id, String sessionId, String worldId, String playerCharId) {
        GameState gs = new GameState();
        gs.setId(id);
        gs.setSessionId(sessionId);
        gs.setWorldId(worldId);
        gs.setPlayerCharId(playerCharId);
        gs.setCurrentLocation("老地方");
        gs.setTurnCount(0);
        return gs;
    }

    /** 带 frontmatter 的记忆文件内容（与 MemoryFileStore 约定一致）。 */
    private static String memoryFile(String name, String description, String content) {
        return "---\nname: " + name + "\ndescription: " + description + "\ntype: user\n---\n" + content + "\n";
    }

    private void writeMemory(String tenantId, String relative, String content) throws Exception {
        Path file = temp.resolve(tenantId).resolve("memories").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ==================== 场景 1：注册 → 登录 → JWT ====================

    @Test
    void 双租户注册登录与JWT验签链路() {
        AuthService.AuthUser a = register("alice@example.com");
        AuthService.AuthUser b = register("bob@example.com");

        // 两个租户身份独立
        assertThat(a.tenantId()).isNotEqualTo(b.tenantId());
        // 登录取回同一身份（签发 JWT 的输入）
        assertThat(authService.login("alice@example.com", "password123").userId()).isEqualTo(a.userId());

        // 签发 → 验签往返（JwtTenantFilter 生命周期入口）
        String token = jwtService.issue(a.userId(), a.tenantId());
        JwtService.Claims claims = jwtService.verify(token);
        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo(a.userId());
        assertThat(claims.tenantId()).isEqualTo(a.tenantId());

        // 篡改签名 → 验签失败（过滤器按未认证处理）
        String[] parts = token.split("\\.");
        assertThat(jwtService.verify(parts[0] + "." + parts[1] + ".AAAA")).isNull();
    }

    // ==================== 场景 2：主聊天会话与回合事件归属 ====================

    @Test
    void 主聊天会话与回合事件按租户归属互不干扰() {
        AuthService.AuthUser a = register("alice@example.com");
        AuthService.AuthUser b = register("bob@example.com");

        String sessionA = createSession(a);
        String sessionB = createSession(b);

        // 各自的流式回合事件落到各自会话（AI_SESSION_EVENT 以 session 键归属；事件 ID 全局唯一）
        runAs(a, () -> appendTurn(sessionA, "a1", "A的玩家消息", "A的GM回复"));
        runAs(b, () -> appendTurn(sessionB, "b1", "B的玩家消息", "B的GM回复"));

        runAs(a, () -> {
            // 列表（Controller 真实调用）：只含本租户会话
            assertThat(sessionController.listSessions())
                    .extracting(m -> m.get("id")).containsExactly(sessionA);
            // 会话归属与事件内容正确
            assertThat(sessionRepository.findById(sessionA).userId()).isEqualTo(a.tenantId());
            List<SessionEvent> events = sessionService.getEvents(sessionA);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getMessage().getText()).isEqualTo("A的玩家消息");
            assertThat(events.get(1).getMessage().getText()).isEqualTo("A的GM回复");
        });
        runAs(b, () -> {
            assertThat(sessionController.listSessions())
                    .extracting(m -> m.get("id")).containsExactly(sessionB);
            assertThat(sessionService.getEvents(sessionB)).hasSize(2);
        });

        // 跨租户删除 / 清空：Controller 统一 404 且零副作用
        runAs(b, () -> {
            assertThat(guard.isForeign(sessionA)).isTrue();
            assertThat(sessionController.deleteSession(sessionA).getStatusCode().value()).isEqualTo(404);
            assertThat(sessionController.clearMessages(sessionA).getStatusCode().value()).isEqualTo(404);
        });

        // A 的会话与事件原封不动
        runAs(a, () -> {
            assertThat(guard.isOwned(sessionA)).isTrue();
            assertThat(sessionService.getEvents(sessionA)).hasSize(2);
        });

        // 本租户删除正常生效
        runAs(a, () ->
                assertThat(sessionController.deleteSession(sessionA).getStatusCode().value()).isEqualTo(204));
        runAs(a, () -> assertThat(sessionRepository.findById(sessionA)).isNull());
    }

    // ==================== 场景 3：RPG 新开冒险（世界/角色/存档）归属 ====================

    @Test
    void RPG世界角色与存档按租户隔离() {
        AuthService.AuthUser a = register("alice@example.com");
        AuthService.AuthUser b = register("bob@example.com");

        // 各自"新开冒险"：世界 → 玩家角色卡 → 存档（绑定各自会话）
        runAs(a, () -> {
            worldRepo.save(world("w-a", "租户A的世界"));
            cardRepo.save(card("pc-a", "w-a", "阿尔托"));
        });
        runAs(b, () -> {
            worldRepo.save(world("w-b", "租户B的世界"));
            cardRepo.save(card("pc-b", "w-b", "波波"));
        });
        String sessionA = createSession(a);
        String sessionB = createSession(b);
        runAs(a, () -> stateRepo.save(gameState("gs-a", sessionA, "w-a", "pc-a")));
        runAs(b, () -> stateRepo.save(gameState("gs-b", sessionB, "w-b", "pc-b")));

        runAs(a, () -> {
            assertThat(worldRepo.findAll()).extracting(WorldSetting::getId).containsExactly("w-a");
            assertThat(cardRepo.findByWorldId("w-a")).extracting(CharacterCard::getId).containsExactly("pc-a");
            // 跨租户主键访问表现为不存在
            assertThat(worldRepo.findById("w-b")).isNull();
            assertThat(stateRepo.findById("gs-b")).isNull();
            // 本租户存档可按会话定位（回溯/读档入口）
            assertThat(stateRepo.findLatestBySessionId(sessionA).getId()).isEqualTo("gs-a");
        });
        runAs(b, () -> {
            assertThat(worldRepo.findAll()).extracting(WorldSetting::getId).containsExactly("w-b");
            assertThat(worldRepo.findById("w-a")).isNull();
            assertThat(stateRepo.findById("gs-a")).isNull();
        });
    }

    // ==================== 场景 4：记忆文件按租户目录隔离 ====================

    @Test
    void 记忆文件按租户目录隔离() throws Exception {
        AuthService.AuthUser a = register("alice@example.com");
        AuthService.AuthUser b = register("bob@example.com");

        writeMemory(a.tenantId(), "user/profile.md", memoryFile("A的记忆", "A的画像", "A的秘密"));
        writeMemory(b.tenantId(), "user/profile.md", memoryFile("B的记忆", "B的画像", "B的秘密"));

        runAs(a, () -> {
            assertThat(memoryService.listMemories())
                    .extracting(m -> m.get("name")).containsExactly("A的记忆");
            assertThat(memoryService.readMemory("user/profile.md").get("content")).contains("A的秘密");
        });
        runAs(b, () -> {
            assertThat(memoryService.listMemories())
                    .extracting(m -> m.get("name")).containsExactly("B的记忆");
            // 同名文件在 B 的根下是自己的，绝无串读
            assertThat(memoryService.readMemory("user/profile.md").get("content")).contains("B的秘密");
        });
    }
}
