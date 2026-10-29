package com.luyu.agent.rpg.controller;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.config.PostEmissionStreamException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.engine.GameLoopService;
import com.luyu.agent.rpg.engine.OpeningNarrationService;
import com.luyu.agent.rpg.engine.RollbackService;
import com.luyu.agent.rpg.engine.RpgHistoryCleaner;
import com.luyu.agent.rpg.engine.SaveDeletionService;
import com.luyu.agent.rpg.engine.StateDeltaSanitizer;
import com.luyu.agent.rpg.engine.TurnGuardService;
import com.luyu.agent.rpg.eval.service.PromptHashService;
import com.luyu.agent.rpg.model.EventLog;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.SaveCard;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.rpg.story.engine.StoryRunEngine;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.tenancy.SessionTenantGuard;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RpgGameController} 回溯相关端点的单元测试（纯 Mockito）。
 * <p>
 * 钉住：history 返回 eventId / currentTurn（4.1）、turn_persisted 尾包三路径（4.2/4.3）、
 * backtrack 端点的参数校验与错误映射（3.2）、图选项视图与选边接入回合流（4.5）、
 * 图上回溯端点（4.8：分叉点列表/回退成功与 400 归控）、
 * 多周目开局端点（4.9：重置成功与 400 归控）。
 */
class RpgGameControllerTest {

    private static final String SESSION_ID = "sess-1";
    private static final String GS_ID = "gs-1";

    private SessionService sessionService;
    private WorkshopService workshopService;
    private GameLoopService gameLoopService;
    private RollbackService rollbackService;
    private RpgHistoryCleaner rpgHistoryCleaner;
    private RpgEventLogRepository eventLogRepository;
    private StoryRunEngine storyRunEngine;
    private RpgGameController controller;

    /** 会话租户守卫 mock：默认 isForeign=false（既有用例均非跨租户场景） */
    private SessionTenantGuard sessionTenantGuard = mock(SessionTenantGuard.class);

    /** 内容审查闸门 mock：默认放行（既有用例均为良性输入） */
    private ContentModerationGate moderationGate = stubPassGate();

    private static ContentModerationGate stubPassGate() {
        ContentModerationGate gate = mock(ContentModerationGate.class);
        when(gate.check(any())).thenReturn(ModerationResult.pass());
        return gate;
    }

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        workshopService = mock(WorkshopService.class);
        gameLoopService = mock(GameLoopService.class);
        rollbackService = mock(RollbackService.class);
        rpgHistoryCleaner = mock(RpgHistoryCleaner.class);
        eventLogRepository = mock(RpgEventLogRepository.class);
        storyRunEngine = mock(StoryRunEngine.class);

        // cleaner 默认行为：无包裹标记时原样 trim 返回
        when(rpgHistoryCleaner.cleanUserMessage(anyString()))
                .thenAnswer(inv -> inv.getArgument(0) == null ? "" : ((String) inv.getArgument(0)).trim());
        when(rpgHistoryCleaner.cleanAssistantMessage(anyString()))
                .thenAnswer(inv -> inv.getArgument(0) == null ? "" : ((String) inv.getArgument(0)).trim());

        controller = new RpgGameController(
                mock(ChatClientRegistry.class),
                gameLoopService,
                mock(OpeningNarrationService.class),
                mock(StateDeltaSanitizer.class),
                workshopService,
                sessionService,
                rpgHistoryCleaner,
                rollbackService,
                new TurnGuardService(),
                mock(SaveDeletionService.class),
                sessionTenantGuard,
                moderationGate,
                mock(QuotaGuard.class),
                eventLogRepository, storyRunEngine, mock(PromptHashService.class));

        // gameTurn 在请求线程捕获租户身份（同生产环境 JwtTenantFilter 建立路径）
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static SessionEvent userEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new UserMessage(text)).timestamp(Instant.now()).build();
    }

    private static SessionEvent assistantEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new AssistantMessage(text)).timestamp(Instant.now()).build();
    }

    private static SessionEvent syntheticUserEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new UserMessage(text)).timestamp(Instant.now())
                .metadata(SessionEvent.METADATA_SYNTHETIC, true).build();
    }

    private static SessionEvent syntheticAssistantEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new AssistantMessage(text)).timestamp(Instant.now())
                .metadata(SessionEvent.METADATA_SYNTHETIC, true).build();
    }

    /** 带 toolCalls 的 assistant（GM 的 get_ 工具调用，不回放） */
    private static SessionEvent assistantEventWithToolCalls(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(AssistantMessage.builder().content(text)
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "get_state", "{}")))
                        .build())
                .timestamp(Instant.now()).build();
    }

    /** 工具响应事件（不回放） */
    private static SessionEvent toolResponseEvent(String id) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                "call-1", "get_state", "{}")))
                        .build())
                .timestamp(Instant.now()).build();
    }

    /** 构造一条存档卡片（sessionId 可为 null，模拟未开场的存档） */
    private static SaveCard saveWithSession(String sessionId) {
        return new SaveCard(GS_ID, sessionId, "w-1", "钟表城", "卢灭朱", "时钟塔", 12, null);
    }

    // ==================== saves 富化：lastMessage 预览（conversation-world-title-preview） ====================

    @Test
    void saves富化取最后一条可回放消息() {
        when(workshopService.listSaveCards()).thenReturn(List.of(saveWithSession(SESSION_ID)));
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。"),
                userEvent("u2", "我要一杯蜜酒"),
                assistantEvent("a2", "酒保推来一杯蜜酒。")));

        List<SaveCard> cards = controller.listSaveCards();

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getLastMessage()).isEqualTo("酒保推来一杯蜜酒。");
    }

    @Test
    void saves富化跳过工具事件与带toolCalls的assistant() {
        when(workshopService.listSaveCards()).thenReturn(List.of(saveWithSession(SESSION_ID)));
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。"),
                assistantEventWithToolCalls("t1", "调用工具中"),
                toolResponseEvent("r1")));

        List<SaveCard> cards = controller.listSaveCards();

        // 倒序跳过 tool_response 与带 toolCalls 的 assistant，停在此前纯文本叙述
        assertThat(cards.get(0).getLastMessage()).isEqualTo("老板娘抬头看了你一眼。");
    }

    @Test
    void saves富化跳过合成用户提示但计入syntheticAssistant() {
        when(workshopService.listSaveCards()).thenReturn(List.of(saveWithSession(SESSION_ID)));
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                assistantEvent("a1", "旧叙述。"),
                syntheticUserEvent("s1", "压缩合成的用户摘要"),
                syntheticAssistantEvent("s2", "压缩合成的助手摘要")));

        List<SaveCard> cards = controller.listSaveCards();

        // 与 /game/history 一致：synthetic user 跳过、synthetic assistant 计入
        assertThat(cards.get(0).getLastMessage()).isEqualTo("压缩合成的助手摘要");
    }

    @Test
    void saves富化中止轮悬停取玩家最后输入() {
        when(workshopService.listSaveCards()).thenReturn(List.of(saveWithSession(SESSION_ID)));
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。"),
                userEvent("u2", "拔剑"))); // 中止轮：assistant 事件缺失

        List<SaveCard> cards = controller.listSaveCards();

        // 与 history 一致的“停在这里”语义：预览显示玩家最后输入
        assertThat(cards.get(0).getLastMessage()).isEqualTo("拔剑");
    }

    @Test
    void saves富化限长截断加省略号() {
        when(workshopService.listSaveCards()).thenReturn(List.of(saveWithSession(SESSION_ID)));
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                assistantEvent("a1", "长".repeat(250))));

        List<SaveCard> cards = controller.listSaveCards();

        assertThat(cards.get(0).getLastMessage())
                .isEqualTo("长".repeat(200) + "…");
    }

    @Test
    void saves富化恰好两百字符不截断() {
        when(workshopService.listSaveCards()).thenReturn(List.of(saveWithSession(SESSION_ID)));
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                assistantEvent("a1", "长".repeat(200))));

        List<SaveCard> cards = controller.listSaveCards();

        assertThat(cards.get(0).getLastMessage()).isEqualTo("长".repeat(200));
    }

    @Test
    void saves富化空档返回null不中断列表() {
        SaveCard noSession = saveWithSession(null);
        SaveCard emptySession = saveWithSession("sess-empty");
        SaveCard onlyTool = saveWithSession("sess-tool");
        SaveCard onlySyntheticUser = saveWithSession("sess-comp");
        when(workshopService.listSaveCards())
                .thenReturn(List.of(noSession, emptySession, onlyTool, onlySyntheticUser));
        when(sessionService.getEvents("sess-empty")).thenReturn(List.of());
        when(sessionService.getEvents("sess-tool")).thenReturn(List.of(toolResponseEvent("r1")));
        when(sessionService.getEvents("sess-comp")).thenReturn(List.of(
                syntheticUserEvent("s1", "压缩合成的用户摘要")));

        List<SaveCard> cards = controller.listSaveCards();

        assertThat(cards).hasSize(4);
        assertThat(cards.get(0).getLastMessage()).isNull(); // 无 sessionId
        assertThat(cards.get(1).getLastMessage()).isNull(); // 空会话
        assertThat(cards.get(2).getLastMessage()).isNull(); // 仅工具事件
        assertThat(cards.get(3).getLastMessage()).isNull(); // 仅合成用户提示
    }

    @Test
    void saves富化查询失败降级null() {
        when(workshopService.listSaveCards()).thenReturn(List.of(saveWithSession(SESSION_ID)));
        when(sessionService.getEvents(SESSION_ID)).thenThrow(new RuntimeException("db down"));

        List<SaveCard> cards = controller.listSaveCards();

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getLastMessage()).isNull();
    }

    @Test
    void saves富化不改写既有字段与世界名占位符() {
        SaveCard card = new SaveCard(GS_ID, null, "w-1", "-", "-", "-", 0, null);
        when(workshopService.listSaveCards()).thenReturn(List.of(card));

        List<SaveCard> cards = controller.listSaveCards();

        // 占位符由 WorkshopService 填充，富化只补 lastMessage，不改写既有字段
        assertThat(cards.get(0).getWorldName()).isEqualTo("-");
        assertThat(cards.get(0).getPlayerCharName()).isEqualTo("-");
        assertThat(cards.get(0).getLastMessage()).isNull();
    }

    // ==================== history：eventId + currentTurn ====================

    @Test
    void history返回每条消息的eventId与顶层currentTurn() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。")));
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setTurnCount(2);
        when(workshopService.getGameStateBySessionId(SESSION_ID)).thenReturn(gs);

        Map<String, Object> result = controller.getHistory(SESSION_ID);

        assertThat(result.get("currentTurn")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("eventId", "u1");
        assertThat(messages.get(1)).containsEntry("eventId", "a1");
    }

    @Test
    void history空会话返回空消息与零轮() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of());

        Map<String, Object> result = controller.getHistory(SESSION_ID);

        assertThat(result.get("currentTurn")).isEqualTo(0);
        assertThat((List<?>) result.get("messages")).isEmpty();
    }

    // ==================== history：state 回填（rpg-hud-bubble-components D4/D7） ====================

    @Test
    void history按sessionEventId精确对齐回填state() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "旧轮叙述。"),
                assistantEvent("a2", "本轮叙述。")));
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setTurnCount(2);
        when(workshopService.getGameStateBySessionId(SESSION_ID)).thenReturn(gs);
        // 仅 a2 轮的对齐键命中（尾包回填的 session_event_id）
        when(eventLogRepository.findStateChangesByGameStateId(GS_ID))
                .thenReturn(List.of(stateEvent("a2", "{\"npc_states\":{\"苏枕雪\":{\"status\":\"合作\"}}}")));

        Map<String, Object> result = controller.getHistory(SESSION_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertThat(messages).hasSize(3);
        // user 分支不携带 state 键
        assertThat(messages.get(0)).doesNotContainKey("state");
        // 无对齐键的 assistant 降级 state null（前端纯叙述气泡）
        assertThat(messages.get(1)).containsEntry("state", null);
        // 对齐命中的 assistant 附带该轮快照原文（D7：所见即所存）
        assertThat(messages.get(2)).containsEntry("state", "{\"npc_states\":{\"苏枕雪\":{\"status\":\"合作\"}}}");
    }

    @Test
    void history旧事件无对齐键state为null() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                assistantEvent("a1", "旧轮叙述。")));
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setTurnCount(1);
        when(workshopService.getGameStateBySessionId(SESSION_ID)).thenReturn(gs);
        // 旧行：对齐键回填机制上线前落库，session_event_id 为 NULL → 不可对齐
        when(eventLogRepository.findStateChangesByGameStateId(GS_ID))
                .thenReturn(List.of(stateEvent(null, "{\"location_change\":\"渡口\"}")));

        Map<String, Object> result = controller.getHistory(SESSION_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).containsEntry("state", null);
    }

    @Test
    void history无GameState降级空映射不报错() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                assistantEvent("a1", "叙述。")));
        when(workshopService.getGameStateBySessionId(SESSION_ID)).thenReturn(null);

        Map<String, Object> result = controller.getHistory(SESSION_ID);

        assertThat(result.get("currentTurn")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).containsEntry("state", null);
        // 存档缺失：无 gameStateId 可查，不触碰事件日志
        verify(eventLogRepository, never()).findStateChangesByGameStateId(anyString());
    }

    @Test
    void history跨租户会话按404拒绝不泄露() {
        when(sessionTenantGuard.isForeign(SESSION_ID)).thenReturn(true);

        assertThatThrownBy(() -> controller.getHistory(SESSION_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        // 拒绝发生在读会话之前：不触碰他租户数据（session-isolation）
        verify(sessionService, never()).getEvents(anyString());
    }

    /** 构造一条 state_change 事件（rpg-hud-bubble-components D4：session_event_id 对齐键） */
    private static EventLog stateEvent(String sessionEventId, String stateDelta) {
        EventLog event = new EventLog();
        event.setGameStateId(GS_ID);
        event.setEventType("state_change");
        event.setSessionEventId(sessionEventId);
        event.setStateDelta(stateDelta);
        return event;
    }

    // ==================== turn_persisted 尾包三路径 ====================

    @Test
    @SuppressWarnings("unchecked")
    void 尾包正常路径包含双事件ID() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。")));

        ServerSentEvent<Object> event = controller.turnPersistedEvent(SESSION_ID, null, null, null);

        assertThat(event).isNotNull();
        Map<String, Object> data = (Map<String, Object>) event.data();
        assertThat(data)
                .containsEntry("type", "turn_persisted")
                .containsEntry("userEventId", "u1")
                .containsEntry("assistantEventId", "a1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 尾包错误轮路径assistantEventId为null() {
        // GM 生成异常：user 事件已落库，assistant 事件缺失
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(userEvent("u1", "我走进客栈")));

        ServerSentEvent<Object> event = controller.turnPersistedEvent(SESSION_ID, null, null, null);

        assertThat(event).isNotNull();
        Map<String, Object> data = (Map<String, Object>) event.data();
        assertThat(data)
                .containsEntry("userEventId", "u1")
                .containsEntry("assistantEventId", null);
    }

    @Test
    void 尾包查询失败路径返回null() {
        when(sessionService.getEvents(SESSION_ID)).thenThrow(new RuntimeException("db down"));

        assertThat(controller.turnPersistedEvent(SESSION_ID, null, null, null)).isNull();
    }

    // ==================== backtrack 端点 ====================

    @Test
    void backtrack参数缺失返回400() {
        ResponseEntity<Map<String, Object>> resp = controller.backtrack(Map.of(
                "sessionId", SESSION_ID, "gameStateId", GS_ID)); // 缺 anchorEventId

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsKey("error");
    }

    @Test
    void backtrack拒绝路径返回400与明确文案() {
        when(rollbackService.backtrack(SESSION_ID, GS_ID, "u9"))
                .thenThrow(new RollbackService.BacktrackException("回溯深度超限"));

        ResponseEntity<Map<String, Object>> resp = controller.backtrack(Map.of(
                "sessionId", SESSION_ID, "gameStateId", GS_ID, "anchorEventId", "u9"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "回溯深度超限");
    }

    @Test
    void backtrack成功路径返回回滚结果() {
        when(rollbackService.backtrack(SESSION_ID, GS_ID, "u2")).thenReturn(
                new RollbackService.RollbackResult(3, 1, "上楼", null));

        ResponseEntity<Map<String, Object>> resp = controller.backtrack(Map.of(
                "sessionId", SESSION_ID, "gameStateId", GS_ID, "anchorEventId", "u2"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .containsEntry("success", true)
                .containsEntry("removedCount", 3)
                .containsEntry("rolledBackToTurn", 1)
                .containsEntry("playerMessage", "上楼");
        assertThat(resp.getBody()).doesNotContainKey("warning");
    }

    // ==================== 回合守卫释放：complete / error / cancel 三路径（design D11） ====================

    /** 构造一个可控制 GM 流的控制器：prepareTurn 已 mock，守卫由调用方预置为「回合进行中」。 */
    private RpgGameController controllerWithTurnStream(Flux<String> gmStream, TurnGuardService guard) {
        return controllerWithTurnStream(gmStream, guard, mock(StoryRunEngine.class));
    }

    /** 同上，另注入可 stub 的 storyRunEngine（选边用例需要 verify/thenThrow） */
    private RpgGameController controllerWithTurnStream(Flux<String> gmStream, TurnGuardService guard,
                                                       StoryRunEngine storyRunEngine) {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(registry.forRpg(any())).thenReturn(chatClient);
        when(chatClient.prompt()
                .user(anyString())
                .advisors(any(Consumer.class))
                .toolContext(anyMap())
                .stream()
                .content())
                .thenReturn(gmStream);
        GameLoopService gameLoop = mock(GameLoopService.class);
        when(gameLoop.prepareTurn(GS_ID, "行动")).thenReturn(new GameLoopService.TurnContext(
                null, "玩家行动", Map.of(), List.of(), false, 2, Set.of()));
        return new RpgGameController(registry, gameLoop,
                mock(OpeningNarrationService.class), mock(StateDeltaSanitizer.class),
                workshopService, sessionService, rpgHistoryCleaner, rollbackService, guard,
                mock(SaveDeletionService.class), sessionTenantGuard, moderationGate, mock(QuotaGuard.class),
                mock(RpgEventLogRepository.class), storyRunEngine, mock(PromptHashService.class));
    }

    @Test
    void 回合流正常完成后守卫释放() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID); // 模拟 prepareTurn 已持锁
        StoryRunEngine engine = mock(StoryRunEngine.class);
        RpgGameController ctl = controllerWithTurnStream(Flux.just("客栈一幕"), guard, engine);

        ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .blockLast();

        assertThat(guard.isInTurn(GS_ID)).isFalse();
        // 4.6：完整回合即兴深度记账（纯即兴轮：无 edgeId → false）
        verify(engine).recordTurnOutcome(GS_ID, false);
    }

    @Test
    void 回合流异常终止后守卫释放() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        RpgGameController ctl = controllerWithTurnStream(
                Flux.error(new RuntimeException("模型超时")), guard);

        // onErrorResume 兑底后流出错事件并正常完成，doFinally 仍触发
        ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .blockLast();

        assertThat(guard.isInTurn(GS_ID)).isFalse();
    }

    @Test
    void 回合流被取消后守卫释放() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        RpgGameController ctl = controllerWithTurnStream(Flux.never(), guard);

        Disposable d = ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .subscribe();
        d.dispose(); // 模拟前端断流：取消订阅

        assertThat(guard.isInTurn(GS_ID)).isFalse();
    }

    /** 4.6：中止轮不计数（半截叙述大概率重试，避免误判超限） */
    @Test
    void 回合流被取消后守卫释放且不计即兴深度() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        StoryRunEngine engine = mock(StoryRunEngine.class);
        RpgGameController ctl = controllerWithTurnStream(Flux.never(), guard, engine);

        Disposable d = ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .subscribe();
        d.dispose(); // 模拟前端断流：取消订阅

        assertThat(guard.isInTurn(GS_ID)).isFalse();
        verify(engine, never()).recordTurnOutcome(anyString(), anyBoolean());
    }

    // ==================== 图选项视图与选边（story-graph-runtime 4.5） ====================

    /** 未挂图 → 空视图零增量（edges 空、improviseAllowed=true，前端选项区与现状一致） */
    @Test
    void storyOptions未挂图返回空视图零增量() {
        ResponseEntity<Object> resp = controller.storyOptions(GS_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("mode")).isEqualTo("");
        assertThat(body.get("nodeId")).isEqualTo("");
        assertThat((List<?>) body.get("edges")).isEmpty();
        assertThat(body.get("improviseAllowed")).isEqualTo(true);
    }

    /** 已挂图 → 完整映射（mode/nodeId/edges 四字段/improviseAllowed，null 字段兑空串） */
    @Test
    void storyOptions完整视图映射字段() {
        StoryEdge edge = new StoryEdge();
        edge.setId("e1_01");
        edge.setLabel("示好");
        edge.setQuote("在下愿为织田家效力");
        when(storyRunEngine.storyOptions(GS_ID)).thenReturn(
                new StoryRunEngine.StoryOptionsView("bounded", "n1_01", List.of(edge), false));

        ResponseEntity<Object> resp = controller.storyOptions(GS_ID);

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("mode")).isEqualTo("bounded");
        assertThat(body.get("nodeId")).isEqualTo("n1_01");
        assertThat(body.get("improviseAllowed")).isEqualTo(false);
        List<?> edges = (List<?>) body.get("edges");
        assertThat(edges).hasSize(1);
        Map<?, ?> e0 = (Map<?, ?>) edges.get(0);
        assertThat(e0.get("id")).isEqualTo("e1_01");
        assertThat(e0.get("label")).isEqualTo("示好");
        assertThat(e0.get("quote")).isEqualTo("在下愿为织田家效力");
        assertThat(e0.get("hint")).isEqualTo("");
    }

    // ==================== 图上回溯（story-graph-runtime 4.8） ====================

    /** 未挂图 → 空列表（无回退点零增量） */
    @Test
    void storyForks未挂图返回空列表() {
        ResponseEntity<Object> resp = controller.storyForks(GS_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) resp.getBody()).isEmpty();
    }

    /** 已挂图 → 分叉点四字段映射（nodeId/nodeType/beat/arrivedViaLabel，null 兑空串） */
    @Test
    void storyForks完整映射字段() {
        when(storyRunEngine.walkedForks(GS_ID)).thenReturn(List.of(
                new StoryRunEngine.StoryForkView("n1_01", "scene", "夜探织田邸", null),
                new StoryRunEngine.StoryForkView("n1_03", "scene", null, "示好")));

        ResponseEntity<Object> resp = controller.storyForks(GS_ID);

        List<?> forks = (List<?>) resp.getBody();
        assertThat(forks).hasSize(2);
        Map<?, ?> f0 = (Map<?, ?>) forks.get(0);
        assertThat(f0.get("nodeId")).isEqualTo("n1_01");
        assertThat(f0.get("nodeType")).isEqualTo("scene");
        assertThat(f0.get("beat")).isEqualTo("夜探织田邸");
        assertThat(f0.get("arrivedViaLabel")).isEqualTo("");
        Map<?, ?> f1 = (Map<?, ?>) forks.get(1);
        assertThat(f1.get("beat")).isEqualTo("");
        assertThat(f1.get("arrivedViaLabel")).isEqualTo("示好");
    }

    /** 回退成功：success + 目标节点 + 重放态（前端 HUD 刷新数据源） */
    @Test
    void storyBacktrack成功返回目标与重放态() {
        StoryNode node = new StoryNode();
        node.setId("n1_03");
        when(storyRunEngine.backtrackToNode(GS_ID, "n1_03"))
                .thenReturn(new StoryRunEngine.BacktrackResult(node, "{\"trust_信长\": 65}"));

        ResponseEntity<Object> resp = controller.storyBacktrack(
                Map.of("gameStateId", GS_ID, "nodeId", "n1_03"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("nodeId")).isEqualTo("n1_03");
        assertThat(body.get("playerStates")).isEqualTo("{\"trust_信长\": 65}");
    }

    /** 回退目标不在已走路径/未挂图 → 400 error（IAE 归控形态，对齐选边） */
    @Test
    void storyBacktrack非法目标返回400() {
        when(storyRunEngine.backtrackToNode(GS_ID, "n1_99"))
                .thenThrow(new IllegalArgumentException("回溯目标不在已走路径上: n1_99"));

        ResponseEntity<Object> resp = controller.storyBacktrack(
                Map.of("gameStateId", GS_ID, "nodeId", "n1_99"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) resp.getBody()).get("error")).isEqualTo("回溯目标不在已走路径上: n1_99");
    }

    /** 参数缺失 → 400（不触引擎零副作用） */
    @Test
    void storyBacktrack参数缺失返回400() {
        ResponseEntity<Object> resp = controller.storyBacktrack(Map.of("gameStateId", GS_ID));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(storyRunEngine, never()).backtrackToNode(anyString(), anyString());
    }

    // ==================== 多周目开局（story-graph-runtime 4.9） ====================

    /** 成功：新周目重置回入口 + 重放态（rematerialize 透传） */
    @Test
    void storyNewCycle成功返回入口与重放态() {
        StoryNode entry = new StoryNode();
        entry.setId("n1_01");
        when(storyRunEngine.startNewCycle(GS_ID, true)).thenReturn(
                new StoryRunEngine.BacktrackResult(entry, "{\"trust_信长\": 60}"));

        ResponseEntity<Object> resp = controller.storyNewCycle(
                Map.of("gameStateId", GS_ID, "rematerialize", true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("nodeId")).isEqualTo("n1_01");
        assertThat(body.get("playerStates")).isEqualTo("{\"trust_信长\": 60}");
    }

    /** 未通关/未挂图 → IAE 400（rematerialize 缺省 false 透传） */
    @Test
    void storyNewCycle未通关返回400() {
        when(storyRunEngine.startNewCycle(eq(GS_ID), any())).thenThrow(
                new IllegalArgumentException("存档尚未通关（当前节点非结局），不能开新周目"));

        ResponseEntity<Object> resp = controller.storyNewCycle(Map.of("gameStateId", GS_ID));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((Map<?, ?>) resp.getBody()).get("error"))
                .isEqualTo("存档尚未通关（当前节点非结局），不能开新周目");
    }

    /** 参数缺失 → 400 零调用 */
    @Test
    void storyNewCycle参数缺失返回400() {
        ResponseEntity<Object> resp = controller.storyNewCycle(Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(storyRunEngine, never()).startNewCycle(anyString(), any());
    }

    /** 选边失败（守卫竞态/他边重放等）零写入：SSE error + [DONE]，不进 prepareTurn */
    @Test
    void 回合选边失败返回错误事件且不进回合() {
        when(storyRunEngine.settleEdgeForGame(GS_ID, "e1_01"))
                .thenThrow(new IllegalArgumentException("守卫不满足: trust_信长"));

        List<ServerSentEvent<Object>> events = controller.gameTurn(Map.of(
                "gameStateId", GS_ID, "sessionId", SESSION_ID,
                "message", "在下愿为织田家效力", "edgeId", "e1_01"))
                .collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).data())
                .isEqualTo(Map.of("error", "选边失败: 守卫不满足: trust_信长"));
        assertThat(events.get(1).data()).isEqualTo("[DONE]");
        verify(gameLoopService, never()).prepareTurn(anyString(), anyString());
    }

    /** 图边发起回合：edgeId 结算在 prepareTurn 之前（组 prompt 时 currentNode 已推进） */
    @Test
    void 回合带edgeId结算成功进正常回合流() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        StoryRunEngine engine = mock(StoryRunEngine.class);
        RpgGameController ctl = controllerWithTurnStream(
                Flux.just("殿中对酌……"), guard, engine);

        List<ServerSentEvent<Object>> events = ctl.gameTurn(Map.of(
                "gameStateId", GS_ID, "sessionId", SESSION_ID,
                "message", "行动", "model", "", "edgeId", "e1_01"))
                .collectList().block();

        // 选边先行（结算成功后组 prompt），GM 正文照常流出
        verify(engine).settleEdgeForGame(GS_ID, "e1_01");
        // 4.6：走图边轮记账（true：settleEdge 已同步重置计数，幂等兑底）
        verify(engine).recordTurnOutcome(GS_ID, true);
        assertThat(joinedContent(events)).contains("殿中对酌");
        assertThat(guard.isInTurn(GS_ID)).isFalse();
    }

    // ==================== 降级重跑：reset + 非流式重发（stream-fallback-duplicate-output） ====================

    /**
     * 构造可控制「流式 GM 输出 + 非流式重跑结果」的控制器：
     * stream().content() 返回 gmStream；call().content() 返回 retryText，
     * retryError 非空时 call().content() 抛该异常；gameLoop 由调用方持有以便验证。
     * <p>
     * 显式 mock 各级 spec 对象（而非深桩）：两条 when() 深桩链共享
     * prompt().user().advisors().toolContext() 前缀时会互相孤儿化，
     * 导致 stream().content() 桩失效。
     */
    private RpgGameController controllerWithFallbackRetry(Flux<String> gmStream, String retryText,
                                                          Throwable retryError,
                                                          TurnGuardService guard, GameLoopService gameLoop) {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(registry.forRpg(any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(gmStream);
        when(requestSpec.call()).thenReturn(callSpec);
        if (retryError != null) {
            when(callSpec.content()).thenThrow(retryError);
        } else {
            when(callSpec.content()).thenReturn(retryText);
        }
        when(gameLoop.prepareTurn(GS_ID, "行动")).thenReturn(new GameLoopService.TurnContext(
                null, "玩家行动", Map.of(), List.of(), false, 2, Set.of()));
        return new RpgGameController(registry, gameLoop,
                mock(OpeningNarrationService.class), mock(StateDeltaSanitizer.class),
                workshopService, sessionService, rpgHistoryCleaner, rollbackService, guard,
                mock(SaveDeletionService.class), sessionTenantGuard, moderationGate, mock(QuotaGuard.class),
                mock(RpgEventLogRepository.class), mock(StoryRunEngine.class), mock(PromptHashService.class));
    }

    private static String joinedContent(List<ServerSentEvent<Object>> events) {
        return events.stream()
                .map(e -> e.data() instanceof Map<?, ?> m && m.get("content") != null
                        ? (String) m.get("content") : null)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    private static boolean hasEventType(List<ServerSentEvent<Object>> events, String type) {
        return events.stream().anyMatch(e ->
                e.data() instanceof Map<?, ?> m && type.equals(m.get("type")));
    }

    @Test
    void 已发内容后降级重跑输出无重复且落库仅重跑结果() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        GameLoopService gameLoop = mock(GameLoopService.class);
        PostEmissionStreamException signal = new PostEmissionStreamException(
                new IllegalArgumentException("no more than one tool call per message currently supported"), 1);
        RpgGameController ctl = controllerWithFallbackRetry(
                Flux.just("半截叙述").concatWith(Flux.error(signal)),
                "完整重跑内容", null, guard, gameLoop);

        List<ServerSentEvent<Object>> events = ctl.gameTurn(Map.of(
                        "gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .collectList()
                .block(Duration.ofSeconds(5));

        // 序列含 reset 恰一次；叙述 = 半截 + 完整重跑（重跑前片段不被再次拼接）
        assertThat(events).isNotNull();
        assertThat(hasEventType(events, "reset")).isTrue();
        assertThat(events.stream().filter(e ->
                e.data() instanceof Map<?, ?> m && "reset".equals(m.get("type"))).count()).isEqualTo(1);
        String joined = joinedContent(events);
        assertThat(joined).isEqualTo("半截叙述完整重跑内容");
        assertThat(joined.indexOf("半截叙述")).isEqualTo(joined.lastIndexOf("半截叙述"));

        // 落库仅重跑结果（不含重跑前已发片段）
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(gameLoop, times(1)).finalizeTurn(eq(GS_ID), saved.capture(), any());
        assertThat(saved.getValue()).isEqualTo("完整重跑内容");

        assertThat(guard.isInTurn(GS_ID)).isFalse();
    }

    @Test
    void 降级重跑失败发错误事件且不落库() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        GameLoopService gameLoop = mock(GameLoopService.class);
        PostEmissionStreamException signal = new PostEmissionStreamException(
                new IllegalArgumentException("no more than one tool call per message currently supported"), 2);
        RpgGameController ctl = controllerWithFallbackRetry(
                Flux.just("半截一", "半截二").concatWith(Flux.error(signal)),
                null, new RuntimeException("模型不可用"), guard, gameLoop);

        List<ServerSentEvent<Object>> events = ctl.gameTurn(Map.of(
                        "gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .collectList()
                .block(Duration.ofSeconds(5));

        // reset 已下发，错误事件含降级重跑失败提示
        assertThat(events).isNotNull();
        assertThat(hasEventType(events, "reset")).isTrue();
        assertThat(events.stream().anyMatch(e ->
                e.data() instanceof Map<?, ?> m
                        && m.get("error") != null
                        && String.valueOf(m.get("error")).contains("降级重跑失败"))).isTrue();

        // 不落库（fullResponse 已清空，空值守卫跳过 finalizeTurn），守卫释放
        verify(gameLoop, never()).finalizeTurn(anyString(), anyString(), any());
        assertThat(guard.isInTurn(GS_ID)).isFalse();
    }

    // ==================== 工具进度事件合流（tool progress visibility） ====================

    @Test
    @SuppressWarnings("unchecked")
    void 工具进度事件合流入SSE且流正常收尾() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        GameLoopService gameLoop = mock(GameLoopService.class);
        // 模拟 GM 流内执行工具：与 ResilientToolCallback 同方式，在元素流经时经
        // Reactor context 读取 toolEventSink 并发射 {"tool":"start"} 进度事件。
        // delaySubscription 还原生产时序：merge 先完成两源订阅，之后元素才流经
        // （同步立即发射的 mock 流会在事件流订阅前发射，被缓冲到 [DONE] 之后）
        Flux<String> gmStream = Flux.just("叙述正文")
                .delaySubscription(Duration.ofMillis(100))
                .flatMap(s -> Mono.deferContextual(ctx -> {
                    Object sinkObj = ctx.getOrDefault("toolEventSink", null);
                    if (sinkObj instanceof Sinks.Many<?> many) {
                        ((Sinks.Many<Map<String, String>>) sinkObj)
                                .tryEmitNext(Map.of("tool", "start", "name", "GmMemoryCreate"));
                    }
                    return Mono.just(s);
                }));
        RpgGameController ctl = controllerWithFallbackRetry(gmStream, null, null, guard, gameLoop);

        List<ServerSentEvent<Object>> events = ctl.gameTurn(Map.of(
                        "gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).isNotNull();
        // 工具进度事件已合流下发（data 携带 tool/name，与主聊天同协议）
        assertThat(events.stream().anyMatch(e -> e.data() instanceof Map<?, ?> m
                && "start".equals(m.get("tool"))
                && "GmMemoryCreate".equals(m.get("name")))).isTrue();
        // 叙述内容完整，[DONE] 仍是最后一个事件（合流不破坏收尾顺序）
        assertThat(joinedContent(events)).isEqualTo("叙述正文");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        // 合并流两侧均被收口（否则 block 不会返回），守卫释放且正常落库
        assertThat(guard.isInTurn(GS_ID)).isFalse();
        verify(gameLoop, times(1)).finalizeTurn(eq(GS_ID), anyString(), any());
    }

    // ==================== 服务端中止（rpg-server-side-abort，design D2/D3） ====================

    /** 轮询等待条件成立（2s 上限），用于流式订阅与断言间的时序对齐。 */
    private static void awaitUntil(BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(cond.getAsBoolean()).isTrue();
    }

    @Test
    void abort缺gameStateId返回400() {
        ResponseEntity<Map<String, Object>> resp = controller.abortTurn(Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsKey("error");
    }

    @Test
    void 思考期abort命中后流终止守卫释放且不落库() throws InterruptedException {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        GameLoopService gameLoop = mock(GameLoopService.class);
        // GM 流挂起不吐字：模拟思考期（纯客户端 abort 场景下服务端收尾不可见）
        RpgGameController ctl = controllerWithFallbackRetry(
                Flux.never(), null, null, guard, gameLoop);

        CountDownLatch terminated = new CountDownLatch(1);
        ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .subscribe(e -> { }, e -> terminated.countDown(), terminated::countDown);

        // abort 命中 → 截断流、释放守卫（秒级，而非等待模型超时）
        ResponseEntity<Map<String, Object>> resp = ctl.abortTurn(Map.of("gameStateId", GS_ID));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("aborted", true);
        assertThat(terminated.await(2, TimeUnit.SECONDS)).isTrue();

        // 守卫已释放：可立即开始新回合（spec：中止后立即开始新回合）
        assertThat(guard.isInTurn(GS_ID)).isFalse();
        assertThat(guard.tryAcquire(GS_ID)).isTrue();

        // 无叙述不落库（spec：中止时尚无叙述）
        verify(gameLoop, never()).finalizeTurn(anyString(), anyString(), any());
    }

    @Test
    void 部分叙述后abort命中落库一次且重复abort落空() throws InterruptedException {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        GameLoopService gameLoop = mock(GameLoopService.class);
        // 两段叙述后挂起：模拟已展示部分内容后中止（spec：中止时已有部分叙述）
        RpgGameController ctl = controllerWithFallbackRetry(
                Flux.just("部分一", "部分二").concatWith(Flux.never()),
                null, null, guard, gameLoop);

        List<ServerSentEvent<Object>> events = new ArrayList<>();
        CountDownLatch terminated = new CountDownLatch(1);
        ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .subscribe(events::add, e -> terminated.countDown(), terminated::countDown);

        awaitUntil(() -> !events.isEmpty()); // 已发内容进入 fullResponse

        assertThat(ctl.abortTurn(Map.of("gameStateId", GS_ID)).getBody())
                .containsEntry("aborted", true);
        assertThat(terminated.await(2, TimeUnit.SECONDS)).isTrue();

        // 部分叙述被保存一次（spec：中止时已有部分叙述）
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(gameLoop, times(1)).finalizeTurn(eq(GS_ID), saved.capture(), any());
        assertThat(saved.getValue()).isEqualTo("部分一部分二");
        assertThat(guard.isInTurn(GS_ID)).isFalse();

        // 重复 abort 幂等落空（spec：重复调用中止）
        assertThat(ctl.abortTurn(Map.of("gameStateId", GS_ID)).getBody())
                .containsEntry("aborted", false);
    }

    @Test
    void abort落在回合完成后返回false且无副作用() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID);
        RpgGameController ctl = controllerWithTurnStream(Flux.just("完整叙述"), guard);

        ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .blockLast(); // 回合正常完成，守卫已释放

        // 迟到的 abort 不得误伤（spec：中止不得跨回合生效）
        ResponseEntity<Map<String, Object>> resp = ctl.abortTurn(Map.of("gameStateId", GS_ID));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("aborted", false);
        assertThat(guard.tryAcquire(GS_ID)).isTrue(); // 新回合不受影响
    }
}
