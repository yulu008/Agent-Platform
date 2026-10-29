package com.luyu.agent.rpg.planner.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.engine.S1ChainedRefineService;
import com.luyu.agent.rpg.planner.engine.S3BatchGenerationService;
import com.luyu.agent.rpg.planner.engine.StageGenerationService;
import com.luyu.agent.rpg.planner.engine.StoryGraphGenerationService;
import com.luyu.agent.rpg.planner.model.DraftItemStatus;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.story.engine.StitchResult;
import com.luyu.agent.rpg.story.engine.StoryGraphParseResult;
import com.luyu.agent.rpg.story.engine.StoryGraphParser;
import com.luyu.agent.rpg.story.engine.StoryGraphStitcher;
import com.luyu.agent.rpg.story.model.StoryAct;

import reactor.core.publisher.Flux;

/**
 * 确认门操作编排层（add-planner-pipeline tasks 4.2-4.5，spec planner-confirm-gates）。
 * <p>
 * 守卫链（run 存在 / manifest 可读 / 阶段待确认态 / 目标即当前阶段）→
 * 业务校验（L2 pending 清零）→ 产物写与状态推进。状态写仍经
 * {@link PipelineRunService} 单点（D3 先 manifest 后库表）；文件产物与
 * 版本快照经 {@link PlannerWorkspaceStore}（同 StageGenerationService 落盘惯例）。
 * <p>
 * S4.5 整稿通过额外执行跨幕拼接（story-graph-authoring tasks 2.5：
 * 幕候选 → 拼接全图 JSON 落盘 stage artifact，出口锚→下幕入口确定性
 * 生成 + 模型越权跨幕边丢弃告警，拼接幂等可重入）。
 * <p>
 * S4.5 幕级 L2 返工门（story-graph-authoring tasks 2.6，spec「幕级 L2
 * 返工门」）：单幕候选返工（reworkAct——目标幕重生成不波及他幕）与
 * 已确认幕回退解锁（unlockAct——候选复位 PENDING + 拼接图删除标记待重拼）；
 * 通用 reworkStage 对 S4.5 路由到幕级专用编排（整轮返工）。
 * <p>
 * 编辑保存不做输入审查（对齐 WorkshopController 保存端点惯例：moderation
 * 只覆盖进入 LLM 的输入线；返工反馈是唯一新输入线，tasks 4.4 返工端点
 * 在 controller 侧前置审查）。
 * <p>
 * 异常约定（同 {@code PipelineRunService}，controller 统一映射）：
 * {@link IllegalArgumentException} → 400、{@link NoSuchElementException} → 404、
 * {@link IllegalStateException} → 409。
 */
@Service
public class StageGateService {

    private static final Logger log = LoggerFactory.getLogger(StageGateService.class);

    /** 拼接全图序列化（story-graph 块协议与 parser 读回形态对齐） */
    private static final ObjectMapper GRAPH_MAPPER = new ObjectMapper();

    private final PipelineRunService runService;
    private final PlannerWorkspaceStore workspaceStore;
    private final PipelineRunRepository runRepository;
    private final StageGenerationService generationService;
    private final StoryGraphGenerationService storyGraphGeneration;
    /** S1 链式汇总模式返工路由（planner-s1-chained-refinement tasks 4.1） */
    private final S1ChainedRefineService s1ChainedRefine;
    /** S3 补批/全量分批路由（planner-s3-batch-generate tasks 3.1，D5 共用路径差参不差路径） */
    private final S3BatchGenerationService s3BatchGeneration;
    /** 链式阈值判定（与生成分叉/详情 chained 派生标记同判） */
    private final RpgPlannerProperties properties;
    private final StoryGraphParser graphParser;
    private final StoryGraphStitcher graphStitcher;

    public StageGateService(PipelineRunService runService,
                            PlannerWorkspaceStore workspaceStore,
                            PipelineRunRepository runRepository,
                            StageGenerationService generationService,
                            StoryGraphGenerationService storyGraphGeneration,
                            S1ChainedRefineService s1ChainedRefine,
                            S3BatchGenerationService s3BatchGeneration,
                            RpgPlannerProperties properties,
                            StoryGraphParser graphParser,
                            StoryGraphStitcher graphStitcher) {
        this.runService = runService;
        this.workspaceStore = workspaceStore;
        this.runRepository = runRepository;
        this.generationService = generationService;
        this.storyGraphGeneration = storyGraphGeneration;
        this.s1ChainedRefine = s1ChainedRefine;
        this.s3BatchGeneration = s3BatchGeneration;
        this.properties = properties;
        this.graphParser = graphParser;
        this.graphStitcher = graphStitcher;
    }

    // ==================== L1 门操作（tasks 4.2） ====================

    /**
     * L1 草稿编辑保存（spec「L1 编辑门草稿编辑」）：S1/S4 Markdown / S2 表单
     * （前端序列化为 Markdown 后走同端点）编辑后全稿覆盖工作区产物文件，
     * 并落同版本号快照（原稿经版本历史可回看）。
     * <p>
     * recordDraftEdit 先行——manifest 版本递增是并发编辑的序列化点
     * （双开标签页交错时版本号恒唯一）；产物写失败时版本号浪费一格但
     * 状态自愈：阶段仍 AWAITING_CONFIRM，重存即恢复。锁定态拒绝
     * （spec「锁定后不可静默改」提示须先回退解锁）。
     *
     * @return {@code {stage, version}}（新版本号 v(N+1)）
     */
    public Map<String, Object> saveL1Draft(String runId, PipelineStage stage, String content) {
        requireL1Editable(stage);
        requireGateManifest(runId, stage);
        int newVersion = runService.recordDraftEdit(runId, stage);
        workspaceStore.writeStageArtifact(runId, stage, content);
        workspaceStore.writeVersionSnapshot(runId, stage, newVersion, content);
        log.info("L1 草稿编辑已保存: runId={}, stage={}, version={}", runId, stage.code(), newVersion);
        return Map.of("stage", stage.code(), "version", newVersion);
    }

    /**
     * 整稿通过（spec「确认稿幂等锁定」）：L2 阶段（S3/S4.5/S5）先做 pending 清零
     * 前置校验（候选须逐个确认或毙掉，L1 阶段无候选恒通过）；状态推进与
     * currentStage 前移经 {@link PipelineRunService#confirmStage} 单点
     * （S6 拒绝——安装端点专用语义，tasks 5.2 置 COMPLETED）。
     * <p>
     * S4.5 整稿通过先执行跨幕拼接（tasks 2.5）：非毙幕候选逐个解析为
     * {@link StoryAct} → {@link StoryGraphStitcher} 确定性拼接（出口锚→
     * 下幕入口 + 越权跨幕边丢弃告警，幂等）→ 拼接全图 JSON 落盘 stage
     * artifact（工坊编辑/安装物化的结构真源）。候选解析失败拒绝确认
     * （阶段状态零副作用，修正候选后可重试）。
     *
     * @return {@code {stage, confirmedVersion, nextStage}}
     */
    public Map<String, Object> confirmStage(String runId, PipelineStage stage) {
        PipelineManifest manifest = requireGateManifest(runId, stage);
        requireNoPendingCandidates(manifest, stage);
        if (stage == PipelineStage.S4P5_STORY_GRAPH) {
            stitchAndPersistGraph(runId, manifest);
        }
        int confirmedVersion = manifest.stage(stage.code()).getVersion();
        runService.confirmStage(runId, stage);
        return Map.of(
                "stage", stage.code(),
                "confirmedVersion", confirmedVersion,
                "nextStage", stage.next().code());
    }

    // ==================== S4.5 跨幕拼接（story-graph-authoring tasks 2.5） ====================

    /**
     * 跨幕拼接与落盘：非毙幕候选（D7 口径，被毙不进确认稿）按候选 ID 升序
     * 逐个解析（单幕 <story_graph> 原文 → {@link StoryAct}；失败即拒绝确认）
     * → 拼接器确定性生成跨幕边 → 全图 JSON（<story_graph> 块包裹，携带第 1 幕
     * 产出的 state_schema 词汇表）落盘 stage artifact。拼接幂等：确认门幂等
     * 锁定拦截重入，回退解锁后再确认时拼接器清旧边重算无残留。
     */
    private void stitchAndPersistGraph(String runId, PipelineManifest manifest) {
        List<PipelineManifest.CandidateState> candidates =
                manifest.stage(PipelineStage.S4P5_STORY_GRAPH.code()).getCandidates();
        List<PipelineManifest.CandidateState> kept = new ArrayList<>();
        for (PipelineManifest.CandidateState candidate : candidates) {
            if (!DraftItemStatus.REJECTED.name().equals(candidate.getStatus())) {
                kept.add(candidate);
            }
        }
        if (kept.isEmpty()) {
            throw new IllegalStateException("无可用幕候选（全部被毙或缺候选），请返工重生成后再整稿通过");
        }
        // 候选 ID 升序（candidate-01 < candidate-02 …，两位零填充字符串序稳定）
        kept.sort(Comparator.comparing(PipelineManifest.CandidateState::getId));

        List<StoryAct> acts = new ArrayList<>(kept.size());
        String stateSchema = null;
        for (PipelineManifest.CandidateState candidate : kept) {
            String text = workspaceStore.readCandidateItem(
                    runId, PipelineStage.S4P5_STORY_GRAPH, candidate.getId());
            StoryGraphParseResult result = graphParser.parse(text);
            if (!result.ok() || result.getActs().size() != 1) {
                throw new IllegalArgumentException("幕候选 " + candidate.getId()
                        + " 解析失败（请修正候选后重试整稿通过）："
                        + String.join("；", result.getErrors()));
            }
            acts.add(result.getActs().get(0));
            if (stateSchema == null) {
                stateSchema = result.getStateSchema();
            }
        }
        StitchResult stitch = graphStitcher.stitch(acts);
        for (String warning : stitch.getWarnings()) {
            log.warn("S4.5 整稿通过拼接告警: runId={}, {}", runId, warning);
        }
        workspaceStore.writeStageArtifact(runId, PipelineStage.S4P5_STORY_GRAPH,
                graphJson(acts, stateSchema));
        log.info("S4.5 整稿通过跨幕拼接落盘: runId={}, acts={}, crossEdges={}, warnings={}",
                runId, acts.size(), stitch.getCrossEdges().size(), stitch.getWarnings().size());
    }

    /**
     * 拼接全图序列化：{@code <story_graph>} 块包裹（对齐候选原文块协议，下游
     * parse 统一读回）+ acts 数组 + state_schema（字符串形态转回对象节点，
     * 与 LLM 原始输出的词汇表对象形态一致）。
     */
    private String graphJson(List<StoryAct> acts, String stateSchema) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("acts", acts);
            if (stateSchema != null && !stateSchema.isBlank()) {
                JsonNode schemaNode = GRAPH_MAPPER.readTree(stateSchema);
                root.put("state_schema", schemaNode);
            }
            String json = GRAPH_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            return "<story_graph>\n" + json + "\n</story_graph>";
        } catch (Exception e) {
            throw new IllegalStateException("拼接全图序列化失败", e);
        }
    }

    // ==================== L2 门逐卡操作（tasks 4.3） ====================

    /**
     * 逐卡毙掉（spec「候选独立状态」）：只流转 manifest 候选状态（REJECTED
     * 幂等；确认后可改毙），不动候选文件——被毙候选的文件在下一轮返工整轮
     * 覆盖时消失（D5），排除表记忆靠 manifest title。
     *
     * @return {@code {stage, candidateId, status}}
     */
    public Map<String, Object> rejectCandidate(String runId, PipelineStage stage, String candidateId) {
        return setCandidateStatus(runId, stage, candidateId, DraftItemStatus.REJECTED);
    }

    /**
     * 逐卡确认（spec「候选独立状态」）：PENDING→CONFIRMED（幂等）；
     * REJECTED 不可确认（终态，救回走返工重生成）。
     *
     * @return {@code {stage, candidateId, status}}
     */
    public Map<String, Object> confirmCandidate(String runId, PipelineStage stage, String candidateId) {
        return setCandidateStatus(runId, stage, candidateId, DraftItemStatus.CONFIRMED);
    }

    private Map<String, Object> setCandidateStatus(String runId, PipelineStage stage,
                                                    String candidateId, DraftItemStatus target) {
        requireL2Gate(stage);
        requireGateManifest(runId, stage);
        runService.setCandidateStatus(runId, stage, candidateId, target);
        return Map.of("stage", stage.code(), "candidateId", candidateId, "status", target.name());
    }

    /**
     * 批量确认（planner-s3-batch-confirm tasks 1.2，spec「L2 门批量确认」）：
     * 全部 PENDING 候选一键置 CONFIRMED——REJECTED 终态与既有 CONFIRMED
     * 均不动；无 PENDING 幂等返回 confirmedCount=0。不锁定阶段（仍
     * AWAITING_CONFIRM，整稿通过与逐卡操作语义不变）。L2 门通用（S3/S4.5/S5
     * 均可调；前端入口本期只挂 S3）。
     *
     * @return {@code {stage, confirmedCount}}
     */
    public Map<String, Object> confirmAllCandidates(String runId, PipelineStage stage) {
        requireL2Gate(stage);
        requireGateManifest(runId, stage);
        int confirmedCount = runService.confirmAllCandidates(runId, stage);
        return Map.of("stage", stage.code(), "confirmedCount", confirmedCount);
    }

    /**
     * S3 确认并整稿通过（planner-s3-batch-confirm tasks 1.3，spec「S3 确认并
     * 整稿通过」组合动作）：批量确认 + 整稿通过原子化——仅 S3 开放（S4.5
     * 拼接预检语义复杂、S5 场景少，本期不开放）；批量确认后复用整稿通过
     * 既有守卫与单点推进（S3 无 S4.5 拼接步骤，天然零副作用路径）。
     *
     * @return {@code {stage, confirmedCount, confirmedVersion, nextStage}}
     */
    public Map<String, Object> confirmRemainingAndPass(String runId, PipelineStage stage) {
        requireL2Gate(stage);
        if (stage != PipelineStage.S3_CHARACTERS) {
            throw new IllegalStateException("「确认并整稿通过」仅对 S3 人物设定开放: " + stage.code());
        }
        requireGateManifest(runId, stage);
        int confirmedCount = runService.confirmAllCandidates(runId, stage);
        // 复用整稿通过门（内部重读 manifest——批量确认写回后 pending 已清零；
        // S3 不经 stitchAndPersistGraph，候选状态与阶段锁定无中间态风险）
        Map<String, Object> result = new LinkedHashMap<>(confirmStage(runId, stage));
        result.put("confirmedCount", confirmedCount);
        log.info("S3 确认并整稿通过: runId={}, confirmedCount={}", runId, confirmedCount);
        return result;
    }

    /**
     * 逐卡编辑保存（spec「候选独立状态」payload 就地编辑）：单卡覆盖写
     * （writeCandidateItem）+ 阶段版本递增 + 语义稿快照（非毙候选聚合，
     * tasks 4.3——被毙候选不进快照口径同语义全稿读取）。
     * REJECTED 不可编辑（已毙不复活，救回走返工重生成）。
     *
     * @return {@code {stage, candidateId, version}}
     */
    public Map<String, Object> saveCandidateEdit(String runId, PipelineStage stage,
                                                  String candidateId, String content) {
        requireL2Gate(stage);
        PipelineManifest manifest = requireGateManifest(runId, stage);
        PipelineManifest.CandidateState candidate = manifest.stage(stage.code()).candidate(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("候选不存在或已过期: " + candidateId);
        }
        if (DraftItemStatus.REJECTED.name().equals(candidate.getStatus())) {
            throw new IllegalStateException("已毙候选不可编辑（救回走返工重生成）");
        }
        int newVersion = runService.recordDraftEdit(runId, stage);
        workspaceStore.writeCandidateItem(runId, stage, candidateId, content);
        // 语义稿快照：编辑后非毙候选聚合（manifest 状态未被本次编辑改变）
        workspaceStore.writeVersionSnapshot(runId, stage, newVersion,
                workspaceStore.readStageFullText(runId, stage,
                        manifest.stage(stage.code()).getCandidates()));
        log.info("L2 候选编辑已保存: runId={}, stage={}, candidate={}, version={}",
                runId, stage.code(), candidateId, newVersion);
        return Map.of("stage", stage.code(), "candidateId", candidateId, "version", newVersion);
    }

    // ==================== L2 返工（tasks 4.4） ====================

    /**
     * L2 返工动作（spec「返工动作」）：反馈记账（rounds 追加——版本树与反馈
     * 历史真源，跨天回看）后委托生成编排触发新一轮生成；版本 v(N+1) 由生成
     * 完成时 markStageCompleted 递增，返工 prompt 注入（上轮全稿语义稿 /
     * 反馈历史 / 保持不变约束 / 已毙候选排除表）在 buildContext 装配
     * （tasks 3.2/3.6）。S4.5 路由到幕级专用编排（同 specializedEndpoint
     * 语义：整轮返工全量重生成幕候选）。
     * <p>
     * 反馈审查（D11——返工反馈是唯一新用户输入线）在 controller 前置：
     * 命中即拒答且零副作用（本方法不被触达，记账与生成都不执行，版本树
     * 停在当前版本）。配额检查在生成流内（治理三段式 quota 段，tasks 3.1）。
     * L1 阶段无返工动作（草稿修改走编辑保存，spec「gate 分级」）；S6 只读。
     *
     * @return 生成 SSE 流（stage_start → content* → draft_ready/error → [DONE]）
     */
    public Flux<ServerSentEvent<Object>> reworkStage(String runId, PipelineStage stage, String feedback) {
        // S1 链式 run 的 L1 门额外提供返工（planner-s1-chained-refinement tasks 4.1，
        // spec「S1 链式返工仅重生汇总轮」）：仅 S1 且原料（text 原文 / ip_name
        // 资料页合并虚拟原文）超链式阈值放行（判定需 run/manifest，见
        // isS1ChainedRun）；其余 L1 阶段（含非链式 S1）维持 requireReworkGate
        // 拒绝语义不变（草稿修改走编辑保存，spec「gate 分级」）
        boolean s1Chained = stage == PipelineStage.S1_IP_ANALYSIS && isS1ChainedRun(runId);
        if (!s1Chained) {
            requireReworkGate(stage);
        }
        requireGateManifest(runId, stage);
        int round = runService.recordReworkRound(runId, stage, feedback);
        log.info("返工轮记账: runId={}, stage={}, round={}", runId, stage.code(), round);
        if (s1Chained) {
            // tasks 4.2 汇总模式：AWAITING_CONFIRM 入口 → 块轮全跳（笔记是原文
            // 确定性派生物，保留复用零重生成），反馈 + rounds 历史注入汇总轮产出
            // v N+1；治理（moderation 审 userFeedback + quota 三段式）在链式服务
            // 流内执行（controller 侧 moderation 前置已审反馈原文）
            return s1ChainedRefine.generateChained(runId, feedback);
        }
        if (stage == PipelineStage.S4P5_STORY_GRAPH) {
            return storyGraphGeneration.generateActs(runId, feedback, null);
        }
        return generationService.generateStage(runId, stage.code());
    }

    /**
     * 上游已变重算：上游确认稿更新（版本高于本阶段生成基线 upstreamVersion）
     * 时不写返工反馈、按上游当前确认稿重新生成本阶段（新一轮 v N+1，L2
     * 候选集整轮重置）。与返工（rework，带用户反馈注入 prompt）互斥互补：
     * 重算响应上游变化、返工响应本阶段稿不满意；两者共用生成收口
     * （markStageCompleted 会刷新基线，重算后提示自然熄灭）。
     * <p>
     * 守卫：S1（原料不可变无上游）/ S6（只读）拒绝；门级守卫同返工
     * （AWAITING_CONFIRM + 当前阶段）；上游须 CONFIRMED 且版本高于基线
     * （未变重算走编辑/返工语义，避免无意义生成消耗）。无用户输入线
     * （不记 rounds、无 moderation——对齐返工「反馈是唯一输入线」的治理边界）。
     *
     * @throws NoSuchElementException run 不存在（404）
     * @throws IllegalStateException   阶段不可操作/上游未确认或未变（409）
     */
    public Flux<ServerSentEvent<Object>> regenerateStage(String runId, PipelineStage stage) {
        if (stage == PipelineStage.S1_IP_ANALYSIS || stage == PipelineStage.S6_INSTALL) {
            throw new IllegalStateException(
                    "本阶段无重算动作（S1 原料不可变 / S6 只读预览）: " + stage.code());
        }
        PipelineManifest manifest = requireGateManifest(runId, stage);
        PipelineStage upstream = stage.upstream();
        PipelineManifest.StageState upstreamState = manifest.stage(upstream.code());
        if (!PipelineStageStatus.CONFIRMED.name().equals(upstreamState.getStatus())) {
            throw new IllegalStateException(
                    "上游阶段未确认，不可重算（须先整稿通过上游）: " + upstream.code());
        }
        Integer baseline = manifest.stage(stage.code()).getUpstreamVersion();
        if (baseline == null || upstreamState.getVersion() <= baseline) {
            throw new IllegalStateException("上游确认稿未变化（v" + upstreamState.getVersion()
                    + " 不高于生成基线），无需重算；修改本阶段稿请走编辑/返工");
        }
        log.info("上游已变重算: runId={}, stage={}, 上游 {} v{}（基线 v{}）",
                runId, stage.code(), upstream.code(), upstreamState.getVersion(), baseline);
        if (stage == PipelineStage.S4P5_STORY_GRAPH) {
            // 整轮全量重生（userFeedback null 同首轮生成：prompt 无反馈段，
            // moderation check(null) 放行）
            return storyGraphGeneration.generateActs(runId, null, null);
        }
        return generationService.generateStage(runId, stage.code());
    }

    // ==================== S3 补批 / 全量生成（planner-s3-batch-generate tasks 3.1，D5 差参不差路径） ====================

    /**
     * S3 补一批（spec「补一批」门动作）：AWAITING_CONFIRM 态从名册剩余清单取
     * 下一批生成（增量追加候选集，既有逐卡状态不重置；返工整轮替换与补批
     * 增量覆盖正交，D5）。
     * <p>
     * 门级守卫（锁定拒绝 / 非当前阶段拒绝）经 {@link #requireGateManifest}
     * （409 语义，controller sseFailure 呈现）；无名册 / 名册剩余空 / 状态机 /
     * 配额 / 并发守卫在分批编排前置链（SSE error 事件零副作用，D5 共用路径）。
     * 无用户输入线（不记 rounds、无 moderation——对齐返工「反馈是唯一输入线」
     * 的治理边界）。
     *
     * @return 生成 SSE 流（stage_start → batch_start/content*（交错合流）→
     *         batch_done* → draft_ready/error → [DONE]）
     * @throws NoSuchElementException run 不存在（404）
     * @throws IllegalStateException   阶段非待确认态 / 非当前阶段 / 非 S3（409）
     */
    public Flux<ServerSentEvent<Object>> appendBatch(String runId, PipelineStage stage) {
        requireS3BatchGate(stage);
        requireGateManifest(runId, stage);
        return s3BatchGeneration.generateRemainingBatches(runId, 1);
    }

    /**
     * S3 全量生成（spec「全量生成」门动作）：名册剩余清单的全部批一次跑完
     * （batchLimit = ∞）。守卫口径与事件协议同 {@link #appendBatch}。
     */
    public Flux<ServerSentEvent<Object>> generateFull(String runId, PipelineStage stage) {
        requireS3BatchGate(stage);
        requireGateManifest(runId, stage);
        return s3BatchGeneration.generateRemainingBatches(runId, Integer.MAX_VALUE);
    }

    /** S3 补批/全量门限定（对齐 requireReworkGate 惯例）：非 S3 阶段无此门动作。 */
    private static void requireS3BatchGate(PipelineStage stage) {
        if (stage != PipelineStage.S3_CHARACTERS) {
            throw new IllegalStateException(
                    "补批/全量生成门动作仅 S3 人物候选阶段可用: " + stage.code());
        }
    }

    /**
     * S1 链式判定（与生成分叉/详情 chained 派生标记同判，planner-s1-chained
     * refinement tasks 4.1；planner-html-source D6 原料统一）：原料（text 原文 /
     * ip_name pages 合并虚拟原文）超链式阈值。run 不存在（404）/ manifest
     * 缺失（409）守卫前置——与门级拒绝同序（存在性优先）。
     */
    private boolean isS1ChainedRun(String runId) {
        runRepository.findById(runId).orElseThrow(
                () -> new NoSuchElementException("管线 run 不存在: " + runId));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }
        String source = workspaceStore.readS1Material(runId, manifest.getSourceMode());
        return source != null && source.length() > properties.getChainThresholdChars();
    }

    // ==================== 幕级 L2 返工门（story-graph-authoring tasks 2.6） ====================

    /**
     * 幕级返工（spec「单幕返工不波及他幕」）：目标幕候选复活（REJECTED 救回 /
     * PENDING 幂等）→ 返工轮记账 → 委托幕级生成编排单幕重生成（仅目标幕过
     * LLM，非目标幕跳过且候选状态不动——spec「幕 6 返工幕 1-5 确认状态不变」；
     * 收口不重置候选，目标幕本轮已在门层置 PENDING）。
     * <p>
     * CONFIRMED 拒绝（须先 {@link #unlockAct} 回退解锁）。幕号从候选 ID 尾部
     * 数字解析（candidate-03 → 第 3 幕）。反馈审查（D11）在 controller 前置。
     *
     * @return 生成 SSE 流（stage_start → act_start/content*（目标幕）→
     *         act_done（非目标幕 resumed=true）→ draft_ready/error → [DONE]）
     */
    public Flux<ServerSentEvent<Object>> reworkAct(String runId, String candidateId, String feedback) {
        PipelineManifest manifest = requireGateManifest(runId, PipelineStage.S4P5_STORY_GRAPH);
        PipelineManifest.CandidateState candidate = manifest
                .stage(PipelineStage.S4P5_STORY_GRAPH.code()).candidate(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("幕候选不存在或已过期: " + candidateId);
        }
        if (DraftItemStatus.CONFIRMED.name().equals(candidate.getStatus())) {
            throw new IllegalStateException("已确认幕须先回退解锁（unlockAct）后返工");
        }
        runService.reviveCandidate(runId, PipelineStage.S4P5_STORY_GRAPH, candidateId);
        int actNumber = actNumberOf(candidateId);
        int round = runService.recordReworkRound(runId, PipelineStage.S4P5_STORY_GRAPH, feedback);
        log.info("幕级返工记账: runId={}, candidate={}, act={}, round={}",
                runId, candidateId, actNumber, round);
        return storyGraphGeneration.generateActs(runId, feedback, actNumber);
    }

    /**
     * 幕级回退解锁（spec「已确认幕显式回退解锁」）：CONFIRMED 幕候选复位
     * PENDING（可编辑/返工；其他幕状态不变）+ 删除拼接图 stage artifact
     * （下游衔接跨幕边标记待重拼——readStageArtifact 为 null 即待重拼语义，
     * 重新整稿通过时确定性重拼）。阶段须为 AWAITING_CONFIRM（整稿锁定后
     * 须先走阶段级 {@link #unlockStage}）。
     *
     * @return {@code {stage, candidateId, status, graphInvalidated}}
     */
    public Map<String, Object> unlockAct(String runId, String candidateId) {
        PipelineManifest manifest = requireGateManifest(runId, PipelineStage.S4P5_STORY_GRAPH);
        PipelineManifest.CandidateState candidate = manifest
                .stage(PipelineStage.S4P5_STORY_GRAPH.code()).candidate(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("幕候选不存在或已过期: " + candidateId);
        }
        runService.demoteCandidate(runId, PipelineStage.S4P5_STORY_GRAPH, candidateId);
        // 拼接图删除 = 下游衔接跨幕边待重拼（任一幕变更后图即过期，重确认时重拼）
        workspaceStore.deleteStageArtifact(runId, PipelineStage.S4P5_STORY_GRAPH);
        log.info("幕级回退解锁（拼接图失效待重拼）: runId={}, candidate={}", runId, candidateId);
        return Map.of(
                "stage", PipelineStage.S4P5_STORY_GRAPH.code(),
                "candidateId", candidateId,
                "status", DraftItemStatus.PENDING.name(),
                "graphInvalidated", true);
    }

    /** 幕号解析：候选 ID 尾部数字（candidate-03 → 3；不可解析拒绝）。 */
    private static int actNumberOf(String candidateId) {
        try {
            return Integer.parseInt(candidateId.substring(candidateId.lastIndexOf('-') + 1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("幕候选 ID 无法解析幕号: " + candidateId);
        }
    }

    // ==================== 回退解锁（tasks 4.5） ====================

    /**
     * 回退解锁（spec「确认稿幂等锁定」）：CONFIRMED → AWAITING_CONFIRM、
     * currentStage 回退到本阶段（确认时曾推进）；confirmedVersion 保留
     * （原确认版本存档——vN 快照已落盘，版本历史可回看，历史确认记录
     * 不可删除）。S1-S5 通用（spec 只约束锁定语义，不区分门级）。
     * <p>
     * 解锁本身不递增版本号：spec「生成新一轮（版本号递增）」由解锁后的
     * 下一次编辑保存（recordDraftEdit）或返工生成（markStageCompleted）
     * 既有单点承担，避免无编辑内容的空版本。解锁须逐级——跨级拒绝
     * （防「上游解锁但下游 CONFIRMED」的脏 manifest 卡死下游）。下游
     * 生成中的 run 状态防御在 {@link PipelineRunService#unlockStage}。
     *
     * @return {@code {stage, status, archivedVersion, currentStage}}
     */
    public Map<String, Object> unlockStage(String runId, PipelineStage stage) {
        requireUnlockableStage(stage);
        PipelineManifest manifest = requireUnlockPosition(runId, stage);
        Integer archivedVersion = manifest.stage(stage.code()).getConfirmedVersion();
        runService.unlockStage(runId, stage);
        return Map.of(
                "stage", stage.code(),
                "status", PipelineStageStatus.AWAITING_CONFIRM.name(),
                "archivedVersion", archivedVersion,
                "currentStage", stage.code());
    }

    // ==================== 守卫 ====================

    /** L2 门专用守卫：S3/S5 有候选级操作；L1/S6 无（spec「gate 分级」）。 */
    private static void requireL2Gate(PipelineStage stage) {
        if (stage.gate() != PipelineStage.GateLevel.L2) {
            throw new IllegalStateException(stage.code() + " 为 L1 编辑门，无候选级操作");
        }
    }

    /** L1 可编辑面：S1/S2/S4；S6 只读预览（spec「S6 只读预览」）、S3/S5 走候选级逐卡操作（tasks 4.3）。 */
    private static void requireL1Editable(PipelineStage stage) {
        if (stage == PipelineStage.S6_INSTALL) {
            throw new IllegalStateException("S6 成稿阶段为只读预览，修改须回退解锁上游阶段");
        }
        if (stage.gate() == PipelineStage.GateLevel.L2) {
            throw new IllegalStateException(stage.code() + " 为 L2 候选级门，草稿编辑走逐卡操作");
        }
    }

    /** L2 返工门守卫（spec「gate 分级」）：返工窗只在 S3/S5；L1 草稿修改走编辑保存、S6 只读预览。 */
    private static void requireReworkGate(PipelineStage stage) {
        if (stage == PipelineStage.S6_INSTALL) {
            throw new IllegalStateException("S6 成稿阶段为只读预览，修改须回退解锁上游阶段");
        }
        if (stage.gate() != PipelineStage.GateLevel.L2) {
            throw new IllegalStateException(stage.code() + " 为 L1 编辑门，无返工动作（草稿修改走编辑保存）");
        }
    }

    /** 回退解锁阶段守卫：S6 终态只读（next() 为 null，须先于紧邻检查拦截）。 */
    private static void requireUnlockableStage(PipelineStage stage) {
        if (stage == PipelineStage.S6_INSTALL) {
            throw new IllegalStateException("终态阶段不可回退解锁: " + stage.code());
        }
    }

    /**
     * 回退解锁前置：run 存在（404）、manifest 可读（409）、阶段为 CONFIRMED
     * （409，未锁定无从解锁）且 currentStage 即 stage.next()（409，逐级解锁——
     * 跨级会造成上游解锁但下游 CONFIRMED 的脏 manifest，提示先解锁下游）。
     */
    private PipelineManifest requireUnlockPosition(String runId, PipelineStage stage) {
        runRepository.findById(runId).orElseThrow(
                () -> new NoSuchElementException("管线 run 不存在: " + runId));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }
        PipelineStageStatus status = PipelineStageStatus.valueOf(
                manifest.stage(stage.code()).getStatus());
        if (status != PipelineStageStatus.CONFIRMED) {
            throw new IllegalStateException("仅锁定阶段可回退解锁，当前状态: " + status);
        }
        String expected = stage.next().code();
        if (!expected.equals(manifest.getCurrentStage())) {
            throw new IllegalStateException("跨级解锁须先回退解锁下游阶段（当前阶段: "
                    + manifest.getCurrentStage() + "，本阶段解锁前置: " + expected + "）");
        }
        return manifest;
    }

    /** L2 整稿通过前置（spec「整稿通过前置校验」）：存在 pending 候选即拒绝。 */
    private static void requireNoPendingCandidates(PipelineManifest manifest, PipelineStage stage) {
        List<PipelineManifest.CandidateState> candidates = manifest.stage(stage.code()).getCandidates();
        long pending = candidates.stream()
                .filter(c -> DraftItemStatus.PENDING.name().equals(c.getStatus()))
                .count();
        if (pending > 0) {
            throw new IllegalStateException("存在待定候选（须逐个确认或毙掉）: " + pending + " 个");
        }
    }

    /**
     * 门操作前置：run 存在（404）、manifest 可读（409）、阶段为待确认态（409，
     * 锁定态提示须先回退解锁——先于当前阶段检查，spec「锁定后不可静默改」的
     * 提示语义）、目标即当前阶段（409，防漂移越权：AWAITING_CONFIRM 与
     * currentStage 的不变式由状态机各转换维护，此处防御性复核脏 manifest）。
     */
    private PipelineManifest requireGateManifest(String runId, PipelineStage stage) {
        runRepository.findById(runId).orElseThrow(
                () -> new NoSuchElementException("管线 run 不存在: " + runId));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }
        PipelineStageStatus status = PipelineStageStatus.valueOf(
                manifest.stage(stage.code()).getStatus());
        if (status != PipelineStageStatus.AWAITING_CONFIRM) {
            throw new IllegalStateException("阶段当前状态不可操作（锁定态须先回退解锁）: " + status);
        }
        if (!stage.code().equals(manifest.getCurrentStage())) {
            throw new IllegalStateException("非当前阶段不可操作: " + stage.code()
                    + "（当前阶段: " + manifest.getCurrentStage() + "）");
        }
        return manifest;
    }
}
