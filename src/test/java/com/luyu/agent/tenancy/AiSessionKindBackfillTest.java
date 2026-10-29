package com.luyu.agent.tenancy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.DefaultSessionService;
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
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiSessionKindBackfill} 存量回填单测（separate-rpg-chat-sessions tasks 4.3）。
 * <p>
 * 真实 H2 + 真实 spring-ai-session JDBC 仓库（MultiTenantEndToEndTest 同款装配），
 * 按 design D5 钉住三分类处置与幂等：
 * <ol>
 *   <li>无 kind 且被 rpg_game_state.session_id 引用 → merge 补 {@code kind="rpg"}；</li>
 *   <li>无 title、不被引用、事件数 0 → 幽灵壳删除（对齐删档删壳语义）；</li>
 *   <li>有 title → chat 会话不动；已带 kind → 跳过。</li>
 * </ol>
 * 重跑零变更（幂等可重入）；引用集查询异常仅告警不阻断启动。
 */
@JdbcTest
@Import(RpgSchemaInitializer.class)
class AiSessionKindBackfillTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionService sessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        // AI_SESSION 两表建表（jar 内置 schema，幂等；@JdbcTest 不含 spring-ai-session 自动配置）
        Resource schema = new DefaultResourceLoader()
                .getResource("classpath:org/springframework/ai/session/jdbc/schema-h2.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(schema);
        try (java.sql.Connection conn = dataSource.getConnection()) {
            populator.populate(conn);
            // 直连清场：兜住上一测试经独立事务提交的残留，保证每测干净起点
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("DELETE FROM AI_SESSION_EVENT");
                st.execute("DELETE FROM AI_SESSION");
                st.execute("DELETE FROM rpg_game_state");
            }
        }
        sessionService = DefaultSessionService.builder()
                .sessionRepository(JdbcSessionRepository.builder()
                        .jdbcTemplate(jdbcTemplate)
                        .dialect(new H2JdbcSessionRepositoryDialect())
                        .transactionManager(transactionManager)
                        .jsonMapper(JsonMapper.builder().build())
                        .build())
                .build();
    }

    // ==================== 三分类 + 幂等 ====================

    @Test
    void 三分类回填_补标删幽灵不动chat且重跑零变更() throws Exception {
        // ① 老 RPG 会话：无 kind，被 GameState 引用 → merge 补标
        createSession("s-old-rpg", null);
        insertGameState("gs-1", "s-old-rpg");
        // ② 幽灵壳：无 title、不被引用、事件数 0 → 删除
        createSession("s-ghost", null);
        // ③ chat 会话：有 title → 不动
        createSession("s-chat", Map.of("title", "新对话"));
        // ④ 已打标 RPG：跳过
        createSession("s-tagged", Map.of("kind", "rpg"));

        // 裸 JdbcTemplate 自建绕租户守卫：与生产同构，注入 DataSource 构造
        AiSessionKindBackfill backfill = new AiSessionKindBackfill(dataSource);
        backfill.backfill();

        // ① 引用即活跃 RPG：补 kind
        assertThat(metadataOf("s-old-rpg")).containsEntry("kind", "rpg");
        // ② 删档残留幽灵壳：连壳删除
        assertThat(sessionCount("s-ghost")).isZero();
        // ③ chat 会话：title 保留、不被误写 kind
        assertThat(metadataOf("s-chat"))
                .containsEntry("title", "新对话")
                .doesNotContainKey("kind");
        // ④ 已打标：原样
        assertThat(metadataOf("s-tagged")).containsEntry("kind", "rpg");

        // 幂等（tasks 4.3 重跑零变更）：补标后归入"已打标跳过"、幽灵已删、chat 依旧不动
        backfill.backfill();
        assertThat(metadataOf("s-old-rpg")).containsEntry("kind", "rpg");
        assertThat(sessionCount("s-ghost")).isZero();
        assertThat(metadataOf("s-chat")).doesNotContainKey("kind");
        assertThat(sessionCount("s-chat")).isEqualTo(1);
    }

    @Test
    void 无title有事件不被引用的会话防御性不动() throws Exception {
        // 理论不存在的形态（RPG 局在引用集合内、chat 会话有 title）：不猜、不删
        createSession("s-defensive", null);
        appendEvent("s-defensive");

        new AiSessionKindBackfill(dataSource).backfill();

        assertThat(sessionCount("s-defensive")).isEqualTo(1);
        assertThat(metadataOf("s-defensive")).doesNotContainKey("kind");
    }

    @Test
    void 回填异常不阻断启动() throws Exception {
        // DataSource 层面失败（裸模板首查即 getConnection）：仅记 error 不抛出
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new RuntimeException("db down"));
        AiSessionKindBackfill backfill = new AiSessionKindBackfill(failing);

        assertThatCode(backfill::backfill).doesNotThrowAnyException();
    }

    // ==================== 数据装配辅助 ====================

    private void createSession(String id, Map<String, Object> metadata) {
        CreateSessionRequest.Builder builder = CreateSessionRequest.builder()
                .id(id)
                .userId("t-backfill");
        if (metadata != null && !metadata.isEmpty()) {
            builder.metadata(metadata);
        }
        sessionService.create(builder.build());
    }

    /** 与 RpgGameStateRepository.save 同列集合的原生插入（回填引用集合的读取源）；
     *  先插 world/card 满足 FK_GAME_WORLD / FK_GAME_PLAYER 外键链 */
    private void insertGameState(String gsId, String sessionId) {
        jdbcTemplate.update(
                "INSERT INTO rpg_world_setting (id, name) VALUES (?, ?)",
                "w-1", "回填测试世界");
        jdbcTemplate.update(
                "INSERT INTO rpg_character_card (id, world_id, name, type) VALUES (?, ?, ?, ?)",
                "c-1", "w-1", "玩家", "player");
        jdbcTemplate.update(
                "INSERT INTO rpg_game_state (id, tenant_id, session_id, world_id, player_char_id, "
                        + "current_location, turn_count, flags, npc_states, player_states) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                gsId, "t-backfill", sessionId, "w-1", "c-1", null, 0, "{}", "{}", "{}");
    }

    private void appendEvent(String sessionId) {
        sessionService.appendEvent(SessionEvent.builder()
                .id("evt-" + sessionId)
                .sessionId(sessionId)
                .message(new UserMessage("消息"))
                .timestamp(Instant.now())
                .build());
    }

    private int sessionCount(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_SESSION WHERE id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    /** metadata 列（CLOB）读回为 Map；空/缺省归一化为空 Map */
    private Map<String, Object> metadataOf(String sessionId) throws Exception {
        String meta = jdbcTemplate.query(
                "SELECT metadata FROM AI_SESSION WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, sessionId);
        if (meta == null || meta.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(meta, new TypeReference<Map<String, Object>>() {});
    }
}
