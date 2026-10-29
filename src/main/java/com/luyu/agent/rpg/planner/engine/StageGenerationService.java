package com.luyu.agent.rpg.planner.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.DraftItemStatus;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineRunStatus;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;
import com.luyu.agent.rpg.planner.model.SourceMode;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorkspaceStore;
import com.luyu.agent.tenancy.TenantContext;

import java.util.List;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

/**
 * 阶段生成 SSE 流统一编排（add-planner-pipeline tasks 3.1 / design D8/D11）。
 * <p>
 * 生成器（{@link StageGenerator}）只管 prompt 组装；本类负责全部编排：
 * 请求线程同步前置（校验/守卫/quota/状态推进）→ LLM 流式（planner 纯净角色，
 * 仅挂 MeteringAdvisor 归账）→ 完成回调（产物落盘 + 阶段 AWAITING_CONFIRM +
 * draft_ready 尾包）→ 失败/取消冻结（阶段 FAILED 不连锁，D7 风险表）。
 * <p>
 * 事件协议（对齐 {@code RpgGameController} SSE 惯例）：
 * <pre>
 * {"type":"stage_start","stage":"S1","label":"IP 拆解"}   流开始
 * {"content":...}                                          token 流
 * {"type":"draft_ready","stage":"S1","version":N}          草稿就绪（成功尾包）
 *   L2 阶段（S3/S5）尾包额外携带 candidates=N（本次产出候选数，tasks 3.6）
 * {"error":...}                                            失败（含前置快速失败）
 * [DONE]                                                   流终结标记
 * </pre>
 * <p>
 * <b>租户传播</b>（同 {@code GameLoopService.finalizeTurn} 的 gm-async 模式）：
 * 请求线程捕获 tenantId → SSE 回调线程内 {@code TenantContext.set("planner-async", …)}
 * 恢复 → 操作完 finally 清理——工作区落盘与库表同步均依赖 TenantContext 定位租户根。
 * <p>
 * <b>单阶段 RUNNING 双层守卫</b>（tasks 2.5 + 3.7）：内存层
 * {@link PlannerGenerationGuard}（进程内单例，与 S4.5 幕级编排共享——story-graph-authoring
 * tasks 2.3）拦真并发（命中即 409 语义 error 事件）；manifest 层残留 RUNNING（服务重启
 * 后无活跃流）由 {@code markStageRunning(staleRunningReset=true)} 复位 FAILED 后重入——
 * 内存守卫必须先于 markStageRunning 占位，否则 stale 复位无法区分「崩溃残留」与
 * 「并发请求刚置 RUNNING」。
 */
@Service
public class StageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(StageGenerationService.class);

    /** 摘要行截断保护（planner-stage-generation spec：名称+一句话概括，不膨胀） */
    static final int SUMMARY_TRUNCATE = 120;

    private final PipelineRunService runService;
    private final PlannerWorkspaceStore workspaceStore;
    private final PipelineRunRepository runRepository;
    private final ChatClientRegistry chatClientRegistry;
    private final QuotaGuard quotaGuard;
    private final S1ChainedRefineService s1ChainedRefineService;
    /** S3 分批分派（planner-s3-batch-generate tasks 2.1；守卫占位前转入，同 S1 链式分叉惯例） */
    private final S3BatchGenerationService s3BatchGenerationService;
    private final RpgPlannerProperties properties;
    private final Map<PipelineStage, StageGenerator> generators;

    /** run 级活跃流守卫（与 S4.5 幕级编排共享，story-graph-authoring tasks 2.3） */
    private final PlannerGenerationGuard generationGuard;

    public StageGenerationService(PipelineRunService runService,
                                  PlannerWorkspaceStore workspaceStore,
                                  PipelineRunRepository runRepository,
                                  ChatClientRegistry chatClientRegistry,
                                  QuotaGuard quotaGuard,
                                  PlannerGenerationGuard generationGuard,
                                  S1ChainedRefineService s1ChainedRefineService,
                                  S3BatchGenerationService s3BatchGenerationService,
                                  RpgPlannerProperties properties,
                                  List<StageGenerator> generatorList) {
        this.runService = runService;
        this.workspaceStore = workspaceStore;
        this.runRepository = runRepository;
        this.chatClientRegistry = chatClientRegistry;
        this.quotaGuard = quotaGuard;
        this.generationGuard = generationGuard;
        this.s1ChainedRefineService = s1ChainedRefineService;
        this.s3BatchGenerationService = s3BatchGenerationService;
        this.properties = properties;
        this.generators = generatorList.stream()
                .collect(Collectors.toUnmodifiableMap(StageGenerator::stage, generator -> generator));
    }

    /**
     * 触发指定阶段的生成流（SSE）。
     * <p>
     * 请求线程同步前置全部快速失败（返回 {@code error + [DONE]} 两事件、零副作用）：
     * 阶段码 → run 存在性（租户过滤）→ 终态守卫 → manifest → 状态机
     * （currentStage 须为目标且可入 RUNNING，跨阶段跳过被拒）→ 生成器存在性 →
     * 配额 → 内存活跃流占位 → markStageRunning（含崩溃残留复位）→ prompt 装配。
     */
    public Flux<ServerSentEvent<Object>> generateStage(String runId, String stageCode) {
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return failedFlux(e.getMessage());
        }

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
        PipelineStageStatus targetStatus = PipelineStageStatus.valueOf(manifest.stage(stage.code()).getStatus());
        if (!PipelineStateMachine.canStartStage(currentStage, stage, targetStatus)) {
            return failedFlux("阶段前置条件不满足：当前阶段为 " + currentStage.code()
                    + "，不能直接生成 " + stage.code() + "（跨阶段跳过被拒）");
        }

        StageGenerator generator = generators.get(stage);
        if (generator == null) {
            return failedFlux(specializedEndpointMessage(stage));
        }

        // S1 链式分叉（planner-s1-chained-refinement tasks 3.1；planner-html-source
        // D6 分档收敛：text 原文 / ip_name pages 合并虚拟原文同判，原料统一
        // readS1Material）：原料超链式阈值转入 S1ChainedRefineService（内部自持
        // 治理/守卫占位/状态推进，故须在 generationGuard.tryAcquire 之前转入，
        // 避免双占位死锁）；≤ 阈值维持下方单流路径零改动。
        // 存量 run 防御（planner-html-source tasks 4.3）：ip_name 无 pages 落盘
        //（scanUrls 时代未完成 run）快速失败——错误事件 + 阶段状态不变（未占位
        // 未推进）；text 模式原料缺失维持走单流（与旧路径行为一致）
        if (stage == PipelineStage.S1_IP_ANALYSIS) {
            String material = workspaceStore.readS1Material(runId, manifest.getSourceMode());
            if (material == null
                    && SourceMode.IP_NAME.code().equals(manifest.getSourceMode())) {
                return failedFlux("资料页缺失：ip_name 模式需上传 HTML 资料页（联网抓取链路已退役），"
                        + "请删除后重建 run");
            }
            if (material != null && material.length() > properties.getChainThresholdChars()) {
                return s1ChainedRefineService.generateChained(runId, null);
            }
        }

        // S3 名册分批分叉（planner-s3-batch-generate tasks 2.1 / spec「名册超批
        // 容量自动分批」；对齐 S1 链式分叉惯例——守卫占位前转入，避免双占位）：
        // 首轮 / 失败重试（状态 NOT_STARTED/FAILED 且 rounds 空或 batches 非空）
        // 名册人数超批容量 → 全量分批编排（batchLimit = ∞；重试经剩余清单推导
        // 跳过已落盘批，spec「重试从失败批续跑」）。rounds 非空但 batches 非空
        // = 分批模式曾运行过的 FAILED 续跑，同样走分批——否则返工反馈后的单流
        // 重试会一次吞全量名册巨型 prompt（run 5ffd6d5b 60s 撞线教训）；rounds
        // 非空且 batches 空（返工单流失败）维持单流重跑既有语义。返工 / 重算
        //（AWAITING_CONFIRM 分派、门语义）与无名册 / 名册未超容量维持单流
        //（既有行为零增量——返工整轮替换与补批增量覆盖正交，D5）。
        if (stage == PipelineStage.S3_CHARACTERS
                && (manifest.stage(stage.code()).getRounds().isEmpty()
                        || !manifest.stage(stage.code()).getBatches().isEmpty())) {
            PipelineStageStatus s3Status = PipelineStageStatus.valueOf(
                    manifest.stage(stage.code()).getStatus());
            if (s3Status == PipelineStageStatus.NOT_STARTED || s3Status == PipelineStageStatus.FAILED) {
                List<String> rosterNames = StagePromptAssembler.extractRosterNames(
                        StagePromptAssembler.extractRosterSection(
                                workspaceStore.readStageArtifact(runId, PipelineStage.S1_IP_ANALYSIS)));
                if (rosterNames.size() > properties.getMaxCandidates()) {
                    return s3BatchGenerationService.generateRemainingBatches(runId, Integer.MAX_VALUE);
                }
            }
        }

        // 配额硬拒（治理三段式 quota 段，tenant-token-metering design D8 惯例）。
        // moderation 段不适用：阶段生成无新用户输入（原始输入创建时已审；
        // 返工反馈的审查在任务 4 返工端点前置）。
        try {
            quotaGuard.check(TenantContext.getTenantId());
        } catch (QuotaExceededException qe) {
            log.info("配额超限拒绝阶段生成: runId={}, stage={}", runId, stage.code());
            return failedFlux(qe.getMessage());
        }

        // 内存活跃流占位（必须在 markStageRunning 之前，见类 javadoc 双层守卫说明）
        if (!generationGuard.tryAcquire(runId, stage)) {
            return failedFlux("该 run 有阶段正在生成中，请等待完成后再操作（单阶段 RUNNING 守卫）");
        }

        // 租户身份随流显式传播（请求线程捕获；回调/工具执行线程 ThreadLocal 不可靠，gm-async 模式）
        final String tenantId = TenantContext.getTenantId();

        final String prompt;
        final Flux<String> contentFlux;
        try {
            runService.markStageRunning(runId, stage, true);
            prompt = generator.buildPrompt(buildContext(runId, stage, manifest));
            contentFlux = chatClientRegistry.forRole("planner")
                    .prompt()
                    .user(prompt)
                    .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "planner"))
                    .stream()
                    .content();
        } catch (RuntimeException e) {
            generationGuard.release(runId, stage);
            log.warn("阶段生成前置失败: runId={}, stage={}", runId, stageCode, e);
            return failedFlux(e.getMessage());
        }

        StringBuilder fullResponse = new StringBuilder();
        AtomicReference<String> failureRef = new AtomicReference<>();
        AtomicReference<Integer> versionRef = new AtomicReference<>();
        // L2 阶段（S3/S5）本次产出候选数（draft_ready 尾包携带，前端逐卡渲染用）
        AtomicReference<Integer> candidatesRef = new AtomicReference<>();

        Flux<ServerSentEvent<Object>> pipeline = Flux.concat(
                Flux.just(stageStartEvent(stage)),
                contentFlux
                        .mapNotNull(token -> {
                            fullResponse.append(token);
                            return contentEvent(token);
                        })
                        .doOnComplete(() -> finalizeDraft(
                                runId, stage, fullResponse, failureRef, versionRef, candidatesRef, tenantId))
                        .onErrorResume(error -> {
                            log.error("阶段生成 SSE 异常: runId={}, stage={}", runId, stage.code(), error);
                            withTenant(tenantId, () -> runService.markStageFailed(runId, stage));
                            failureRef.set("阶段生成异常: " + error.getMessage());
                            // 尾包 defer 统一发 error + [DONE]，保持 [DONE] 恒为流终结标记
                            return Flux.empty();
                        }))
                .concatWith(Flux.defer(() -> {
                    String failure = failureRef.get();
                    if (failure != null) {
                        return Flux.just(errorEvent(failure), doneEvent());
                    }
                    Integer version = versionRef.get();
                    Integer candidates = candidatesRef.get();
                    return Flux.just(draftReadyEvent(stage, version == null ? 0 : version, candidates), doneEvent());
                }))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        // 前端断流/中止：显式置 FAILED（重试直接 FAILED→RUNNING，无残留），
                        // 区别于崩溃冻结的 RUNNING（下次生成时 staleReset 复位路径）
                        log.info("阶段生成被取消，置 FAILED: runId={}, stage={}", runId, stage.code());
                        withTenant(tenantId, () -> runService.markStageFailed(runId, stage));
                    }
                    generationGuard.release(runId, stage);
                });

        return pipeline;
    }

    /** 当前活跃流探测（测试与诊断用）：runId 正在生成的阶段。 */
    public Optional<PipelineStage> activeStage(String runId) {
        return generationGuard.activeStage(runId);
    }

    // ==================== 完成回调 ====================

    /**
     * 流完成回调（回调线程）：空响应守卫 → 产物落盘 → 阶段 AWAINING_CONFIRM + version+1
     * + manifest 摘要行（spec：草稿版本与摘要行一起落盘，供前端步骤条与下游
     * 上游摘要段消费，见 {@link #buildStageSummary}）。
     * L2 阶段（S3/S5）走候选集路径（tasks 3.6）：拆分（{@link CandidateSplitter}）→
     * 逐候选落盘 + 尾包携带候选数。无二级标题的输出由 splitter 降级为单候选
     * （格式漂移不丢内容）；零候选分支为纯防御（正常路径不可达）。
     * 落盘后写版本快照（tasks 4.1 版本树：v1→vN 全历史）+ L2 候选状态初始化
     * （PENDING 重置，D5 整轮重生成）。
     * 落盘/推进失败时置 FAILED（防御性，失败标记自身不能再抛）。
     */
    private void finalizeDraft(String runId, PipelineStage stage, StringBuilder fullResponse,
                               AtomicReference<String> failureRef, AtomicReference<Integer> versionRef,
                               AtomicReference<Integer> candidatesRef, String tenantId) {
        if (fullResponse.length() == 0) {
            log.warn("阶段生成为空响应，置 FAILED: runId={}, stage={}", runId, stage.code());
            withTenant(tenantId, () -> runService.markStageFailed(runId, stage));
            failureRef.set("模型返回空内容，本次生成未产出草稿");
            return;
        }
        final boolean candidateSet = stage.gate() == PipelineStage.GateLevel.L2;
        final List<String> candidates = candidateSet
                ? CandidateSplitter.split(fullResponse.toString()) : null;
        if (candidateSet && candidates.isEmpty()) {
            log.warn("候选集拆分为零候选，置 FAILED: runId={}, stage={}", runId, stage.code());
            withTenant(tenantId, () -> runService.markStageFailed(runId, stage));
            failureRef.set("模型输出未包含任何候选块（## 二级标题），本次生成未产出候选");
            return;
        }
        withTenant(tenantId, () -> {
            try {
                if (candidateSet) {
                    workspaceStore.writeStageCandidates(runId, stage, candidates);
                    candidatesRef.set(candidates.size());
                } else {
                    workspaceStore.writeStageArtifact(runId, stage, fullResponse.toString());
                }
                // 版本快照（tasks 4.1）：本轮将产生的版本号 = 当前 version + 1
                //（markStageCompleted 尚未推进，先写快照失败可置 FAILED）
                int nextVersion = workspaceStore.readManifest(runId)
                        .stage(stage.code()).getVersion() + 1;
                String snapshot = candidateSet
                        ? String.join("\n\n", candidates) : fullResponse.toString();
                workspaceStore.writeVersionSnapshot(runId, stage, nextVersion, snapshot);
                if (candidateSet) {
                    runService.initStageCandidates(runId, stage,
                            candidates.stream().map(StageDraftSet::titleOf).toList());
                }
                versionRef.set(runService.markStageCompleted(runId, stage,
                        buildStageSummary(candidates, fullResponse.toString())));
            } catch (RuntimeException e) {
                log.error("草稿落盘/状态推进失败，置 FAILED: runId={}, stage={}", runId, stage.code(), e);
                runService.markStageFailed(runId, stage);
                failureRef.set("草稿落盘失败: " + e.getMessage());
            }
        });
    }

    /**
     * 阶段摘要行提取（spec：名称+一句话概括；v1 免 LLM 规则提取，不额外耗 token）。
     * L1：产物首个非空非标题内容行（剥离列表/加粗 markdown 标记）；L2：候选标题
     * 列表（标题即候选名，复用 {@link StageDraftSet#titleOf}）。提取不出内容时
     * 返回 null（markStageCompleted 保留旧摘要，不抹值）。
     */
    static String buildStageSummary(List<String> candidates, String artifact) {
        final String raw;
        if (candidates != null && !candidates.isEmpty()) {
            raw = candidates.size() + " 个候选：" + candidates.stream()
                    .map(StageDraftSet::titleOf).collect(Collectors.joining("、"));
        } else {
            raw = firstContentLine(artifact);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replace("**", "").replace("- ", "").strip();
        return cleaned.length() > SUMMARY_TRUNCATE
                ? cleaned.substring(0, SUMMARY_TRUNCATE) + "…" : cleaned;
    }

    /** 产物首个非空非标题内容行（L1 摘要源；纯结构稿返回 null）。 */
    private static String firstContentLine(String artifact) {
        for (String line : artifact.split("\n", -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                return stripped;
            }
        }
        return null;
    }

    /**
     * 装配生成上下文（D7：直接上游确认稿全文 + manifest 全量状态）。
     * L2 阶段（S3/S5）的「全稿」语义 = 候选集聚合（返工注入与下游上游注入同口径，tasks 3.6）；
     * rejectedTitles 从 manifest 候选状态取（已毙候选排除表，D5——候选文件被整轮
     * 覆盖后记忆仍在 manifest，tasks 4.1）。
     */
    private GenerationContext buildContext(String runId, PipelineStage stage, PipelineManifest manifest) {
        // S1 原料统一入口（planner-html-source D6）：text = source.md 原文 /
        // ip_name = IP 名头 + pages 合并虚拟原文（无 pages 存量为 null）；
        // 非 S1 阶段无 sourceText 消费方，保持 readSource 语义零改动
        String sourceText = stage == PipelineStage.S1_IP_ANALYSIS
                ? workspaceStore.readS1Material(runId, manifest.getSourceMode())
                : workspaceStore.readSource(runId);
        // upstream 而非 prev（story-graph-authoring 2.1）：内容上游与顺序前驱分离，
        // S5 的注入上游仍锚定 S4 剧情结构（spec「S5 及后续阶段的输入输出不受影响」）。
        // S4 的直接上游是 S3：紧凑索引行注入（planner-s3-batch-generate tasks 4.2，
        // D6——全量分批后候选集数十张卡，全文聚合撞截断保护且稀释下游注意力；
        // 人物卡全文留在工作区由生成器按需读取），见 readUpstreamForContext
        String upstreamConfirmed = readUpstreamForContext(runId, stage, manifest);
        String currentDraft = readStageFullText(runId, stage, manifest);
        List<String> rejectedTitles = manifest.stage(stage.code()).getCandidates().stream()
                .filter(c -> DraftItemStatus.REJECTED.name().equals(c.getStatus()))
                .map(PipelineManifest.CandidateState::getTitle)
                .toList();
        // S3 对照池（名册遗漏反馈）：S1 确认稿「人物全量名册」节直通 S3 prompt——
        // D7 摘要行只有一句话概括，名册不进视野会让人物候选只能从 S2 世界书
        // 显式人物里挑（faithful 名册数十人被裁到首领级几个）
        String roster = stage == PipelineStage.S3_CHARACTERS
                ? StagePromptAssembler.extractRosterSection(
                        workspaceStore.readStageArtifact(runId, PipelineStage.S1_IP_ANALYSIS))
                : null;
        return new GenerationContext(manifest, sourceText, upstreamConfirmed, currentDraft,
                manifest.stage(stage.code()).getRounds(), rejectedTitles, roster);
    }

    /**
     * 上游确认稿读取（D7 全稿通用 / D6 紧凑特化）：直接上游为 S3 的阶段（即 S4）
     * 改传紧凑索引行（每张非毙卡一行「人名：身份钩子（命定）」，REJECTED 天然
     * 排除——readStageFullText 滤后聚合再切卡，D4 现算不缓存）；其余上游
     * （S2→S3、S4→S5 的 L1 稿）全稿语义不变。空集返回 null（零注入），
     * {@code upstreamConfirmedSection} 原样包裹（含截断保护，紧凑索引远低于阈值）。
     */
    private String readUpstreamForContext(String runId, PipelineStage stage, PipelineManifest manifest) {
        PipelineStage upstream = stage.upstream();
        if (upstream == null) {
            return null;
        }
        if (upstream == PipelineStage.S3_CHARACTERS) {
            String fullText = readStageFullText(runId, upstream, manifest);
            if (fullText == null) {
                return null;
            }
            return StagePromptAssembler.compactCharacterIndex(
                    CandidateSplitter.split(fullText));
        }
        return readStageFullText(runId, upstream, manifest);
    }

    /**
     * 阶段全稿读取（语义稿，tasks 4.3）：L1 单文件；L2 按 manifest 候选状态
     * 过滤被毙候选后聚合（D7 下游确认稿天然隔离被毙候选；返工「上轮全稿」
     * 同口径——被毙候选不进重生成上下文，其记忆走 rejectedTitles 排除表）。
     * 空集返回 null。
     */
    private String readStageFullText(String runId, PipelineStage stage, PipelineManifest manifest) {
        if (stage.gate() == PipelineStage.GateLevel.L2) {
            return workspaceStore.readStageFullText(runId, stage,
                    manifest.stage(stage.code()).getCandidates());
        }
        return workspaceStore.readStageArtifact(runId, stage);
    }

    // ==================== 租户传播（gm-async 模式） ====================

    /**
     * SSE 回调线程内恢复租户上下文后执行动作（同 {@code GameLoopService.finalizeTurn}）：
     * 恢复者负责清理；当前线程已有上下文时不覆盖不清理。
     */
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

    // ==================== SSE 事件构造 ====================

    /** 无通用生成器的阶段提示（S6 走安装端点；S4.5 走幕级生成编排，story-graph-authoring 2.3 接线）。 */
    private static String specializedEndpointMessage(PipelineStage stage) {
        if (stage == PipelineStage.S6_INSTALL) {
            return "成稿安装阶段不经生成端点（S6 由安装流程触发）";
        }
        if (stage == PipelineStage.S4P5_STORY_GRAPH) {
            return "故事图骨架阶段不经通用生成端点（S4.5 由幕级生成编排触发，逐幕小契约调用）";
        }
        return "阶段生成器尚未实现: " + stage.code();
    }

    private ServerSentEvent<Object> stageStartEvent(PipelineStage stage) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "stage_start", "stage", stage.code(), "label", stage.label()))
                .build();
    }

    private ServerSentEvent<Object> contentEvent(String token) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("content", token))
                .build();
    }

    private ServerSentEvent<Object> draftReadyEvent(PipelineStage stage, int version, Integer candidates) {
        // L2 阶段尾包带 candidates 数量；Map.of 不接受 null，用 LinkedHashMap 分支构造
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "draft_ready");
        data.put("stage", stage.code());
        data.put("version", version);
        if (candidates != null) {
            data.put("candidates", candidates);
        }
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
