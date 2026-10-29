package com.luyu.agent.rpg.planner.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.engine.PlannerGenerationGuard;
import com.luyu.agent.rpg.planner.engine.StageVersionDiffer;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;
import com.luyu.agent.rpg.planner.model.RunDetail;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * run 生命周期服务测试（add-planner-pipeline tasks 2.1-2.5）。
 * <p>
 * repository 走 mock（不触 DB 与 TenantContext）；工作区走真实文件（@TempDir 注入
 * PlannerWorkspaceStore 测试构造），manifest 读写为真实序列化往返——钉住：
 * <ul>
 *   <li>创建写序「先工作区后库表」（D3 漂移对冲约定，零脏 run）</li>
 *   <li>manifest roundtrip（六阶段 NOT_STARTED 初始化、字段还原）</li>
 *   <li>放弃：终态守卫 + manifest 先写 + 库表后同步；不删工作区</li>
 *   <li>单阶段 RUNNING 守卫（tasks 2.5）：生成中拒绝、终态拒绝、通过放行</li>
 *   <li>详情渲染：阶段视图 + sourcePreview 截断</li>
 * </ul>
 */
class PipelineRunServiceTest {

    /** ip_name 用例资料页夹具（原始字节 + 清洗文本，planner-html-source D5 双文件载体） */
    private static final List<HtmlSourceService.SourcePage> ONE_PAGE = List.of(
            new HtmlSourceService.SourcePage(
                    "<html><body><h1>三体</h1></body></html>".getBytes(StandardCharsets.UTF_8),
                    "# 三体"));

    @TempDir
    Path tempDir;

    private PipelineRunRepository runRepository;
    private PlannerWorkspaceStore workspaceStore;
    private PlannerGenerationGuard generationGuard;
    private PipelineRunService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(PipelineRunRepository.class);
        workspaceStore = new PlannerWorkspaceStore(() -> tempDir);
        generationGuard = new PlannerGenerationGuard();
        service = new PipelineRunService(runRepository, workspaceStore,
                new RpgPlannerProperties(), generationGuard);
    }

    // ===== 创建（tasks 2.1） =====

    @Test
    void text模式创建先写工作区后落库() {
        PipelineRun run = service.createRun("text", "faithful",
                "  夜幕降临，冥星升起。  ", null, null);

        assertNotNull(run.getId());
        assertEquals("CREATED", run.getStatus());
        assertEquals("S1", run.getCurrentStage());
        assertEquals("text", run.getSourceMode());
        assertEquals("faithful", run.getStyleMode());
        // 展示名缺省推导：原文截断 + 空白归一
        assertEquals("夜幕降临，冥星升起。", run.getName());

        // 写序（D3）：先工作区（manifest+source）后库表
        InOrder order = inOrder(runRepository);
        order.verify(runRepository).insert(any(PipelineRun.class));

        // 工作区产物真实落盘
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        assertNotNull(manifest);
        assertEquals(run.getId(), manifest.getRunId());
        assertEquals("CREATED", manifest.getStatus());
        assertEquals("S1", manifest.getCurrentStage());
        assertEquals(7, manifest.stages().size());
        manifest.stages().values()
                .forEach(s -> assertEquals(PipelineStageStatus.NOT_STARTED.name(), s.getStatus()));
        assertEquals("夜幕降临，冥星升起。", workspaceStore.readSource(run.getId()));
    }

    @Test
    void ip_name模式创建name缺省用IP名() {
        PipelineRun run = service.createRun("ip_name", "remix", null, "  三体  ", null, ONE_PAGE);

        assertEquals("三体", run.getName());
        assertEquals("三体", workspaceStore.readSource(run.getId()));
    }

    @Test
    void 显式展示名优先于自动推导() {
        PipelineRun run = service.createRun("ip_name", "faithful", null, "三体", "我的三体拆解", ONE_PAGE);
        assertEquals("我的三体拆解", run.getName());
    }

    @Test
    void text模式原文缺失拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createRun("text", "faithful", "  ", null, null));
        assertEquals("text 模式下原文不能为空", e.getMessage());
        verify(runRepository, never()).insert(any());
    }

    @Test
    void ip_name模式IP名缺失拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createRun("ip_name", "faithful", null, null, null));
        assertEquals("ip_name 模式下 IP 名不能为空", e.getMessage());
        verify(runRepository, never()).insert(any());
    }

    @Test
    void 未知模式码拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createRun("audio", "faithful", "原文", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.createRun("text", "bold", "原文", null, null));
        verify(runRepository, never()).insert(any());
    }

    // ===== ip_name HTML 资料页（planner-html-source tasks 3.3/3.4） =====

    @Test
    void ip_name模式pages经roundtrip落manifest且双文件落盘() {
        PipelineRun run = service.createRun("ip_name", "faithful", null, "进击的巨人", null, ONE_PAGE);

        // manifest 序列化往返还原（yaml 真实落盘）：sourcePages 页名列表
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        assertEquals(List.of("page-01"), manifest.getSourcePages());
        // 新 run 不写 sourceUrls（退役字段，仅存量 run 兼容读）
        assertNull(manifest.getSourceUrls());
        // pages 双文件真实落盘（D5：原始字节 + 清洗文本）
        assertTrue(java.nio.file.Files.exists(
                tempDir.resolve(run.getId()).resolve("pages").resolve("page-01.html")));
        assertTrue(java.nio.file.Files.exists(
                tempDir.resolve(run.getId()).resolve("pages").resolve("page-01.txt")));
        // 详情视图携带 sourcePages（前端源信息卡渲染；repository 走 mock 需 stub findById）
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));
        assertEquals(List.of("page-01"), service.getRunDetail(run.getId()).getSourcePages());
    }

    @Test
    void ip_name模式无资料页拒绝创建() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createRun("ip_name", "faithful", null, "三体", null));
        assertEquals("ip_name 模式下 HTML 资料页不能为空（至少 1 份，请上传浏览器另存的 HTML）",
                e.getMessage());
        verify(runRepository, never()).insert(any());
    }

    @Test
    void text模式传资料页拒绝() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.createRun("text", "faithful", "原文", null, null, ONE_PAGE));
        assertEquals("text 模式不支持 HTML 资料页（仅 ip_name 模式）", e.getMessage());
        verify(runRepository, never()).insert(any());
    }

    @Test
    void 存量sourceUrls字段roundtrip保留() {
        // 旧 run 兼容读（design D8）：fromMap 读到的 sourceUrls 经 writeManifest 重序列化
        // 不丢（阶段状态更新走同一写径，防存量兼容读数据静默丢失）
        PipelineRun run = service.createRun("text", "faithful", "原文", null, null);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.setSourceUrls(List.of("https://legacy.com/x"));
        workspaceStore.writeManifest(run.getId(), manifest);

        assertEquals(List.of("https://legacy.com/x"),
                workspaceStore.readManifest(run.getId()).getSourceUrls());
    }

    // ===== 放弃（tasks 2.4） =====

    @Test
    void 放弃先写manifest后同步库表且工作区保留() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));

        service.abandonRun(run.getId());

        verify(runRepository).abandon(run.getId());
        // manifest 状态已为 ABANDONED；工作区文件仍存在（spec「放弃保留产物」）
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        assertEquals("ABANDONED", manifest.getStatus());
        assertEquals("原文内容", workspaceStore.readSource(run.getId()));
    }

    @Test
    void 放弃终态run拒绝() {
        PipelineRun completed = dbRun("run-done", "S6", "COMPLETED");
        when(runRepository.findById("run-done")).thenReturn(Optional.of(completed));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.abandonRun("run-done"));
        assertEquals("非法的 run 状态转换: COMPLETED → ABANDONED", e.getMessage());
        verify(runRepository, never()).abandon(anyString());
    }

    @Test
    void 放弃已放弃run拒绝() {
        when(runRepository.findById("run-x")).thenReturn(Optional.of(dbRun("run-x", "S2", "ABANDONED")));

        assertThrows(IllegalStateException.class, () -> service.abandonRun("run-x"));
        verify(runRepository, never()).abandon(anyString());
    }

    @Test
    void 放弃不存在run拒绝() {
        when(runRepository.findById("run-none")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.abandonRun("run-none"));
        verify(runRepository, never()).abandon(anyString());
    }

    // ===== 硬删除（planner-run-delete tasks 1.5） =====

    @Test
    void 硬删除清除双真源() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        // 深入工作区：阶段产物 + 版本快照 + 块笔记，验证全树清除
        workspaceStore.writeStageArtifact(run.getId(), PipelineStage.S1_IP_ANALYSIS, "S1 全稿");
        workspaceStore.writeVersionSnapshot(run.getId(), PipelineStage.S1_IP_ANALYSIS, 1, "v1");
        workspaceStore.writeChunkNote(run.getId(), 1, "块笔记");

        service.deleteRun(run.getId());

        // 库表行已删（写序：先库表后工作区，D3 删除镜像反转）
        verify(runRepository).deleteById(run.getId());
        // 工作区目录整树消失（manifest、source、产物、版本快照、笔记全清）
        assertFalse(java.nio.file.Files.exists(tempDir.resolve(run.getId())));
    }

    @Test
    void 硬删除终态run放行且已安装世界不受波及() {
        // S6 物化产物是独立 world 资产（与 run 解耦），COMPLETED run 可删
        PipelineRun completed = dbRun("run-done", "S6", "COMPLETED");
        when(runRepository.findById("run-done")).thenReturn(Optional.of(completed));

        service.deleteRun("run-done");
        verify(runRepository).deleteById("run-done");
    }

    @Test
    void 生成中硬删除拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        generationGuard.tryAcquire(run.getId(), PipelineStage.S1_IP_ANALYSIS);   // 模拟活跃 SSE 流占位

        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> service.deleteRun(run.getId()));
            assertEquals("run 正在生成中，请等待完成后再删除（活跃流守卫）", e.getMessage());
            verify(runRepository, never()).deleteById(anyString());
            // 守卫拒绝零副作用：工作区保留
            assertNotNull(workspaceStore.readManifest(run.getId()));
        } finally {
            generationGuard.release(run.getId(), PipelineStage.S1_IP_ANALYSIS);
        }
    }

    @Test
    void 硬删除不存在run拒绝且重复删幂等() {
        when(runRepository.findById("run-none")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.deleteRun("run-none"));
        assertThrows(NoSuchElementException.class, () -> service.deleteRun("run-none"));
        verify(runRepository, never()).deleteById(anyString());
    }

    @Test
    void 工作区删除失败降级warn不阻断() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        // 模拟目录删除 IO 失败（文件锁等）：删行已成功，降级 warn 不外抛
        PlannerWorkspaceStore spyStore = org.mockito.Mockito.spy(workspaceStore);
        org.mockito.Mockito.doThrow(new IllegalStateException("run 工作区目录删除失败"))
                .when(spyStore).deleteRunDir(run.getId());
        PipelineRunService svc = new PipelineRunService(runRepository, spyStore,
                new RpgPlannerProperties(), generationGuard);

        assertDoesNotThrow(() -> svc.deleteRun(run.getId()));
        // 写序：先库表行后工作区（孤儿目录无害，功能面 404 兼底）
        InOrder order = inOrder(runRepository, spyStore);
        order.verify(runRepository).deleteById(run.getId());
        order.verify(spyStore).deleteRunDir(run.getId());
    }

    // ===== 单阶段 RUNNING 守卫（tasks 2.5） =====

    @Test
    void 有阶段RUNNING时守卫拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        PipelineRun dbRow = dbRun(run);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRow));

        // 制造 S1 RUNNING 状态（绕过状态机直接改 manifest，模拟生成流进行中）
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S1").setStatus(PipelineStageStatus.RUNNING.name());
        workspaceStore.writeManifest(run.getId(), manifest);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.requireNoRunningStage(run.getId()));
        assertEquals("该 run 有阶段正在生成中，请等待完成后再操作（单阶段 RUNNING 守卫）", e.getMessage());
    }

    @Test
    void 无阶段RUNNING时守卫放行返回run() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));

        PipelineRun guarded = service.requireNoRunningStage(run.getId());
        assertEquals(run.getId(), guarded.getId());
    }

    @Test
    void 终态run守卫拒绝() {
        when(runRepository.findById("run-done")).thenReturn(Optional.of(dbRun("run-done", "S6", "COMPLETED")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.requireNoRunningStage("run-done"));
        assertEquals("run 已处于终态，不可再推进: COMPLETED", e.getMessage());
    }

    @Test
    void 守卫下manifest缺失视为数据损坏拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));

        // 模拟工作区文件树被误删
        deleteRunDir(run.getId());

        assertThrows(IllegalStateException.class, () -> service.requireNoRunningStage(run.getId()));
    }

    // ===== 详情渲染（tasks 2.3） =====

    @Test
    void 详情渲染七阶段视图与sourcePreview() {
        PipelineRun run = service.createRun("text", "faithful", "a".repeat(300), null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));

        RunDetail detail = service.getRunDetail(run.getId());

        assertEquals(run.getId(), detail.getId());
        assertEquals("CREATED", detail.getStatus());
        assertEquals("S1", detail.getCurrentStage());
        assertEquals(7, detail.getStages().size());
        assertEquals("S1", detail.getStages().get(0).getCode());
        assertEquals("IP 拆解", detail.getStages().get(0).getLabel());
        assertEquals("L1", detail.getStages().get(0).getGate());
        assertEquals("人物设定", detail.getStages().get(2).getLabel());
        assertEquals("L2", detail.getStages().get(2).getGate());
        // S4.5 结构层插入阶段（story-graph-authoring 2.1）：顺序位于 S4 与 S5 之间
        assertEquals("S4.5", detail.getStages().get(4).getCode());
        assertEquals("故事图骨架", detail.getStages().get(4).getLabel());
        assertEquals("L2", detail.getStages().get(4).getGate());
        assertEquals("剧情结构", detail.getStages().get(3).getLabel());
        // 预览截断：300 字符原文 → 200 + 省略号
        assertEquals("a".repeat(200) + "…", detail.getSourcePreview());
    }

    @Test
    void 详情S1链式chained派生标记仅text超阈值() {
        // text 超链式阈值（默认 1 万字）：S1 节点 chained=true，其余阶段恒 false
        //（planner-s1-chained-refinement tasks 3.3：与生成分叉同判，前端返工窗依据）
        PipelineRun chained = service.createRun("text", "faithful", "字".repeat(10_001), null, null);
        when(runRepository.findById(chained.getId())).thenReturn(Optional.of(dbRun(chained)));

        RunDetail detail = service.getRunDetail(chained.getId());

        assertTrue(detail.getStages().get(0).isChained());
        detail.getStages().stream()
                .filter(s -> !"S1".equals(s.getCode()))
                .forEach(s -> assertFalse(s.isChained(), s.getCode() + " 应为非链式"));

        // 短文与 ip_name（单页短原料）：非链式（前端 L1 门行为零改动）
        PipelineRun shortText = service.createRun("text", "faithful", "短原文", null, null);
        when(runRepository.findById(shortText.getId())).thenReturn(Optional.of(dbRun(shortText)));
        assertFalse(service.getRunDetail(shortText.getId()).getStages().get(0).isChained());

        PipelineRun ipName = service.createRun("ip_name", "faithful", null, "三体", null, ONE_PAGE);
        when(runRepository.findById(ipName.getId())).thenReturn(Optional.of(dbRun(ipName)));
        assertFalse(service.getRunDetail(ipName.getId()).getStages().get(0).isChained());
    }

    @Test
    void 详情manifest缺失视为数据损坏拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        deleteRunDir(run.getId());

        assertThrows(IllegalStateException.class, () -> service.getRunDetail(run.getId()));
    }

    @Test
    void 详情不存在返回404语义() {
        when(runRepository.findById("run-none")).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> service.getRunDetail("run-none"));
    }

    @Test
    void 详情渲染L2候选卡片集与payload全文() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        stageAwaiting(run.getId(), "S3", 2);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S3").getCandidates()
                .add(new PipelineManifest.CandidateState("candidate-01", "人物一", "PENDING"));
        manifest.stage("S3").getCandidates()
                .add(new PipelineManifest.CandidateState("candidate-02", "人物二", "REJECTED"));
        workspaceStore.writeManifest(run.getId(), manifest);
        workspaceStore.writeCandidateItem(run.getId(), PipelineStage.S3_CHARACTERS, "candidate-01", "人物一全文");
        workspaceStore.writeCandidateItem(run.getId(), PipelineStage.S3_CHARACTERS, "candidate-02", "人物二全文");

        RunDetail detail = service.getRunDetail(run.getId());

        // tasks 6.6：manifest 状态行 + 候选文件 payload 全文合成（卡片集预览/编辑零额外请求）
        List<RunDetail.CandidateView> candidates = detail.getStages().get(2).getCandidates();
        assertEquals(2, candidates.size());
        assertEquals("candidate-01", candidates.get(0).getId());
        assertEquals("人物一", candidates.get(0).getTitle());
        assertEquals("PENDING", candidates.get(0).getStatus());
        assertEquals("人物一全文", candidates.get(0).getPayload());
        assertEquals("REJECTED", candidates.get(1).getStatus());
        assertEquals("人物二全文", candidates.get(1).getPayload());
        // L1 阶段候选恒空（卡片集只在 L2 门渲染，S1/S2/S4/S6）
        assertThat(detail.getStages().get(0).getCandidates()).isEmpty();
        assertThat(detail.getStages().get(1).getCandidates()).isEmpty();
    }

    @Test
    void 详情候选文件缺失payload为空() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        stageAwaiting(run.getId(), "S3", 1);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S3").getCandidates()
                .add(new PipelineManifest.CandidateState("candidate-01", "人物一", "PENDING"));
        workspaceStore.writeManifest(run.getId(), manifest);

        RunDetail detail = service.getRunDetail(run.getId());

        // 文件缺失（返工整轮覆盖窗口等）：payload null，前端只渲染状态行不崩
        assertNull(detail.getStages().get(2).getCandidates().get(0).getPayload());
    }

    // ===== S3 名册剩余派生（planner-s3-batch-generate tasks 5.1；6.3 E2E 发现的口径修复） =====

    @Test
    void S3名册剩余派生含已毙卡与分派同口径() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        workspaceStore.writeStageArtifact(run.getId(), PipelineStage.S1_IP_ANALYSIS,
                "## 一、世界观总纲\n\n设定。\n\n## 三、人物全量名册\n\n"
                        + "- **甲**：主角。\n- **乙**：反派。\n- **丙**：配角。\n");
        stageAwaiting(run.getId(), "S3", 1);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S3").getCandidates()
                .add(new PipelineManifest.CandidateState("candidate-01", "甲", "PENDING"));
        manifest.stage("S3").getCandidates()
                .add(new PipelineManifest.CandidateState("candidate-02", "乙", "REJECTED"));
        manifest.stage("S3").getCandidates()
                .add(new PipelineManifest.CandidateState("candidate-03", "丙", "PENDING"));
        workspaceStore.writeManifest(run.getId(), manifest);

        RunDetail detail = service.getRunDetail(run.getId());

        // 毙掉即出局（spec「已毙人物不复活」）：含 REJECTED 算已产出，与
        // S3BatchGenerationService 分派同口径——名册 3 人全出局 remaining=0。
        // 修复前乙被滤回剩余清单 → 1（前端「补一批」按钮数字虚高误导分派）
        assertEquals(0, detail.getStages().get(2).getRosterRemaining());
        assertEquals(20, detail.getStages().get(2).getBatchCapacity());
        // 名册字段只在 S3 透出，S1 视图恒 null（补批按钮不渲染）
        assertNull(detail.getStages().get(0).getRosterRemaining());
    }

    @Test
    void S1稿无名册节S3不透出名册剩余() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        // remix / 旧稿场景：S1 稿无名册节
        workspaceStore.writeStageArtifact(run.getId(), PipelineStage.S1_IP_ANALYSIS,
                "## 一、世界观总纲\n\n无名册节。\n");
        stageAwaiting(run.getId(), "S3", 1);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S3").getCandidates()
                .add(new PipelineManifest.CandidateState("candidate-01", "甲", "PENDING"));
        workspaceStore.writeManifest(run.getId(), manifest);

        RunDetail detail = service.getRunDetail(run.getId());

        // 名册派生不可用：rosterRemaining 不透出（null），前端不渲染补批按钮，
        // 后端补批守卫兜底拒绝；batchCapacity 属阶段属性恒透出
        assertNull(detail.getStages().get(2).getRosterRemaining());
        assertEquals(20, detail.getStages().get(2).getBatchCapacity());
    }

    // ===== 阶段 FAILED 冻结与重试（tasks 3.7） =====

    @Test
    void 失败标记只置目标阶段不波及其他阶段() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);

        // 模拟推进至 S5 生成中：S1-S4 已确认、S5 RUNNING、run RUNNING
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.setStatus("RUNNING");
        manifest.setCurrentStage("S5");
        for (String code : List.of("S1", "S2", "S3", "S4")) {
            manifest.stage(code).setStatus("CONFIRMED");
        }
        manifest.stage("S5").setStatus(PipelineStageStatus.RUNNING.name());
        workspaceStore.writeManifest(run.getId(), manifest);

        service.markStageFailed(run.getId(), PipelineStage.S5_BRANCH_DESIGN);

        // 目标阶段冻结 FAILED、run 同步 FAILED；上游 CONFIRMED 与 S6 均不动（D7 失败不连环）
        PipelineManifest after = workspaceStore.readManifest(run.getId());
        assertEquals("FAILED", after.stage("S5").getStatus());
        assertEquals("FAILED", after.getStatus());
        assertEquals("S5", after.getCurrentStage());
        for (String code : List.of("S1", "S2", "S3", "S4")) {
            assertEquals("CONFIRMED", after.stage(code).getStatus());
        }
        assertEquals("NOT_STARTED", after.stage("S6").getStatus());
        verify(runRepository).updateProgress(run.getId(), "S5", "FAILED");
    }

    @Test
    void 非RUNNING态失败标记静默跳过() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);

        // NOT_STARTED（非生成中）调用失败标记：幂等静默，不改任何状态
        service.markStageFailed(run.getId(), PipelineStage.S1_IP_ANALYSIS);

        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        assertEquals("NOT_STARTED", manifest.stage("S1").getStatus());
        assertEquals("CREATED", manifest.getStatus());
        verify(runRepository, never()).updateProgress(anyString(), anyString(), anyString());
    }

    @Test
    void manifest缺失时失败标记吞异常不外抛() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        deleteRunDir(run.getId());

        // 防御性：失败标记自身不能再抛（否则丢掉调用方的原始错误）
        assertDoesNotThrow(() -> service.markStageFailed(run.getId(), PipelineStage.S1_IP_ANALYSIS));
        verify(runRepository, never()).updateProgress(anyString(), anyString(), anyString());
    }

    // ===== 生成完成状态推进与摘要行（planner-stage-generation spec） =====

    @Test
    void 生成完成推进版本并落摘要行() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageRunning(run.getId(), "S1");

        int version = service.markStageCompleted(run.getId(), PipelineStage.S1_IP_ANALYSIS, "IP 拆解摘要行");

        assertEquals(1, version);
        PipelineManifest after = workspaceStore.readManifest(run.getId());
        assertEquals("AWAITING_CONFIRM", after.stage("S1").getStatus());
        assertEquals(1, after.stage("S1").getVersion());
        // spec「manifest 更新草稿版本与摘要行」：摘要随完成落盘（前端步骤条 + 上游摘要段数据源）
        assertEquals("IP 拆解摘要行", after.stage("S1").getSummary());
        assertEquals("AWAITING", after.getStatus());
        verify(runRepository).updateProgress(run.getId(), "S1", "AWAITING");
    }

    @Test
    void 生成完成空摘要保留既有摘要行() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageRunning(run.getId(), "S1");
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S1").setSummary("旧摘要");
        workspaceStore.writeManifest(run.getId(), manifest);

        service.markStageCompleted(run.getId(), PipelineStage.S1_IP_ANALYSIS, "  ");

        // 空摘要不抹旧值（返工轮提取失败的防御：保留上次可用摘要）
        assertEquals("旧摘要", workspaceStore.readManifest(run.getId()).stage("S1").getSummary());
    }

    // ===== 上游版本基线与已变派生（重算检测） =====

    @Test
    void 生成收口记录上游版本基线() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        // S1 确认稿 v2 后 S2 生成收口：基线记 S2 本次生成所读的上游版本 2
        //（上游解锁后编辑/返工递增 version，高于基线即「上游已变」提示重算）
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.setStatus("RUNNING");
        manifest.setCurrentStage("S2");
        manifest.stage("S1").setStatus("CONFIRMED");
        manifest.stage("S1").setVersion(2);
        manifest.stage("S2").setStatus("RUNNING");
        workspaceStore.writeManifest(run.getId(), manifest);

        service.markStageCompleted(run.getId(), PipelineStage.S2_WORLD_SETTING, "S2 摘要");

        assertEquals(Integer.valueOf(2),
                workspaceStore.readManifest(run.getId()).stage("S2").getUpstreamVersion());

        // S1 无上游不记（null 恒不提示，前端零渲染）
        stageRunning(run.getId(), "S1");
        service.markStageCompleted(run.getId(), PipelineStage.S1_IP_ANALYSIS, "S1 摘要");
        assertNull(workspaceStore.readManifest(run.getId()).stage("S1").getUpstreamVersion());
    }

    @Test
    void 基线字段manifest往返与缺省() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S3").setUpstreamVersion(3);
        workspaceStore.writeManifest(run.getId(), manifest);

        // 有值：YAML 写读往返还原；未写键（S1 无上游/旧 run）读回 null
        assertEquals(Integer.valueOf(3),
                workspaceStore.readManifest(run.getId()).stage("S3").getUpstreamVersion());
        assertNull(workspaceStore.readManifest(run.getId()).stage("S2").getUpstreamVersion());
    }

    @Test
    void 详情派生上游已变提示与代码() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(dbRun(run)));
        // S1 确认后解锁编辑（v3 高于 S2 生成基线 v2）：S2 提示上游已变
        stageAwaiting(run.getId(), "S2", 1);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S1").setStatus("CONFIRMED");
        manifest.stage("S1").setVersion(3);
        manifest.stage("S2").setUpstreamVersion(2);
        workspaceStore.writeManifest(run.getId(), manifest);

        RunDetail detail = service.getRunDetail(run.getId());
        RunDetail.StageView s2 = detail.getStages().get(1);
        assertTrue(s2.isUpstreamChanged());
        assertEquals("S1", s2.getUpstreamCode());
        // S1 无上游：无代码无提示
        assertFalse(detail.getStages().get(0).isUpstreamChanged());
        assertNull(detail.getStages().get(0).getUpstreamCode());

        // 上游未变（版本等于基线）：不提示
        manifest.stage("S1").setVersion(2);
        workspaceStore.writeManifest(run.getId(), manifest);
        assertFalse(service.getRunDetail(run.getId()).getStages().get(1).isUpstreamChanged());

        // 上游解锁编辑中（AWAITING_CONFIRM）：不提示（重算须先等上游重新确认）
        manifest.stage("S1").setStatus("AWAITING_CONFIRM");
        manifest.stage("S1").setVersion(3);
        workspaceStore.writeManifest(run.getId(), manifest);
        assertFalse(service.getRunDetail(run.getId()).getStages().get(1).isUpstreamChanged());

        // 无基线（重算检测之前的旧 run）：保守不提示
        manifest.stage("S1").setStatus("CONFIRMED");
        manifest.stage("S2").setUpstreamVersion(null);
        workspaceStore.writeManifest(run.getId(), manifest);
        assertFalse(service.getRunDetail(run.getId()).getStages().get(1).isUpstreamChanged());
    }

    /** 把指定阶段摆到生成中（RUNNING + run RUNNING，markStageCompleted 的合法前置）。 */
    private void stageRunning(String runId, String stageCode) {
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.setStatus("RUNNING");
        manifest.setCurrentStage(stageCode);
        manifest.stage(stageCode).setStatus("RUNNING");
        workspaceStore.writeManifest(runId, manifest);
    }

    // ===== 确认门状态推进（tasks 4.1） =====

    @Test
    void 整稿通过置CONFIRMED并推进currentStage() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageAwaiting(run.getId(), "S1", 1);

        service.confirmStage(run.getId(), PipelineStage.S1_IP_ANALYSIS);

        PipelineManifest after = workspaceStore.readManifest(run.getId());
        assertEquals("CONFIRMED", after.stage("S1").getStatus());
        assertEquals(Integer.valueOf(1), after.stage("S1").getConfirmedVersion());
        assertEquals("S2", after.getCurrentStage());
        // run 保持 AWAITING（等待下一阶段被触发，D6 跨天 gate）
        assertEquals("AWAITING", after.getStatus());
        verify(runRepository).updateProgress(run.getId(), "S2", "AWAITING");
    }

    // ===== S4.5 插入语义（story-graph-authoring tasks 2.1） =====

    @Test
    void S4整稿通过后解锁S4点5() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageAwaiting(run.getId(), "S4", 1);

        service.confirmStage(run.getId(), PipelineStage.S4_PLOT_STRUCTURE);

        PipelineManifest after = workspaceStore.readManifest(run.getId());
        // spec「S4 通过后 S4.5 就绪」：currentStage 推进到 S4.5，幕生成编排可进入
        assertEquals("S4.5", after.getCurrentStage());
        assertEquals("CONFIRMED", after.stage("S4").getStatus());
        assertEquals(PipelineStageStatus.NOT_STARTED.name(), after.stage("S4.5").getStatus());
        verify(runRepository).updateProgress(run.getId(), "S4.5", "AWAITING");
    }

    @Test
    void S4点5整稿通过后推进S5() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageAwaiting(run.getId(), "S4.5", 1);

        service.confirmStage(run.getId(), PipelineStage.S4P5_STORY_GRAPH);

        PipelineManifest after = workspaceStore.readManifest(run.getId());
        assertEquals("S5", after.getCurrentStage());
        assertEquals("CONFIRMED", after.stage("S4.5").getStatus());
        assertEquals(Integer.valueOf(1), after.stage("S4.5").getConfirmedVersion());
        verify(runRepository).updateProgress(run.getId(), "S5", "AWAITING");
    }

    @Test
    void S6不走通用确认门() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.confirmStage(run.getId(), PipelineStage.S6_INSTALL));
        assertThat(e.getMessage()).contains("安装端点专用语义");
        verify(runRepository, never()).updateProgress(anyString(), anyString(), anyString());
    }

    @Test
    void 非待确认态整稿通过拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        // S1 仍 NOT_STARTED（未生成过）

        assertThrows(IllegalStateException.class,
                () -> service.confirmStage(run.getId(), PipelineStage.S1_IP_ANALYSIS));
        verify(runRepository, never()).updateProgress(anyString(), anyString(), anyString());
    }

    @Test
    void 回退解锁回待确认态且存档保留() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageAwaiting(run.getId(), "S1", 1);
        service.confirmStage(run.getId(), PipelineStage.S1_IP_ANALYSIS);

        service.unlockStage(run.getId(), PipelineStage.S1_IP_ANALYSIS);

        PipelineManifest after = workspaceStore.readManifest(run.getId());
        assertEquals("AWAITING_CONFIRM", after.stage("S1").getStatus());
        // currentStage 回退本阶段（确认时曾推进 S2）；confirmedVersion 存档保留（历史确认记录不可删除）
        assertEquals("S1", after.getCurrentStage());
        assertEquals(Integer.valueOf(1), after.stage("S1").getConfirmedVersion());
        assertEquals("AWAITING", after.getStatus());
        verify(runRepository).updateProgress(run.getId(), "S1", "AWAITING");
    }

    @Test
    void 非确认态回退解锁拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        // S1 NOT_STARTED，非 CONFIRMED

        assertThrows(IllegalStateException.class,
                () -> service.unlockStage(run.getId(), PipelineStage.S1_IP_ANALYSIS));
        verify(runRepository, never()).updateProgress(anyString(), anyString(), anyString());
    }

    @Test
    void 已解锁阶段再解锁拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageAwaiting(run.getId(), "S1", 1);
        service.confirmStage(run.getId(), PipelineStage.S1_IP_ANALYSIS);

        service.unlockStage(run.getId(), PipelineStage.S1_IP_ANALYSIS);   // 合法解锁

        // 阶段已回 AWAITING_CONFIRM，再解锁拒绝（状态机校验，防重复解锁越权）
        assertThrows(IllegalStateException.class,
                () -> service.unlockStage(run.getId(), PipelineStage.S1_IP_ANALYSIS));
        // 仅合法那次推进过库表（确认 S2 + 解锁回 S1 各一次……确认那次的 updateProgress 在上面 verify 过）
        verify(runRepository, times(2)).updateProgress(anyString(), anyString(), anyString());
    }

    @Test
    void 编辑保存版本递增且锁定态拒绝() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageAwaiting(run.getId(), "S1", 1);

        int v2 = service.recordDraftEdit(run.getId(), PipelineStage.S1_IP_ANALYSIS);
        assertEquals(2, v2);
        assertEquals(2, workspaceStore.readManifest(run.getId()).stage("S1").getVersion());

        // 锁定态拒绝（spec「锁定后不可静默改，须先回退解锁」）
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S1").setStatus("CONFIRMED");
        workspaceStore.writeManifest(run.getId(), manifest);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.recordDraftEdit(run.getId(), PipelineStage.S1_IP_ANALYSIS));
        assertThat(e.getMessage()).contains("须先回退解锁");
    }

    @Test
    void 返工轮记账追加反馈历史() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        stageAwaiting(run.getId(), "S1", 1);

        assertEquals(1, service.recordReworkRound(run.getId(), PipelineStage.S1_IP_ANALYSIS, "人物太扁平"));
        assertEquals(2, service.recordReworkRound(run.getId(), PipelineStage.S1_IP_ANALYSIS, "再加一个茶屋"));

        List<PipelineManifest.ReRound> rounds =
                workspaceStore.readManifest(run.getId()).stage("S1").getRounds();
        assertEquals(2, rounds.size());
        assertEquals("人物太扁平", rounds.get(0).getFeedback());
        assertEquals("再加一个茶屋", rounds.get(1).getFeedback());
        assertEquals(1, rounds.get(0).getRound());
        assertEquals(2, rounds.get(1).getRound());
    }

    @Test
    void 候选状态初始化重置为PENDING() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);

        service.initStageCandidates(run.getId(), PipelineStage.S3_CHARACTERS,
                List.of("人物 1：甲", "人物 2：乙"));

        List<PipelineManifest.CandidateState> candidates =
                workspaceStore.readManifest(run.getId()).stage("S3").getCandidates();
        assertEquals(2, candidates.size());
        assertEquals("candidate-01", candidates.get(0).getId());
        assertEquals("人物 1：甲", candidates.get(0).getTitle());
        assertEquals("PENDING", candidates.get(0).getStatus());
        assertEquals("candidate-02", candidates.get(1).getId());
        assertEquals("PENDING", candidates.get(1).getStatus());
    }

    @Test
    void 候选状态与确认版本manifest序列化往返() {
        PipelineRun run = service.createRun("text", "faithful", "原文内容", null, null);
        PipelineManifest manifest = workspaceStore.readManifest(run.getId());
        manifest.stage("S3").setStatus("AWAITING_CONFIRM");
        manifest.stage("S3").setVersion(2);
        manifest.stage("S3").setConfirmedVersion(2);
        manifest.stage("S3").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "人物 1：甲", "PENDING"),
                new PipelineManifest.CandidateState("candidate-02", "人物 2：乙", "REJECTED")));
        manifest.stage("S3").setRounds(
                List.of(new PipelineManifest.ReRound(1, "反馈文本", "2026-10-20T10:00:00")));
        workspaceStore.writeManifest(run.getId(), manifest);

        PipelineManifest loaded = workspaceStore.readManifest(run.getId());
        assertEquals(Integer.valueOf(2), loaded.stage("S3").getConfirmedVersion());
        List<PipelineManifest.CandidateState> candidates = loaded.stage("S3").getCandidates();
        assertEquals(2, candidates.size());
        assertEquals("candidate-02", candidates.get(1).getId());
        assertEquals("人物 2：乙", candidates.get(1).getTitle());
        assertEquals("REJECTED", candidates.get(1).getStatus());
        // 未写入的 L1 阶段不受影响
        assertEquals(0, loaded.stage("S1").getCandidates().size());
        assertNull(loaded.stage("S1").getConfirmedVersion());
    }

    // ===== 辅助 =====

    /** 把指定阶段推到「生成完成待确认」状态（AWAITING_CONFIRM + version + run AWAITING）。 */
    private void stageAwaiting(String runId, String stageCode, int version) {
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.setStatus("AWAITING");
        manifest.setCurrentStage(stageCode);
        manifest.stage(stageCode).setStatus("AWAITING_CONFIRM");
        manifest.stage(stageCode).setVersion(version);
        workspaceStore.writeManifest(runId, manifest);
    }

    /** 模拟库表行（service 创建返回的 run 即含全部字段，直接用作 findById 的返回）。 */
    private static PipelineRun dbRun(PipelineRun run) {
        return run;
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

    /** 模拟工作区文件树被误删（递归删除 run 目录）。 */
    private void deleteRunDir(String runId) {
        try {
            java.nio.file.Files.walk(tempDir.resolve(runId))
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.delete(p);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    // ===== 版本历史与 diff（tasks 4.6） =====

    /** 摆 S3 待确认门 + 多版本快照（返工轮经真实 recordReworkRound 落 manifest）。 */
    private String runWithVersions(String... snapshots) {
        PipelineRun run = service.createRun("text", "faithful", "原文", null, null);
        String runId = run.getId();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(dbRun(runId, "S3", "AWAITING")));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.setStatus("AWAITING");
        manifest.setCurrentStage("S3");
        manifest.stage("S3").setStatus("AWAITING_CONFIRM");
        workspaceStore.writeManifest(runId, manifest);
        for (int i = 0; i < snapshots.length; i++) {
            workspaceStore.writeVersionSnapshot(
                    runId, PipelineStage.S3_CHARACTERS, i + 1, snapshots[i]);
        }
        service.recordReworkRound(runId, PipelineStage.S3_CHARACTERS, "反馈甲：光秀要更悲情");
        return runId;
    }

    @Test
    void 版本历史返回全部版本与反馈记录() {
        String runId = runWithVersions("v1 全稿", "v2 全稿", "v3 全稿");

        Map<String, Object> result = service.listStageVersions(runId, PipelineStage.S3_CHARACTERS);

        assertEquals("S3", result.get("stage"));
        assertEquals(List.of(1, 2, 3), result.get("versions"));
        // spec「历史可回看」：每轮反馈随版本列表一并返回（rounds 真源在 manifest）
        @SuppressWarnings("unchecked")
        List<PipelineManifest.ReRound> rounds = (List<PipelineManifest.ReRound>) result.get("rounds");
        assertEquals(1, rounds.size());
        assertEquals("反馈甲：光秀要更悲情", rounds.get(0).getFeedback());
    }

    @Test
    void 版本快照全文回看与不存在拒绝() {
        String runId = runWithVersions("v1 全稿", "v2 全稿");

        Map<String, Object> result = service.readStageVersion(runId, PipelineStage.S3_CHARACTERS, 2);
        assertEquals("S3", result.get("stage"));
        assertEquals(2, result.get("version"));
        assertEquals("v2 全稿", result.get("content"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.readStageVersion(runId, PipelineStage.S3_CHARACTERS, 9));
        assertThat(e.getMessage()).contains("版本快照不存在").contains("v9");
    }

    @Test
    void 相邻版本diff缺省对比最新两版() {
        String runId = runWithVersions(
                "## 人物 1：甲\n性格：沉稳",
                "## 人物 1：甲\n性格：狡黠",
                "## 人物 1：甲\n性格：狡黠\n结局：战死");

        Map<String, Object> result = service.diffStageVersions(
                runId, PipelineStage.S3_CHARACTERS, null, null);

        assertEquals("S3", result.get("stage"));
        // 缺省 to=最新（v3）、from=to-1（v2）
        assertEquals(2, result.get("from"));
        assertEquals(3, result.get("to"));
        // 行级 diff：未变更行 context、新增行 added（D5 核对未要求改动的部分）
        @SuppressWarnings("unchecked")
        List<StageVersionDiffer.DiffLine> lines =
                (List<StageVersionDiffer.DiffLine>) result.get("lines");
        assertThat(lines).hasSize(3);
        assertEquals("context", lines.get(0).type());
        assertEquals("added", lines.get(2).type());
        assertEquals("结局：战死", lines.get(2).text());
    }

    @Test
    void 显式from与to对比指定版本对() {
        String runId = runWithVersions(
                "## 人物 1：甲\n性格：沉稳",
                "## 人物 1：甲\n性格：狡黠",
                "## 人物 1：甲\n性格：狡黠\n结局：战死");

        Map<String, Object> result = service.diffStageVersions(
                runId, PipelineStage.S3_CHARACTERS, 1, 2);

        assertEquals(1, result.get("from"));
        assertEquals(2, result.get("to"));
    }

    @Test
    void 不足两版diff拒绝() {
        String runId = runWithVersions("仅有 v1");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.diffStageVersions(runId, PipelineStage.S3_CHARACTERS, null, null));
        assertThat(e.getMessage()).contains("暂无相邻版本");
    }

    @Test
    void from不小于to拒绝() {
        String runId = runWithVersions("v1", "v2");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.diffStageVersions(runId, PipelineStage.S3_CHARACTERS, 2, 2));
        assertThat(e.getMessage()).contains("起始版本须小于目标版本");
    }

    @Test
    void 引用不存在版本diff拒绝() {
        String runId = runWithVersions("v1", "v2");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.diffStageVersions(runId, PipelineStage.S3_CHARACTERS, 1, 9));
        assertThat(e.getMessage()).contains("版本快照不存在").contains("v9");
    }

    @Test
    void run不存在版本查询拒绝() {
        when(runRepository.findById("run-x")).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.listStageVersions("run-x", PipelineStage.S3_CHARACTERS));
        assertThrows(NoSuchElementException.class,
                () -> service.readStageVersion("run-x", PipelineStage.S3_CHARACTERS, 1));
        assertThrows(NoSuchElementException.class,
                () -> service.diffStageVersions("run-x", PipelineStage.S3_CHARACTERS, 1, 2));
    }

    @Test
    void 锁定态版本历史仍可回看() {
        // spec「原 confirmed 版本仍可回看」：只读端点不受锁定态限制（存档不可删除）
        String runId = runWithVersions("v1 确认稿", "v2 新稿");
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S3").setStatus("CONFIRMED");
        manifest.stage("S3").setConfirmedVersion(1);
        workspaceStore.writeManifest(runId, manifest);

        Map<String, Object> result = service.readStageVersion(runId, PipelineStage.S3_CHARACTERS, 1);
        assertEquals("v1 确认稿", result.get("content"));
        assertEquals(List.of(1, 2),
                service.listStageVersions(runId, PipelineStage.S3_CHARACTERS).get("versions"));
    }

    // ===== S3 批次记账（planner-s3-batch-generate tasks 2.1/2.4，spec「批次记账与断点续跑」「候选追加不重置」） =====

    @Test
    void 批次计划记账index全局递增批行append为PENDING() {
        String runId = runAtS3Awaiting();

        List<PipelineManifest.BatchState> first = service.recordBatchPlan(
                runId, PipelineStage.S3_CHARACTERS, List.of(List.of("甲", "乙"), List.of("丙")));
        assertThat(first).extracting(PipelineManifest.BatchState::getIndex,
                        PipelineManifest.BatchState::getNames, PipelineManifest.BatchState::getStatus)
                .containsExactly(
                        tuple(1, List.of("甲", "乙"), "PENDING"),
                        tuple(2, List.of("丙"), "PENDING"));

        // 断点续跑第二轮：批 index 从既有 max+1 全局递增（幂等键 = 批内人名清单）
        List<PipelineManifest.BatchState> second = service.recordBatchPlan(
                runId, PipelineStage.S3_CHARACTERS, List.of(List.of("丁")));
        assertThat(second.get(0).getIndex()).isEqualTo(3);

        // 真实序列化往返：批行持久化在 manifest（batches 键），断点重读可对账
        assertThat(workspaceStore.readManifest(runId).stage("S3").getBatches()).hasSize(3);
    }

    @Test
    void 批状态流转置DONE与FAILED未知序号忽略不抛() {
        String runId = runAtS3Awaiting();
        service.recordBatchPlan(runId, PipelineStage.S3_CHARACTERS,
                List.of(List.of("甲"), List.of("乙")));

        service.setBatchStatus(runId, PipelineStage.S3_CHARACTERS, 2, "DONE");
        service.setBatchStatus(runId, PipelineStage.S3_CHARACTERS, 1, "FAILED");

        assertThat(workspaceStore.readManifest(runId).stage("S3").getBatches())
                .extracting(PipelineManifest.BatchState::getIndex,
                        PipelineManifest.BatchState::getStatus)
                .containsExactly(tuple(1, "FAILED"), tuple(2, "DONE"));
        // 未知批序号防御忽略（失败标记路径不能因记账缺失丢原始错误）
        assertDoesNotThrow(() ->
                service.setBatchStatus(runId, PipelineStage.S3_CHARACTERS, 99, "DONE"));
    }

    @Test
    void 候选追加记账append新卡PENDING既有逐卡状态不动() {
        String runId = runAtS3Awaiting();
        // 前次编排已产出甲乙（甲已逐卡确认、乙待确认）
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.stage("S3").getCandidates().addAll(List.of(
                new PipelineManifest.CandidateState("candidate-01", "人物 1：甲", "CONFIRMED"),
                new PipelineManifest.CandidateState("candidate-02", "人物 2：乙", "PENDING")));
        workspaceStore.writeManifest(runId, manifest);

        service.appendStageCandidates(runId, PipelineStage.S3_CHARACTERS,
                List.of("candidate-03", "candidate-04"),
                List.of("人物 3：丙", "人物 4：丁"));

        // spec「追加不重置」：既有甲 CONFIRMED / 乙 PENDING 原样，新卡丙丁 PENDING
        assertThat(workspaceStore.readManifest(runId).stage("S3").getCandidates())
                .extracting(PipelineManifest.CandidateState::getId,
                        PipelineManifest.CandidateState::getTitle,
                        PipelineManifest.CandidateState::getStatus)
                .containsExactly(
                        tuple("candidate-01", "人物 1：甲", "CONFIRMED"),
                        tuple("candidate-02", "人物 2：乙", "PENDING"),
                        tuple("candidate-03", "人物 3：丙", "PENDING"),
                        tuple("candidate-04", "人物 4：丁", "PENDING"));
    }

    @Test
    void 旧run无batches键读取按空记账从一起() {
        // 旧 run / 单流路径 manifest 无 batches 键：fromMap 容错读空，
        // recordBatchPlan 从 index 1 起零冲突（存量 run 补批零迁移）
        String runId = runAtS3Awaiting();
        assertThat(workspaceStore.readManifest(runId).stage("S3").getBatches()).isEmpty();

        List<PipelineManifest.BatchState> planned = service.recordBatchPlan(
                runId, PipelineStage.S3_CHARACTERS, List.of(List.of("甲")));
        assertThat(planned.get(0).getIndex()).isEqualTo(1);
    }

    /** S3 待确认门夹具（真实创建 + 摆到 AWAITING/S3 AWAITING_CONFIRM v1）。 */
    private String runAtS3Awaiting() {
        PipelineRun run = service.createRun("text", "faithful", "原文", null, null);
        String runId = run.getId();
        when(runRepository.findById(runId))
                .thenReturn(Optional.of(dbRun(runId, "S3", "AWAITING")));
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        manifest.setStatus("AWAITING");
        manifest.setCurrentStage("S3");
        manifest.stage("S3").setStatus("AWAITING_CONFIRM");
        manifest.stage("S3").setVersion(1);
        workspaceStore.writeManifest(runId, manifest);
        return runId;
    }
}
