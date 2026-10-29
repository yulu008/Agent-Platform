package com.luyu.agent.rpg.story.service;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.story.engine.GuardEvaluator;
import com.luyu.agent.rpg.story.engine.StateSchemaValidator;
import com.luyu.agent.rpg.story.engine.StoryGraphFinalizeReport;
import com.luyu.agent.rpg.story.engine.StoryGraphValidator;
import com.luyu.agent.rpg.story.engine.VariantCoverageValidator;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.RpgStoryRunRepository;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工坊故事图服务测试（story-graph-authoring tasks 3.1 / story-graph-model
 * spec「挂 world 模板层与多租户隔离」+ generation spec「工坊骨架编辑与整稿通过」）。
 * <p>
 * repository 全 mock（租户过滤已由 RpgStoryGraphRepositoryTest 钉住），校验链
 * 走真组件（与 planner S4.5 整稿同一实例语义——spec「手工建图走同一校验」）。
 * 钉住：建图三档校验与词汇表语法即检、保存即时校验拒答不落库（spec
 * 「编辑视图修改变体并即时校验」）、空图草稿免词汇表宽容、整稿通过全图
 * 校验链（修复落库 / 跨幕对接断裂 409 报告逐条定位）、删图运行副本守卫。
 */
class StoryGraphWorkshopServiceTest {

    private static final String WORLD_ID = "world-1";
    private static final String GRAPH_ID = "sg-1";
    private static final String TID = "tenant-a";

    private static final String SCHEMA = "{\"trust_信长\": \"number\"}";

    /** 合法两幕图（含拼接边 e1_09 与死端修复靶——to 悬空 n1_99） */
    private static final String VALID_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "潜入与结识",
               "convergeTo": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 30}}],
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                 {"id": "n1_07", "type": "anchor", "beat": "拿到密信"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_07", "label": "脱身"},
                 {"id": "e1_09", "from": "n1_07", "to": "n2_01", "label": "交接"}]},
              {"id": "act2", "theme": "终局对决",
               "convergeTo": [{"orbit": "终局", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
               "nodes": [
                 {"id": "n2_01", "type": "scene", "beat": "清洲密议"},
                 {"id": "n2_05", "type": "ending", "beat": "终局"}],
               "edges": [
                 {"id": "e2_01", "from": "n2_01", "to": "n2_05", "label": "摊牌"}]}
            ]
            """;

    /** 断边修复靶：e1_01 to 悬空 n1_99（改指最近同幕锚点 n1_07） */
    private static final String DANGLING_GRAPH_JSON = VALID_GRAPH_JSON
            .replace("\"to\": \"n1_07\", \"label\": \"脱身\"", "\"to\": \"n1_99\", \"label\": \"脱身\"");

    /** 跨幕对接断裂靶：去掉拼接边 e1_09（幕 1 锚点无出口边 → 拒绝级 error；
     * 匹配段须吃掉整个 e1_09 对象含其闭合 }，剩余 ]}, 正确闭合 edges/act——
     * text block 剥离 12 空格后 e1_09 行 5 空格缩进） */
    private static final String BROKEN_DOCK_GRAPH_JSON = VALID_GRAPH_JSON
            .replace("},\n     {\"id\": \"e1_09\", \"from\": \"n1_07\", \"to\": \"n2_01\", \"label\": \"交接\"}", "}");

    private RpgStoryGraphRepository graphRepo;
    private RpgStoryRunRepository runRepo;
    private RpgWorldSettingRepository worldRepo;
    private StoryGraphWorkshopService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TID);
        graphRepo = mock(RpgStoryGraphRepository.class);
        runRepo = mock(RpgStoryRunRepository.class);
        worldRepo = mock(RpgWorldSettingRepository.class);
        when(worldRepo.findById(WORLD_ID)).thenReturn(new WorldSetting());
        StateSchemaValidator schemaValidator = new StateSchemaValidator(new GuardEvaluator());
        service = new StoryGraphWorkshopService(graphRepo, runRepo, worldRepo,
                schemaValidator, new VariantCoverageValidator(schemaValidator), new StoryGraphValidator());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ===== 建图 =====

    @Test
    void 建图默认bounded生成UUID空幕容器() {
        StoryGraph graph = service.create(WORLD_ID, "夜雾纪行", null, null, SCHEMA);

        assertThat(graph.getId()).isNotBlank();
        assertThat(graph.getImproviseMode()).isEqualTo(StoryGraph.IMPROVISE_BOUNDED);
        assertThat(graph.getGraphJson()).isEqualTo("[]");
        assertThat(graph.getWorldId()).isEqualTo(WORLD_ID);
        verify(graphRepo).save(graph);
    }

    @Test
    void 建图名称空拒绝() {
        assertThatThrownBy(() -> service.create(WORLD_ID, " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称不能为空");
        verify(graphRepo, never()).save(any());
    }

    @Test
    void 建图非法即兴模式拒绝() {
        assertThatThrownBy(() -> service.create(WORLD_ID, "夜雾纪行", "chaos", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("off/bounded/free");
        verify(graphRepo, never()).save(any());
    }

    @Test
    void 建图world不存在404语义() {
        assertThatThrownBy(() -> service.create("world-x", "夜雾纪行", null, null, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("世界不存在");
    }

    @Test
    void 建图词汇表语法非法拒绝() {
        assertThatThrownBy(() -> service.create(WORLD_ID, "夜雾纪行", null, null, "{\"trust\": \"unknow_type\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state_schema");
        verify(graphRepo, never()).save(any());
    }
    
    @Test
    void 建图即兴深度上限透传落库() {
        // spec「锚点引力与即兴深度上限」：作品级上限作者可调，建图即落库（null = 引擎侧缺省 5）
        StoryGraph graph = service.create(WORLD_ID, "夜雾纪行", null, 3, SCHEMA);
    
        assertThat(graph.getImproviseDepthLimit()).isEqualTo(3);
        verify(graphRepo).save(graph);
    }
    
    @Test
    void 建图即兴深度上限非正拒绝() {
        assertThatThrownBy(() -> service.create(WORLD_ID, "夜雾纪行", null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("即兴深度上限必须是正整数");
        verify(graphRepo, never()).save(any());
    }
    
    // ===== 保存 + 即时校验（spec「编辑视图修改变体并即时校验」） =====

    @Test
    void 保存合法图落库返回更新图() {
        StoryGraph graph = storedGraph();

        StoryGraph saved = service.save(GRAPH_ID, "夜雾纪行·改", null, null, null, VALID_GRAPH_JSON);

        assertThat(saved.getName()).isEqualTo("夜雾纪行·改");
        assertThat(saved.getGraphJson()).isEqualTo(VALID_GRAPH_JSON);
        verify(graphRepo).update(graph);
    }

    @Test
    void 保存即兴深度上限透传更新() {
        StoryGraph graph = storedGraph();

        StoryGraph saved = service.save(GRAPH_ID, null, null, 2, null, VALID_GRAPH_JSON);

        assertThat(saved).isSameAs(graph);
        assertThat(graph.getImproviseDepthLimit()).isEqualTo(2);
        verify(graphRepo).update(graph);
    }

    @Test
    void 保存即兴深度上限非正拒绝() {
        storedGraph();

        assertThatThrownBy(() -> service.save(GRAPH_ID, null, null, -1, null, VALID_GRAPH_JSON))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("即兴深度上限必须是正整数");
        verify(graphRepo, never()).update(any());
    }

    @Test
    void 保存变体guard语法错误不落库() {
        // spec Scenario「编辑视图修改变体并即时校验」：guard 非法（op 非白名单），
        // 保存时即时报出该变体的谓词语法错误，不落库
        String badVariant = VALID_GRAPH_JSON.replace(
                "{\"id\": \"n1_07\", \"type\": \"anchor\", \"beat\": \"拿到密信\"}",
                "{\"id\": \"n1_07\", \"type\": \"anchor\", \"beat\": \"拿到密信\","
                        + " \"variants\": [{\"guard\": {\"key\": \"trust_信长\", \"op\": \"!=\", \"value\": 30},"
                        + " \"beatVariant\": \"密信是伪造的\"}]}");
        storedGraph();

        assertThatThrownBy(() -> service.save(GRAPH_ID, null, null, null, null, badVariant))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("保存校验未通过")
                .hasMessageContaining("n1_07")
                .hasMessageContaining("variant");
        verify(graphRepo, never()).update(any());
    }

    @Test
    void 保存guard引用词汇表外键拒绝() {
        String outOfSchema = VALID_GRAPH_JSON.replace("\"key\": \"trust_信长\", \"op\": \">=\", \"value\": 30",
                "\"key\": \"loyalty_家康\", \"op\": \">=\", \"value\": 30");
        storedGraph();

        assertThatThrownBy(() -> service.save(GRAPH_ID, null, null, null, null, outOfSchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("词汇表外键")
                .hasMessageContaining("loyalty_家康");
        verify(graphRepo, never()).update(any());
    }

    @Test
    void 保存空图草稿免词汇表宽容() {
        // 建图初期空 acts 无引用：不强制词汇表（整稿链兜底）
        StoryGraph graph = new StoryGraph(GRAPH_ID, TID, WORLD_ID, "夜雾纪行");
        graph.setGraphJson("[]");
        when(graphRepo.findById(GRAPH_ID)).thenReturn(graph);

        StoryGraph saved = service.save(GRAPH_ID, null, null, null, null, "[]");

        verify(graphRepo).update(graph);
        assertThat(saved.getGraphJson()).isEqualTo("[]");
    }

    @Test
    void 保存坏JSON快速失败400语义() {
        storedGraph();

        assertThatThrownBy(() -> service.save(GRAPH_ID, null, null, null, null, "[{\"id\": "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graph_json 解析失败");
        verify(graphRepo, never()).update(any());
    }

    // ===== 整稿通过（全图校验链） =====

    @Test
    void 整稿通过断边确定性修复落库() {
        // e1_01 to 悬空 n1_99 → 改指最近同幕锚点 n1_07（repair 流水 + 修复后图落库）
        StoryGraph graph = storedGraph();
        graph.setGraphJson(DANGLING_GRAPH_JSON);

        StoryGraphFinalizeReport report = service.finalizeGraph(GRAPH_ID);

        assertThat(report.ok()).isTrue();
        assertThat(report.getRepairs()).anySatisfy(r -> assertThat(r)
                .contains("e1_01").contains("n1_99").contains("n1_07"));
        // 修复后图落库（to 已改指锚点）
        assertThat(report.getGraph()).isSameAs(graph);
        assertThat(graph.getGraphJson()).contains("\"to\":\"n1_07\"");
        verify(graphRepo).update(graph);
    }

    @Test
    void 整稿通过合法图零修复落库() {
        StoryGraph graph = storedGraph();

        StoryGraphFinalizeReport report = service.finalizeGraph(GRAPH_ID);

        assertThat(report.ok()).isTrue();
        assertThat(report.getRepairs()).isEmpty();
        assertThat(report.getErrors()).isEmpty();
        verify(graphRepo).update(graph);
    }

    @Test
    void 整稿通过跨幕对接断裂拒绝不落库() {
        // spec Scenario「手工建图走同一校验」：失败项逐条报出幕/节点/边 ID
        // （同一校验器组件与 planner 产物一致）
        StoryGraph graph = storedGraph();
        graph.setGraphJson(BROKEN_DOCK_GRAPH_JSON);

        StoryGraphFinalizeReport report = service.finalizeGraph(GRAPH_ID);

        assertThat(report.ok()).isFalse();
        assertThat(report.getErrors()).anySatisfy(e -> assertThat(e)
                .contains("act1").contains("n1_07").contains("无出口边"));
        // 拒绝不落库：图保持编辑态
        assertThat(report.getGraph()).isNull();
        assertThat(graph.getGraphJson()).isEqualTo(BROKEN_DOCK_GRAPH_JSON);
        verify(graphRepo, never()).update(any());
    }

    @Test
    void 整稿通过词汇表缺失拒绝() {
        StoryGraph graph = new StoryGraph(GRAPH_ID, TID, WORLD_ID, "夜雾纪行");
        graph.setGraphJson(VALID_GRAPH_JSON);
        when(graphRepo.findById(GRAPH_ID)).thenReturn(graph);

        StoryGraphFinalizeReport report = service.finalizeGraph(GRAPH_ID);

        assertThat(report.ok()).isFalse();
        assertThat(report.getErrors()).anySatisfy(e -> assertThat(e).contains("词汇表"));
        verify(graphRepo, never()).update(any());
    }

    // ===== 删除（运行副本守卫） =====

    @Test
    void 删图有运行副本拒绝() {
        storedGraph();
        when(runRepo.existsByGraphId(GRAPH_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(GRAPH_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("运行副本");
        verify(graphRepo, never()).deleteById(GRAPH_ID);
    }

    @Test
    void 删图成功与不存在404语义() {
        storedGraph();
        service.delete(GRAPH_ID);
        verify(graphRepo).deleteById(GRAPH_ID);

        when(graphRepo.findById(GRAPH_ID)).thenReturn(null);   // 已删：不存在
        assertThatThrownBy(() -> service.delete(GRAPH_ID))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ===== 辅助 =====

    private StoryGraph storedGraph() {
        StoryGraph graph = new StoryGraph(GRAPH_ID, TID, WORLD_ID, "夜雾纪行");
        graph.setImproviseMode(StoryGraph.IMPROVISE_BOUNDED);
        graph.setStateSchema(SCHEMA);
        graph.setGraphJson(VALID_GRAPH_JSON);
        when(graphRepo.findById(GRAPH_ID)).thenReturn(graph);
        return graph;
    }
}
