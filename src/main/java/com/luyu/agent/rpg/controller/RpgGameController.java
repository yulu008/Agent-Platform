package com.luyu.agent.rpg.controller;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.config.PostEmissionStreamException;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.engine.GameLoopService;
import com.luyu.agent.rpg.engine.OpeningNarrationService;
import com.luyu.agent.rpg.engine.RollbackService;
import com.luyu.agent.rpg.engine.RpgHistoryCleaner;
import com.luyu.agent.rpg.engine.SaveDeletionService;
import com.luyu.agent.rpg.engine.StateDeltaSanitizer;
import com.luyu.agent.rpg.engine.StateDeltaStreamFilter;
import com.luyu.agent.rpg.engine.TurnGuardService;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.SaveCard;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.tenancy.SessionTenantGuard;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RPG 游戏流程 Controller。
 * <p>
 * - GET  /rpg: 返回 rpg.html 模板
 * - POST /rpg/game/start: 加载 GameState → 组装首轮 system prompt → 开场白 SSE 流式 → GM 接管
 * - POST /rpg/game/turn: 玩家输入 → GameLoopService 执行 → GM 叙述 SSE 流式（过滤 state_delta）
 * - GET  /rpg/game/saves: 存档卡片列表（富化 DTO）
 * - DELETE /rpg/game/state/{id}: 删除存档（全量级联清理）
 * - POST /rpg/game/load: 读档
 * - GET  /rpg/game/state: 返回当前 GameState 供前端状态面板更新
 * <p>
 * 每回合 finalizeTurn 已自动落库，无手动保存端点（旧 /game/save 为空操作已移除）。
 */
@RestController
@RequestMapping("/rpg")
public class RpgGameController {

    private static final Logger log = LoggerFactory.getLogger(RpgGameController.class);

    /** Reactor context 中存放工具事件 Sink 的 key（与 ResilientToolCallback / ChatStreamController 一致） */
    private static final String TOOL_EVENT_SINK_KEY = "toolEventSink";

    private final ChatClientRegistry chatClientRegistry;
    private final GameLoopService gameLoopService;
    private final OpeningNarrationService openingNarrationService;
    private final StateDeltaSanitizer stateDeltaSanitizer;
    private final WorkshopService workshopService;
    private final SessionService sessionService;
    private final RpgHistoryCleaner rpgHistoryCleaner;
    private final RollbackService rollbackService;
    private final TurnGuardService turnGuardService;
    private final SaveDeletionService saveDeletionService;
    private final SessionTenantGuard sessionTenantGuard;
    private final ContentModerationGate moderationGate;
    private final QuotaGuard quotaGuard;

    public RpgGameController(ChatClientRegistry chatClientRegistry,
                             GameLoopService gameLoopService,
                             OpeningNarrationService openingNarrationService,
                             StateDeltaSanitizer stateDeltaSanitizer,
                             WorkshopService workshopService,
                             SessionService sessionService,
                             RpgHistoryCleaner rpgHistoryCleaner,
                             RollbackService rollbackService,
                             TurnGuardService turnGuardService,
                             SaveDeletionService saveDeletionService,
                             SessionTenantGuard sessionTenantGuard,
                             ContentModerationGate moderationGate,
                             QuotaGuard quotaGuard) {
        this.chatClientRegistry = chatClientRegistry;
        this.gameLoopService = gameLoopService;
        this.openingNarrationService = openingNarrationService;
        this.stateDeltaSanitizer = stateDeltaSanitizer;
        this.workshopService = workshopService;
        this.sessionService = sessionService;
        this.rpgHistoryCleaner = rpgHistoryCleaner;
        this.rollbackService = rollbackService;
        this.turnGuardService = turnGuardService;
        this.saveDeletionService = saveDeletionService;
        this.sessionTenantGuard = sessionTenantGuard;
        this.moderationGate = moderationGate;
        this.quotaGuard = quotaGuard;
    }

    /**
     * 游戏开始：加载 GameState → 开场白 SSE 流式 → GM 接管。
     * <p>
     * 请求体: {"gameStateId":"xxx","sessionId":"yyy"}
     */
    @PostMapping(value = "/game/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> startGame(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        String sessionId = request.get("sessionId");

        // 跨租户会话守卫（session-isolation spec）：开局写入事件/标题必须归属本租户
        if (sessionTenantGuard.isForeign(sessionId)) {
            log.warn("跨租户会话访问被拒（开局）: sessionId={}", sessionId);
            return Flux.just(ServerSentEvent.<Object>builder()
                    .data(Map.of("error", "会话不存在或无权访问"))
                    .build());
        }

        // 生成开场白
        String opening = openingNarrationService.generateOpening(gameStateId);
        if (opening == null || opening.isBlank()) {
            opening = "（开场白未配置，故事直接开始。）";
        }

        // 组装首轮 system prompt
        String systemPrompt = gameLoopService.prepareTurn(gameStateId, "").systemPrompt();
        // 这里简化：首轮只展示开场白，GM 从第一轮玩家输入开始
        // 实际首轮注入在 prepareTurn 时完成

        // 流式推送开场白（逐字）；回合守卫在流终止（含取消/异常）时释放（D11）；
        // 服务端中止（rpg-server-side-abort，design D7）：takeUntilOther 监听守卫取消信号，
        // abort 命中时截断开场白流并释放守卫
        Flux<ServerSentEvent<Object>> openingFlux = charStreamFlux(opening);
        return openingFlux.concatWith(Flux.just(
                ServerSentEvent.<Object>builder()
                        .data(Map.of("type", "opening_complete", "gameStateId", gameStateId))
                        .build(),
                ServerSentEvent.<Object>builder()
                        .data("[DONE]")
                        .build()
        ))
                .takeUntilOther(turnGuardService.abortSignal(gameStateId))
                .doFinally(signal -> turnGuardService.release(gameStateId));
    }

    /**
     * 游戏回合：玩家输入 → GameLoopService → GM 叙述 SSE 流式。
     * <p>
     * 请求体: {"gameStateId":"xxx","sessionId":"yyy","message":"我走进客栈","model":"可选模型名"}
     */
    @PostMapping(value = "/game/turn", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> gameTurn(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        String sessionId = request.get("sessionId");
        String message = request.get("message");
        String model = request.get("model");

        // 请求线程捕获租户 ID：SSE 订阅与降级重跑（callGmContent）都在异步线程执行，
        // ThreadLocal 不可靠；租户身份随 GmTurnSpec 显式传播。
        // USER_ID_CONTEXT_KEY：SessionMemoryAdvisor 请求级 userId 覆盖（记忆按租户隔离）。
        final String tenantId = TenantContext.requireTenantId();

        // 跨租户会话守卫（session-isolation spec）：回合事件必须写进本租户会话
        if (sessionTenantGuard.isForeign(sessionId)) {
            log.warn("跨租户会话访问被拒（回合）: sessionId={}, tenant={}", sessionId, tenantId);
            return Flux.just(ServerSentEvent.<Object>builder()
                    .data(Map.of("error", "会话不存在或无权访问"))
                    .build());
        }

        // 输入内容审查闸门（input-content-moderation）：GM 生成前同步审查玩家输入原文。
        // 命中即整条拒答 + 中止，且在 prepareTurn（持有回合守卫、落玩家事件）之前提前返回 ——
        // 被拒输入不写 rpg_event_log、doOnComplete 不执行 finalizeTurn（spec「命中输入不落库」）。
        ModerationResult moderation = moderationGate.check(message);
        if (moderation.blocked()) {
            return Flux.just(
                    contentEvent(moderationGate.refusalMessage()),
                    ServerSentEvent.<Object>builder()
                            .data("[DONE]")
                            .build());
        }

        // 配额硬拒（tenant-token-metering / design D8）：用户发起型入口起流前事前检查。
        // 超限以 SSE error 事件返回友好提示，且在 prepareTurn（持有回合守卫、落玩家事件）之前返回 ——
        // 被拒回合不写 rpg_event_log、不占用回合守卫；配额开关关闭时 QuotaGuard 直接放行。
        try {
            quotaGuard.check(tenantId);
        } catch (QuotaExceededException qe) {
            log.info("配额超限拒绝回合: gameStateId={}, tenant={}", gameStateId, tenantId);
            return Flux.just(
                    ServerSentEvent.<Object>builder()
                            .data(Map.of("error", qe.getMessage()))
                            .build(),
                    ServerSentEvent.<Object>builder()
                            .data("[DONE]")
                            .build());
        }

        // 步骤1-4: 准备轮次（触发器扫描、软条件评估、概率检定、GM 上下文组装）
        GameLoopService.TurnContext turnContext;
        try {
            turnContext = gameLoopService.prepareTurn(gameStateId, message);
        } catch (Exception e) {
            log.error("准备轮次失败: {}", e.getMessage(), e);
            return Flux.just(ServerSentEvent.<Object>builder()
                    .data(Map.of("error", "准备轮次失败: " + e.getMessage()))
                    .build());
        }

        // 获取 GM 专属 ChatClient（rpgSessionMemoryAdvisor，maxEvents=30，与主聊天隔离），按前端选择的模型路由
        ChatClient chatClient;
        try {
            chatClient = chatClientRegistry.forRpg(model);
        } catch (IllegalArgumentException e) {
            // 回合未开流即失败：prepareTurn 已持有的守卫在此释放（D11）
            turnGuardService.release(gameStateId);
            log.warn("RPG 模型路由失败: {}", e.getMessage());
            return Flux.just(ServerSentEvent.<Object>builder()
                    .data(Map.of("error", e.getMessage()))
                    .build());
        }

        // 拼接完整回复（用于 post-processing）；注意保存的是原始文本（含 state_delta），
        // finalizeTurn 依它提取状态，展示层过滤由 deltaFilter 单独负责
        StringBuilder fullResponse = new StringBuilder();
        // 服务端流式过滤：state_delta 块（含跨 chunk 分片/未闭合）不下发，
        // 避免 JSON 状态数据在流式期间闪现在前端（前端 filterStateDelta 作二次兜底）。
        // 降级重跑时整体替换新实例（AtomicReference 兼顾 lambda 捕获与重置，design D2）
        AtomicReference<StateDeltaStreamFilter> deltaFilterRef =
                new AtomicReference<>(new StateDeltaStreamFilter());
        GmTurnSpec spec = new GmTurnSpec(gameStateId, sessionId, chatClient,
                turnContext, fullResponse, deltaFilterRef, tenantId);

        // 工具进度事件 Sink：经 Reactor context 传递给 ResilientToolCallback（与主聊天
        // ChatStreamController 同机制），GM 每次调用工具时向 SSE 流注入 {"tool":"start","name":…}，
        // 让叙述前后的静默期（如模型连续写记忆文件）在前端有可见反馈。
        // 降级重跑（call() 阻塞路径）无 Reactor context，不发事件，属可接受的降级。
        Sinks.Many<Map<String, String>> toolEventSink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<ServerSentEvent<Object>> toolEventFlux = toolEventSink.asFlux()
                .map(event -> ServerSentEvent.<Object>builder().data(event).build());

        // 构建 GM 请求；流式管道统一由 wrapGmStream 包装（token 记账 + 已发内容后降级重跑分流）
        Flux<ServerSentEvent<Object>> contentFlux;
        if (turnContext.firstRound() && turnContext.systemPrompt() != null) {
            // 首轮：注入 system prompt + 玩家输入
            contentFlux = wrapGmStream(chatClient.prompt()
                    .system(turnContext.systemPrompt())
                    .user(turnContext.userPrompt())
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, tenantId)
                            .param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "rpg"))
                    .toolContext(turnContext.toolContext())
                    .stream()
                    .content(), spec);
        } else {
            // 后续轮：增量注入
            contentFlux = wrapGmStream(chatClient.prompt()
                    .user(turnContext.userPrompt())
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, tenantId)
                            .param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "rpg"))
                    .toolContext(turnContext.toolContext())
                    .stream()
                    .content(), spec);
        }

        // post-processing: 在流完成后执行
        Flux<ServerSentEvent<Object>> turnPipeline = contentFlux
                .doOnComplete(() -> {
                    // 步骤6: state_delta 提取 + 状态更新 + 动机变更 + 事件日志
                    // 空值守卫：降级重跑失败等场景 fullResponse 已清空，无内容可落库则跳过
                    if (fullResponse.length() == 0) {
                        log.warn("回合无内容可落库，跳过 finalizeTurn: gameStateId={}", gameStateId);
                        return;
                    }
                    try {
                        gameLoopService.finalizeTurn(gameStateId, fullResponse.toString(), turnContext);
                    } catch (Exception e) {
                        log.error("finalizeTurn 失败: gameStateId={}", gameStateId, e);
                    }
                })
                .doOnCancel(() -> {
                    // 流式中止：保存已生成的部分
                    if (fullResponse.length() > 0) {
                        try {
                            gameLoopService.finalizeTurn(gameStateId, fullResponse.toString(), turnContext);
                        } catch (Exception e) {
                            log.error("中止后 finalizeTurn 失败: gameStateId={}", gameStateId, e);
                        }
                    }
                })
                // 流收尾：放行被扣留的非块内容；未闭合的 state_delta 块在 finish 内部丢弃
                .concatWith(Flux.defer(() -> {
                    String tail = deltaFilterRef.get().finish();
                    return tail.isEmpty()
                            ? Flux.<ServerSentEvent<Object>>empty()
                            : Flux.just(contentEvent(tail));
                }))
                // 尾包 turn_persisted：回合已落库，回报本局事件 ID 供前端 live 气泡挂载回溯锚点。
                // defer 保证在 doOnComplete/doOnCancel（finalizeTurn）之后才取事件；
                // 中止路径下流已被取消、尾包无法送达，前端中止后自行刷新历史对账。
                .concatWith(Flux.defer(() -> {
                    ServerSentEvent<Object> persisted = turnPersistedEvent(sessionId);
                    return persisted == null
                            ? Flux.<ServerSentEvent<Object>>empty()
                            : Flux.just(persisted);
                }))
                .concatWith(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data("[DONE]")
                                .build()
                ))
                // 服务端中止（rpg-server-side-abort，design D2）：takeUntilOther 监听守卫取消信号，
                // abort 命中时截断本管道并取消上游 GM 流，走既有 doOnCancel（部分落库）/doFinally（释放守卫）收尾
                .takeUntilOther(turnGuardService.abortSignal(gameStateId))
                // 回合终止（complete/cancel/error 全部路径）释放回合守卫（design D11）：
                // 兜底覆盖流式中止、前端断流、异常等一切终止态，杜绝锁泄漏与回溯竞态；
                // 同步收口工具事件流——Flux.merge 两侧齐终才能整体完成，SSE 连接才会关闭
                .doFinally(signal -> {
                    turnGuardService.release(gameStateId);
                    toolEventSink.tryEmitComplete();
                })
                // 工具事件通道挂入 Reactor context：上游 GM 流内执行工具的
                // ResilientToolCallback 经 ToolCallReactiveContextHolder 桥接读取此 Sink
                .contextWrite(ctx -> ctx.put(TOOL_EVENT_SINK_KEY, toolEventSink));

        // 叙述内容流与工具进度事件流合流：内容流负责 [DONE]/落库/守卫，
        // 工具流只补充进度事件，终态由 turnPipeline 的 doFinally 统一收口
        return Flux.merge(turnPipeline, toolEventFlux);
    }

    /**
     * 服务端中止回合（rpg-server-side-abort，design D3）：触发该存档进行中回合
     * （含开场白）的取消信号，回合流随即截断并走既有 doOnCancel/doFinally 收尾
     * （部分叙述落库 + 守卫释放）。
     * <p>
     * 请求体: {"gameStateId":"xxx"}<br>
     * 响应: {aborted: true|false}——命中进行中的回合为 true；无回合 / 已中止 /
     * 迟到调用均为 false 且无任何副作用（幂等，前端 fire-and-forget 可安全重试）。
     */
    @PostMapping("/game/abort")
    public ResponseEntity<Map<String, Object>> abortTurn(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        if (gameStateId == null || gameStateId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gameStateId 不能为空"));
        }
        boolean aborted = turnGuardService.fireAbort(gameStateId);
        if (aborted) {
            log.info("RPG 回合中止信号已发出: gameStateId={}", gameStateId);
        }
        return ResponseEntity.ok(Map.of("aborted", aborted));
    }
    
    // ==================== GM 流式包装与降级重跑（stream-fallback-duplicate-output） ====================
    
    /** 回合流式记账载体：fullResponse / deltaFilter 随降级重跑整体重置（design D2）。 */
    private record GmTurnSpec(String gameStateId, String sessionId, ChatClient chatClient,
                              GameLoopService.TurnContext turnContext, StringBuilder fullResponse,
                              AtomicReference<StateDeltaStreamFilter> deltaFilterRef,
                              String tenantId) {
    }
    
    /**
     * GM 内容流统一包装：token 记账（fullResponse / deltaFilter）+ 错误分流。
     * <p>
     * 错误分流（design D2）：
     * <ul>
     *   <li>{@link PostEmissionStreamException}（已发内容后 ChunkMerger 不兼容）→
     *       先发 reset 事件让前端清空当前 GM 气泡，再以非流式重跑本回合重发完整叙述</li>
     *   <li>其余错误 → 既有 error 事件路径</li>
     * </ul>
     */
    private Flux<ServerSentEvent<Object>> wrapGmStream(Flux<String> content, GmTurnSpec spec) {
        return content
                .mapNotNull(token -> {
                    spec.fullResponse().append(token);
                    String safe = spec.deltaFilterRef().get().accept(token);
                    return safe.isEmpty() ? null : contentEvent(safe);
                })
                .onErrorResume(error -> {
                    PostEmissionStreamException signal = PostEmissionStreamException.findIn(error);
                    if (signal != null) {
                        log.warn("GM 流式在已发出内容后中断（{} 个 chunk），降级为 reset + 非流式重跑: gameStateId={}",
                                signal.getEmittedCount(), spec.gameStateId());
                        return Flux.concat(
                                // reset：指示前端清空当前 GM 气泡与叙述缓冲，后续为重发的完整内容
                                Flux.just(ServerSentEvent.<Object>builder()
                                        .data(Map.of("type", "reset"))
                                        .build()),
                                Flux.defer(() -> nonStreamRetry(spec)));
                    }
                    log.error("GM SSE 流式异常: gameStateId={}", spec.gameStateId(), error);
                    return Flux.just(ServerSentEvent.<Object>builder()
                            .data(Map.of("error", "GM 生成异常: " + error.getMessage()))
                            .build());
                });
    }
    
    /**
     * 非流式重跑（design D2/D3）：复用 TurnContext（不重新 prepareTurn，避免触发器/概率检定
     * 一轮双计），重置 fullResponse 与 deltaFilter 后以 call() 拿完整响应，过滤 state_delta
     * 后逐字下发。重跑自身失败：发 error 事件收尾（fullResponse 已清空，doOnComplete 的
     * 空值守卫跳过 finalizeTurn，不落库）。外层 doFinally 保证守卫释放。
     */
    private Flux<ServerSentEvent<Object>> nonStreamRetry(GmTurnSpec spec) {
        return Mono.fromCallable(() -> callGmContent(spec))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(rawText -> {
                    String text = rawText == null ? "" : rawText;
                    spec.fullResponse().setLength(0);
                    spec.fullResponse().append(text);
                    // 非流式全文一次性过滤 state_delta；finish 放行扣留的尾部非块内容。
                    // 重置后的过滤器对外层尾包 finish() 而言已是终态（返回空串）
                    StateDeltaStreamFilter filter = new StateDeltaStreamFilter();
                    String safe = filter.accept(text) + filter.finish();
                    spec.deltaFilterRef().set(filter);
                    return contentCharFlux(safe);
                })
                .onErrorResume(e -> {
                    spec.fullResponse().setLength(0);
                    log.error("GM 降级重跑失败: gameStateId={}", spec.gameStateId(), e);
                    return Flux.just(ServerSentEvent.<Object>builder()
                            .data(Map.of("error", "GM 生成异常: 降级重跑失败 - " + e.getMessage()))
                            .build());
                });
    }
    
    /** 非流式重跑的 GM 调用：与流式路径同构（首轮含 system prompt），仅 stream() 换 call()。 */
    private String callGmContent(GmTurnSpec spec) {
        if (spec.turnContext().firstRound() && spec.turnContext().systemPrompt() != null) {
            return spec.chatClient().prompt()
                    .system(spec.turnContext().systemPrompt())
                    .user(spec.turnContext().userPrompt())
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, spec.sessionId())
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, spec.tenantId())
                            .param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "rpg"))
                    .toolContext(spec.turnContext().toolContext())
                    .call()
                    .content();
        }
        return spec.chatClient().prompt()
                .user(spec.turnContext().userPrompt())
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, spec.sessionId())
                        .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, spec.tenantId())
                        .param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "rpg"))
                .toolContext(spec.turnContext().toolContext())
                .call()
                .content();
    }
    
    /**
     * 将已过滤的完整文本转为逐字内容事件流（降级重跑重放用；
     * 与 charStreamFlux 不同，不携带 opening 类型标记）。
     */
    private Flux<ServerSentEvent<Object>> contentCharFlux(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromStream(() -> text.chars().mapToObj(c -> String.valueOf((char) c)))
                .map(this::contentEvent);
    }

    /**
     * 存档卡片列表：返回富化后的卡片 DTO（世界名/玩家角色名/地点/轮次/最后游玩时间），
     * 按最后游玩时间倒序。字段缺失时为占位符 "-"，前端可直接渲染。
     */
    @GetMapping("/game/saves")
    public List<SaveCard> listSaveCards() {
        return workshopService.listSaveCards();
    }

    /**
     * 阻塞式删除存档：全量级联清理（DB 行 + 附属表 + session 事件 + 记忆目录与快照）。
     * <p>
     * 不存在 404；回合进行中 409（含事务内互斥复查）；清理失败 500；成功 204。
     */
    @DeleteMapping("/game/state/{gameStateId}")
    public ResponseEntity<Object> deleteSave(@PathVariable String gameStateId) {
        try {
            saveDeletionService.deleteSave(gameStateId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("删除存档执行失败: gameStateId={}", gameStateId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "删除存档失败: " + e.getMessage()));
        }
    }

    /**
     * 读档：加载已有 GameState 并关联新 session（卡片"继续"与既有读档链路共用）。
     */
    @PostMapping("/game/load")
    public Map<String, Object> loadGame(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        String sessionId = request.get("sessionId");
        GameState gs = workshopService.getGameState(gameStateId);
        if (gs == null) {
            return Map.of("error", "存档未找到");
        }
        // 更新 session 关联
        workshopService.updateGameSession(gameStateId, sessionId);
        return Map.of("success", true,
                "gameStateId", gameStateId,
                "worldId", gs.getWorldId(),
                "turnCount", gs.getTurnCount());
    }

    /**
     * 查询当前 GameState 供前端状态面板更新。
     */
    @GetMapping("/game/state")
    public GameState getGameState(@RequestParam String gameStateId) {
        return workshopService.getGameState(gameStateId);
    }

    /**
     * 会话历史查询（对话式回放数据源，含回溯锚点所需的事件 ID）。
     * <p>
     * 请求: GET /rpg/game/history?sessionId=xxx<br>
     * 响应: {"currentTurn": N, "messages":[{"role":"user|assistant","content":"...","eventId":"...","synthetic":true?},...]}
     * <p>
     * 复用 {@link SessionService#getEvents}，在读取返回时做 read-time 清洗（不改写存储）：
     * <ul>
     *   <li>user：从包裹 prompt 抽取玩家行动（{@link RpgHistoryCleaner}）；合成用户提示不返回</li>
     *   <li>assistant：移除 state_delta 块，仅保留叙述正文</li>
     *   <li>tool_call / tool_response：整条过滤（隐藏 GM 的 get_ 工具调用，D10）</li>
     *   <li>synthetic：合成助手摘要作为一条 assistant 返回（synthetic=true）</li>
     * </ul>
     * 每条消息携带 eventId（气泡挂载后作为回溯锚点）；顶层 currentTurn 供前端近似置灰
     * （session 事件无轮次号，后端校验仍是唯一真源）。空 sessionId 或空会话返回空列表，不报错。
     */
    @GetMapping("/game/history")
    public Map<String, Object> getHistory(@RequestParam String sessionId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        int currentTurn = 0;
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            // 跨租户会话历史不可达（session-isolation spec）：404，不泄露他租户会话存在性
            if (sessionTenantGuard.isForeign(sessionId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
            }
            List<SessionEvent> events = sessionService.getEvents(sessionId);
            for (SessionEvent event : events) {
                Message msg = event.getMessage();
                if (msg == null) {
                    continue;
                }
                // 隐藏工具事件（D10）：GM 的 get_ 工具调用不回放
                if (msg instanceof ToolResponseMessage) {
                    continue;
                }
                if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
                    continue;
                }

                boolean synthetic = event.isSynthetic();
                String content = msg.getText();

                if (msg instanceof UserMessage) {
                    // 合成用户提示（压缩产物）不返回
                    if (synthetic) {
                        continue;
                    }
                    String cleaned = rpgHistoryCleaner.cleanUserMessage(content);
                    if (cleaned == null || cleaned.isEmpty()) {
                        continue;
                    }
                    messages.add(historyEntry("user", cleaned, false, event.getId()));
                } else if (msg instanceof AssistantMessage) {
                    // 叙述正文（合成摘要原样保留）
                    String cleaned = synthetic
                            ? (content != null ? content.trim() : "")
                            : rpgHistoryCleaner.cleanAssistantMessage(content);
                    if (cleaned == null || cleaned.isEmpty()) {
                        continue;
                    }
                    messages.add(historyEntry("assistant", cleaned, synthetic, event.getId()));
                }
                // 其余类型（如 SystemMessage）不回放
            }
            // 顶层 currentTurn：前端按消息序从末尾倒推近似轮次做置灰（D7）
            GameState gs = workshopService.getGameStateBySessionId(sessionId);
            if (gs != null && gs.getTurnCount() != null) {
                currentTurn = gs.getTurnCount();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("currentTurn", currentTurn);
        result.put("messages", messages);
        return result;
    }

    /** 构建历史消息条目（含回溯锚点事件 ID）。 */
    private Map<String, Object> historyEntry(String role, String content, boolean synthetic, String eventId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("content", content);
        entry.put("eventId", eventId != null ? eventId : "");
        if (synthetic) {
            entry.put("synthetic", true);
        }
        return entry;
    }

    /**
     * 手动触发 RPG 会话上下文压缩。
     * <p>
     * 请求: POST /rpg/sessions/{sessionId}/compact<br>
     * 响应: {archivedCount, summaryPreview}
     * <p>
     * 沿用 RPG 自身的 {@link SlidingWindowCompactionStrategy}（maxEvents=30），与
     * {@code rpgSessionMemoryAdvisor} 的自动压缩同策略：保护 root/system 事件（首轮全量世界观
     * prompt 不被截断），仅归档最近 30 个轮次之外的旧叙事事件。滑窗不做 LLM 摘要、不产出
     * synthetic 合成事件，故 summaryPreview 为固定文案。
     * <p>
     * 与 chat 的 {@code POST /chat/sessions/{id}/compact}（RecursiveSummarization, maxEventsToKeep=10）
     * 各自独立，互不影响。
     */
    @PostMapping("/sessions/{sessionId}/compact")
    public ResponseEntity<Map<String, Object>> compactSession(@PathVariable String sessionId) {
        // 跨租户会话压缩不可达：404 且零副作用
        if (sessionTenantGuard.isForeign(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        try {
            // 与 rpgSessionMemoryAdvisor 自动压缩同策略：滑动窗口保留最近 30 个事件
            SlidingWindowCompactionStrategy strategy = SlidingWindowCompactionStrategy.builder()
                    .maxEvents(30)
                    .build();
            // 使用 always-fire trigger 无条件触发；SessionService.compact() 内部处理 CAS 与归档
            CompactionResult result = sessionService.compact(sessionId, req -> true, strategy);

            int archivedCount = result.archivedEvents().size();
            if (archivedCount == 0) {
                return ResponseEntity.ok(Map.of(
                        "archivedCount", 0,
                        "summaryPreview", "对话轮次过少，无需压缩"
                ));
            }

            log.info("RPG 手动压缩成功: sessionId={}, archivedCount={}, tokensSaved={}",
                    sessionId, archivedCount, result.tokensEstimatedSaved());

            // 滑窗无 synthetic 摘要，固定文案
            return ResponseEntity.ok(Map.of(
                    "archivedCount", archivedCount,
                    "summaryPreview", "压缩完成"
            ));
        } catch (Exception e) {
            log.error("RPG 压缩执行失败: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "archivedCount", 0,
                    "summaryPreview", "压缩失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 回溯：删除锚点所在轮次起的全部会话事件，游戏状态与记忆回滚到该轮之前。
     * <p>
     * 请求体: {"sessionId":"xxx","gameStateId":"yyy","anchorEventId":"zzz"}<br>
     * 响应: {success, removedCount, rolledBackToTurn, playerMessage, warning?} 或 400 {error}
     * <p>
     * 回溯与重新生成共用本端点（design D1）：重新生成 = 前端调本端点后自动重发 playerMessage。
     */
    @PostMapping("/game/backtrack")
    public ResponseEntity<Map<String, Object>> backtrack(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");
        String gameStateId = request.get("gameStateId");
        String anchorEventId = request.get("anchorEventId");

        if (sessionId == null || sessionId.isBlank()
                || gameStateId == null || gameStateId.isBlank()
                || anchorEventId == null || anchorEventId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sessionId / gameStateId / anchorEventId 均不能为空"));
        }

        // 跨租户会话回溯不可达（session-isolation spec）：404 且零副作用——
        // 否则他租户会话事件会被硬删，session-isolation 的核心红线
        if (sessionTenantGuard.isForeign(sessionId)) {
            log.warn("跨租户会话访问被拒（回溯）: sessionId={}, gameStateId={}", sessionId, gameStateId);
            return ResponseEntity.notFound().build();
        }

        try {
            RollbackService.RollbackResult result = rollbackService.backtrack(sessionId, gameStateId, anchorEventId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("removedCount", result.removedCount());
            body.put("rolledBackToTurn", result.rolledBackToTurn());
            body.put("playerMessage", result.playerMessage() != null ? result.playerMessage() : "");
            if (result.warning() != null) {
                body.put("warning", result.warning());
            }
            return ResponseEntity.ok(body);
        } catch (RollbackService.BacktrackException e) {
            log.warn("回溯被拒绝: sessionId={}, gameStateId={}, anchor={}: {}",
                    sessionId, gameStateId, anchorEventId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("回溯执行失败: sessionId={}, gameStateId={}", sessionId, gameStateId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "回溯执行失败: " + e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建回合落库尾包：从会话末尾取最近的 user / assistant 事件 ID。
     * <p>
     * 错误轮（onErrorResume 路径）assistant 事件可能缺失，assistantEventId 为 null；
     * 查询失败时返回 null（尾包缺席，前端靠刷新后拉 history 对账）。包级可见以便单测。
     */
    ServerSentEvent<Object> turnPersistedEvent(String sessionId) {
        try {
            List<SessionEvent> events = sessionService.getEvents(sessionId);
            String userEventId = null;
            String assistantEventId = null;
            for (int i = events.size() - 1; i >= 0; i--) {
                SessionEvent e = events.get(i);
                if (e.isSynthetic()) {
                    continue;
                }
                if (e.getMessage() instanceof UserMessage && userEventId == null) {
                    userEventId = e.getId();
                } else if (e.getMessage() instanceof AssistantMessage && assistantEventId == null) {
                    assistantEventId = e.getId();
                }
                if (userEventId != null && assistantEventId != null) {
                    break;
                }
            }
            if (userEventId == null && assistantEventId == null) {
                return null;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", "turn_persisted");
            data.put("userEventId", userEventId);
            data.put("assistantEventId", assistantEventId);
            return ServerSentEvent.<Object>builder().data(data).build();
        } catch (Exception e) {
            log.warn("构建 turn_persisted 尾包失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    /** 构建一条叙述内容 SSE 事件。 */
    private ServerSentEvent<Object> contentEvent(String content) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("content", content))
                .build();
    }

    /**
     * 将文本转为逐字 SSE 流。
     */
    private Flux<ServerSentEvent<Object>> charStreamFlux(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        // 按字符流式推送（模拟逐字展示）
        return Flux.fromStream(() -> text.chars().mapToObj(c -> String.valueOf((char) c)))
                .map(token -> ServerSentEvent.<Object>builder()
                        .data(Map.of("content", token, "type", "opening"))
                        .build());
    }
}
