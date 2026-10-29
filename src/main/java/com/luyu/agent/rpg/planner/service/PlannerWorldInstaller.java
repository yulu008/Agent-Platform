package com.luyu.agent.rpg.planner.service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.planner.engine.InstallParser;
import com.luyu.agent.rpg.planner.engine.InstallParser.MaterializedWorld;
import com.luyu.agent.rpg.planner.model.DraftItemStatus;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineRunStatus;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.story.engine.StoryGraphParseResult;
import com.luyu.agent.rpg.story.engine.StoryGraphParser;
import com.luyu.agent.rpg.story.engine.StoryGraphValidator;
import com.luyu.agent.rpg.story.engine.TopologyResult;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;

/**
 * 成稿安装服务（add-planner-pipeline tasks 5.1 / design D10）。
 * <p>
 * 工作区确认稿 → world 模板层物化：S6 安装端点在 S1-S5 全 CONFIRMED 后执行，
 * 读取各阶段当前版（CONFIRMED 锁定态不可静默改，当前版即确认版）解析为
 * {@link MaterializedWorld} 载荷（{@link InstallParser} 纯函数，物化映射见其
 * 类 javadoc），在单事务内全部落库，成功后经
 * {@link PipelineRunService#markInstallCompleted} 收口 run 状态。
 * <p>
 * <b>每次安装产出独立世界</b>（spec planner-world-install）：每次生成新
 * world_id（UUID，非预设确定性键），不覆盖已有世界；重复安装（run 已
 * COMPLETED）允许且产出新世界。安装原子性（spec「安装原子性」）：全部物化
 * 写入包在一个 {@link TransactionTemplate} 事务内，任一写入失败整体回滚零
 * 残留；run 收口在事务成功后调用——失败时 run 停在 AWAITING 可重试（重试
 * 产出新 world_id，上次半途而废的行已随回滚消失）。
 * <p>
 * 物化写路径复用既有惯例：世界观 INSERT（{@code RpgWorldSettingRepository}，
 * 13 列含 narrative_rules）、人物卡逐行 INSERT（{@code RpgCharacterCardRepository}）、
 * 命定档案全量重写（{@code RpgDestinedFigureRepository.rewriteAll}，安装
 * world 为全新 id 故 delete 为 no-op，纯插入）、世界书条目逐行 INSERT
 * （{@code RpgWorldbookEntryRepository}，模板层 set_id/source_ref 恒 null）。
 * 覆盖列（cover/theme_tokens/custom_blocks）不落值——保持 NULL 走前端渐变
 * 兜底（world-card-gallery）与默认 HUD 主题，安装世界无预设封面资产。
 * <p>
 * 故事图物化（story-graph-authoring tasks 5.2）：S4.5 整稿通过时拼接落盘的
 * 全图 JSON（stage artifact，结构真源）→ 宽进严出解析 → 拓扑校验确定性修复
 * （与工坊 finalize 同一验证器）→ 落 {@code rpg_story_graph} 模板层行
 * （与工坊手工建图同权，bounded 缺省即兴模式）。产物缺失/解析失败/结构
 * 校验未过 → 409 拒绝安装零写入（宁可不装半成品，回 S4.5 修正后重试）。
 * <p>
 * 异常约定（对齐 {@code PipelineRunService}，controller 统一映射）：
 * {@link NoSuchElementException} → 404（run 不存在）、
 * {@link IllegalStateException} → 409（守卫拒绝/工作区损坏/产物不完整）。
 */
@Service
public class PlannerWorldInstaller {

    private static final Logger log = LoggerFactory.getLogger(PlannerWorldInstaller.class);

    private final PipelineRunRepository runRepository;
    private final PlannerWorkspaceStore workspaceStore;
    private final PipelineRunService runService;
    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository characterRepo;
    private final RpgDestinedFigureRepository destinedFigureRepo;
    private final RpgWorldbookEntryRepository worldbookRepo;
    private final RpgStoryGraphRepository graphRepo;
    private final StoryGraphParser graphParser;
    private final StoryGraphValidator graphValidator;
    private final TransactionTemplate txTemplate;

    /** 序列化惯例同工坊链（无全局 bean，内部静态实例） */
    private static final ObjectMapper mapper = new ObjectMapper();

    public PlannerWorldInstaller(PipelineRunRepository runRepository,
                                 PlannerWorkspaceStore workspaceStore,
                                 PipelineRunService runService,
                                 RpgWorldSettingRepository worldRepo,
                                 RpgCharacterCardRepository characterRepo,
                                 RpgDestinedFigureRepository destinedFigureRepo,
                                 RpgWorldbookEntryRepository worldbookRepo,
                                 RpgStoryGraphRepository graphRepo,
                                 StoryGraphParser graphParser,
                                 StoryGraphValidator graphValidator,
                                 TransactionTemplate txTemplate) {
        this.runRepository = runRepository;
        this.workspaceStore = workspaceStore;
        this.runService = runService;
        this.worldRepo = worldRepo;
        this.characterRepo = characterRepo;
        this.destinedFigureRepo = destinedFigureRepo;
        this.worldbookRepo = worldbookRepo;
        this.graphRepo = graphRepo;
        this.graphParser = graphParser;
        this.graphValidator = graphValidator;
        this.txTemplate = txTemplate;
    }

    /** 安装结果（响应体载荷：新世界定位与规模统计 + 物化故事图定位）。 */
    public record InstallResult(String worldId, String worldName, int entryCount,
                                 String storyGraphId, int storyGraphNodeCount) {
    }

    /**
     * 执行安装：守卫 → 收集确认稿 → 解析载荷 → 事务物化 → run 收口。
     *
     * @throws NoSuchElementException run 不存在或跨租户（404）
     * @throws IllegalStateException   守卫拒绝（阶段未全确认/终态不可装/产物不完整，
     *                                 409）或工作区 manifest 缺失（409）
     */
    public InstallResult install(String runId) {
        PipelineManifest manifest = requireInstallable(runId);

        String worldId = UUID.randomUUID().toString();
        MaterializedWorld payload = InstallParser.parse(
                workspaceStore.readStageArtifact(runId, PipelineStage.S2_WORLD_SETTING),
                workspaceStore.readStageArtifact(runId, PipelineStage.S4_PLOT_STRUCTURE),
                collectConfirmedCandidates(runId, manifest, PipelineStage.S3_CHARACTERS),
                collectConfirmedCandidates(runId, manifest, PipelineStage.S5_BRANCH_DESIGN),
                worldId);
        requireMaterializable(payload);
        // S4.5 拼接产物 → 模板层故事图（与工坊手工建图同权；解析/修复在事务外，
        // 失败零写入可回 S4.5 修正后重试）
        StoryGraphPayload storyGraph = materializeStoryGraph(runId, worldId,
                payload.world().getName());

        txTemplate.executeWithoutResult(tx -> writeAll(payload, storyGraph.graph()));
        runService.markInstallCompleted(runId);

        log.info("策划管线安装完成: runId={}, worldId={}, name={}, entries={}, storyGraph={}, nodes={}",
                runId, worldId, payload.world().getName(), payload.entryCount(),
                storyGraph.graph().getId(), storyGraph.nodeCount());
        return new InstallResult(worldId, payload.world().getName(), payload.entryCount(),
                storyGraph.graph().getId(), storyGraph.nodeCount());
    }

    // ==================== 守卫 ====================

    /**
     * 安装前置守卫链：run 存在 → manifest 可读 → S1-S5 全 CONFIRMED →
     * run 状态可装（AWAITING = S5 确认后的正常等待态；COMPLETED = 重复
     * 安装产出独立世界，spec 允许）。
     */
    private PipelineManifest requireInstallable(String runId) {
        Optional<PipelineRun> run = runRepository.findById(runId);
        if (run.isEmpty()) {
            throw new NoSuchElementException("管线 run 不存在: " + runId);
        }
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }
        for (PipelineStage stage : PipelineStage.values()) {
            if (stage == PipelineStage.S6_INSTALL) {
                continue;
            }
            if (!PipelineStageStatus.CONFIRMED.name()
                    .equals(manifest.stage(stage.code()).getStatus())) {
                throw new IllegalStateException("前置阶段未全部确认，不可安装: "
                        + stage.code() + " 处于 "
                        + manifest.stage(stage.code()).getStatus());
            }
        }
        PipelineRunStatus status = PipelineRunStatus.valueOf(manifest.getStatus());
        if (status != PipelineRunStatus.AWAITING && status != PipelineRunStatus.COMPLETED) {
            throw new IllegalStateException("run 当前状态不可安装: " + status);
        }
        return manifest;
    }

    /**
     * 载荷完整性守卫：世界名缺失（02-world.md 名称小节漂移/缺失）拒绝安装——
     * name 是 NOT NULL 列，落库即半成品行，宁可整体拒绝让用户回 S2 修正。
     */
    private static void requireMaterializable(MaterializedWorld payload) {
        if (payload.world().getName() == null || payload.world().getName().isBlank()) {
            throw new IllegalStateException(
                    "世界观确认稿缺少名称（02-world.md「## 名称」小节），不可安装");
        }
    }

    /**
     * 收集 L2 阶段非毙候选块（S3 人物 / S5 分支）：按 manifest 候选状态行过滤
     * REJECTED（readStageFullText 同口径），逐块读取保持确认序；文件缺失的
     * 候选跳过（manifest 与文件树同写不变式下的防御）。
     */
    private List<String> collectConfirmedCandidates(String runId, PipelineManifest manifest,
                                                    PipelineStage stage) {
        List<PipelineManifest.CandidateState> states =
                manifest.stage(stage.code()).getCandidates();
        List<String> blocks = new ArrayList<>(states.size());
        for (PipelineManifest.CandidateState candidate : states) {
            if (DraftItemStatus.REJECTED.name().equals(candidate.getStatus())) {
                continue;
            }
            String content = workspaceStore.readCandidateItem(runId, stage, candidate.getId());
            if (content != null) {
                blocks.add(content);
            }
        }
        return blocks;
    }

    // ==================== 故事图物化（story-graph-authoring tasks 5.2） ====================

    /** 物化产物：模板层图实体 + 节点规模（InstallResult 载荷）。 */
    private record StoryGraphPayload(StoryGraph graph, int nodeCount) {
    }

    /**
     * S4.5 拼接产物 → 模板层故事图：读 stage artifact（确认稿幂等锁定，
     * 当前版即拼接全图）→ {@link StoryGraphParser} 宽进严出解析 →
     * {@link StoryGraphValidator#validateGraph} 确定性修复（与工坊 finalize
     * 同链：断边改指锚点/孤儿剔除就地 mutate）→ bounded 缺省实体。
     * 图名 = 世界名 + 「·主线」（工坊下拉可辨）。errors 非空拒绝安装
     * （结构不可用，回 S4.5 返工修正）。
     *
     * @throws IllegalStateException 产物缺失/解析失败/结构校验未过（409，零写入）
     */
    private StoryGraphPayload materializeStoryGraph(String runId, String worldId,
                                                     String worldName) {
        String stitched = workspaceStore.readStageArtifact(
                runId, PipelineStage.S4P5_STORY_GRAPH);
        if (stitched == null || stitched.isBlank()) {
            throw new IllegalStateException(
                    "S4.5 故事图拼接产物缺失（工作区可能损坏），不可安装");
        }
        StoryGraphParseResult parsed = graphParser.parse(stitched);
        if (!parsed.ok() || parsed.getActs().isEmpty()) {
            throw new IllegalStateException("S4.5 故事图拼接产物解析失败（回 S4.5 返工后重试）: "
                    + String.join("；", parsed.getErrors()));
        }
        List<StoryAct> acts = parsed.getActs();
        TopologyResult topology = graphValidator.validateGraph(acts);
        if (!topology.getErrors().isEmpty()) {
            throw new IllegalStateException("S4.5 故事图结构校验未通过（回 S4.5 返工后重试）: "
                    + String.join("；", topology.getErrors()));
        }
        for (String warning : topology.getWarnings()) {
            log.warn("安装物化故事图拓扑告警: runId={}, {}", runId, warning);
        }
        int nodeCount = acts.stream()
                .mapToInt(a -> a.getNodes() == null ? 0 : a.getNodes().size())
                .sum();
        StoryGraph graph = new StoryGraph(UUID.randomUUID().toString(),
                null, worldId, worldName + "·主线");
        graph.setImproviseMode(StoryGraph.IMPROVISE_BOUNDED);
        graph.setStateSchema(parsed.getStateSchema());
        try {
            graph.setGraphJson(mapper.writeValueAsString(acts));
        } catch (Exception e) {
            throw new IllegalStateException("故事图序列化失败：" + e.getMessage(), e);
        }
        return new StoryGraphPayload(graph, nodeCount);
    }

    // ==================== 物化写入（事务内） ====================

    /**
     * 全部实体落库（调用方保证已包在事务内）：世界行 → 人物卡 → 命定档案 →
     * 世界书条目（S2 通用轨 + S5 分支）→ 故事图（S4.5 拼接产物修复后模板行）。
     * 任一写入抛出即整体回滚（安装原子性；租户列由 repo 层注入）。
     */
    private void writeAll(MaterializedWorld payload, StoryGraph storyGraph) {
        worldRepo.save(payload.world());
        for (CharacterCard card : payload.characters()) {
            characterRepo.save(card);
        }
        destinedFigureRepo.rewriteAll(payload.world().getId(), payload.figures());
        for (WorldbookEntry entry : payload.worldbookEntries()) {
            worldbookRepo.save(entry);
        }
        for (WorldbookEntry entry : payload.branchEntries()) {
            worldbookRepo.save(entry);
        }
        graphRepo.save(storyGraph);
    }
}
