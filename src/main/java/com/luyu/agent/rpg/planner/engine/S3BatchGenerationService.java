package com.luyu.agent.rpg.planner.engine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorkspaceStore;
import com.luyu.agent.tenancy.TenantContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.util.retry.Retry;

/**
 * S3 名册分批生成 SSE 编排（planner-s3-batch-generate tasks 2.1-2.5 / design D1-D3/D5）。
 * <p>
 * 编排单元是「批」（名册剩余清单按序切块，批容量 = {@code rpg.planner.max-candidates}）：
 * 剩余清单 = 名册全集 − 已产出候选标题（含 REJECTED——毙掉即出局，D7 容差匹配）。
 * 每批复用 {@link CharactersGenerator} 批内形态装配 prompt（本批名册块 + S2 确认稿 +
 * 已产出人名索引，spec「批内 prompt 不膨胀」），一次 LLM 流式调用经
 * {@link CandidateSplitter} 切卡后<b>追加</b>落盘（id 顺延、既有候选逐卡状态不重置）。
 * <p>
 * 两个入口共用本编排（D5 差参不差路径）：
 * <ul>
 *   <li>{@code StageGenerationService.generateStage} 首轮 / 失败重试分叉
 *       （rounds 空且名册超批容量，batchLimit = ∞ 全量）；</li>
 *   <li>{@code StageGateService} 门动作（AWAITING_CONFIRM 态，补一批 = 1 /
 *       全量生成 = ∞）。</li>
 * </ul>
 * 返工 / 重算（AWAITING_CONFIRM 分派、rounds 非空）与无名册 / 名册未超容量
 * 继续走单流路径（既有行为零增量）。
 * <p>
 * 事件协议（对齐 StageGenerationService 惯例 + 批粒度事件扩展，前端未知事件忽略）：
 * <pre>
 * {"type":"stage_start","stage":"S3","label":"人物候选"}            流开始
 * {"type":"batch_start","stage":"S3","index":k,"seq":s,"total":N,"count":M}  批开始（index 本轮序号从 1 起 / seq 全局批号）
 * {"content":...}                                                     token 流（并行批交错合流）
 * {"type":"batch_done","stage":"S3","index":k,"seq":s}               批收口完成（已追加落盘）
 * {"type":"draft_ready","stage":"S3","version":N,"candidates":M}      全部批收口（成功尾包）
 * {"error":...}                                                       失败（含前置快速失败）
 * [DONE]                                                              流终结标记
 * </pre>
 * <p>
 * <b>批间并行</b>（D3）：{@code Flux.merge} bounded（{@code rpg.planner.batch-
 * concurrency}，置 1 即串行回退）——批 LLM 流并行交错，批完成收口（读 manifest /
 * 追加落盘 / 记账）经 {@link #batchFinalizeLock} 串行化（读-改-写 manifest 与
 * 候选 id 顺延无并发竞态；临界区毫秒级）。已产出人名索引含<b>先序批的批内人名
 * 预知</b>（编排期即可声明，并行批 2 的 prompt 与重名检测基准稳定）。
 * <p>
 * <b>批粒度断点续跑</b>（D2）：剩余清单以 manifest 候选行为真源推导——批成功落盘
 * 即持久化（spec「成功一批持久化一批」），失败置阶段 FAILED 冻结推进不回滚已
 * 成功批；重试经 rosterRemaining 排除已落盘产出自然跳过（宁重复不漏人：批内
 * 人名清单对不上的批整体重跑）。批次记账（manifest StageState.batches，PENDING
 * → DONE/FAILED）是编排轨迹与诊断真源，不参与剩余推导。
 * <p>
 * <b>治理三段式</b>（spec「批间并行与守卫前置」）：quota 在请求线程前置一次
 * （快速失败零副作用）+ 每批启动前拦截（长流中途额度用尽即拦下一批）；无新
 * 用户输入线（moderation 不适用）；每批模型调用仍挂计量 advisor 归账发起租户。
 * <p>
 * <b>RUNNING 守卫与租户传播</b>：与通用阶段生成共享 {@link PlannerGenerationGuard}
 * （占位先于 markStageRunning，双层守卫顺序约束）；分叉方在守卫占位之前转入。
 * 租户随流显式传播（gm-async 模式，同 StageGenerationService）。
 * <p>
 * <b>瞬时网络故障自动重试</b>：批 LLM 流 IOException 族（读空闲超时 / 连接重置，
 * run 5ffd6d5b 批 10 okhttp timeout 杀流的生产教训）按
 * {@code rpg.planner.batch-network-retries} 原批重发（defer 重订阅 = 完整重调）；
 * 重试耗尽才走失败冻结。业务性错误（切卡失败 / 全重名 / 配额超限）不重试。
 */
@Service
public class S3BatchGenerationService {

    private static final Logger log = LoggerFactory.getLogger(S3BatchGenerationService.class);

    /** 批完成收口串行锁：manifest 读-改-写与候选 id 顺延的并发竞态防护（跨 run 串行，毫秒级临界区） */
    private final Object batchFinalizeLock = new Object();

    private final PipelineRunService runService;
    private final PlannerWorkspaceStore workspaceStore;
    private final PipelineRunRepository runRepository;
    private final ChatClientRegistry chatClientRegistry;
    private final QuotaGuard quotaGuard;
    private final PlannerGenerationGuard generationGuard;
    private final RpgPlannerProperties properties;
    private final CharactersGenerator charactersGenerator;

    public S3BatchGenerationService(PipelineRunService runService,
                                    PlannerWorkspaceStore workspaceStore,
                                    PipelineRunRepository runRepository,
                                    ChatClientRegistry chatClientRegistry,
                                    QuotaGuard quotaGuard,
                                    PlannerGenerationGuard generationGuard,
                                    RpgPlannerProperties properties,
                                    CharactersGenerator charactersGenerator) {
        this.runService = runService;
        this.workspaceStore = workspaceStore;
        this.runRepository = runRepository;
        this.chatClientRegistry = chatClientRegistry;
        this.quotaGuard = quotaGuard;
        this.generationGuard = generationGuard;
        this.properties = properties;
        this.charactersGenerator = charactersGenerator;
    }

    /**
     * 触发 S3 剩余名册分批生成流（SSE）。请求线程同步前置全部快速失败
     * （{@code error + [DONE]} 两事件、零副作用）：run 存在性（租户过滤）→
     * 终态守卫 → manifest → 状态机（S3 须为当前阶段且可入 RUNNING）→ 名册
     * 提取（无名册拒绝）→ 剩余清单（空拒绝——名册人物已全部产出）→ 切块
     * （批容量截断 batchLimit）→ quota 一次判定 → 内存活跃流占位 →
     * markStageRunning（含崩溃残留复位）→ 批计划记账。
     *
     * @param batchLimit 本次编排最多执行的批数（补一批 = 1；全量传
     *                   {@link Integer#MAX_VALUE}，D5 共用路径差参不差路径）
     */
    public Flux<ServerSentEvent<Object>> generateRemainingBatches(String runId, int batchLimit) {
        if (batchLimit < 1) {
            return failedFlux("非法的批数上限: " + batchLimit);
        }
        final PipelineStage stage = PipelineStage.S3_CHARACTERS;

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
                PipelineStageStatus.valueOf(manifest.stage(stage.code()).getStatus());
        if (!PipelineStateMachine.canStartStage(currentStage, stage, targetStatus)) {
            return failedFlux("阶段前置条件不满足：当前阶段为 " + currentStage.code()
                    + "，不能直接生成 " + stage.code() + "（跨阶段跳过被拒）");
        }

        // 名册提取与剩余清单（D7）：已产出取 manifest 候选行标题（含 REJECTED——
        // 毙掉即出局，不进补批批内名册）
        final String rosterSection = StagePromptAssembler.extractRosterSection(
                workspaceStore.readStageArtifact(runId, PipelineStage.S1_IP_ANALYSIS));
        final List<String> rosterNames = StagePromptAssembler.extractRosterNames(rosterSection);
        if (rosterNames.isEmpty()) {
            return failedFlux("S1 确认稿无人物名册，无法分批生成（remix / 无名稿走单批生成路径）");
        }
        // S2 上游确认稿编排期快照（6.3 E2E 发现）：批流 defer 在订阅线程执行——
        // 批 3 起由上游完成信号驱动于 reactor 线程订阅，ThreadLocal 租户不在，
        // 批内读工作区会抛 TenantContextMissingException；S3 生成中 S2 已
        // CONFIRMED 锁定（单阶段 RUNNING 守卫 + 解锁须回退），编排期快照与批读
        // 同值，批流内零工作区 IO
        final String upstreamConfirmed = stage.upstream() != null
                ? workspaceStore.readStageArtifact(runId, stage.upstream()) : null;
        List<String> producedTitles = manifest.stage(stage.code()).getCandidates().stream()
                .map(PipelineManifest.CandidateState::getTitle)
                .collect(Collectors.toList());
        List<String> remaining = StagePromptAssembler.rosterRemaining(rosterNames, producedTitles);
        if (remaining.isEmpty()) {
            return failedFlux("名册人物已全部产出候选（" + rosterNames.size()
                    + " 人；毙掉的人物即出局不补），无可生成的剩余批次");
        }

        // 剩余清单切块（批容量 = max-candidates，每批上限截断 batchLimit——补一批）
        final int batchSize = Math.max(1, properties.getMaxCandidates());
        List<List<String>> nameBatches = partitionNames(remaining, batchSize, batchLimit);
        final int totalBatches = nameBatches.size();

        // 配额硬拒（治理三段式 quota 段：请求线程一次总量判定——快速失败零批次
        // 启动；批启动前另做批间拦截）。moderation 不适用：分批生成无新用户输入线
        //（原始输入创建时已审，spec「批间并行与守卫前置」）。
        final String tenantId = TenantContext.getTenantId();
        try {
            quotaGuard.check(tenantId);
        } catch (QuotaExceededException qe) {
            log.info("配额超限拒绝 S3 分批生成: runId={}, batches={}", runId, totalBatches);
            return failedFlux(qe.getMessage());
        }

        // 内存活跃流占位（必须先于 markStageRunning，双层守卫顺序约束）
        if (!generationGuard.tryAcquire(runId, stage)) {
            return failedFlux("该 run 有阶段正在生成中，请等待完成后再操作（单阶段 RUNNING 守卫）");
        }

        // 批计划记账（PENDING 批 append manifest）+ 预拼批（本批名册块文本，
        // 不落 manifest 仅流内使用）
        final List<PlannedBatch> plannedBatches;
        try {
            runService.markStageRunning(runId, stage, true);
            List<PipelineManifest.BatchState> batchStates =
                    runService.recordBatchPlan(runId, stage, nameBatches);
            plannedBatches = new ArrayList<>(batchStates.size());
            for (int i = 0; i < batchStates.size(); i++) {
                String rosterText = String.join("\n",
                        StagePromptAssembler.rosterBlocksForNames(rosterSection, nameBatches.get(i)));
                plannedBatches.add(new PlannedBatch(batchStates.get(i), rosterText, i + 1));
            }
        } catch (RuntimeException e) {
            generationGuard.release(runId, stage);
            log.warn("S3 分批生成前置失败: runId={}", runId, e);
            return failedFlux(e.getMessage());
        }
        log.info("S3 分批生成开始: runId={}, 剩余 {} 人, {} 批（容量 {}, 并行度 {}, 上限 {}）",
                runId, remaining.size(), totalBatches, batchSize,
                Math.max(1, properties.getBatchConcurrency()), batchLimit);

        // ===== 批流编排（Flux.merge bounded 并行；token 流交错合流单 SSE）=====
        final AtomicReference<String> failureRef = new AtomicReference<>();
        final AtomicReference<Integer> versionRef = new AtomicReference<>();
        final AtomicReference<Integer> totalCandidatesRef = new AtomicReference<>();

        List<Flux<ServerSentEvent<Object>>> batchFluxes = new ArrayList<>(plannedBatches.size());
        for (PlannedBatch planned : plannedBatches) {
            batchFluxes.add(batchFlux(runId, manifest, upstreamConfirmed, rosterNames, planned,
                    plannedBatches, failureRef, tenantId));
        }

        return Flux.<ServerSentEvent<Object>>concat(
                        Flux.just(stageStartEvent(stage)),
                        Flux.fromIterable(batchFluxes)
                                .flatMap(f -> f, Math.max(1, properties.getBatchConcurrency()))
                                .onErrorResume(error -> {
                                    // 防御兜底：批流内部已各自 onErrorResume，这里拦收口段异常
                                    log.error("S3 分批生成 SSE 异常: runId={}", runId, error);
                                    failBatch(runId, stage, null,
                                            "分批生成异常: " + error.getMessage(), failureRef, tenantId);
                                    return Flux.empty();
                                })
                                .concatWith(Mono.<ServerSentEvent<Object>>defer(() -> {
                                    // 收口（全部批完成后；失败短路由 failureRef 检查——阶段已
                                    // FAILED）。concatWith 透传批流事件后追加收口（不能
                                    // 用 then——它丢弃上游全部 onNext，批进度事件会
                                    // 全部丢失，S3BatchGenerationServiceTest 事件序钉住）
                                    finalizeBatches(runId, stage, failureRef, versionRef,
                                            totalCandidatesRef, tenantId);
                                    return Mono.empty();
                                })),
                        // 尾包 defer：失败发 error；成功发 draft_ready（[DONE] 恒为流终结标记）
                        Flux.defer(() -> {
                            String failure = failureRef.get();
                            if (failure != null) {
                                return Flux.just(errorEvent(failure), doneEvent());
                            }
                            Integer version = versionRef.get();
                            Integer candidates = totalCandidatesRef.get();
                            return Flux.just(draftReadyEvent(stage,
                                    version == null ? 0 : version, candidates), doneEvent());
                        }))
                .doFinally(signal -> {
                    if (signal == SignalType.CANCEL) {
                        // 前端断流/中止：显式置 FAILED（重试走断点续跑，已落盘批保留）
                        log.info("S3 分批生成被取消，置 FAILED: runId={}", runId);
                        withTenant(tenantId, () -> runService.markStageFailed(runId, stage));
                    }
                    generationGuard.release(runId, stage);
                });
    }

    // ==================== 单批流 ====================

    /**
     * 组装单批生成流：订阅时短路检查（前批失败）→ 批间 quota 拦截 → 批内形态
     * prompt 装配 → LLM 流式（token 透流，并行批交错合流）→ 批收口（切卡 /
     * 重名检测 / 追加落盘 / 记账 / 批 DONE，锁内串行）→ batch_done 收尾。
     * <p>
     * 已产出人名索引 = manifest 未毙候选命中名册的身份键 + 先序批人名预知（编排
     * 期声明，并行批的防重复基准稳定；先序批失败后人物缺失由后续补批经
     * rosterRemaining 自愈——名册不漏人）。
     */
    private Flux<ServerSentEvent<Object>> batchFlux(String runId, PipelineManifest manifest,
                                                    String upstreamConfirmed,
                                                    List<String> rosterNames, PlannedBatch planned,
                                                    List<PlannedBatch> allPlanned,
                                                    AtomicReference<String> failureRef, String tenantId) {
        final PipelineStage stage = PipelineStage.S3_CHARACTERS;
        final PipelineManifest.BatchState batch = planned.state();
        final int totalBatches = allPlanned.size();
        return Flux.defer(() -> {
            // 短路：前批失败/拦截（failureRef）时空流（merge 不阻断，须显式短路防继续耗 token）
            if (failureRef.get() != null) {
                return Flux.empty();
            }

            // 批间 quota 拦截（治理三段式：超限在下一批启动前拦截）
            try {
                quotaGuard.check(tenantId);
            } catch (QuotaExceededException qe) {
                log.info("配额超限拦截本轮第 {}/{} 批（批号 {}）: runId={}",
                        planned.roundIndex(), totalBatches, batch.getIndex(), runId);
                failBatch(runId, stage, batch, "配额超限，本轮第 " + planned.roundIndex()
                        + "/" + totalBatches + " 批未启动（已成功批次保留，额度恢复后重试续跑）: "
                        + qe.getMessage(),
                        failureRef, tenantId);
                return Flux.just(batchDoneEvent(batch, planned.roundIndex(), "error"));
            }

            final String prompt = charactersGenerator.buildPrompt(
                    buildBatchContext(manifest, upstreamConfirmed, rosterNames, planned, allPlanned));
            final StringBuilder response = new StringBuilder();
            // defer：重订阅即完整重调（retry 自动重发的载体）——瞬时网络故障
            //（IOException 族：读空闲超时 / 连接重置，run 5ffd6d5b 批 10 生产教训）
            // 按 batch-network-retries 原批重发，重试耗尽才走下方 onErrorResume
            // 失败冻结；业务性错误（切卡失败 / 全重名 / 配额超限）不匹配过滤器不重试
            Flux<String> contentFlux = Flux.defer(() -> chatClientRegistry.forRole("planner")
                            .prompt()
                            .user(prompt)
                            .advisors(a -> {
                                a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "planner");
                                if (tenantId != null && !tenantId.isBlank()) {
                                    a.param(MeteringAdvisor.TENANT_CONTEXT_KEY, tenantId);
                                }
                            })
                            .stream()
                            .content())
                    .retryWhen(Retry.max(Math.max(0, properties.getBatchNetworkRetries()))
                            .filter(S3BatchGenerationService::isTransientNetworkFailure)
                            .doBeforeRetry(signal -> {
                                // 清掉失败半程已累积的 token（重发后重新累积；已推送
                                // 前端的半程 token 不撤回——batch_done 后重渲染覆盖）
                                response.setLength(0);
                                log.warn("本轮第 {}/{} 批（批号 {}）LLM 流瞬时网络故障，自动重发: runId={}, failure={}",
                                        planned.roundIndex(), totalBatches, batch.getIndex(), runId,
                                        String.valueOf(signal.failure()));
                            }));

            return Flux.concat(
                    Flux.just(batchStartEvent(batch, planned.roundIndex(), totalBatches)),
                    contentFlux
                            .mapNotNull(token -> {
                                response.append(token);
                                return contentEvent(token);
                            })
                            .concatWith(Mono.defer(() -> {
                                // 批收口：切卡 → 重名检测 → 追加落盘 → 候选行 append →
                                // 批 DONE（锁内串行；抛异常走 onErrorResume 统一失败冻结）。
                                // concatWith 透传 content token 事件（不能用 then——
                                // 它丢弃上游全部 onNext，token 会全丢）
                                finalizeBatch(runId, stage, planned, allPlanned, rosterNames,
                                        response.toString(), failureRef, tenantId);
                                return Mono.just(batchDoneEvent(batch, planned.roundIndex(), "done"));
                            }))
                            .onErrorResume(error -> {
                                log.error("本轮第 {}/{} 批（批号 {}）生成失败: runId={}",
                                        planned.roundIndex(), totalBatches, batch.getIndex(), runId, error);
                                failBatch(runId, stage, batch, "本轮第 " + planned.roundIndex()
                                                + "/" + totalBatches + " 批生成失败（已成功批次保留，可重试续跑）: "
                                                + error.getMessage(),
                                        failureRef, tenantId);
                                return Flux.empty();
                            }));
        });
    }

    // ==================== 批收口（锁内串行） ====================

    /**
     * 单批收口：{@link CandidateSplitter} 切卡 → 重名检测（vs manifest 未毙人名
     * + 先序批人名预知 + 本批互查；身份比对键限定名册人名域——「人物 N」序
     * 号前缀仅当名册名本身是编号形时才是身份键，跨轮/跨批 LLM 自编序号天然
     * 撞车不作重名，6.3 E2E 后真实使用发现；重名卡丢弃日志告警不阻断，spec
     * 「宁重复提示重跑该批」的兜底）→ <b>批内名册对账</b>（kept 覆盖批内名册
     * 人名低于半数判该批失败——design D2「批内人名清单对不上的批整体重跑」
     * 的收口实现，run 5ffd6d5b 批 16/17/19 三次模型只回「## 人物 1」空壳卡
     * 的生产教训）→ 追加落盘（id 顺延不重置既有卡）→ manifest 候选行 append
     *（新卡 PENDING，spec「追加不重置」）→ 批 DONE 记账。全部新卡被重名过滤
     *（模型漂移只重产出已有人物）视为该批失败。
     *
     * @throws RuntimeException 落盘/记账失败（调用方 onErrorResume 统一失败冻结）
     */
    private void finalizeBatch(String runId, PipelineStage stage, PlannedBatch planned,
                               List<PlannedBatch> allPlanned, List<String> rosterNames,
                               String batchDraft,
                               AtomicReference<String> failureRef, String tenantId) {
        final PipelineManifest.BatchState batch = planned.state();
        List<String> candidates = CandidateSplitter.split(batchDraft);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("本轮第 " + planned.roundIndex() + "/" + allPlanned.size()
                    + " 批（批号 " + batch.getIndex() + "）模型输出未包含任何候选块（## 二级标题），该批未产出候选");
        }
        synchronized (batchFinalizeLock) {
            withTenant(tenantId, () -> {
                // 重名检测基准：manifest 未毙人名（实时重读——先序并行批可能刚追加）+
                // 先序批人名预知（编排期声明，防并行窗口漏检）。身份键限定
                // 名册域（rosterHitNames）：「人物 N」序号前缀仅当名册名本身是
                // 编号形时才是身份，跨轮/跨批 LLM 自编序号撞车不误杀
                PipelineManifest manifest = workspaceStore.readManifest(runId);
                if (manifest == null) {
                    throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
                }
                List<String> producedNames = new ArrayList<>();
                manifest.stage(stage.code()).getCandidates().stream()
                        .filter(c -> !DraftItemStatus.REJECTED.name().equals(c.getStatus()))
                        .forEach(c -> producedNames.addAll(
                                StagePromptAssembler.rosterHitNames(c.getTitle(), rosterNames)));
                for (PlannedBatch prior : allPlanned) {
                    if (prior.state().getIndex() < batch.getIndex()) {
                        producedNames.addAll(prior.state().getNames());
                    }
                }
                // 新卡过滤（重名丢弃日志告警；本批互查经 producedNames 累积实现）。
                // 比对键 = 命中名册的身份键（名册域外不判身份——宁保留人工毙，
                // 不误杀）；标题完全无有效人名仍丢弃（无效卡）
                List<String> kept = new ArrayList<>();
                for (String candidate : candidates) {
                    List<String> nameCandidates = StagePromptAssembler
                            .titleNameCandidates(StageDraftSet.titleOf(candidate));
                    if (nameCandidates.isEmpty()) {
                        log.warn("批 {}（本轮 {}/{}）候选标题无有效人名，丢弃: runId={}",
                                batch.getIndex(), planned.roundIndex(), allPlanned.size(), runId);
                        continue;
                    }
                    List<String> rosterKeys = StagePromptAssembler
                            .rosterHitNames(StageDraftSet.titleOf(candidate), rosterNames);
                    if (!rosterKeys.isEmpty() && rosterKeys.stream().anyMatch(
                            nc -> StagePromptAssembler.containsName(producedNames, nc))) {
                        log.warn("批 {}（本轮 {}/{}）产出重名候选丢弃（模型漂移重产出已有人物）: runId={}, name={}",
                                batch.getIndex(), planned.roundIndex(), allPlanned.size(), runId,
                                nameCandidates.get(0));
                        continue;
                    }
                    producedNames.addAll(rosterKeys);
                    kept.add(candidate);
                }
                if (kept.isEmpty()) {
                    throw new IllegalStateException("本轮第 " + planned.roundIndex() + "/" + allPlanned.size()
                            + " 批（批号 " + batch.getIndex() + "）候选全部为重名或无效（模型漂移），该批未追加任何候选");
                }
                // 批内名册对账（D2「批内人名清单对不上的批整体重跑」收口）：kept 卡
                // 覆盖批内名册人名低于半数判该批失败冻结（模型输出疑似截断或只回
                // 空壳卡「## 人物 N」——无有效名册人名，批 16/17/19 三次复现），漏
                // 的人由 rosterRemaining 断点续跑自愈重跑，宁重跑不落废卡；轻微缺漏
                //（模型少写个别人物）静默容忍，漏的人自然进下一轮剩余清单补齐
                LinkedHashSet<String> covered = new LinkedHashSet<>();
                for (String candidate : kept) {
                    covered.addAll(StagePromptAssembler.rosterHitNames(
                            StageDraftSet.titleOf(candidate), batch.getNames()));
                }
                if (covered.size() * 2 < batch.getNames().size()) {
                    throw new IllegalStateException("本轮第 " + planned.roundIndex() + "/" + allPlanned.size()
                            + " 批（批号 " + batch.getIndex() + "）产出对不上批内名册（覆盖 "
                            + covered.size() + "/" + batch.getNames().size()
                            + " 人，模型输出疑似截断或空壳卡），该批整体重跑（已成功批次保留）");
                }
                // 追加落盘（id 顺延）+ manifest 候选行 append（新卡 PENDING，既有状态不动）
                List<String> ids = workspaceStore.appendStageCandidates(runId, stage, kept);
                List<String> titles = kept.stream().map(StageDraftSet::titleOf).toList();
                runService.appendStageCandidates(runId, stage, ids, titles);
                // 批 DONE 记账
                runService.setBatchStatus(runId, stage, batch.getIndex(), "DONE");
                log.info("S3 批 {}（本轮 {}/{}）收口完成: runId={}, 追加 {} 张候选（丢弃重名 {} 张）",
                        batch.getIndex(), planned.roundIndex(), allPlanned.size(), runId,
                        kept.size(), candidates.size() - kept.size());
            });
        }
    }

    /**
     * 全部批完成后的编排收口（失败短路由 failureRef 前置检查——阶段已 FAILED
     * 无版本推进）：版本 v(N+1) + 全量聚合快照（语义稿口径：滤 REJECTED 候选
     * 聚合，spec「版本快照含全量聚合」）+ markStageCompleted（AWAITING_CONFIRM +
     * 摘要行）+ draft_ready 尾包数据（version / 总候选数）。
     * 收口失败置 FAILED（防御性，失败标记自身不能再抛）。
     */
    private void finalizeBatches(String runId, PipelineStage stage,
                                 AtomicReference<String> failureRef,
                                 AtomicReference<Integer> versionRef,
                                 AtomicReference<Integer> totalCandidatesRef, String tenantId) {
        if (failureRef.get() != null) {
            return;   // 已有失败：阶段已 FAILED，无版本推进（已成功批产物保留）
        }
        synchronized (batchFinalizeLock) {
            withTenant(tenantId, () -> {
                try {
                    PipelineManifest manifest = workspaceStore.readManifest(runId);
                    List<PipelineManifest.CandidateState> all =
                            manifest.stage(stage.code()).getCandidates();
                    int nextVersion = manifest.stage(stage.code()).getVersion() + 1;
                    // 全量聚合快照（滤 REJECTED 语义稿口径，与下游注入/上轮全稿同源）
                    String snapshot = workspaceStore.readStageFullText(runId, stage, all);
                    workspaceStore.writeVersionSnapshot(runId, stage, nextVersion, snapshot);
                    versionRef.set(runService.markStageCompleted(runId, stage,
                            batchSummary(all)));
                    totalCandidatesRef.set(all.size());
                    log.info("S3 分批生成收口: runId={}, version={}, 总候选 {} 张",
                            runId, versionRef.get(), all.size());
                } catch (RuntimeException e) {
                    log.error("S3 分批生成收口失败，置 FAILED: runId={}", runId, e);
                    runService.markStageFailed(runId, stage);
                    failureRef.set("分批生成收口失败: " + e.getMessage());
                }
            });
        }
    }

    /** 批次摘要行（对齐 StageGenerationService.buildStageSummary 惯例：候选标题列表 + 120 截断）。 */
    private static String batchSummary(List<PipelineManifest.CandidateState> candidates) {
        String raw = candidates.size() + " 个候选：" + candidates.stream()
                .map(PipelineManifest.CandidateState::getTitle)
                .collect(Collectors.joining("、"));
        return raw.length() > StageGenerationService.SUMMARY_TRUNCATE
                ? raw.substring(0, StageGenerationService.SUMMARY_TRUNCATE) + "…" : raw;
    }

    /**
     * 批失败冻结（spec「批粒度断点续跑」）：阶段置 FAILED 冻结推进（不回滚已
     * 成功批）、批记账置 FAILED、failureRef 短路后续批（不重复耗 token）。
     * 防御性：标记失败自身吞异常（不能丢原始错误）。
     */
    private void failBatch(String runId, PipelineStage stage, PipelineManifest.BatchState batch,
                           String message, AtomicReference<String> failureRef, String tenantId) {
        failureRef.compareAndSet(null, message);
        withTenant(tenantId, () -> {
            try {
                runService.markStageFailed(runId, stage);
                if (batch != null) {
                    runService.setBatchStatus(runId, stage, batch.getIndex(), "FAILED");
                }
            } catch (RuntimeException e) {
                log.error("批失败标记写入异常（保留原始错误，忽略本次标记失败）: runId={}, stage={}",
                        runId, stage.code(), e);
            }
        });
    }

    // ==================== 批内形态装配 ====================

    /**
     * 批内形态 {@link GenerationContext}（tasks 3.3）：upstreamConfirmed = S2
     * 确认稿全文（S3 唯一内容上游，编排期快照传入——订阅线程无租户上下文，
     * 批流内不可读工作区）；roster 置 null（批内不走全量名册——
     * {@link CharactersGenerator} 按 isBatch 走 appendBatchSections）；rounds /
     * rejectedTitles 置空（补批/全量与返工互斥，批内无返工段）；producedNames
     * 含先序批人名预知（并行批防重复基准稳定）。
     */
    private GenerationContext buildBatchContext(PipelineManifest manifest, String upstreamConfirmed,
                                                List<String> rosterNames, PlannedBatch planned,
                                                List<PlannedBatch> allPlanned) {
        final PipelineStage stage = PipelineStage.S3_CHARACTERS;
        // 已产出人名索引：manifest 未毙候选命中名册的身份键（「人物 N」自编序
        // 号不进索引误导 LLM 编号）+ 先序批人名预知（编排期声明，本就是名册名）
        List<String> producedNames = new ArrayList<>();
        manifest.stage(stage.code()).getCandidates().stream()
                .filter(c -> !DraftItemStatus.REJECTED.name().equals(c.getStatus()))
                .forEach(c -> producedNames.addAll(
                        StagePromptAssembler.rosterHitNames(c.getTitle(), rosterNames)));
        for (PlannedBatch prior : allPlanned) {
            if (prior.state().getIndex() < planned.state().getIndex()) {
                producedNames.addAll(prior.state().getNames());
            }
        }
        return new GenerationContext(manifest, null, upstreamConfirmed, null,
                List.of(), List.of(), null, planned.rosterText(), producedNames);
    }

    // ==================== 工具 ====================

    /** 编排批（预拼形态：批记账 + 本批名册块文本 + 本轮序号——断点续跑后全局批号跨轮
     * 累计，SSE 进度语义用本轮序；rosterText 不落 manifest 仅流内使用）。 */
    private record PlannedBatch(PipelineManifest.BatchState state, String rosterText, int roundIndex) {}

    /** 名单按批容量顺序切块并截断上限（拷贝脱离原列表视图）。 */
    private static List<List<String>> partitionNames(List<String> names, int batchSize, int limit) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < names.size() && batches.size() < limit; i += batchSize) {
            batches.add(List.copyOf(names.subList(i, Math.min(i + batchSize, names.size()))));
        }
        return batches;
    }

    /**
     * 瞬时网络故障判定：异常本身或其 cause 链任一为 {@link IOException}（okhttp
     * 读空闲超时 / StreamReset / 连接重置族）。业务性错误（切卡失败 / 全重名的
     * IllegalStateException / 配额 QuotaExceededException）不匹配不重试。
     */
    private static boolean isTransientNetworkFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
            if (t.getCause() == t) {
                break;   // 自引用 cause 防御
            }
        }
        return false;
    }

    // ==================== 租户传播（gm-async 模式） ====================

    /**
     * SSE 回调线程内恢复租户上下文后执行动作（同 StageGenerationService）：恢复者
     * 负责清理；当前线程已有上下文时不覆盖不清理。
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

    private ServerSentEvent<Object> stageStartEvent(PipelineStage stage) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "stage_start", "stage", stage.code(), "label", stage.label()))
                .build();
    }

    /** 批开始事件（index 本轮序号从 1 起 / seq 全局批号 / total 本次编排总批数 / count 批内人数）。 */
    private ServerSentEvent<Object> batchStartEvent(PipelineManifest.BatchState batch, int roundIndex,
                                                    int totalBatches) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "batch_start", "stage", PipelineStage.S3_CHARACTERS.code(),
                        "index", roundIndex, "seq", batch.getIndex(), "total", totalBatches,
                        "count", batch.getNames().size()))
                .build();
    }

    /** 批收口事件（status = done 已追加落盘 / error 该批失败冻结；index 本轮序号 / seq 全局批号）。 */
    private ServerSentEvent<Object> batchDoneEvent(PipelineManifest.BatchState batch, int roundIndex,
                                                   String status) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("type", "batch_done", "stage", PipelineStage.S3_CHARACTERS.code(),
                        "index", roundIndex, "seq", batch.getIndex(), "status", status))
                .build();
    }

    private ServerSentEvent<Object> contentEvent(String token) {
        return ServerSentEvent.<Object>builder()
                .data(Map.of("content", token))
                .build();
    }

    /** 成功尾包（candidates = 收口后候选总数，对齐 L2 draft_ready 惯例）。 */
    private ServerSentEvent<Object> draftReadyEvent(PipelineStage stage, int version, Integer candidates) {
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
