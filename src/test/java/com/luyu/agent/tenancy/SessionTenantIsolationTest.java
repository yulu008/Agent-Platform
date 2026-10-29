package com.luyu.agent.tenancy;

import com.luyu.agent.controller.ChatStreamController;
import com.luyu.agent.controller.CompactionController;
import com.luyu.agent.controller.SessionController;
import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.controller.RpgGameController;
import com.luyu.agent.rpg.engine.GameLoopService;
import com.luyu.agent.rpg.engine.OpeningNarrationService;
import com.luyu.agent.rpg.engine.RollbackService;
import com.luyu.agent.rpg.engine.RpgHistoryCleaner;
import com.luyu.agent.rpg.engine.SaveDeletionService;
import com.luyu.agent.rpg.engine.StateDeltaSanitizer;
import com.luyu.agent.rpg.engine.TurnGuardService;
import com.luyu.agent.rpg.eval.service.PromptHashService;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.rpg.story.engine.StoryRunEngine;
import com.luyu.agent.service.ContextInfoService;
import com.luyu.agent.service.ImageUploadService;
import com.luyu.agent.service.SessionTitleGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话租户隔离集成测试（tasks 5.4，session-isolation spec 三条 Requirement 的对账）：
 * <ol>
 *   <li><b>会话归属租户</b>：创建携带租户 ID（AI_SESSION.user_id）、列表只查本租户。</li>
 *   <li><b>跨租户会话访问不可达</b>：删除 / 回溯 / 历史 / 发消息跨租户统一 404（流式为 error 事件）
 *       且零副作用（底层 service 从未被调用）。</li>
 *   <li><b>存量会话归入保留租户</b>：AiSessionTenantBackfill 幂等回填 default-user → default。</li>
 * </ol>
 * 另钉住 {@link SessionTenantGuard} 的双语义（isOwned / isForeign）——
 * 会话不存在时两者皆 false（advisor 自动建会话是合法路径）。
 */
class SessionTenantIsolationTest {

    private static final String SESSION_ID = "sess-1";

    private SessionRepository sessionRepository;
    private SessionTenantGuard guard;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(SessionRepository.class);
        guard = new SessionTenantGuard(sessionRepository);
        TenantContext.set("u-a", "t-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Session sessionOf(String userId) {
        Session session = mock(Session.class);
        when(session.userId()).thenReturn(userId);
        when(session.id()).thenReturn(SESSION_ID);
        when(session.createdAt()).thenReturn(Instant.now());
        return session;
    }

    /** 带自定义 id 与 metadata 的壳 mock（kind 隔离断言用） */
    private Session sessionOf(String id, String userId, Map<String, Object> metadata) {
        Session session = sessionOf(userId);
        when(session.id()).thenReturn(id);
        when(session.metadata()).thenReturn(metadata);
        return session;
    }

    // ==================== Requirement 1: 会话归属租户 ====================

    @Test
    void 新建会话携带当前租户ID() {
        SessionService sessionService = mock(SessionService.class);
        Session created = sessionOf("t-a");
        when(sessionService.create(any(CreateSessionRequest.class))).thenReturn(created);
        SessionController controller = new SessionController(sessionService, sessionRepository, guard);

        Map<String, String> result = controller.createSession();

        ArgumentCaptor<CreateSessionRequest> captor = ArgumentCaptor.forClass(CreateSessionRequest.class);
        verify(sessionService).create(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo("t-a");
        assertThat(result).containsEntry("id", SESSION_ID);
    }

    @Test
    void 会话列表只查询当前租户() {
        Session own = sessionOf("t-a");
        when(sessionRepository.findByUserId("t-a")).thenReturn(List.of(own));
        SessionController controller = new SessionController(mock(SessionService.class), sessionRepository, guard);

        List<Map<String, Object>> result = controller.listSessions();

        // 查询键 = 租户 ID（t-b 的会话天然不在 findByUserId("t-a") 结果内）
        verify(sessionRepository).findByUserId("t-a");
        verify(sessionRepository, never()).findByUserId("t-b");
        assertThat(result).hasSize(1);
    }

    @Test
    void 会话列表过滤kindRpg的RPG会话() {
        Session chat = sessionOf("sess-chat", "t-a", Map.of("title", "新对话"));
        Session rpg = sessionOf("sess-rpg", "t-a", Map.of("kind", "rpg"));
        // metadata 为 null 的存量理论态：不属于 RPG，照常返回
        Session legacy = sessionOf("sess-legacy", "t-a", null);
        when(sessionRepository.findByUserId("t-a")).thenReturn(List.of(chat, rpg, legacy));
        SessionController controller = new SessionController(mock(SessionService.class), sessionRepository, guard);

        List<Map<String, Object>> result = controller.listSessions();

        // kind=rpg 被滤除；chat 与无 metadata 会话照常返回（title 逻辑不变）
        assertThat(result).extracting(m -> m.get("id"))
                .containsExactlyInAnyOrder("sess-chat", "sess-legacy");
    }

    // ==================== Requirement 2: 跨租户会话访问不可达 ====================

    @Test
    void 跨租户删除会话返回404且零副作用() {
        Session foreign = sessionOf("t-b");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(foreign);
        SessionService sessionService = mock(SessionService.class);
        SessionController controller = new SessionController(sessionService, sessionRepository, guard);

        ResponseEntity<Void> resp = controller.deleteSession(SESSION_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sessionService, never()).delete(anyString());
    }

    @Test
    void 跨租户清空会话消息返回404且零副作用() {
        Session foreign = sessionOf("t-b");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(foreign);
        SessionService sessionService = mock(SessionService.class);
        SessionController controller = new SessionController(sessionService, sessionRepository, guard);

        ResponseEntity<Void> resp = controller.clearMessages(SESSION_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sessionService, never()).delete(anyString());
    }

    @Test
    void 跨租户回溯返回404且零副作用() {
        Session foreign = sessionOf("t-b");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(foreign);
        RollbackService rollbackService = mock(RollbackService.class);
        RpgGameController controller = newRpgController(rollbackService);

        ResponseEntity<Map<String, Object>> resp = controller.backtrack(Map.of(
                "sessionId", SESSION_ID, "gameStateId", "gs-1", "anchorEventId", "u9"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(rollbackService, never()).backtrack(anyString(), anyString(), anyString());
    }

    @Test
    void 跨租户历史查询返回404() {
        Session foreign = sessionOf("t-b");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(foreign);
        ChatControllerHolder holder = newChatController();

        try {
            holder.controller().getHistory(SESSION_ID);
            throw new AssertionError("跨租户历史访问必须抛 404");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        verify(holder.sessionService(), never()).getEvents(anyString());
    }

    @Test
    void 跨租户发消息返回错误事件且零副作用() {
        Session foreign = sessionOf("t-b");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(foreign);
        ChatControllerHolder holder = newChatController();

        ServerSentEvent<Object> event = holder.controller().streamChat(
                Map.of("message", "你好", "sessionId", SESSION_ID), null).blockFirst();

        assertThat(event.data())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("error", "会话不存在或无权访问");
        verify(holder.sessionService(), never()).getEvents(anyString());
    }

    @Test
    void 跨租户RPG回合返回错误事件且零副作用() {
        Session foreign = sessionOf("t-b");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(foreign);
        GameLoopService gameLoop = mock(GameLoopService.class);
        ContentModerationGate moderationGate = mock(ContentModerationGate.class);
        when(moderationGate.check(any())).thenReturn(ModerationResult.pass());
        RpgGameController controller = new RpgGameController(mock(ChatClientRegistry.class),
                gameLoop, mock(OpeningNarrationService.class), mock(StateDeltaSanitizer.class),
                mock(WorkshopService.class), mock(SessionService.class), mock(RpgHistoryCleaner.class),
                mock(RollbackService.class), new TurnGuardService(),
                mock(SaveDeletionService.class), guard, moderationGate, mock(QuotaGuard.class),
                mock(RpgEventLogRepository.class), mock(StoryRunEngine.class), mock(PromptHashService.class));

        ServerSentEvent<Object> event = controller.gameTurn(Map.of(
                "gameStateId", "gs-1", "sessionId", SESSION_ID, "message", "我走进客栈")).blockFirst();

        assertThat(event.data())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("error", "会话不存在或无权访问");
        verify(gameLoop, never()).prepareTurn(anyString(), anyString());
    }

    @Test
    void 跨租户压缩返回404且零副作用() {
        Session foreign = sessionOf("t-b");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(foreign);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.forRole("compaction")).thenReturn(mock(org.springframework.ai.chat.client.ChatClient.class));
        SessionService sessionService = mock(SessionService.class);
        CompactionController controller = new CompactionController(sessionService, registry, guard);

        ResponseEntity<Map<String, Object>> resp = controller.compactSession(SESSION_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sessionService, never()).compact(anyString(), any(), any());
    }

    @Test
    void 本租户RPG会话历史查询返回404且零副作用() {
        Session rpg = sessionOf(SESSION_ID, "t-a", Map.of("kind", "rpg"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(rpg);
        ChatControllerHolder holder = newChatController();

        try {
            holder.controller().getHistory(SESSION_ID);
            throw new AssertionError("RPG 会话历史访问必须抛 404");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        // 原始事件流（工具调用、state_delta）绝不从主会话端点暴露
        verify(holder.sessionService(), never()).getEvents(anyString());
    }

    @Test
    void 本租户RPG会话压缩返回404且零副作用() {
        Session rpg = sessionOf(SESSION_ID, "t-a", Map.of("kind", "rpg"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(rpg);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        when(registry.forRole("compaction")).thenReturn(mock(org.springframework.ai.chat.client.ChatClient.class));
        SessionService compactionSessionService = mock(SessionService.class);
        CompactionController controller = new CompactionController(compactionSessionService, registry, guard);

        ResponseEntity<Map<String, Object>> resp = controller.compactSession(SESSION_ID);

        // chat 递归摘要会向 RPG 会话写 synthetic 事件，破坏其零合成事件契约 → 拒绝
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(compactionSessionService, never()).compact(anyString(), any(), any());
    }

    @Test
    void RPG会话的contextInfo走白名单放行() {
        Session rpg = sessionOf(SESSION_ID, "t-a", Map.of("kind", "rpg"));
        when(sessionRepository.findById(SESSION_ID)).thenReturn(rpg);
        ContextInfoService contextInfoService = mock(ContextInfoService.class);
        ChatStreamController controller = new ChatStreamController(
                mock(ChatClientRegistry.class), mock(SessionService.class),
                mock(SessionTitleGenerator.class), contextInfoService,
                mock(ImageUploadService.class), guard,
                mock(ContentModerationGate.class), mock(QuotaGuard.class));

        controller.getContextInfo(SESSION_ID);

        // 白名单（rpg/game.js 上下文条依赖 /chat/context-info）：isRpg 不拦截
        verify(contextInfoService).getContextInfo(SESSION_ID);
    }

    // ==================== Requirement 3: 存量会话归入保留租户 ====================

    @Test
    void 存量会话回填defaultUser到default租户且幂等可重跑() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(
                eq("UPDATE AI_SESSION SET user_id = ? WHERE user_id = ?"),
                eq(TenantPaths.DEFAULT_TENANT), eq(AiSessionTenantBackfill.LEGACY_USER_ID)))
                .thenReturn(3);
        AiSessionTenantBackfill backfill = new AiSessionTenantBackfill(jdbcTemplate);

        backfill.backfill();
        backfill.backfill(); // 重跑：WHERE 条件天然幂等

        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(
                eq("UPDATE AI_SESSION SET user_id = ? WHERE user_id = ?"),
                eq(TenantPaths.DEFAULT_TENANT), eq(AiSessionTenantBackfill.LEGACY_USER_ID));
    }

    @Test
    void 回填失败不阻断启动() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("table not found"));
        AiSessionTenantBackfill backfill = new AiSessionTenantBackfill(jdbcTemplate);

        assertThatCode(backfill::backfill).doesNotThrowAnyException();
    }

    // ==================== SessionTenantGuard 双语义 ====================

    @Test
    void 守卫双语义归属与外来判定() {
        Session own = sessionOf("t-a");
        when(sessionRepository.findById(SESSION_ID)).thenReturn(own);

        assertThat(guard.isOwned(SESSION_ID)).isTrue();
        assertThat(guard.isForeign(SESSION_ID)).isFalse();

        TenantContext.set("u-b", "t-b");
        assertThat(guard.isOwned(SESSION_ID)).isFalse();
        assertThat(guard.isForeign(SESSION_ID)).isTrue();
    }

    @Test
    void 会话不存在时空与白名单均为合法态() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(null);
        when(sessionRepository.findById("")).thenReturn(null);

        // 不存在：advisor 自动建会话路径，两个语义都放行
        assertThat(guard.isOwned(SESSION_ID)).isFalse();
        assertThat(guard.isForeign(SESSION_ID)).isFalse();
        assertThat(guard.isForeign("")).isFalse();
        assertThat(guard.isForeign(null)).isFalse();
    }

    @Test
    void 守卫查询异常按不存在处理() {
        when(sessionRepository.findById(SESSION_ID)).thenThrow(new RuntimeException("db down"));

        assertThat(guard.isOwned(SESSION_ID)).isFalse();
        assertThat(guard.isForeign(SESSION_ID)).isFalse();
    }

    @Test
    void isRpg按metadataKind判定且容错() {
        // mock 构造与 when 语句分离：thenReturn 参数位内嵌套 when 会触发 UnfinishedStubbing
        Session rpg = sessionOf(SESSION_ID, "t-a", Map.of("kind", "rpg"));
        Session chat = sessionOf(SESSION_ID, "t-a", Map.of("kind", "chat"));
        Session untagged = sessionOf(SESSION_ID, "t-a", null);

        when(sessionRepository.findById(SESSION_ID)).thenReturn(rpg);
        assertThat(guard.isRpg(SESSION_ID)).isTrue();

        when(sessionRepository.findById(SESSION_ID)).thenReturn(chat);
        assertThat(guard.isRpg(SESSION_ID)).isFalse();

        // metadata null（老壳未打标）不拦截，由回填治理
        when(sessionRepository.findById(SESSION_ID)).thenReturn(untagged);
        assertThat(guard.isRpg(SESSION_ID)).isFalse();

        when(sessionRepository.findById(SESSION_ID)).thenReturn(null);
        assertThat(guard.isRpg(SESSION_ID)).isFalse();

        // 查询失败按非 RPG 容错降级不阻断
        when(sessionRepository.findById(SESSION_ID)).thenThrow(new RuntimeException("db down"));
        assertThat(guard.isRpg(SESSION_ID)).isFalse();
    }

    // ==================== 构造辅助 ====================

    /** 持有 mock sessionService 的 ChatStreamController（供 verify 零副作用断言复用）。 */
    private record ChatControllerHolder(ChatStreamController controller, SessionService sessionService) {
    }

    private ChatControllerHolder newChatController() {
        SessionService sessionService = mock(SessionService.class);
        ContentModerationGate moderationGate = mock(ContentModerationGate.class);
        when(moderationGate.check(any())).thenReturn(ModerationResult.pass());
        ChatStreamController controller = new ChatStreamController(
                mock(ChatClientRegistry.class), sessionService,
                mock(SessionTitleGenerator.class), mock(ContextInfoService.class),
                mock(ImageUploadService.class), guard, moderationGate, mock(QuotaGuard.class));
        return new ChatControllerHolder(controller, sessionService);
    }

    private RpgGameController newRpgController(RollbackService rollbackService) {
        ContentModerationGate moderationGate = mock(ContentModerationGate.class);
        when(moderationGate.check(any())).thenReturn(ModerationResult.pass());
        return new RpgGameController(mock(ChatClientRegistry.class),
                mock(GameLoopService.class), mock(OpeningNarrationService.class),
                mock(StateDeltaSanitizer.class), mock(WorkshopService.class),
                mock(SessionService.class), mock(RpgHistoryCleaner.class),
                rollbackService, new TurnGuardService(), mock(SaveDeletionService.class), guard,
                moderationGate, mock(QuotaGuard.class), mock(RpgEventLogRepository.class),
                mock(StoryRunEngine.class), mock(PromptHashService.class));
    }
}
