package com.luyu.agent.rpg.planner.service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.codec.ServerSentEvent;

import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.engine.PlannerGenerationGuard;
import com.luyu.agent.rpg.planner.engine.S1ChainedRefineService;
import com.luyu.agent.rpg.planner.engine.S3BatchGenerationService;
import com.luyu.agent.rpg.planner.engine.StageGenerationService;
import com.luyu.agent.rpg.planner.engine.StoryGraphGenerationService;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.story.engine.StoryGraphParser;
import com.luyu.agent.rpg.story.engine.StoryGraphStitcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

import reactor.core.publisher.Flux;

/**
 * {@link StageGateService} 门操作编排测试（tasks 4.2-4.5，spec
 * planner-confirm-gates「L1 编辑门草稿编辑」/「确认稿幂等锁定」/「返工动作」）。
 * <p>
 * repository mock + 真实工作区（@TempDir）+ 真实 {@link PipelineRunService}
 * ——守卫链（404 / 锁定态 / 非当前阶段 / S6 只读 / L2 逐卡）、编辑保存
 * 产物与版本快照落盘、整稿通过 pending 前置与锁定推进、回退解锁存档
 * 保留与逐级约束均为全链路真实断言。S4.5 整稿通过拼接（story-graph-
 * authoring 2.5）与幕级 L2 返工门（2.6：reworkStage S4.5 路由 /
 * reworkAct 单幕返工 / unlockAct 回退解锁）用真实 parser/stitcher
 * （真链路断言：候选解析→拼接→全图 JSON 落盘）。
 */
class StageGateServiceTest {

    @TempDir
    Path tempDir;

    private PipelineRunRepository runRepository;
    private PlannerWorkspaceStore workspaceStore;
    private StageGenerationService generationService;
    private StoryGraphGenerationService storyGraphGeneration;
    /** S1 链式汇总返工路由目标（planner-s1-chained-refinement tasks 4.1） */
    private S1ChainedRefineService s1ChainedRefine;
    /** S3 补批/全量门分派目标（planner-s3-batch-generate tasks 3.1） */
    private S3BatchGenerationService s3BatchGeneration;
    private StageGateService service;

    /** 单幕候选夹具（与 S4.5 生成链落盘形态一致：<story_graph> 块单幕 JSON） */
    private static final String STITCH_ACT1 = """
            <story_graph>
            {
              "id": "act1", "theme": "潜入与结识",
              "state_schema": {"trust_信长": {"type": "number"}},
              "nodes": [
                {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                {"id": "n1_05", "type": "anchor", "beat": "拿到密信"}
              ],
              "edges": [{"id": "e1_01", "from": "n1_01", "to": "n1_05", "label": "潜入"}]
            }
            </story_graph>
            """;

    private static final String STITCH_ACT2 = """
            <story_graph>
            {
              "id": "act2", "theme": "分裂与択択",
              "nodes": [
                {"id": "n2_01", "type": "scene", "beat": "清洲会议"},
                {"id": "n2_04", "type": "anchor", "beat": "摊牌"}
              ],
              "edges": [{"id": "e2_01", "from": "n2_01", "to": "n2_04", "label": "试探"}]
            }
            </story_graph>
            """;

    @BeforeEach
    void setUp() {
        runRepository = mock(PipelineRunRepository.class);
        workspaceStore = new PlannerWorkspaceStore(() -> tempDir);
        generationService = mock(StageGenerationService.class);
        storyGraphGeneration = mock(StoryGraphGenerationService.class);
        s1ChainedRefine = mock(S1ChainedRefineService.class);
        s3BatchGeneration = mock(S3BatchGenerationService.class);
        service = new StageGateService(
                new PipelineRunService(runRepository, workspaceStore,
                        new RpgPlannerProperties(), new PlannerGenerationGuard()), workspaceStore,
                runRepository, generationService, storyGraphGeneration,
                s1ChainedRefine, s3BatchGeneration, new RpgPlannerProperties(),
                new StoryGraphParser(), new StoryGraphStitcher());
    }

    /** 真实创建 run（工作区 manifest + source 落盘），返回 runId。 */
    private String newRun() {
        PipelineRun run = new PipelineRunService(runRepository, workspaceStore,
                new RpgPlannerProperties(), new PlannerGenerationGuard())
                .createRun("text", "faithful", "原始输入", null, null);
        return run.getId();
    }

    /** S1 链式 run（text 原文超默认链式阈值 1 万字，链式判定与生成分叉同判）。 */
    private String newChainedRun() {
        PipelineRun run = new PipelineRunService(runRepository, workspaceStore,
                new RpgPlannerProperties(), new PlannerGenerationGuard())
                .createRun("text", "faithful", "字".repeat(10_001), null, null);
        return run.getId();
    }

    /** 把 run 摆到指定阶段待确认门（manifest 状态 + 库表行 stub）。 */
    private void stageAwaiting(String runId, String stageCode, int version) {
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.setStatus("AWAITING");
        manifest.setCurrentStage(stageCode);
        manifest.stage(stageCode).setStatus("AWAITING_CONFIRM");
        manifest.stage(stageCode).setVersion(version);
        workspaceStore.writeManifest(runId, manifest);
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(dbRun(runId, stageCode, "AWAITING")));
    }

    private static PipelineRun dbRun(String id, String currentStage, String status) {
        PipelineRun run = new PipelineRun();
        run.setId(id);
        run.setName("库表行");
        run.setSourceMode("text");
        run.setStyleMode("faithful");
        run.setCurrentStage(currentStage);
        run.setStatus(status);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        return run;
    }

    // ===== L1 草稿编辑保存（tasks 4.2） =====

    @Test
    void L1编辑保存覆盖产物并递增版本() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);
        workspaceStore.writeStageArtifact(runId, PipelineStage.S2_WORLD_SETTING, "S2 首稿");

        Map<String, Object> result = service.saveL1Draft(
                runId, PipelineStage.S2_WORLD_SETTING, "编辑后内容");

        assertEquals("S2", result.get("stage"));
        assertEquals(2, result.get("version"));
        // spec「编辑后的版本覆盖工作区文件」
        assertEquals("编辑后内容",
                workspaceStore.readStageArtifact(runId, PipelineStage.S2_WORLD_SETTING));
        // 同版本号快照落盘（原稿 v1 经版本历史回看由生成时快照承担）
        assertEquals("编辑后内容",
                workspaceStore.readVersionSnapshot(runId, PipelineStage.S2_WORLD_SETTING, 2));
        // manifest 版本递增、阶段状态不变（仍待确认，可再编辑或整稿通过）
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals(2, manifest.stage("S2").getVersion());
        assertEquals("AWAITING_CONFIRM", manifest.stage("S2").getStatus());
        assertEquals("S2", manifest.getCurrentStage());
    }

    @Test
    void 锁定态编辑拒绝并提示回退解锁() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);
        workspaceStore.writeStageArtifact(runId, PipelineStage.S2_WORLD_SETTING, "原稿");
        // 整稿通过锁定后再收到编辑请求（spec「锁定后不可静默改」）
        service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.saveL1Draft(runId, PipelineStage.S2_WORLD_SETTING, "越权改稿"));
        assertThat(e.getMessage()).contains("回退解锁");
        // 零副作用：产物与版本不动
        assertEquals("原稿",
                workspaceStore.readStageArtifact(runId, PipelineStage.S2_WORLD_SETTING));
        assertEquals(1, workspaceStore.readManifest(runId).stage("S2").getVersion());
    }

    @Test
    void S6只读预览拒绝编辑() {
        String runId = newRun();
        stageAwaiting(runId, "S6", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.saveL1Draft(runId, PipelineStage.S6_INSTALL, "改稿"));
        assertThat(e.getMessage()).contains("只读");
    }

    @Test
    void L2阶段编辑拒绝走逐卡操作() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.saveL1Draft(runId, PipelineStage.S3_CHARACTERS, "整稿改写"));
        assertThat(e.getMessage()).contains("逐卡");
        // S3 产物是候选集目录，不允许全稿覆盖写
        assertThat(workspaceStore.readStageCandidates(runId, PipelineStage.S3_CHARACTERS)).isEmpty();
    }

    @Test
    void 漂移防御非当前阶段拒绝操作() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        // 脏 manifest：S2 手工置 AWAITING_CONFIRM 但 currentStage=S3
        // （正常状态机不可达，防御性复核钉住不越权）
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S2").setStatus("AWAITING_CONFIRM");
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.saveL1Draft(runId, PipelineStage.S2_WORLD_SETTING, "改稿"));
        assertThat(e.getMessage()).contains("非当前阶段");
    }

    @Test
    void run不存在编辑保存404() {
        when(runRepository.findById("run-x")).thenReturn(Optional.empty());

        NoSuchElementException e = assertThrows(NoSuchElementException.class,
                () -> service.saveL1Draft("run-x", PipelineStage.S2_WORLD_SETTING, "稿"));
        assertThat(e.getMessage()).contains("不存在");
    }

    // ===== 整稿通过（tasks 4.2，L2 pending 前置一并钉住） =====

    @Test
    void 整稿通过锁定阶段并推进当前阶段() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);

        Map<String, Object> result = service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING);

        assertEquals("S2", result.get("stage"));
        assertEquals(1, result.get("confirmedVersion"));
        assertEquals("S3", result.get("nextStage"));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals("CONFIRMED", manifest.stage("S2").getStatus());
        assertEquals(1, manifest.stage("S2").getConfirmedVersion());
        assertEquals("S3", manifest.getCurrentStage());
        // 库表同步（D3 后写）
        verify(runRepository).updateProgress(runId, "S3", "AWAITING");
    }

    @Test
    void 待定候选阻止整稿通过() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        // 3 候选：1 已确认、1 已毙、1 待定（spec「整稿通过前置校验」）
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S3").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "人物 1：甲", "CONFIRMED"),
                new PipelineManifest.CandidateState("candidate-02", "人物 2：乙", "REJECTED"),
                new PipelineManifest.CandidateState("candidate-03", "人物 3：丙", "PENDING")));
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmStage(runId, PipelineStage.S3_CHARACTERS));
        assertThat(e.getMessage()).contains("待定");
        // 零副作用：状态不动、库表不推进
        assertEquals("AWAITING_CONFIRM",
                workspaceStore.readManifest(runId).stage("S3").getStatus());
        verify(runRepository, never()).updateProgress(any(), any(), any());
    }

    @Test
    void 候选全部处置后整稿通过放行() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 2);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S3").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "人物 1：甲", "CONFIRMED"),
                new PipelineManifest.CandidateState("candidate-02", "人物 2：乙", "REJECTED")));
        workspaceStore.writeManifest(runId, manifest);

        Map<String, Object> result = service.confirmStage(runId, PipelineStage.S3_CHARACTERS);

        assertEquals("S4", result.get("nextStage"));
        assertEquals("CONFIRMED",
                workspaceStore.readManifest(runId).stage("S3").getStatus());
        assertEquals(2, workspaceStore.readManifest(runId).stage("S3").getConfirmedVersion());
    }

    @Test
    void S6整稿通过走安装端点语义拒绝() {
        String runId = newRun();
        stageAwaiting(runId, "S6", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmStage(runId, PipelineStage.S6_INSTALL));
        assertThat(e.getMessage()).contains("安装");
        verify(runRepository, never()).updateProgress(any(), any(), any());
    }

    // ===== S4.5 整稿通过跨幕拼接（story-graph-authoring tasks 2.5） =====

    /** 摆 S4.5 待确认门 + 两幕候选（状态可配）+ 候选文件落盘。 */
    private void stageWithGraphCandidates(String runId, String act1Status, String act2Status) {
        stageAwaiting(runId, "S4.5", 1);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S4.5").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "第 1 幕「潜入与结识」", act1Status),
                new PipelineManifest.CandidateState("candidate-02", "第 2 幕「分裂与択択」", act2Status)));
        workspaceStore.writeManifest(runId, manifest);
        workspaceStore.writeCandidateItem(runId, PipelineStage.S4P5_STORY_GRAPH, "candidate-01", STITCH_ACT1);
        workspaceStore.writeCandidateItem(runId, PipelineStage.S4P5_STORY_GRAPH, "candidate-02", STITCH_ACT2);
    }

    @Test
    void S4_5整稿通过执行跨幕拼接并落盘全图() {
        String runId = newRun();
        stageWithGraphCandidates(runId, "CONFIRMED", "CONFIRMED");

        Map<String, Object> result = service.confirmStage(runId, PipelineStage.S4P5_STORY_GRAPH);

        assertEquals("S4.5", result.get("stage"));
        assertEquals("S5", result.get("nextStage"));
        assertEquals("CONFIRMED", workspaceStore.readManifest(runId).stage("S4.5").getStatus());
        // 拼接全图落盘 stage artifact：块协议包裹 + 两幕 + 引擎拼接边 + 词汇表
        String graph = workspaceStore.readStageArtifact(runId, PipelineStage.S4P5_STORY_GRAPH);
        assertThat(graph).contains("<story_graph>");
        assertThat(graph).contains("\"acts\"");
        assertThat(graph).contains("\"id\" : \"act1\"").contains("\"id\" : \"act2\"");
        // 出口锚 n1_05 → 下幕入口 n2_01 的引擎拼接边（确定性 ID）
        assertThat(graph).contains("\"id\" : \"x1_01\"").contains("\"from\" : \"n1_05\"")
                .contains("\"to\" : \"n2_01\"");
        // 第 1 幕产出的词汇表随全图携带
        assertThat(graph).contains("state_schema").contains("trust_信长");
        // 幕内模型边保留
        assertThat(graph).contains("\"id\" : \"e1_01\"");
    }

    @Test
    void 坏幕候选拒绝整稿通过零副作用() {
        String runId = newRun();
        stageWithGraphCandidates(runId, "CONFIRMED", "CONFIRMED");
        // 幕 1 候选被手工改坏（后续编辑端点接线后的防御场景）
        workspaceStore.writeCandidateItem(runId, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-01", "<story_graph>{坏 JSON");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.confirmStage(runId, PipelineStage.S4P5_STORY_GRAPH));
        assertThat(e.getMessage()).contains("解析失败").contains("candidate-01");
        // 零副作用：阶段仍待确认、无拼接产物落盘、库表不推进
        assertEquals("AWAITING_CONFIRM",
                workspaceStore.readManifest(runId).stage("S4.5").getStatus());
        assertThat(workspaceStore.readStageArtifact(runId, PipelineStage.S4P5_STORY_GRAPH)).isNull();
        verify(runRepository, never()).updateProgress(any(), any(), any());
    }

    @Test
    void 全毙幕候选拒绝整稿通过() {
        String runId = newRun();
        stageWithGraphCandidates(runId, "REJECTED", "REJECTED");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmStage(runId, PipelineStage.S4P5_STORY_GRAPH));
        assertThat(e.getMessage()).contains("无可用幕候选").contains("返工重生成");
        assertEquals("AWAITING_CONFIRM",
                workspaceStore.readManifest(runId).stage("S4.5").getStatus());
        verify(runRepository, never()).updateProgress(any(), any(), any());
    }

    @Test
    void 被毙幕候选不进拼接() {
        String runId = newRun();
        // 幕 1 被毙：拼接只含幕 2（单幕无跨幕对，无拼接边）
        stageWithGraphCandidates(runId, "REJECTED", "CONFIRMED");

        service.confirmStage(runId, PipelineStage.S4P5_STORY_GRAPH);

        String graph = workspaceStore.readStageArtifact(runId, PipelineStage.S4P5_STORY_GRAPH);
        // D7 口径：被毙候选不进确认稿聚合（幕 1 节点/边不出现）
        assertThat(graph).doesNotContain("act1").doesNotContain("n1_");
        assertThat(graph).contains("\"id\" : \"act2\"");
        // 单幕无跨幕对：无引擎拼接边
        assertThat(graph).doesNotContain("x1_");
    }

    @Test
    void 非待确认态整稿通过拒绝() {
        String runId = newRun();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(dbRun(runId, "S2", "RUNNING")));
        // currentStage=S2 但 S2 尚在生成中（RUNNING 态无门可过）
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.setStatus("RUNNING");
        manifest.setCurrentStage("S2");
        manifest.stage("S2").setStatus("RUNNING");
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("不可操作");
    }

    // ===== L2 门逐卡操作（tasks 4.3） =====

    /**
     * S3 待确认门 + 候选集初始化（对齐真实链路：生成完成回调
     * initStageCandidates 全 PENDING + 候选文件落盘；title 取首行去 ## 前缀）。
     */
    private void stageWithCandidates(String runId, int version, String... cards) {
        stageAwaiting(runId, "S3", version);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        List<PipelineManifest.CandidateState> fresh = new ArrayList<>(cards.length);
        for (int i = 0; i < cards.length; i++) {
            String title = cards[i].lines().findFirst().orElse("")
                    .replaceFirst("^##\\s*", "");
            fresh.add(new PipelineManifest.CandidateState(
                    String.format("candidate-%02d", i + 1), title, "PENDING"));
        }
        manifest.stage("S3").setCandidates(fresh);
        workspaceStore.writeManifest(runId, manifest);
        workspaceStore.writeStageCandidates(runId, PipelineStage.S3_CHARACTERS, List.of(cards));
    }

    @Test
    void 逐卡毙掉置REJECTED且幂等不影响他卡() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲\n原稿", "## 人物 2：乙\n原稿");

        Map<String, Object> result = service.rejectCandidate(
                runId, PipelineStage.S3_CHARACTERS, "candidate-01");

        assertEquals("REJECTED", result.get("status"));
        List<PipelineManifest.CandidateState> candidates =
                workspaceStore.readManifest(runId).stage("S3").getCandidates();
        assertEquals("REJECTED", candidates.get(0).getStatus());
        // 候选独立状态：其余不受影响
        assertEquals("PENDING", candidates.get(1).getStatus());

        // 幂等：再点一次零异常、状态不变
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        assertEquals("REJECTED",
                workspaceStore.readManifest(runId).stage("S3").getCandidates().get(0).getStatus());
    }

    @Test
    void 逐卡确认置CONFIRMED且确认后可改毙() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲");

        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        assertEquals("CONFIRMED",
                workspaceStore.readManifest(runId).stage("S3").getCandidates().get(0).getStatus());

        // 确认后可改毙（毙掉优先于早前确认）
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        assertEquals("REJECTED",
                workspaceStore.readManifest(runId).stage("S3").getCandidates().get(0).getStatus());
    }

    @Test
    void 已毙候选不可确认救回走返工() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲");
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01"));
        assertThat(e.getMessage()).contains("不可确认");
        // 状态不动（REJECTED 终态）
        assertEquals("REJECTED",
                workspaceStore.readManifest(runId).stage("S3").getCandidates().get(0).getStatus());
    }

    @Test
    void 未知候选id拒绝400() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-99"));
        assertThat(e.getMessage()).contains("候选不存在");
    }

    @Test
    void L1阶段无候选级操作() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.rejectCandidate(runId, PipelineStage.S2_WORLD_SETTING, "candidate-01"));
        assertThat(e.getMessage()).contains("L1 编辑门");
    }

    // ===== 批量确认（planner-s3-batch-confirm，spec「L2 门批量确认」） =====

    @Test
    void 批量确认置齐pending且rejected不动() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲", "## 人物 2：乙", "## 人物 3：丙");
        // 摆混合状态：1 confirmed + 1 rejected + 1 pending
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-02");

        Map<String, Object> result = service.confirmAllCandidates(
                runId, PipelineStage.S3_CHARACTERS);

        assertEquals("S3", result.get("stage"));
        assertEquals(1, result.get("confirmedCount"));
        List<PipelineManifest.CandidateState> candidates =
                workspaceStore.readManifest(runId).stage("S3").getCandidates();
        assertEquals("CONFIRMED", candidates.get(0).getStatus());
        assertEquals("REJECTED", candidates.get(1).getStatus());
        assertEquals("CONFIRMED", candidates.get(2).getStatus());
        // 不锁定阶段（spec「批量确认 SHALL NOT 锁定阶段」）
        assertEquals("AWAITING_CONFIRM",
                workspaceStore.readManifest(runId).stage("S3").getStatus());
    }

    @Test
    void 批量确认无pending幂等返0() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲", "## 人物 2：乙");
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-02");

        Map<String, Object> result = service.confirmAllCandidates(
                runId, PipelineStage.S3_CHARACTERS);

        assertEquals(0, result.get("confirmedCount"));
        // 零变化：既有状态不动
        List<PipelineManifest.CandidateState> candidates =
                workspaceStore.readManifest(runId).stage("S3").getCandidates();
        assertEquals("CONFIRMED", candidates.get(0).getStatus());
        assertEquals("REJECTED", candidates.get(1).getStatus());
    }

    @Test
    void 批量确认L1阶段409() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmAllCandidates(runId, PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("L1 编辑门");
    }

    // ===== S3 确认并整稿通过（planner-s3-batch-confirm，spec「S3 确认并整稿通过」） =====

    @Test
    void 确认并整稿通过一键直达确认稿() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲", "## 人物 2：乙", "## 人物 3：丙");
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-02");
        // candidate-03 留 pending（组合动作应一并确认）

        Map<String, Object> result = service.confirmRemainingAndPass(
                runId, PipelineStage.S3_CHARACTERS);

        assertEquals("S3", result.get("stage"));
        assertEquals(1, result.get("confirmedCount"));
        assertEquals("S4", result.get("nextStage"));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals("CONFIRMED", manifest.stage("S3").getStatus());
        assertEquals("S4", manifest.getCurrentStage());
        assertEquals(1, manifest.stage("S3").getConfirmedVersion());
        List<PipelineManifest.CandidateState> candidates = manifest.stage("S3").getCandidates();
        assertEquals("CONFIRMED", candidates.get(0).getStatus());
        assertEquals("REJECTED", candidates.get(1).getStatus());
        assertEquals("CONFIRMED", candidates.get(2).getStatus());
    }

    @Test
    void 确认并整稿通过仅S3开放() {
        String runId = newRun();
        // 手工摆 S5 待确认门 + 1 张 pending 候选（stageWithCandidates 夹具硬编码 S3）
        stageAwaiting(runId, "S5", 1);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S5").setCandidates(new ArrayList<>(List.of(
                new PipelineManifest.CandidateState("candidate-01", "分支：暗杀线", "PENDING"))));
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmRemainingAndPass(runId, PipelineStage.S5_BRANCH_DESIGN));
        assertThat(e.getMessage()).contains("仅对 S3");
        // 零变化：阶段状态与候选状态均不动
        assertEquals("AWAITING_CONFIRM",
                workspaceStore.readManifest(runId).stage("S5").getStatus());
        assertEquals("PENDING",
                workspaceStore.readManifest(runId).stage("S5").getCandidates().get(0).getStatus());
    }

    @Test
    void 确认并整稿通过锁定态409零变化() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲");
        service.confirmRemainingAndPass(runId, PipelineStage.S3_CHARACTERS);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmRemainingAndPass(runId, PipelineStage.S3_CHARACTERS));
        assertThat(e.getMessage()).contains("回退解锁");
        // 零变化：仍锁定在 CONFIRMED、currentStage 未回漂
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals("CONFIRMED", manifest.stage("S3").getStatus());
        assertEquals("S4", manifest.getCurrentStage());
    }

    @Test
    void 逐卡编辑覆盖单卡并递增版本落语义快照() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲\n原稿", "## 人物 2：乙\n原稿");
        // 第二卡已毙：语义快照不应包含它
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-02");

        Map<String, Object> result = service.saveCandidateEdit(
                runId, PipelineStage.S3_CHARACTERS, "candidate-01", "## 人物 1：甲\n编辑后");

        assertEquals(2, result.get("version"));
        // 单卡覆盖写（另一卡不动）
        assertEquals("## 人物 1：甲\n编辑后",
                workspaceStore.readCandidateItem(runId, PipelineStage.S3_CHARACTERS, "candidate-01"));
        assertEquals("## 人物 2：乙\n原稿",
                workspaceStore.readCandidateItem(runId, PipelineStage.S3_CHARACTERS, "candidate-02"));
        // 语义快照：非毙候选聚合（被毙的 candidate-02 不进，口径同语义全稿）
        assertEquals("## 人物 1：甲\n编辑后",
                workspaceStore.readVersionSnapshot(runId, PipelineStage.S3_CHARACTERS, 2));
        // manifest 版本递增、候选状态不变
        assertEquals(2, workspaceStore.readManifest(runId).stage("S3").getVersion());
        assertEquals("PENDING",
                workspaceStore.readManifest(runId).stage("S3").getCandidates().get(0).getStatus());
    }

    @Test
    void 已毙候选不可编辑() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲\n原稿");
        service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.saveCandidateEdit(
                        runId, PipelineStage.S3_CHARACTERS, "candidate-01", "越权改卡"));
        assertThat(e.getMessage()).contains("不可编辑");
        // 零副作用：文件与版本不动
        assertEquals("## 人物 1：甲\n原稿",
                workspaceStore.readCandidateItem(runId, PipelineStage.S3_CHARACTERS, "candidate-01"));
        assertEquals(1, workspaceStore.readManifest(runId).stage("S3").getVersion());
    }

    @Test
    void 锁定态逐卡操作拒绝() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲", "## 人物 2：乙");
        // 全部处置后整稿通过锁定
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-02");
        service.confirmStage(runId, PipelineStage.S3_CHARACTERS);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.rejectCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01"));
        assertThat(e.getMessage()).contains("回退解锁");
    }

    // ===== L2 返工（tasks 4.4） =====

    @Test
    void 返工记账轮次并委托生成流() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        when(generationService.generateStage(runId, "S3"))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().data("[DONE]").build()));

        Flux<ServerSentEvent<Object>> flux = service.reworkStage(
                runId, PipelineStage.S3_CHARACTERS, "光秀要更悲情的野心家");

        // 反馈记账落 manifest（rounds 真源：反馈历史 + 版本树跨天回看）
        List<PipelineManifest.ReRound> rounds =
                workspaceStore.readManifest(runId).stage("S3").getRounds();
        assertEquals(1, rounds.size());
        assertEquals(1, rounds.get(0).getRound());
        assertEquals("光秀要更悲情的野心家", rounds.get(0).getFeedback());
        // 委托生成流（v N+1 与返工 prompt 注入由 StageGenerationService 承担）
        List<ServerSentEvent<Object>> events = flux.collectList().block();
        assertThat(events).isNotNull();
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        verify(generationService).generateStage(runId, "S3");
    }

    @Test
    void 二轮返工轮号递增() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        when(generationService.generateStage(anyString(), anyString()))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().data("[DONE]").build()));

        service.reworkStage(runId, PipelineStage.S3_CHARACTERS, "第一轮反馈");
        service.reworkStage(runId, PipelineStage.S3_CHARACTERS, "第二轮反馈");

        List<PipelineManifest.ReRound> rounds =
                workspaceStore.readManifest(runId).stage("S3").getRounds();
        assertEquals(2, rounds.size());
        assertEquals(2, rounds.get(1).getRound());
        assertEquals("第二轮反馈", rounds.get(1).getFeedback());
    }

    @Test
    void L1阶段返工拒绝走编辑保存() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reworkStage(runId, PipelineStage.S2_WORLD_SETTING, "反馈"));
        assertThat(e.getMessage()).contains("L1 编辑门").contains("无返工动作");
        // 零副作用：不记账、不触发生成
        assertThat(workspaceStore.readManifest(runId).stage("S2").getRounds()).isEmpty();
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    // ===== 上游已变重算（与返工互斥互补：响应上游变化 vs 响应本阶段稿不满意） =====

    @Test
    void 重算拒绝S1原料不可变() {
        String runId = newRun();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.regenerateStage(runId, PipelineStage.S1_IP_ANALYSIS));
        assertThat(e.getMessage()).contains("无重算动作").contains("S1 原料不可变");
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void 重算拒绝S6只读预览() {
        String runId = newRun();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.regenerateStage(runId, PipelineStage.S6_INSTALL));
        assertThat(e.getMessage()).contains("无重算动作").contains("只读预览");
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void 重算上游未确认拒绝() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);

        // 门级前置通过（S3 待确认且是当前阶段）但上游 S2 未整稿通过
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.regenerateStage(runId, PipelineStage.S3_CHARACTERS));
        assertThat(e.getMessage()).contains("上游阶段未确认");
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void 重算上游未变或无基线拒绝() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S2").setStatus("CONFIRMED");
        manifest.stage("S2").setVersion(2);
        // 基线等于上游当前版本：未变（未变重算走编辑/返工语义，避免无意义生成消耗）
        manifest.stage("S3").setUpstreamVersion(2);
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.regenerateStage(runId, PipelineStage.S3_CHARACTERS));
        assertThat(e.getMessage()).contains("未变化").contains("无需重算");
        verify(generationService, never()).generateStage(anyString(), anyString());

        // 无基线（重算检测之前的旧 run）：保守拒绝，引导走编辑/返工
        manifest.stage("S3").setUpstreamVersion(null);
        workspaceStore.writeManifest(runId, manifest);
        IllegalStateException legacy = assertThrows(IllegalStateException.class,
                () -> service.regenerateStage(runId, PipelineStage.S3_CHARACTERS));
        assertThat(legacy.getMessage()).contains("无需重算");
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void 重算按上游最新稿分派生成流() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S2").setStatus("CONFIRMED");
        manifest.stage("S2").setVersion(3);
        manifest.stage("S3").setUpstreamVersion(2);
        workspaceStore.writeManifest(runId, manifest);
        when(generationService.generateStage(runId, "S3"))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().data("[DONE]").build()));

        Flux<ServerSentEvent<Object>> flux = service.regenerateStage(
                runId, PipelineStage.S3_CHARACTERS);

        List<ServerSentEvent<Object>> events = flux.collectList().block();
        assertThat(events).isNotNull();
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        // 按上游当前确认稿分派通用生成（v N+1 与 L2 候选集整轮重置由
        // StageGenerationService 承担；收口刷新基线，重算提示自然燄灭）
        verify(generationService).generateStage(runId, "S3");
        // 无用户输入线：不记返工 rounds（与 rework 反馈线互斥）
        assertThat(workspaceStore.readManifest(runId).stage("S3").getRounds()).isEmpty();
    }

    @Test
    void 重算S4点5整轮全量重生() {
        String runId = newRun();
        stageAwaiting(runId, "S4.5", 1);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S4").setStatus("CONFIRMED");
        manifest.stage("S4").setVersion(2);
        manifest.stage("S4.5").setUpstreamVersion(1);
        workspaceStore.writeManifest(runId, manifest);
        when(storyGraphGeneration.generateActs(runId, null, null))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().data("[DONE]").build()));

        Flux<ServerSentEvent<Object>> flux = service.regenerateStage(
                runId, PipelineStage.S4P5_STORY_GRAPH);

        List<ServerSentEvent<Object>> events = flux.collectList().block();
        assertThat(events).isNotNull();
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        // 整轮全量重生（userFeedback null 同首轮生成：prompt 无反馈段）
        verify(storyGraphGeneration).generateActs(runId, null, null);
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    // ===== S1 链式汇总模式返工路由（planner-s1-chained-refinement tasks 4.1/4.3） =====

    @Test
    void S1链式返工路由汇总模式不走通用生成() {
        // spec「返工反馈只重生汇总」：反馈记账 + 路由链式汇总模式（v N+1 由
        // 汇总轮 markStageCompleted 递增；块笔记零重生成在链式服务测试钉住）
        String runId = newChainedRun();
        stageAwaiting(runId, "S1", 1);
        when(s1ChainedRefine.generateChained(runId, "主角动机改写为复仇驱动"))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().data("[DONE]").build()));

        Flux<ServerSentEvent<Object>> flux = service.reworkStage(
                runId, PipelineStage.S1_IP_ANALYSIS, "主角动机改写为复仇驱动");

        List<ServerSentEvent<Object>> events = flux.collectList().block();
        assertThat(events).isNotNull();
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        // 反馈记账落 manifest（rounds 真源，汇总轮 reworkSection 注入）
        List<PipelineManifest.ReRound> rounds =
                workspaceStore.readManifest(runId).stage("S1").getRounds();
        assertThat(rounds).hasSize(1);
        assertEquals("主角动机改写为复仇驱动", rounds.get(0).getFeedback());
        // 路由链式汇总模式，不走通用 generateStage
        verify(s1ChainedRefine).generateChained(runId, "主角动机改写为复仇驱动");
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void S1非链式返工仍被L1拒绝() {
        // spec「非链式 run 不出现返工窗」：短原文（4 字，未超链式阈值）拒绝
        String runId = newRun();
        stageAwaiting(runId, "S1", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reworkStage(runId, PipelineStage.S1_IP_ANALYSIS, "反馈"));
        assertThat(e.getMessage()).contains("L1 编辑门").contains("无返工动作");
        assertThat(workspaceStore.readManifest(runId).stage("S1").getRounds()).isEmpty();
        verify(s1ChainedRefine, never()).generateChained(anyString(), anyString());
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void S6返工拒绝只读预览() {
        String runId = newRun();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reworkStage(runId, PipelineStage.S6_INSTALL, "反馈"));
        assertThat(e.getMessage()).contains("只读预览");
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void 锁定态返工拒绝并零副作用() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲");
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        service.confirmStage(runId, PipelineStage.S3_CHARACTERS);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reworkStage(runId, PipelineStage.S3_CHARACTERS, "锁定后反馈"));
        assertThat(e.getMessage()).contains("回退解锁");
        // 零副作用：rounds 不记账、生成不触发（spec「锁定后不可静默改」）
        assertThat(workspaceStore.readManifest(runId).stage("S3").getRounds()).isEmpty();
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void run不存在返工拒绝() {
        when(runRepository.findById("run-x")).thenReturn(Optional.empty());

        NoSuchElementException e = assertThrows(NoSuchElementException.class,
                () -> service.reworkStage("run-x", PipelineStage.S3_CHARACTERS, "反馈"));
        assertThat(e.getMessage()).contains("run 不存在");
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void 非当前阶段返工拒绝零副作用() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        // 脏 manifest：S5 手工置 AWAITING_CONFIRM 但 currentStage=S3
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S5").setStatus("AWAITING_CONFIRM");
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reworkStage(runId, PipelineStage.S5_BRANCH_DESIGN, "越权反馈"));
        assertThat(e.getMessage()).contains("非当前阶段");
        assertThat(workspaceStore.readManifest(runId).stage("S3").getRounds()).isEmpty();
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    // ===== 幕级 L2 返工门（story-graph-authoring tasks 2.6） =====

    @Test
    void S4_5整轮返工路由到幕级生成编排() {
        String runId = newRun();
        stageAwaiting(runId, "S4.5", 1);
        when(storyGraphGeneration.generateActs(runId, "整轮反馈", null))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().data("[DONE]").build()));

        service.reworkStage(runId, PipelineStage.S4P5_STORY_GRAPH, "整轮反馈");

        // 反馈记账落 manifest + 路由幕级编排（targetAct=null 整轮），不走通用 generateStage
        assertThat(workspaceStore.readManifest(runId).stage("S4.5").getRounds()).hasSize(1);
        verify(storyGraphGeneration).generateActs(runId, "整轮反馈", null);
        verify(generationService, never()).generateStage(anyString(), anyString());
    }

    @Test
    void 幕级返工复活被毙幕并仅重生目标幕() {
        String runId = newRun();
        // spec Scenario「单幕返工不波及他幕」：幕 2 被毙返工，幕 1 确认状态不变
        stageWithGraphCandidates(runId, "CONFIRMED", "REJECTED");
        when(storyGraphGeneration.generateActs(eq(runId), anyString(), eq(2)))
                .thenReturn(Flux.just(ServerSentEvent.<Object>builder().data("[DONE]").build()));

        service.reworkAct(runId, "candidate-02", "幕 2 节奏太拖");

        // REJECTED 复活 PENDING（D5 救回通道的单幕具体化）；幕 1 状态不动
        List<PipelineManifest.CandidateState> candidates =
                workspaceStore.readManifest(runId).stage("S4.5").getCandidates();
        assertEquals("PENDING", candidates.get(1).getStatus());
        assertEquals("CONFIRMED", candidates.get(0).getStatus());
        // 返工轮记账 + 委托单幕生成（幕号从候选 ID 尾部数字解析）
        assertThat(workspaceStore.readManifest(runId).stage("S4.5").getRounds()).hasSize(1);
        verify(storyGraphGeneration).generateActs(runId, "幕 2 节奏太拖", 2);
    }

    @Test
    void 幕级返工已确认幕拒绝须先解锁() {
        String runId = newRun();
        stageWithGraphCandidates(runId, "PENDING", "CONFIRMED");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reworkAct(runId, "candidate-02", "反馈"));
        assertThat(e.getMessage()).contains("先回退解锁");
        // 零副作用：候选状态不动、不记账、不触发生成
        assertEquals("CONFIRMED",
                workspaceStore.readManifest(runId).stage("S4.5").getCandidates().get(1).getStatus());
        assertThat(workspaceStore.readManifest(runId).stage("S4.5").getRounds()).isEmpty();
        verify(storyGraphGeneration, never()).generateActs(anyString(), any(), any());
    }

    @Test
    void 幕级返工未知幕候选拒绝() {
        String runId = newRun();
        stageWithGraphCandidates(runId, "PENDING", "PENDING");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.reworkAct(runId, "candidate-99", "反馈"));
        assertThat(e.getMessage()).contains("幕候选不存在");
        assertThat(workspaceStore.readManifest(runId).stage("S4.5").getRounds()).isEmpty();
        verify(storyGraphGeneration, never()).generateActs(anyString(), any(), any());
    }

    @Test
    void 幕级解锁复位确认幕并删除拼接图待重拼() {
        String runId = newRun();
        // spec Scenario「已确认幕显式回退解锁」：先整稿通过产出拼接图，
        // 阶段级解锁回 AWAITING_CONFIRM 后，幕 1 解锁且拼接图失效待重拼
        stageWithGraphCandidates(runId, "CONFIRMED", "CONFIRMED");
        service.confirmStage(runId, PipelineStage.S4P5_STORY_GRAPH);
        service.unlockStage(runId, PipelineStage.S4P5_STORY_GRAPH);
        assertThat(workspaceStore.readStageArtifact(runId, PipelineStage.S4P5_STORY_GRAPH)).isNotNull();

        Map<String, Object> result = service.unlockAct(runId, "candidate-01");

        assertEquals("S4.5", result.get("stage"));
        assertEquals("PENDING", result.get("status"));
        assertEquals(true, result.get("graphInvalidated"));
        // 拼接图删除：readStageArtifact null 即下游衔接待重拼语义（重确认时重拼）
        assertThat(workspaceStore.readStageArtifact(runId, PipelineStage.S4P5_STORY_GRAPH)).isNull();
        // 幕 1 解锁复位 PENDING；幕 2 状态不变（其他幕不动）
        List<PipelineManifest.CandidateState> candidates =
                workspaceStore.readManifest(runId).stage("S4.5").getCandidates();
        assertEquals("PENDING", candidates.get(0).getStatus());
        assertEquals("CONFIRMED", candidates.get(1).getStatus());
    }

    @Test
    void 幕级解锁非已确认幕拒绝() {
        String runId = newRun();
        stageWithGraphCandidates(runId, "PENDING", "REJECTED");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.unlockAct(runId, "candidate-01"));
        assertThat(e.getMessage()).contains("仅已确认候选");
        // 零副作用：候选状态不动
        assertEquals("PENDING",
                workspaceStore.readManifest(runId).stage("S4.5").getCandidates().get(0).getStatus());
    }

    @Test
    void 幕级解锁未知幕候选拒绝() {
        String runId = newRun();
        stageWithGraphCandidates(runId, "CONFIRMED", "CONFIRMED");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.unlockAct(runId, "candidate-99"));
        assertThat(e.getMessage()).contains("幕候选不存在");
    }

    // ===== 回退解锁（tasks 4.5） =====

    @Test
    void 解锁翻转状态回退currentStage且存档保留() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);
        workspaceStore.writeStageArtifact(runId, PipelineStage.S2_WORLD_SETTING, "原稿 v1");
        service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING);

        Map<String, Object> result = service.unlockStage(runId, PipelineStage.S2_WORLD_SETTING);

        assertEquals("S2", result.get("stage"));
        assertEquals("AWAITING_CONFIRM", result.get("status"));
        assertEquals(1, result.get("archivedVersion"));
        assertEquals("S2", result.get("currentStage"));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals("AWAITING_CONFIRM", manifest.stage("S2").getStatus());
        assertEquals("S2", manifest.getCurrentStage());
        // spec「回退解锁保留存档」：confirmedVersion 不清除（v1 快照已落盘，可回看）
        assertEquals(1, manifest.stage("S2").getConfirmedVersion());
        // 解锁本身不递增版本（新一轮 v(N+1) 由解锁后的编辑/生成承担）
        assertEquals(1, manifest.stage("S2").getVersion());
        verify(runRepository).updateProgress(runId, "S2", "AWAITING");
    }

    @Test
    void 解锁后恢复可编辑且新编辑版本递增() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);
        workspaceStore.writeStageArtifact(runId, PipelineStage.S2_WORLD_SETTING, "原稿 v1");
        service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING);
        service.unlockStage(runId, PipelineStage.S2_WORLD_SETTING);

        Map<String, Object> result = service.saveL1Draft(
                runId, PipelineStage.S2_WORLD_SETTING, "解锁后新稿");

        // spec「解锁生成新一轮（版本号递增）」由解锁后的第一次编辑承担
        assertEquals(2, result.get("version"));
        assertEquals("解锁后新稿",
                workspaceStore.readStageArtifact(runId, PipelineStage.S2_WORLD_SETTING));
    }

    @Test
    void L2阶段解锁走完整确认回退循环() {
        String runId = newRun();
        stageWithCandidates(runId, 2, "## 人物 1：甲");
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        service.confirmStage(runId, PipelineStage.S3_CHARACTERS);   // currentStage → S4

        Map<String, Object> result = service.unlockStage(runId, PipelineStage.S3_CHARACTERS);

        assertEquals(2, result.get("archivedVersion"));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals("AWAITING_CONFIRM", manifest.stage("S3").getStatus());
        assertEquals("S3", manifest.getCurrentStage());
        // 候选状态不受解锁影响（仍可逐卡编辑/再毙，锁定语义只在阶段级）
        assertEquals("CONFIRMED", manifest.stage("S3").getCandidates().get(0).getStatus());
    }

    @Test
    void 未锁定阶段解锁拒绝() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.unlockStage(runId, PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("仅锁定阶段");
        // 零副作用：状态与 currentStage 不动、无库表推进
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals("AWAITING_CONFIRM", manifest.stage("S2").getStatus());
        assertEquals("S2", manifest.getCurrentStage());
        verify(runRepository, never()).updateProgress(any(), any(), any());
    }

    @Test
    void 跨级解锁拒绝提示先解锁下游() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);
        // 合法不变式摆位：S2 与 S3 均已确认，currentStage 推进 S4
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S2").setStatus("CONFIRMED");
        manifest.stage("S3").setStatus("CONFIRMED");
        manifest.setCurrentStage("S4");
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.unlockStage(runId, PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("先回退解锁下游");
        // 零副作用：跨级回退会造成 S2 解锁但 S3 仍 CONFIRMED 的脏 manifest
        PipelineManifest after = workspaceStore.readManifest(runId);
        assertEquals("S4", after.getCurrentStage());
        assertEquals("CONFIRMED", after.stage("S2").getStatus());
        verify(runRepository, never()).updateProgress(any(), any(), any());
    }

    @Test
    void S6解锁拒绝终态只读() {
        String runId = newRun();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.unlockStage(runId, PipelineStage.S6_INSTALL));
        assertThat(e.getMessage()).contains("终态").contains("不可回退解锁");
        verify(runRepository, never()).updateProgress(any(), any(), any());
    }

    @Test
    void run不存在解锁拒绝() {
        when(runRepository.findById("run-x")).thenReturn(Optional.empty());

        NoSuchElementException e = assertThrows(NoSuchElementException.class,
                () -> service.unlockStage("run-x", PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("run 不存在");
    }

    @Test
    void 下游生成中解锁拒绝防产物撕裂() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);
        service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING);   // currentStage → S3
        // 下游 S3 生成中（run RUNNING）——并发回退与生成会造成产物撕裂
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.setStatus("RUNNING");
        workspaceStore.writeManifest(runId, manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.unlockStage(runId, PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("run 当前状态不匹配");
        // 零副作用：S2 仍锁定、currentStage 仍 S3
        PipelineManifest after = workspaceStore.readManifest(runId);
        assertEquals("CONFIRMED", after.stage("S2").getStatus());
        assertEquals("S3", after.getCurrentStage());
    }

    // ===== 引擎单测补全（tasks 4.7：幂等分支） =====

    @Test
    void 锁定后重复整稿通过拒绝防重入() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);
        service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING);

        // spec「确认稿幂等锁定」：锁定后重复确认不二次推进（门层锁定态守卫拦下，
        // 提示须先回退解锁）
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmStage(runId, PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("回退解锁");
        // 零副作用：currentStage 不二次推进、版本不递增
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals("CONFIRMED", manifest.stage("S2").getStatus());
        assertEquals("S3", manifest.getCurrentStage());
        assertEquals(1, manifest.stage("S2").getVersion());
    }

    @Test
    void 已确认候选再确认幂等零重写() {
        String runId = newRun();
        stageWithCandidates(runId, 1, "## 人物 1：甲", "## 人物 2：乙");
        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");
        String updatedAt = workspaceStore.readManifest(runId).getUpdatedAt();

        service.confirmCandidate(runId, PipelineStage.S3_CHARACTERS, "candidate-01");

        // 幂等分支：同状态再点零重写（manifest 未落盘，updatedAt 不变）
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        assertEquals(updatedAt, manifest.getUpdatedAt());
        assertEquals("CONFIRMED", manifest.stage("S3").getCandidates().get(0).getStatus());
        // 他卡不受幂等重入影响
        assertEquals("PENDING", manifest.stage("S3").getCandidates().get(1).getStatus());
    }

    // ===== S3 补批/全量门分派（planner-s3-batch-generate tasks 3.1，spec「补一批」「全量生成」） =====

    @Test
    void 补批门分派S3分批服务单批上限() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        when(s3BatchGeneration.generateRemainingBatches(runId, 1))
                .thenReturn(Flux.just(ServerSentEvent.builder().data("[DONE]").build()));

        List<ServerSentEvent<Object>> events = service
                .appendBatch(runId, PipelineStage.S3_CHARACTERS).collectList().block();

        // D5 差参不差路径：补一批 = batchLimit 1，流透传
        assertThat(events).extracting(ServerSentEvent::data).containsExactly("[DONE]");
        verify(s3BatchGeneration).generateRemainingBatches(runId, 1);
    }

    @Test
    void 全量生成门分派无批数上限() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        when(s3BatchGeneration.generateRemainingBatches(runId, Integer.MAX_VALUE))
                .thenReturn(Flux.just(ServerSentEvent.builder().data("[DONE]").build()));

        service.generateFull(runId, PipelineStage.S3_CHARACTERS).collectList().block();

        // 全量 = batchLimit ∞（与补批共用编排路径，D5）
        verify(s3BatchGeneration).generateRemainingBatches(runId, Integer.MAX_VALUE);
    }

    @Test
    void 补批门非S3阶段拒绝零分派() {
        String runId = newRun();
        stageAwaiting(runId, "S2", 1);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.appendBatch(runId, PipelineStage.S2_WORLD_SETTING));
        assertThat(e.getMessage()).contains("仅 S3 人物候选阶段可用");
        verify(s3BatchGeneration, never()).generateRemainingBatches(anyString(), anyInt());
    }

    @Test
    void 补批门锁定态拒绝零分派() {
        String runId = newRun();
        stageAwaiting(runId, "S3", 1);
        // 整稿确认锁定后（S3 CONFIRMED、currentStage S4）补批门拒绝：返工/解锁先行
        service.confirmStage(runId, PipelineStage.S3_CHARACTERS);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.appendBatch(runId, PipelineStage.S3_CHARACTERS));
        assertThat(e.getMessage()).contains("锁定态须先回退解锁");
        verify(s3BatchGeneration, never()).generateRemainingBatches(anyString(), anyInt());
    }
}
