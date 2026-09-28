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
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.tenancy.SessionTenantGuard;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
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
import static org.mockito.ArgumentMatchers.any;
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
 * backtrack 端点的参数校验与错误映射（3.2）。
 */
class RpgGameControllerTest {

    private static final String SESSION_ID = "sess-1";
    private static final String GS_ID = "gs-1";

    private SessionService sessionService;
    private WorkshopService workshopService;
    private RollbackService rollbackService;
    private RpgHistoryCleaner rpgHistoryCleaner;
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
        rollbackService = mock(RollbackService.class);
        rpgHistoryCleaner = mock(RpgHistoryCleaner.class);

        // cleaner 默认行为：无包裹标记时原样 trim 返回
        when(rpgHistoryCleaner.cleanUserMessage(anyString()))
                .thenAnswer(inv -> inv.getArgument(0) == null ? "" : ((String) inv.getArgument(0)).trim());
        when(rpgHistoryCleaner.cleanAssistantMessage(anyString()))
                .thenAnswer(inv -> inv.getArgument(0) == null ? "" : ((String) inv.getArgument(0)).trim());

        controller = new RpgGameController(
                mock(ChatClientRegistry.class),
                mock(GameLoopService.class),
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
                mock(QuotaGuard.class));

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

    // ==================== turn_persisted 尾包三路径 ====================

    @Test
    @SuppressWarnings("unchecked")
    void 尾包正常路径包含双事件ID() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。")));

        ServerSentEvent<Object> event = controller.turnPersistedEvent(SESSION_ID);

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

        ServerSentEvent<Object> event = controller.turnPersistedEvent(SESSION_ID);

        assertThat(event).isNotNull();
        Map<String, Object> data = (Map<String, Object>) event.data();
        assertThat(data)
                .containsEntry("userEventId", "u1")
                .containsEntry("assistantEventId", null);
    }

    @Test
    void 尾包查询失败路径返回null() {
        when(sessionService.getEvents(SESSION_ID)).thenThrow(new RuntimeException("db down"));

        assertThat(controller.turnPersistedEvent(SESSION_ID)).isNull();
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
                mock(SaveDeletionService.class), sessionTenantGuard, moderationGate, mock(QuotaGuard.class));
    }

    @Test
    void 回合流正常完成后守卫释放() {
        TurnGuardService guard = new TurnGuardService();
        guard.tryAcquire(GS_ID); // 模拟 prepareTurn 已持锁
        RpgGameController ctl = controllerWithTurnStream(Flux.just("客栈一幕"), guard);

        ctl.gameTurn(Map.of("gameStateId", GS_ID, "sessionId", SESSION_ID,
                        "message", "行动", "model", ""))
                .blockLast();

        assertThat(guard.isInTurn(GS_ID)).isFalse();
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
                mock(SaveDeletionService.class), sessionTenantGuard, moderationGate, mock(QuotaGuard.class));
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
