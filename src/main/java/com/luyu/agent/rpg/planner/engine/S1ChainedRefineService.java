package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineRunStatus;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;
import com.luyu.agent.rpg.planner.model.SourceMode;
import com.luyu.agent.rpg.planner.model.StyleMode;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorkspaceStore;
import com.luyu.agent.tenancy.TenantContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * S1 text 模式多轮链式提炼 SSE 编排（planner-s1-chained-refinement tasks 2.2/2.3/2.4 /
 * design D4-D6，spec「块笔记逐块串行生成与落盘」「块级断点续跑」「汇总轮成稿与收口」）。
 * <p>
 * 与 {@link StoryGraphGenerationService}（S4.5 幕级串行）同构的块级串行编排：
 * 超阈值原文经 {@link SourceChunker} 确定性切块（三级降级）→ 逐块串行非流式 LLM
 * 调用产出结构化笔记落盘 {@code 01-ip-analysis/notes/chunk-NN.md} → 汇总轮注入
 * 全部笔记 + faithful/remix 成稿模板（复用 {@link IpAnalysisGenerator} 的指令）
 * 流式生成拆解稿 → 收口对齐 {@code StageGenerationService.finalizeDraft} 的 L1
 * 分支（产物落盘 + 版本快照 + AWAITING_CONFIRM + draft_ready + [DONE]）。
 * <p>
 * 事件协议（对齐 StageGenerationService 惯例 + 块级 tool 事件扩展）：
 * <pre>
 * {"type":"stage_start","stage":"S1","label":"IP 拆解"}          流开始
 * {"tool":"start","name":"切块策略","icon":..,"description":..}    切块策略提示（自动结构化/降级）
 * {"tool":"start","name":"分块提炼 k/N","icon":..,"description":..}  每块开始（renderToolEvent 现成渲染）
 * {"tool":"end","name":"分块提炼 k/N","status":"ok"}              每块笔记落盘完成
 * {"content":...}                                                 汇总轮 token 流（草稿区可观感）
 * {"type":"draft_ready","stage":"S1","version":N}                 汇总成稿（成功尾包）
 * {"error":...}                                                   失败（含前置快速失败）
 * [DONE]                                                          流终结标记
 * </pre>
 * 块轮 SHALL NOT 向前端流笔记 token（中间笔记不进草稿区，spec「块进度事件协议」）——
 * 块调用走非流式（对齐 StoryGraphGenerationService.repairAct 模式），进度经既有
 * tool 事件协议呈现（前端生成链路零改动复用 renderToolEvent）。
 * <p>
 * <b>断点续跑</b>：阶段进流状态 NOT_STARTED/FAILED 时已落盘非空块笔记跳过重生成
 * （{@link PlannerWorkspaceStore#readChunkNotes}，同 ip_name 搜索轮/S4.5 幕级惯例；
 * 切块确定性保证重算切分与已落盘笔记的序号对齐）。AWAITING_CONFIRM 进入为返工模式：
 * 只重生汇总轮（块笔记是原文的确定性派生物，反馈注入汇总 prompt，v1 决策——省
 * 90% 重生成 token；用户对笔记本身不满走 L1 编辑门改稿兜底）。
 * <p>
 * <b>治理三段式</b>（spec「每块调用前过治理检查」）：第 1 块在请求线程前置链
 * （返工模式审 userFeedback）；第 2+ 块在块流订阅时检查（长流中途额度用尽即拦
 * 下一块）；汇总轮调用前 quota 再检查。quota 拦截或任一块失败置 failureRef 短路
 * 后续块（不重复耗 token）+ 阶段 FAILED 可重试（已落盘块保留）。
 * <p>
 * <b>计费</b>（tasks 2.4）：块轮与汇总轮统一 call type {@code s1_refine}，
 * 与普通阶段（{@code planner}）、故事图幕级（{@code story_graph}）三分可查。
 * <p>
 * <b>RUNNING 守卫</b>：与通用阶段生成共享 {@link PlannerGenerationGuard}；
 * 占位先于 markStageRunning（双层守卫顺序约束）。分叉方
 * （StageGenerationService）在守卫占位<b>之前</b>转入本类，避免双占位死锁。
 * <p>
 * <b>租户传播</b>（gm-async 模式，同 StageGenerationService）：请求线程捕获
 * tenantId → 块流/汇总流回调线程 withTenant 恢复 → finally 清理。
 */
@Service
public class S1ChainedRefineService {

    private static final Logger log = LoggerFactory.getLogger(S1ChainedRefineService.class);

    /** 链式提炼全程 metering call type（块轮 + 汇总轮统一，与 planner/story_graph 三分） */
    static final String S1_REFINE_CALL_TYPE = "s1_refine";

    private final PipelineRunService runService;
    private final PlannerWorkspaceStore workspaceStore;
    private final PipelineRunRepository runRepository;
    private final ChatClientRegistry chatClientRegistry;
    private final QuotaGuard quotaGuard;
    private final ContentModerationGate moderationGate;
    private final PlannerGenerationGuard generationGuard;
    private final RpgPlannerProperties properties;

    public S1ChainedRefineService(PipelineRunService runService,
                                   PlannerWorkspaceStore workspaceStore,
                                   PipelineRunRepository runRepository,
                                   ChatClientRegistry chatClientRegistry,
                                   QuotaGuard quotaGuard,
                                   ContentModerationGate moderationGate,
                                   PlannerGenerationGuard generationGuard,
                                   RpgPlannerProperties properties) {
        this.runService = runService;
        this.workspaceStore = workspaceStore;
        this.runRepository = runRepository;
        this.chatClientRegistry = chatClientRegistry;
        this.quotaGuard = quotaGuard;
        this.moderationGate = moderationGate;
        this.generationGuard = generationGuard;
        this.properties = properties;
    }

    /**
     * 触发 S1 链式提炼流（SSE）。两个入口：
     * <ul>
     *   <li>首轮/断点续跑：StageGenerationService 守卫占位前分叉转入
     *       （原文超链式阈值的 text run；userFeedback 传 null）；</li>
     *   <li>返工（仅汇总轮）：StageGateService.reworkStage 路由（AWAITING_CONFIRM，
     *       userFeedback 为返工反馈——块笔记零重生成，直接注入汇总轮）。</li>
     * </ul>
     *
     * @param userFeedback 返工反馈文本（返工轮注入汇总 prompt 与 moderation 审查面；
     *                     首轮/断点续跑传 null——check(null) 直接放行零成本）
     */
    public Flux<ServerSentEvent<Object>> generateChained(String runId, String userFeedback) {
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

        PipelineStage stage = PipelineStage.S1_IP_ANALYSIS;
        PipelineStage currentStage = PipelineStage.fromCode(manifest.getCurrentStage());
        PipelineStageStatus targetStatus =
                PipelineStageStatus.valueOf(manifest.stage(stage.code()).getStatus());
        if (!PipelineStateMachine.canStartStage(currentStage, stage, targetStatus)) {
            return failedFlux("阶段前置条件不满足：当前阶段为 " + currentStage.code()
                    + "，不能直接生成 " + stage.code() + "（跨阶段跳过被拒）");
        }

        // 原料与阈值防御（分叉方已判定超阈值；这里防御直调/配置漂移路径）。
        // planner-html-source D6：原料统一 readS1Material（text 原文 / ip_name
        // IP 名头 + pages 合并虚拟原文；null = ip_name 存量 run 无 pages 落盘）
        String sourceText = workspaceStore.readS1Material(runId, manifest.getSourceMode());
        if (sourceText == null || sourceText.length() <= properties.getChainThresholdChars()) {
            return failedFlux("S1 链式提炼需原料长度超过链式阈值（当前 "
                    + properties.getChainThresholdChars() + " 字），请走单流生成路径");
        }

        // 确定性切块（三级降级；断点续跑重算切分与已落盘笔记序号对齐的前提）
        SourceChunker.ChunkResult chunkResult =
                SourceChunker.split(sourceText, properties.getChunkMaxChars());
        if (chunkResult.size() == 0) {
            return failedFlux("原文切分为零块，无法链式提炼: " + runId);
        }

        final int totalChunks = chunkResult.size();
        // 断点续跑：NOT_STARTED/FAILED 跳过已落盘块；AWAITING_CONFIRM（返工）只跑汇总轮
        final boolean resumeMode = targetStatus == PipelineStageStatus.NOT_STARTED
                || targetStatus == PipelineStageStatus.FAILED;

        final String tenantId = TenantContext.getTenantId();
        // 第 1 块治理三段式前置（第 2+ 块在块流内逐块检查；返工模式无块轮——
        // userFeedback 是唯一的用户输入面，moderation 在此审）
        if (resumeMode) {
            try {
                governanceCheck(null, tenantId);
            } catch (QuotaExceededException qe) {
                log.info("配额超限拒绝链式提炼: runId={}", runId);
                return failedFlux(qe.getMessage());
            } catch (ModerationBlockedException me) {
                log.info("输入命中内容审查，拒答链式提炼: runId={}", runId);
                return failedFlux(me.getMessage());
            }
        } else {
            try {
                governanceCheck(userFeedback, tenantId);
            } catch (QuotaExceededException qe) {
                log.info("配额超限拒绝汇总返工: runId={}", runId);
                return failedFlux(qe.getMessage());
            } catch (ModerationBlockedException me) {
                log.info("输入命中内容审查，拒答汇总返工: runId={}", runId);
                return failedFlux(me.getMessage());
            }
        }

        // 内存活跃流占位（必须先于 markStageRunning，双层守卫顺序约束）
        if (!generationGuard.tryAcquire(runId, stage)) {
            return failedFlux("该 run 有阶段正在生成中，请等待完成后再操作（单阶段 RUNNING 守卫）");
        }

        try {
            runService.markStageRunning(runId, stage, true);
        } catch (RuntimeException e) {
            generationGuard.release(runId, stage);
            log.warn("链式提炼前置失败: runId={}", runId, e);
            return failedFlux(e.getMessage());
        }

        // ===== 块循环编排（Flux.concat 串行：前块落盘后才组装下一块调用）=====
        // 回调线程顺序执行（concat 语义保证），可变累积器无并发竞争
        final AtomicReference<String> failureRef = new AtomicReference<>();
        final AtomicReference<Integer> versionRef = new AtomicReference<>();
        final StringBuilder fullResponse = new StringBuilder();

        List<Flux<ServerSentEvent<Object>>> blockFluxes = new ArrayList<>(totalChunks);
        if (resumeMode) {
            for (SourceChunker.Chunk chunk : chunkResult.chunks()) {
                blockFluxes.add(blockFlux(runId, chunk, totalChunks, failureRef, tenantId));
            }
        }

        return Flux.concat(
                        Flux.just(stageStartEvent()),
                        // 切块策略提示（tool 事件对：start+end 立即收尾成一行 done，复用 renderToolEvent）
                        Flux.just(
                                toolStartEvent(STRATEGY_TOOL_NAME, strategyDescription(chunkResult)),
                                toolEndEvent(STRATEGY_TOOL_NAME, "ok")),
                        Flux.concat(blockFluxes)
                                .onErrorResume(error -> {
                                    log.error("链式提炼块轮 SSE 异常: runId={}", runId, error);
                                    withTenant(tenantId, () -> runService.markStageFailed(runId, stage));
                                    failureRef.compareAndSet(null, "链式提炼异常: " + error.getMessage());
                                    return Flux.empty();
                                }),
                        // 汇总轮（content 流 + 收口）
                        summaryFlux(runId, manifest, totalChunks, failureRef, versionRef,
                                fullResponse, tenantId),
                        // 尾包 defer：失败发 error；成功发 draft_ready（[DONE] 恒为流终结标记）
                        Flux.defer(() -> {
                            String failure = failureRef.get();
                            if (failure != null) {
                                return Flux.just(errorEvent(failure), doneEvent());
                            }
                            Integer version = versionRef.get();
                            return Flux.just(draftReadyEvent(version == null ? 0 : version), doneEvent());
                        }))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        // 前端断流/中止：显式置 FAILED（重试走断点续跑，已落盘块笔记保留）
                        log.info("链式提炼被取消，置 FAILED: runId={}", runId);
                        withTenant(tenantId, () -> runService.markStageFailed(runId, stage));
                    }
                    generationGuard.release(runId, stage);
                });
    }

    // ==================== 单块流 ====================

    /**
     * 组装单块提炼流：短路检查（前块失败/quota 拦截）→ 断点续跑跳过已落盘块 →
     * 第 2+ 块治理三段式 → 非流式 LLM 调用（块笔记不透流，spec「块进度事件协议」）
     * → 落盘 writeChunkNote（幂等覆盖）→ tool 事件对收尾。
     * <p>
     * 事件序：start 先发（订阅即出 running 行）→ defer 段同步执行块调用（期间
     * 前端状态行可观感）→ end 收尾（ok ✓ / error ⚠）。
     */
    private Flux<ServerSentEvent<Object>> blockFlux(String runId, SourceChunker.Chunk chunk,
                                                     int totalChunks,
                                                     AtomicReference<String> failureRef,
                                                     String tenantId) {
        String progressName = "分块提炼 " + chunk.index() + "/" + totalChunks;
        return Flux.concat(
                Flux.just(toolStartEvent(progressName, chunkDescription(chunk))),
                Flux.defer(() -> {
                    // 短路：前块失败/拦截（failureRef）时空流（concat 不阻断，须显式短路防继续耗 token）
                    if (failureRef.get() != null) {
                        return Flux.empty();
                    }

                    // 断点续跑：已落盘非空块跳过（幂等复用，失败重试不重复耗 token）
                    String existing = readChunkNoteQuietly(runId, chunk.index(), tenantId);
                    if (existing != null && !existing.isBlank()) {
                        return Flux.just(toolEndEvent(progressName, "ok"));
                    }

                    // 第 2+ 块治理三段式（块 1 已在请求线程前置链过，不重复检查；
                    // 块轮无新用户输入面——首轮/续跑 moderation 恒 null 放行，只查 quota）
                    if (chunk.index() > 1) {
                        try {
                            quotaGuard.check(tenantId);
                        } catch (QuotaExceededException qe) {
                            log.info("配额超限拦截第 {} 块提炼: runId={}", chunk.index(), runId);
                            failStage(runId, qe.getMessage(), failureRef, tenantId);
                            return Flux.just(toolEndEvent(progressName, "error"));
                        }
                    }

                    // 非流式块调用（call type s1_refine；期间 running 行已展示）；
                    // TENANT_CONTEXT_KEY 显式带租户（metering 归属——回调线程
                    // ThreadLocal 丢失，对齐 JudgeClient/SessionTitleGenerator 先例）
                    String note;
                    try {
                        note = chatClientRegistry.forRole("planner")
                                .prompt()
                                .user(buildChunkPrompt(chunk, totalChunks))
                                .advisors(a -> {
                                    a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, S1_REFINE_CALL_TYPE);
                                    if (tenantId != null && !tenantId.isBlank()) {
                                        a.param(MeteringAdvisor.TENANT_CONTEXT_KEY, tenantId);
                                    }
                                })
                                .call()
                                .content();
                    } catch (RuntimeException e) {
                        log.error("第 {} 块提炼调用失败: runId={}", chunk.index(), runId, e);
                        failStage(runId, "第 " + chunk.index() + " 块提炼调用失败: "
                                + e.getMessage(), failureRef, tenantId);
                        return Flux.just(toolEndEvent(progressName, "error"));
                    }
                    if (note == null || note.isBlank()) {
                        log.warn("第 {} 块笔记为空响应: runId={}", chunk.index(), runId);
                        failStage(runId, "第 " + chunk.index()
                                + " 块笔记为空，可重试（已落盘块不受影响）", failureRef, tenantId);
                        return Flux.just(toolEndEvent(progressName, "error"));
                    }

                    // 落盘（幂等覆盖；回调线程恢复租户）
                    try {
                        withTenant(tenantId, () ->
                                workspaceStore.writeChunkNote(runId, chunk.index(), note));
                    } catch (RuntimeException e) {
                        log.error("第 {} 块笔记落盘失败: runId={}", chunk.index(), runId, e);
                        failStage(runId, "第 " + chunk.index() + " 块笔记落盘失败: "
                                + e.getMessage(), failureRef, tenantId);
                        return Flux.just(toolEndEvent(progressName, "error"));
                    }
                    return Flux.just(toolEndEvent(progressName, "ok"));
                }));
    }

    /**
     * 块提炼 prompt（spec「逐块串行生成与落盘」：注入该块全文 + 结构化提取指令）。
     * 只提取不扩写；出处定位供汇总轮 faithful 模板「出处对照表」消费（remix 忽略）。
     * 人物节显式全量（成稿全量名册的前置保证——块笔记是汇总轮唯一素材源，
     * 漏记即永久丢失，见 IpAnalysisGenerator 决策注释）。
     */
    private String buildChunkPrompt(SourceChunker.Chunk chunk, int totalChunks) {
        String title = chunk.title() == null ? "无标题块" : chunk.title();
        return """
                你的任务：下面是长篇原文的第 %d/%d 块（%s）。对这一块做结构化提取笔记，\
                供后续汇总轮整合成完整的 IP 拆解报告。
                笔记分节输出（只提取本块实际出现的内容，没有的节直接省略）：
                ## 设定
                力量体系/规则、世界观要素——每条一行，附出处定位（本块内位置描述或引文片段）
                ## 人物
                本块出现的所有具名人物逐一列出，不得省略或并入他人条目：
                人物名：身份、动机、关系、标志性特征——附出处定位
                ## 事件
                本块发生的关键剧情事件（按发生顺序）——附出处定位
                ## 伏笔与悬念
                埋设的伏笔、未解决的悬念——附出处定位
                硬约束：只提取不扩写；每条信息必须来自本块原文；笔记将在汇总轮与其他块的笔记统一整合，\
                不需要自行补全全局信息。

                【本块原文】（第 %d/%d 块，%s）
                %s
                """.formatted(chunk.index(), totalChunks, title,
                chunk.index(), totalChunks, title, chunk.text());
    }

    // ==================== 汇总轮 ====================

    /**
     * 汇总流（流式 token → 草稿区 + 收口）：笔记全量就绪检查 → 笔记总量防御
     * （超上限置 FAILED 可读错误不静默截断）→ quota 检查 → 注入全部笔记 +
     * faithful/remix 成稿模板 + 返工约束 → 流式生成 → 收口（对齐
     * StageGenerationService.finalizeDraft 的 L1 分支）。
     */
    private Flux<ServerSentEvent<Object>> summaryFlux(String runId, PipelineManifest manifest,
                                                       int totalChunks,
                                                       AtomicReference<String> failureRef,
                                                       AtomicReference<Integer> versionRef,
                                                       StringBuilder fullResponse, String tenantId) {
        return Flux.defer(() -> {
            if (failureRef.get() != null) {
                return Flux.<ServerSentEvent<Object>>empty();
            }

            // 笔记读取（回调线程恢复租户；返工/断点续跑统一以最新落盘为准）
            List<PlannerWorkspaceStore.ChunkNote> notes = withTenantValue(tenantId,
                    () -> workspaceStore.readChunkNotes(runId));
            if (notes.size() < totalChunks) {
                failStage(runId, "块笔记不全（" + notes.size() + "/" + totalChunks
                        + "），无法汇总——请重试生成补齐缺失块", failureRef, tenantId);
                return Flux.<ServerSentEvent<Object>>empty();
            }

            // 笔记总量防御（notesTotalMaxChars：超限置 FAILED 引导拆 run，不静默截断）
            long notesTotal = notes.stream().mapToLong(n -> n.content().length()).sum();
            if (notesTotal > properties.getNotesTotalMaxChars()) {
                failStage(runId, "块笔记总量超过防御上限（" + notesTotal + " > "
                        + properties.getNotesTotalMaxChars()
                        + " 字），请拆分为多个 run 分别提炼", failureRef, tenantId);
                return Flux.<ServerSentEvent<Object>>empty();
            }

            // 汇总调用前 quota（长流中途额度用尽的最后一道闸）
            try {
                quotaGuard.check(tenantId);
            } catch (QuotaExceededException qe) {
                log.info("配额超限拦截汇总轮: runId={}", runId);
                failStage(runId, qe.getMessage(), failureRef, tenantId);
                return Flux.<ServerSentEvent<Object>>empty();
            }

            String prompt = buildSummaryPrompt(runId, manifest, notes, tenantId);
            return chatClientRegistry.forRole("planner")
                    .prompt()
                    .user(prompt)
                    // TENANT_CONTEXT_KEY 显式带租户（metering 归属，同块轮）
                    .advisors(a -> {
                        a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, S1_REFINE_CALL_TYPE);
                        if (tenantId != null && !tenantId.isBlank()) {
                            a.param(MeteringAdvisor.TENANT_CONTEXT_KEY, tenantId);
                        }
                    })
                    .stream()
                    .content()
                    .mapNotNull(token -> {
                        fullResponse.append(token);
                        return contentEvent(token);
                    })
                    .doOnComplete(() -> finalizeSummary(runId, fullResponse, failureRef,
                            versionRef, tenantId))
                    .onErrorResume(error -> {
                        log.error("汇总轮生成 SSE 异常: runId={}", runId, error);
                        withTenant(tenantId, () ->
                                runService.markStageFailed(runId, PipelineStage.S1_IP_ANALYSIS));
                        failureRef.compareAndSet(null, "汇总轮生成异常: " + error.getMessage());
                        return Flux.empty();
                    });
        });
    }

    /**
     * 汇总轮 prompt：风格段 + faithful/remix 成稿模板（复用 {@link IpAnalysisGenerator}
     * 包级指令——与单流路径同稿结构，下游消费零差异）+ 返工约束段（D5 四件套）+
     * 全部块笔记（素材源声明：汇总以笔记为唯一素材，不再注入原文全文）。
     */
    private String buildSummaryPrompt(String runId, PipelineManifest manifest,
                                       List<PlannerWorkspaceStore.ChunkNote> notes, String tenantId) {
        StyleMode styleMode = StyleMode.fromCode(manifest.getStyleMode());
        List<PipelineManifest.ReRound> rounds =
                manifest.stage(PipelineStage.S1_IP_ANALYSIS.code()).getRounds();
        // 返工轮注入上轮拆解稿（D5「上轮全稿」；首轮/断点续跑为 null）
        String currentDraft = withTenantValue(tenantId, () ->
                workspaceStore.readStageArtifact(runId, PipelineStage.S1_IP_ANALYSIS));
        GenerationContext context = new GenerationContext(manifest, null, null,
                currentDraft, rounds, List.of(), null);

        StringBuilder prompt = new StringBuilder();
        prompt.append(StagePromptAssembler.styleSection(styleMode));
        // ip_name 模式忠实拆解出处措辞适配（planner-html-source tasks 4.2，与
        // IpAnalysisGenerator.buildPrompt 同口径）：块笔记携带资料页分隔行锚点，
        // 汇总轮的出处对照表锚到资料页页号
        String instruction = styleMode == StyleMode.REMIX
                ? IpAnalysisGenerator.REMIX_INSTRUCTION : IpAnalysisGenerator.FAITHFUL_INSTRUCTION;
        if (styleMode == StyleMode.FAITHFUL
                && SourceMode.IP_NAME.code().equals(manifest.getSourceMode())) {
            instruction = IpAnalysisGenerator.FAITHFUL_INSTRUCTION.replace(
                    "原文出处（章节/段落/引文片段）", "资料页出处（===== 资料页 NN ===== 分隔行页号）");
        }
        prompt.append(instruction);
        prompt.append('\n');
        prompt.append(StagePromptAssembler.reworkSection(context));
        prompt.append("【素材说明】\n");
        prompt.append("原文因篇幅过长已按 ").append(notes.size())
                .append(" 块完成结构化提炼。以下笔记是各块的提取结果（按原文顺序），请以笔记为唯一素材来源整合成完整拆解报告；笔记中的出处定位可用于报告的出处对照。\n\n");
        for (PlannerWorkspaceStore.ChunkNote note : notes) {
            prompt.append("### 块 ").append(note.index()).append(" 笔记\n")
                    .append(note.content()).append("\n\n");
        }
        return prompt.toString();
    }

    /**
     * 汇总收口（回调线程）：空响应守卫 → 产物落盘 → 版本快照 → AWAITING_CONFIRM +
     * version+1 + manifest 摘要行（复用 {@code StageGenerationService.buildStageSummary}
     * 规则提取，v1 免 LLM）。收口失败置 failureRef 尾包兜底 error（防御性置 FAILED）。
     */
    private void finalizeSummary(String runId, StringBuilder fullResponse,
                                  AtomicReference<String> failureRef,
                                  AtomicReference<Integer> versionRef, String tenantId) {
        if (fullResponse.length() == 0) {
            log.warn("汇总轮为空响应，置 FAILED: runId={}", runId);
            failStage(runId, "汇总轮返回空内容，本次生成未产出草稿", failureRef, tenantId);
            return;
        }
        Integer version = withTenantValue(tenantId, () -> {
            try {
                String draft = fullResponse.toString();
                workspaceStore.writeStageArtifact(runId, PipelineStage.S1_IP_ANALYSIS, draft);
                // 版本快照：本轮将产生的版本号 = 当前 version + 1（markStageCompleted 尚未推进）
                int nextVersion = workspaceStore.readManifest(runId)
                        .stage(PipelineStage.S1_IP_ANALYSIS.code()).getVersion() + 1;
                workspaceStore.writeVersionSnapshot(runId, PipelineStage.S1_IP_ANALYSIS,
                        nextVersion, draft);
                return runService.markStageCompleted(runId, PipelineStage.S1_IP_ANALYSIS,
                        StageGenerationService.buildStageSummary(null, draft));
            } catch (RuntimeException e) {
                log.error("汇总稿落盘/状态推进失败，置 FAILED: runId={}", runId, e);
                runService.markStageFailed(runId, PipelineStage.S1_IP_ANALYSIS);
                failureRef.compareAndSet(null, "汇总稿落盘失败: " + e.getMessage());
                return null;
            }
        });
        if (version != null) {
            versionRef.set(version);
        }
    }

    // ==================== 治理与工具 ====================

    /**
     * 治理检查（对齐 StoryGraphGenerationService.governanceCheck）：moderation
     * （用户输入面——返工反馈文本，首轮/续跑传 null 直接放行）+ quota。
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

    /** 统一失败处理：置阶段 FAILED（可重试，断点续跑保留已落盘块）+ failureRef 短路后续块。 */
    private void failStage(String runId, String message,
                            AtomicReference<String> failureRef, String tenantId) {
        withTenant(tenantId, () ->
                runService.markStageFailed(runId, PipelineStage.S1_IP_ANALYSIS));
        failureRef.compareAndSet(null, message);
    }

    /** moderation 拒答信号（消息即统一拒答话术，尾包 error 呈现）。 */
    private static final class ModerationBlockedException extends RuntimeException {
        ModerationBlockedException(String message) {
            super(message);
        }
    }

    /**
     * 块笔记读取兜底（续跑判定用；读失败视为无笔记走重生成，不快速失败）。
     * 块流 defer 在 concat 尾流完成线程（无租户上下文）执行：读取走
     * workspaceStore（runDir 依赖 TenantContext），须 withTenantValue 恢复租户
     * 再读（gm-async 惯例，同 StoryGraphGenerationService.readCandidateQuietly）。
     */
    private String readChunkNoteQuietly(String runId, int index, String tenantId) {
        try {
            return withTenantValue(tenantId, () ->
                    workspaceStore.readChunkNote(runId, index));
        } catch (RuntimeException e) {
            log.warn("块笔记读取失败（按无笔记处理走重生成）: runId={}, chunk={}", runId, index, e);
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

    // ==================== SSE 事件构造（对齐 StageGenerationService 惯例 + 块级 tool 事件） ====================

    /** 切块策略提示行名（tool 事件 name 匹配 key） */
    private static final String STRATEGY_TOOL_NAME = "切块策略";

    /** 切块策略提示文案（spec：伪章自动结构化 / 降级切块均在事件流内提示）。 */
    private static String strategyDescription(SourceChunker.ChunkResult result) {
        return switch (result.strategy()) {
            case HEADING -> "已按 Markdown 一级标题切块：" + result.size() + " 块";
            case PSEUDO -> "未发现 # 标题，已按章节模式行自动结构化切块：" + result.size() + " 块";
            case FALLBACK -> "未发现章节结构，按字数上限降级切块：" + result.size() + " 块";
        };
    }

    /** 块进度行描述（块标题；断点续跑复用块标注来源）。 */
    private static String chunkDescription(SourceChunker.Chunk chunk) {
        return chunk.title() == null ? null : chunk.title();
    }

    /** tool 事件（Map&lt;String,String&gt;，对齐 ResilientToolCallback 事件协议，前端 renderToolEvent 现成渲染）。 */
    private ServerSentEvent<Object> toolStartEvent(String name, String description) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("tool", "start");
        data.put("name", name);
        data.put("icon", "📖");
        if (description != null && !description.isBlank()) {
            data.put("description", description);
        }
        return ServerSentEvent.<Object>builder().data(data).build();
    }

    private ServerSentEvent<Object> toolEndEvent(String name, String status) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("tool", "end");
        data.put("name", name);
        data.put("status", status);
        return ServerSentEvent.<Object>builder().data(data).build();
    }

    private ServerSentEvent<Object> stageStartEvent() {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "stage_start",
                        "stage", PipelineStage.S1_IP_ANALYSIS.code(),
                        "label", PipelineStage.S1_IP_ANALYSIS.label()))
                .build();
    }

    private ServerSentEvent<Object> contentEvent(String token) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("content", token))
                .build();
    }

    private ServerSentEvent<Object> draftReadyEvent(int version) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "draft_ready");
        data.put("stage", PipelineStage.S1_IP_ANALYSIS.code());
        data.put("version", version);
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

    /** 快速失败流：error + [DONE]（对齐 RpgGameController 配额/守卫拒绝惯例）。 */
    private Flux<ServerSentEvent<Object>> failedFlux(String message) {
        return Flux.just(errorEvent(message), doneEvent());
    }
}
