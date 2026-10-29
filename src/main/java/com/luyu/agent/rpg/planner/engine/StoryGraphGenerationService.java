package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineRunStatus;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;
import com.luyu.agent.rpg.planner.model.StyleMode;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorkspaceStore;
import com.luyu.agent.rpg.story.engine.StoryGraphParseResult;
import com.luyu.agent.rpg.story.engine.StoryGraphParser;
import com.luyu.agent.tenancy.TenantContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * S4.5 逐幕生成 SSE 编排（story-graph-authoring tasks 2.3 /
 * story-graph-generation spec「生成治理与计费对齐」「planner S4.5 阶段接线」）。
 * <p>
 * 与 {@link StageGenerationService}（通用单流阶段生成）平行的幕级专用编排：
 * 幕划分真源是 S4 确认稿「## 分幕结构」小节（{@link StoryActOutlineParser}），
 * 逐幕串行调用 LLM（小契约单幕 JSON，spec「单幕生成小契约」——每幕 ≤25 节点
 * 的独立调用而非全图一次成型），每幕产物为 LLM 原文候选
 * （{@code 04.5-story-graph/candidate-NN.md = 第 NN 幕}，解析链在 tasks 2.4 接线）。
 * <p>
 * 事件协议（对齐 StageGenerationService 惯例 + 幕级扩展）：
 * <pre>
 * {"type":"stage_start","stage":"S4.5","label":"故事图骨架"}   流开始
 * {"type":"act_start","act":1,"total":3,"theme":...}          每幕开始
 * {"content":...,"act":1}                                     token 流（带幕归属）
 * {"type":"act_repair","act":2,"errors":[...]}               解析失败自动修复通知（2.4）
 * {"type":"act_done","act":1,"resumed":false}                 单幕解析通过落盘完成（resumed=断点续跑跳过）
 * {"type":"draft_ready","stage":"S4.5","version":N,"candidates":3}   全幕完成（成功尾包）
 * {"error":...}                                               失败（含前置快速失败）
 * [DONE]                                                      流终结标记
 * </pre>
 * <p>
 * <b>解析链（2.4，spec「单幕生成小契约」）</b>：每幕 LLM 原文在落盘前过
 * {@link StoryGraphParser#parse}（1.2 宽进严出：双 key 归一/标签提取/截断提取
 * /节点数上限 25）；解析失败走 LLM fallback 修复一次（非流式，对齐
 * {@code NpcKeyRepairService} 模式——修复是内部机制不透流前端），修复文再解析，
 * 通过则落盘修复文；仍失败（或修复调用失败/空）则本幕 FAILED 可重试：置
 * failureRef 短路后续幕（不重复耗 token）、置阶段 FAILED（重试走断点续跑，
 * 已落盘幕保留，run 状态不损坏，其他幕产物不受影响）。
 * <p>
 * 治理三段式对齐 spec「每次幕生成调用前 SHALL 过 ContentModerationGate 与
 * QuotaGuard」：第 1 幕在请求线程前置链（快速失败零副作用，同 createRun 惯例）；
 * 第 2+ 幕在幕流订阅时检查（长流中途额度用尽即拦下一幕）。moderation 审查的
 * 用户输入面当前为返工反馈文本（2.6 返工端点接线传入；首轮生成传 null 直接放行）。
 * 模型调用经 {@code ChatClientRegistry.forRole("planner")} 纯净角色 client，
 * 仅挂 {@link MeteringAdvisor} 归账（call type {@code story_graph}）。
 * <p>
 * <b>RUNNING 守卫</b>：与通用阶段生成共享 {@link PlannerGenerationGuard}
 * （spec「并发生成被守卫拦截」——幕生成 RUNNING 中触发本 run 任一阶段生成被拒，
 * 反向同理）；占位先于 markStageRunning（双层守卫顺序约束）。
 * <p>
 * <b>断点续跑</b>：阶段进流状态为 NOT_STARTED/FAILED 时，已落盘幕跳过重生成
 * （幂等覆盖写无撕裂，对齐 IpNameSearchService 断点续跑惯例）；AWAITING_CONFIRM/
 * CONFIRMED（返工轮/回退解锁）进入则为整轮重生成（候选覆盖写 + 状态全量重置 PENDING，
 * D5 语义；注入阶段级 rounds 与该幕上轮稿）。
 * <p>
 * <b>单幕返工（2.6，spec「幕级 L2 返工门」）</b>：返工端点携 targetAct 进入时
 * 仅重生目标幕（注入阶段级 rounds 反馈历史与该幕上轮稿），非目标幕跳过重生成
 * （读已有候选进前幕摘要链，候选状态不动——<b>单幕返工不波及其他已确认幕</b>）；
 * 收口不重置候选状态（目标幕在门层已置 PENDING），版本仍 v(N+1) 递增（版本树）。
 * 仅在阶段 AWAITING_CONFIRM 时可用（返工窗）。
 * <p>
 * <b>租户传播</b>（gm-async 模式，同 StageGenerationService）：请求线程捕获
 * tenantId → 幕流回调线程 withTenant 恢复 → finally 清理。
 */
@Service
public class StoryGraphGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StoryGraphGenerationService.class);

    private final PipelineRunService runService;
    private final PlannerWorkspaceStore workspaceStore;
    private final PipelineRunRepository runRepository;
    private final ChatClientRegistry chatClientRegistry;
    private final QuotaGuard quotaGuard;
    private final ContentModerationGate moderationGate;
    private final PlannerGenerationGuard generationGuard;
    private final StoryGraphPromptBuilder promptBuilder;
    private final StoryGraphDigest digest;
    private final StoryGraphParser parser;

    public StoryGraphGenerationService(PipelineRunService runService,
                                       PlannerWorkspaceStore workspaceStore,
                                       PipelineRunRepository runRepository,
                                       ChatClientRegistry chatClientRegistry,
                                       QuotaGuard quotaGuard,
                                       ContentModerationGate moderationGate,
                                       PlannerGenerationGuard generationGuard,
                                       StoryGraphPromptBuilder promptBuilder,
                                       StoryGraphDigest digest,
                                       StoryGraphParser parser) {
        this.runService = runService;
        this.workspaceStore = workspaceStore;
        this.runRepository = runRepository;
        this.chatClientRegistry = chatClientRegistry;
        this.quotaGuard = quotaGuard;
        this.moderationGate = moderationGate;
        this.generationGuard = generationGuard;
        this.promptBuilder = promptBuilder;
        this.digest = digest;
        this.parser = parser;
    }

    /**
     * 触发 S4.5 逐幕生成流（整轮：全量重生成，SSE）。
     *
     * @param userFeedback 返工反馈文本（返工轮注入；首轮生成传 null——
     *                     ContentModerationGate.check(null) 直接放行零成本）
     */
    public Flux<ServerSentEvent<Object>> generateActs(String runId, String userFeedback) {
        return generateActs(runId, userFeedback, null);
    }

    /**
     * 触发 S4.5 幕生成流（SSE）：整轮（{@code targetAct == null}，全量重生成）
     * 或单幕返工（{@code targetAct} 指定目标幕号，仅该幕重生成）。
     *
     * @param userFeedback 返工反馈文本（返工轮注入；首轮生成传 null）
     * @param targetAct    单幕返工目标幕号（null = 整轮；幕级返工门 2.6 接线，
     *                    仅阶段 AWAITING_CONFIRM 时可用）
     */
    public Flux<ServerSentEvent<Object>> generateActs(String runId, String userFeedback, Integer targetAct) {
        // ===== 请求线程同步前置（快速失败，error + [DONE] 两事件、零副作用）=====
        Optional<PipelineRun> runOpt = runRepository.findById(runId);
        if (runOpt.isEmpty()) {
            return failedFlux("管线 run 不存在: " + runId);
        }
        PipelineRun run = runOpt.get();
        PipelineRunStatus runStatus = PipelineRunStatus.valueOf(run.getStatus());
        if (runStatus == PipelineRunStatus.COMPLETED || runStatus == PipelineRunStatus.ABANDONED) {
            return failedFlux("run 已处于终态，不可再生成: " + runStatus);
        }

        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            return failedFlux("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }

        PipelineStage currentStage = PipelineStage.fromCode(manifest.getCurrentStage());
        PipelineStageStatus targetStatus =
                PipelineStageStatus.valueOf(manifest.stage(PipelineStage.S4P5_STORY_GRAPH.code()).getStatus());
        if (!PipelineStateMachine.canStartStage(currentStage, PipelineStage.S4P5_STORY_GRAPH, targetStatus)) {
            return failedFlux("阶段前置条件不满足：当前阶段为 " + currentStage.code()
                    + "，不能直接生成故事图骨架（跨阶段跳过被拒）");
        }
        // 单幕返工窗守卫：仅在待确认态可用（断点续跑/首生成无单幕语义）
        if (targetAct != null && targetStatus != PipelineStageStatus.AWAITING_CONFIRM) {
            return failedFlux("单幕返工仅在阶段待确认（AWAITING_CONFIRM）时可用，当前: " + targetStatus);
        }

        // 幕划分真源：S4 确认稿「## 分幕结构」小节（直接内容上游）
        String plotDraft = workspaceStore.readStageArtifact(runId, PipelineStage.S4_PLOT_STRUCTURE);
        if (plotDraft == null || plotDraft.isBlank()) {
            return failedFlux("S4 剧情结构确认稿缺失，无法解析分幕结构: " + runId);
        }
        List<StoryActOutlineParser.ActOutline> outlines;
        try {
            outlines = StoryActOutlineParser.parse(plotDraft);
        } catch (IllegalArgumentException e) {
            return failedFlux("S4 确认稿分幕结构解析失败: " + e.getMessage());
        }
        if (outlines.isEmpty()) {
            return failedFlux("S4 确认稿缺少「## 分幕结构」小节，无法分幕生成（请先生成含分幕结构的剧情结构稿）");
        }
        if (targetAct != null && outlines.stream().noneMatch(o -> o.actNumber() == targetAct)) {
            return failedFlux("目标幕号超出分幕结构范围: " + targetAct
                    + "（共 " + outlines.size() + " 幕）");
        }

        final String tenantId = TenantContext.getTenantId();
        // 第 1 幕治理三段式前置（第 2+ 幕在幕流内逐幕检查；单幕返工目标为幕 1
        // 时同样前置，其余单幕目标由幕流内检查——避免非目标幕被重复治理）
        if (targetAct == null || targetAct == 1) {
            try {
                governanceCheck(userFeedback, tenantId);
            } catch (QuotaExceededException qe) {
                log.info("配额超限拒绝幕生成: runId={}", runId);
                return failedFlux(qe.getMessage());
            } catch (ModerationBlockedException me) {
                log.info("输入命中内容审查，拒答幕生成: runId={}", runId);
                return failedFlux(me.getMessage());
            }
        }

        // 内存活跃流占位（必须先于 markStageRunning，双层守卫顺序约束）
        if (!generationGuard.tryAcquire(runId, PipelineStage.S4P5_STORY_GRAPH)) {
            return failedFlux("该 run 有阶段正在生成中，请等待完成后再操作（单阶段 RUNNING 守卫）");
        }

        final int totalActs = outlines.size();
        // 断点续跑：NOT_STARTED/FAILED 进流时已落盘幕跳过；返工/解锁重生成全量
        final boolean resumeMode = targetStatus == PipelineStageStatus.NOT_STARTED
                || targetStatus == PipelineStageStatus.FAILED;
        final List<PipelineManifest.ReRound> actRounds =
                manifest.stage(PipelineStage.S4P5_STORY_GRAPH.code()).getRounds();
        // 候选 title 真源 = S4 分幕大纲主题（幕候选是 LLM 原文 JSON，无标题可提取）
        final List<String> actThemes = outlines.stream()
                .map(StoryActOutlineParser.ActOutline::theme)
                .toList();

        try {
            runService.markStageRunning(runId, PipelineStage.S4P5_STORY_GRAPH, true);
        } catch (RuntimeException e) {
            generationGuard.release(runId, PipelineStage.S4P5_STORY_GRAPH);
            log.warn("幕生成前置失败: runId={}", runId, e);
            return failedFlux(e.getMessage());
        }

        // ===== 幕循环编排（Flux.concat 串行：前幕落盘后才组装下一幕 prompt）=====
        // 回调线程顺序执行（concat 语义保证），可变累积器无并发竞争
        final List<String> actDrafts = new ArrayList<>();
        final AtomicReference<String> failureRef = new AtomicReference<>();
        final AtomicReference<Integer> versionRef = new AtomicReference<>();

        List<Flux<ServerSentEvent<Object>>> actFluxes = new ArrayList<>(totalActs);
        for (StoryActOutlineParser.ActOutline outline : outlines) {
            actFluxes.add(actFlux(runId, outline, totalActs, resumeMode, actRounds, actDrafts,
                    failureRef, userFeedback, tenantId, manifest, plotDraft, targetAct));
        }

        return Flux.concat(
                        Flux.just(stageStartEvent()),
                        Flux.concat(actFluxes)
                                .onErrorResume(error -> {
                                    log.error("幕生成 SSE 异常: runId={}", runId, error);
                                    withTenant(tenantId, () ->
                                            runService.markStageFailed(runId, PipelineStage.S4P5_STORY_GRAPH));
                                    failureRef.compareAndSet(null, "幕生成异常: " + error.getMessage());
                                    return Flux.empty();
                                }),
                        // 尾包 defer：失败发 error；成功收口（快照 + 候选初始化 + AWAITING_CONFIRM）
                        Flux.defer(() -> {
                            String failure = failureRef.get();
                            if (failure != null) {
                                return Flux.just(errorEvent(failure), doneEvent());
                            }
                            return finalizeActs(runId, actDrafts, actThemes, failureRef, versionRef,
                                    tenantId, targetAct != null);
                        }))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        // 前端断流/中止：显式置 FAILED（重试走断点续跑，已落盘幕保留）
                        log.info("幕生成被取消，置 FAILED: runId={}", runId);
                        withTenant(tenantId, () ->
                                runService.markStageFailed(runId, PipelineStage.S4P5_STORY_GRAPH));
                    }
                    generationGuard.release(runId, PipelineStage.S4P5_STORY_GRAPH);
                });
    }

    /** 当前活跃流探测转发（测试与诊断用，与 StageGenerationService 共守卫）。 */
    public Optional<PipelineStage> activeStage(String runId) {
        return generationGuard.activeStage(runId);
    }

    // ==================== 单幕流 ====================

    /**
     * 组装单幕生成流：单幕返工非目标幕跳过 → 治理三段式（第 2+ 幕）→
     * 断点续跑跳过 → prompt 组装（前幕锚点链/轨道表摘要 + 词汇表沿用）→
     * LLM 流（call type story_graph）→ 落盘 writeCandidateItem + act_done。
     * <p>
     * 单幕返工模式（{@code targetAct != null}）非目标幕在治理检查之前跳过
     * 重生成（不耗治理与 token），已有候选直接进前幕摘要链、候选状态不动
     * （不波及其他已确认幕）；无候选即幕级前置缺失快速失败。跳过事件复用
     * act_done（resumed=true，前端展示语义同断点续跑跳过）。
     */
    private Flux<ServerSentEvent<Object>> actFlux(String runId, StoryActOutlineParser.ActOutline outline,
                                                  int totalActs, boolean resumeMode,
                                                  List<PipelineManifest.ReRound> actRounds,
                                                  List<String> actDrafts, AtomicReference<String> failureRef,
                                                  String userFeedback, String tenantId,
                                                  PipelineManifest manifest, String plotDraft,
                                                  Integer targetAct) {
        int actNumber = outline.actNumber();
        String candidateId = String.format("candidate-%02d", actNumber);
        return Flux.defer(() -> {
            // 短路检查：前一幕已被拦截（quota/moderation/落盘失败置 failureRef）时
            // 后续幕全部空流（Flux.empty 完成不阻断 concat，必须显式短路防继续耗 token）
            if (failureRef.get() != null) {
                return Flux.<ServerSentEvent<Object>>empty();
            }

            // 候选读取（单幕返工非目标幕跳过 / 断点续跑 / 返工轮上轮稿共用；
            // 幕间回调线程无租户上下文，租户恢复见 readCandidateQuietly javadoc）
            String existing = readCandidateQuietly(runId, candidateId, tenantId);

            // 单幕返工（2.6）：非目标幕跳过重生成（候选状态不动，不波及其他
            // 已确认幕——已落盘候选直接进前幕摘要链）；无候选即前置缺失快速失败
            if (targetAct != null && actNumber != targetAct) {
                if (existing == null || existing.isBlank()) {
                    failAct(runId, actNumber, "第 " + actNumber
                            + " 幕无候选（单幕返工前置缺失，请走整轮返工）", failureRef, tenantId);
                    return Flux.empty();
                }
                actDrafts.add(existing);
                return Flux.just(actDoneEvent(actNumber, true));
            }

            // 第 2+ 幕治理三段式（幕 1 已在请求线程前置链过，不重复检查）
            // moderation 审用户输入面（返工反馈文本；首轮 null 放行）+ quota 逐幕前检查
            if (actNumber > 1) {
                try {
                    governanceCheck(userFeedback, tenantId);
                } catch (QuotaExceededException qe) {
                    log.info("配额超限拦截第 {} 幕生成: runId={}", actNumber, runId);
                    withTenant(tenantId, () ->
                            runService.markStageFailed(runId, PipelineStage.S4P5_STORY_GRAPH));
                    failureRef.compareAndSet(null, qe.getMessage());
                    return Flux.<ServerSentEvent<Object>>empty();
                } catch (ModerationBlockedException me) {
                    log.info("输入命中内容审查，拦截第 {} 幕生成: runId={}", actNumber, runId);
                    withTenant(tenantId, () ->
                            runService.markStageFailed(runId, PipelineStage.S4P5_STORY_GRAPH));
                    failureRef.compareAndSet(null, me.getMessage());
                    return Flux.<ServerSentEvent<Object>>empty();
                }
            }

            // 断点续跑：已落盘幕跳过重生成（幂等保留，失败重试不重复耗 token）
            if (resumeMode && existing != null && !existing.isBlank()) {
                actDrafts.add(existing);
                return Flux.just(actDoneEvent(actNumber, true));
            }

            // 前幕摘要（锚点链 + 出口轨道表 + 词汇表提取；第 1 幕为 null）
            StoryGraphDigest.Digest previousDigest = actDrafts.isEmpty()
                    ? new StoryGraphDigest.Digest(null, null)
                    : digest.build(actDrafts);

            // 返工轮注入：该幕上轮稿（非续跑模式且已有候选时；首轮生成 null）
            String lastActDraft = resumeMode ? null : existing;

            StyleMode styleMode = StyleMode.fromCode(manifest.getStyleMode());
            String prompt = promptBuilder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                    styleMode, actNumber, totalActs, outline, plotDraft, manifest,
                    previousDigest.summaryText(), previousDigest.stateSchema(),
                    actRounds, lastActDraft));

            StringBuilder fullResponse = new StringBuilder();
            return Flux.concat(
                    Flux.just(actStartEvent(actNumber, totalActs, outline.theme())),
                    chatClientRegistry.forRole("planner")
                            .prompt()
                            .user(prompt)
                            .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "story_graph"))
                            .stream()
                            .content()
                            .mapNotNull(token -> {
                                fullResponse.append(token);
                                return contentEvent(token, actNumber);
                            }))
                    // 尾段（流完成后）：解析链（2.4）→ 修复一次 → 落盘 + act_done。
                    // 解析/修复在 defer 同步段执行（串行幕语义：幕 N 落盘后才组装幕 N+1）
                    .concatWith(Flux.defer(() -> parseAndPersistAct(runId, candidateId, actNumber,
                            fullResponse, actDrafts, failureRef, tenantId)));
        });
    }

    /**
     * 单幕解析链与落盘（tasks 2.4，流完成后尾段 defer 内同步执行）：空响应守卫
     * → 1.2 归一解析（含节点数上限 25）→ 解析失败 LLM fallback 修复一次（非流式）
     * → 修复文再解析 → 通过才落盘（原文或修复文）+ act_done + 累积进前幕摘要列表；
     * 仍失败置本幕 FAILED 可重试（spec「坏 JSON 修复一次后仍坏则失败可重试」：
     * failureRef 短路后续幕、阶段 FAILED、已落盘幕保留、run 状态不损坏）。
     * 落盘失败同样置 failureRef（防御性：自身不能再抛）。
     */
    private Flux<ServerSentEvent<Object>> parseAndPersistAct(String runId, String candidateId,
                                                              int actNumber,
                                                              StringBuilder fullResponse,
                                                              List<String> actDrafts,
                                                              AtomicReference<String> failureRef,
                                                              String tenantId) {
        // 空响应守卫（在解析之前——空文无修复意义）
        if (fullResponse.length() == 0) {
            log.warn("第 {} 幕生成为空响应: runId={}", actNumber, runId);
            failAct(runId, actNumber, "第 " + actNumber
                    + " 幕模型返回空内容，本次生成未产出骨架", failureRef, tenantId);
            return Flux.empty();
        }
        String draft = fullResponse.toString();
        // 幕号 ground truth 注入解析（防漏写顶层 act/id 字段被误补 act1 连锁误报命名空间违规）
        StoryGraphParseResult result = parser.parse(draft, actNumber);
        if (!result.ok()) {
            // 解析失败 → LLM fallback 修复一次（发 act_repair 通知，含原始错误清单）
            log.info("第 {} 幕解析未过（{} 项错误），走 LLM 修复一次: runId={}",
                    actNumber, result.getErrors().size(), runId);
            ServerSentEvent<Object> repairNotice = actRepairEvent(actNumber, result.getErrors());
            String repaired = repairAct(actNumber, draft, result.getErrors(), tenantId);
            if (repaired == null) {
                failAct(runId, actNumber, "第 " + actNumber
                        + " 幕解析失败且修复调用未产出（可重试；已落盘幕不受影响）", failureRef, tenantId);
                return Flux.just(repairNotice);
            }
            StoryGraphParseResult repairedResult = parser.parse(repaired, actNumber);
            if (!repairedResult.ok()) {
                failAct(runId, actNumber, "第 " + actNumber + " 幕解析失败（修复一次后仍非法："
                        + String.join("；", repairedResult.getErrors())
                        + "）。本幕可重试，其他幕产物不受影响", failureRef, tenantId);
                return Flux.just(repairNotice);
            }
            log.info("第 {} 幕解析修复成功，落盘修复文: runId={}", actNumber, runId);
            draft = repaired;
            Flux<ServerSentEvent<Object>> tail = persistActOrEmpty(runId, candidateId, actNumber,
                    draft, actDrafts, failureRef, tenantId);
            if (failureRef.get() != null) {
                return Flux.concat(Flux.just(repairNotice), tail);
            }
            return Flux.concat(Flux.just(repairNotice), tail,
                    Flux.just(actDoneEvent(actNumber, false)));
        }
        Flux<ServerSentEvent<Object>> tail = persistActOrEmpty(runId, candidateId, actNumber,
                draft, actDrafts, failureRef, tenantId);
        if (failureRef.get() != null) {
            return tail;
        }
        return Flux.concat(tail, Flux.just(actDoneEvent(actNumber, false)));
    }

    /** 落盘（LLM 原文或修复文）+ 累积进前幕摘要列表；失败置 failureRef 返回空流。 */
    private Flux<ServerSentEvent<Object>> persistActOrEmpty(String runId, String candidateId,
                                                             int actNumber, String draft,
                                                             List<String> actDrafts,
                                                             AtomicReference<String> failureRef,
                                                             String tenantId) {
        try {
            withTenant(tenantId, () ->
                    workspaceStore.writeCandidateItem(runId, PipelineStage.S4P5_STORY_GRAPH,
                            candidateId, draft));
        } catch (RuntimeException e) {
            log.error("第 {} 幕候选落盘失败: runId={}", actNumber, runId, e);
            failAct(runId, actNumber, "第 " + actNumber + " 幕候选落盘失败: " + e.getMessage(),
                    failureRef, tenantId);
            return Flux.empty();
        }
        actDrafts.add(draft);
        return Flux.empty();
    }

    /** 本幕失败统一处理：置阶段 FAILED（可重试）+ failureRef 短路后续幕。 */
    private void failAct(String runId, int actNumber, String message,
                         AtomicReference<String> failureRef, String tenantId) {
        withTenant(tenantId, () ->
                runService.markStageFailed(runId, PipelineStage.S4P5_STORY_GRAPH));
        failureRef.compareAndSet(null, message);
    }

    /**
     * 解析失败的单幕 LLM 修复调用（非流式，对齐 NpcKeyRepairService 模式：
     * 修复是内部机制不透流前端；quota 前置——修复也是幕生成 LLM 调用）。
     * 修复输出同样归账 story_graph。返回修复输出原文；调用失败/空响应/
     * quota 拦截返回 null（调用方置本幕 FAILED 可重试）。
     */
    private String repairAct(int actNumber, String draft, List<String> errors, String tenantId) {
        try {
            quotaGuard.check(tenantId);
        } catch (QuotaExceededException qe) {
            log.info("第 {} 幕修复调用被配额拦截", actNumber);
            return null;
        }
        try {
            String prompt = promptBuilder.buildActRepairPrompt(actNumber, draft, errors);
            String response = chatClientRegistry.forRole("planner")
                    .prompt()
                    .user(prompt)
                    .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "story_graph"))
                    .call()
                    .content();
            if (response == null || response.isBlank()) {
                log.warn("第 {} 幕修复调用返回空响应", actNumber);
                return null;
            }
            return response;
        } catch (RuntimeException e) {
            log.warn("第 {} 幕修复调用失败: {}", actNumber, e.getMessage());
            return null;
        }
    }

    // ==================== 全幕收口 ====================

    /**
     * 全幕完成收口（尾包 defer 内，回调线程）：版本快照（候选聚合全历史）→
     * 候选状态初始化（title = 幕主题行，PENDING 重置 D5）→ AWAITING_CONFIRM +
     * version+1 + manifest 摘要行（「N 幕：主题…」）。收口失败置 failureRef
     * 尾包兜底 error（防御性置 FAILED）。
     * <p>
     * 单幕返工模式（{@code singleActMode}）跳过候选初始化：整轮重置 PENDING
     * 会抹掉其他幕的确认状态（违反 spec「单幕返工不波及他幕」）——目标幕
     * 候选已在门层（reworkAct）置 PENDING，其余幕状态保持原样。版本快照与
     * 版本递增照常（单幕新稿 + 其他幕原候选聚合，版本树连续 vN→vN+1）。
     */
    private Flux<ServerSentEvent<Object>> finalizeActs(String runId, List<String> actDrafts,
                                                       List<String> actThemes,
                                                       AtomicReference<String> failureRef,
                                                       AtomicReference<Integer> versionRef, String tenantId,
                                                       boolean singleActMode) {
        Integer version = withTenantValue(tenantId, () -> {
            try {
                int nextVersion = workspaceStore.readManifest(runId)
                        .stage(PipelineStage.S4P5_STORY_GRAPH.code()).getVersion() + 1;
                workspaceStore.writeVersionSnapshot(runId, PipelineStage.S4P5_STORY_GRAPH,
                        nextVersion, String.join("\n\n", actDrafts));
                if (!singleActMode) {
                    runService.initStageCandidates(runId, PipelineStage.S4P5_STORY_GRAPH,
                            actTitles(actThemes));
                }
                return runService.markStageCompleted(runId, PipelineStage.S4P5_STORY_GRAPH,
                        actSummary(actThemes));
            } catch (RuntimeException e) {
                log.error("幕生成收口失败，置 FAILED: runId={}", runId, e);
                runService.markStageFailed(runId, PipelineStage.S4P5_STORY_GRAPH);
                failureRef.compareAndSet(null, "幕生成收口失败: " + e.getMessage());
                return null;
            }
        });
        if (version == null) {
            return Flux.just(errorEvent(failureRef.get()), doneEvent());
        }
        versionRef.set(version);
        return Flux.just(draftReadyEvent(version, actDrafts.size()), doneEvent());
    }

    // ==================== 治理三段式（每幕调用前，spec「生成治理与计费对齐」） ====================

    /**
     * 每幕调用前的治理检查：moderation（用户输入面 = 返工反馈文本，首轮 null
     * 直接放行零成本——2.6 返工端点接线后传入反馈原文）+ quota（长流中途
     * 额度用尽即拦下一幕）。
     *
     * @throws QuotaExceededException       配额超限（QuotaGuard 拦截）
     * @throws ModerationBlockedException   输入命中内容审查（消息为拒答话术）
     */
    private void governanceCheck(String userFeedback, String tenantId)
            throws QuotaExceededException, ModerationBlockedException {
        if (moderationGate.isEnabled()) {
            var moderation = moderationGate.check(userFeedback);
            if (moderation.blocked()) {
                throw new ModerationBlockedException(moderationGate.refusalMessage());
            }
        }
        quotaGuard.check(tenantId);
    }

    /** moderation 拒答信号（消息即统一拒答话术，尾包 error 呈现）。 */
    private static final class ModerationBlockedException extends RuntimeException {
        ModerationBlockedException(String message) {
            super(message);
        }
    }

    // ==================== 候选 title / 摘要行 ====================

    /**
     * 候选 title（幕主题行，「第 N 幕「主题」」格式，对齐 Digest 行）：
     * 真源是 S4 分幕大纲主题（幕候选为 LLM 原文 JSON，无标题可提取）。
     */
    static List<String> actTitles(List<String> actThemes) {
        List<String> titles = new ArrayList<>(actThemes.size());
        for (int i = 0; i < actThemes.size(); i++) {
            String theme = actThemes.get(i);
            titles.add("第 " + (i + 1) + " 幕「" + (theme == null ? "" : theme) + "」");
        }
        return titles;
    }

    /** 阶段摘要行（「N 幕：主题、主题…」，步骤条与上游摘要段消费）。 */
    static String actSummary(List<String> actThemes) {
        String joined = actThemes.stream()
                .map(t -> t == null ? "" : t)
                .collect(Collectors.joining("、"));
        return actThemes.size() + " 幕：" + joined;
    }

    // ==================== 工具 ====================

    /**
     * 候选读取兜底（续跑判定用；读失败视为无候选走重生成，不快速失败）。
     * <p>
     * 幕间 defer 在 concat 尾流完成线程（LLM 流回调，无租户上下文）执行：
     * 读取走 workspaceStore（runDir 依赖 TenantContext），须 withTenantValue
     * 恢复租户再读（gm-async 租户传播惯例，同落盘侧 persistActOrEmpty）——
     * 否则无租户线程读取抛异常被吞成 null，单幕返工误报「非目标幕无候选」、
     * 断点续跑幂等跳过失效（已落盘幕重生成，白耗 token）。
     */
    private String readCandidateQuietly(String runId, String candidateId, String tenantId) {
        try {
            return withTenantValue(tenantId, () ->
                    workspaceStore.readCandidateItem(runId, PipelineStage.S4P5_STORY_GRAPH, candidateId));
        } catch (RuntimeException e) {
            log.warn("候选读取失败（按无候选处理走重生成）: runId={}, candidate={}",
                    runId, candidateId, e);
            return null;
        }
    }

    // ==================== 租户传播（gm-async 模式，同 StageGenerationService） ====================

    private void withTenant(String tenantId, Runnable action) {
        boolean restored = false;
        if (!TenantContext.isPresent() && tenantId != null && !tenantId.isBlank()) {
            TenantContext.set("planner-async", tenantId);
            restored = true;
        }
        try {
            action.run();
        } finally {
            if (restored) {
                TenantContext.clear();
            }
        }
    }

    /** 带返回值的租户传播（同 withTenant 语义）。 */
    private <T> T withTenantValue(String tenantId, Supplier<T> action) {
        boolean restored = false;
        if (!TenantContext.isPresent() && tenantId != null && !tenantId.isBlank()) {
            TenantContext.set("planner-async", tenantId);
            restored = true;
        }
        try {
            return action.get();
        } finally {
            if (restored) {
                TenantContext.clear();
            }
        }
    }

    // ==================== SSE 事件构造（对齐 StageGenerationService 惯例 + 幕级扩展） ====================

    private ServerSentEvent<Object> stageStartEvent() {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "stage_start",
                        "stage", PipelineStage.S4P5_STORY_GRAPH.code(),
                        "label", PipelineStage.S4P5_STORY_GRAPH.label()))
                .build();
    }

    private ServerSentEvent<Object> actStartEvent(int actNumber, int totalActs, String theme) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "act_start", "act", actNumber,
                        "total", totalActs, "theme", theme == null ? "" : theme))
                .build();
    }

    private ServerSentEvent<Object> contentEvent(String token, int actNumber) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("content", token, "act", actNumber))
                .build();
    }

    private ServerSentEvent<Object> actDoneEvent(int actNumber, boolean resumed) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "act_done", "act", actNumber, "resumed", resumed))
                .build();
    }

    /** 解析失败自动修复通知（2.4；错误清单透出，前端未知 type 忽略无害）。 */
    private ServerSentEvent<Object> actRepairEvent(int actNumber, List<String> errors) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "act_repair", "act", actNumber, "errors", List.copyOf(errors)))
                .build();
    }

    private ServerSentEvent<Object> draftReadyEvent(int version, int candidates) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "draft_ready");
        data.put("stage", PipelineStage.S4P5_STORY_GRAPH.code());
        data.put("version", version);
        data.put("candidates", candidates);
        return ServerSentEvent.<Object>builder().data(data).build();
    }

    private ServerSentEvent<Object> errorEvent(String message) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("error", message))
                .build();
    }

    private ServerSentEvent<Object> doneEvent() {
        return ServerSentEvent.<Object>builder()
                .data("[DONE]")
                .build();
    }

    private Flux<ServerSentEvent<Object>> failedFlux(String message) {
        return Flux.just(errorEvent(message), doneEvent());
    }
}
