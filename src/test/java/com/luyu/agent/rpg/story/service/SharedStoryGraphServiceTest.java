package com.luyu.agent.rpg.story.service;

import java.time.Instant;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.story.engine.GuardEvaluator;
import com.luyu.agent.rpg.story.engine.StateSchemaValidator;
import com.luyu.agent.rpg.story.engine.StoryGraphValidator;
import com.luyu.agent.rpg.story.engine.VariantCoverageValidator;
import com.luyu.agent.rpg.story.model.SharedStoryGraph;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.SharedStoryGraphRepository;
import com.luyu.agent.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共享故事图包服务测试（story-graph-authoring tasks 3.3 / model spec「发布快照
 * 与导入副本对齐世界书三层」）。
 * <p>
 * repository 全 mock（租户过滤/读写不对称 SQL 已由共享包 repo 层测试惯例承担），
 * 校验链走真组件（与 {@code finalizeGraph} 同一实例——spec「发布门槛 = 整稿通过」）。
 * 钉住四组语义：
 * <ol>
 *   <li><b>发布快照</b>：三件套 = 发布时点字符串级复制（发布后源图再改不渗入，
 *       spec「发布后编辑不影响快照」）；发布者锚点/出处信息/节点数落库；
 *       未过整稿链 ISE 零写入（坏图不进共享库）、空图/图不可见/包名空拒绝；</li>
 *   <li><b>覆盖更新</b>：同发布者同源图 → refreshPack 整行重写、overwrite=true、
 *       不插新包行；覆盖性警告不阻断（对齐 finalize 语义）；</li>
 *   <li><b>导入物化</b>：新 id、tenant_id 归导入方、world_id 重绑、快照三件套
 *       复制；preset- 前缀 / 目标世界无效 / 包不在架 / 坏快照防御拒绝；</li>
 *   <li><b>撤回</b>：发布者锚点受影响行数决定 true/false（404 语义）。</li>
 * </ol>
 */
class SharedStoryGraphServiceTest {

    private static final String WORLD_ID = "world-1";
    private static final String GRAPH_ID = "sg-1";
    private static final String TARGET_WORLD_ID = "world-2";
    private static final String PACK_ID = "pack-1";

    private static final String SCHEMA = "{\"trust_信长\": \"number\"}";

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 合法两幕图（与 StoryGraphWorkshopServiceTest 同构：含拼接边 e1_09，4 节点） */
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

    /** 跨幕对接断裂靶：去掉拼接边 e1_09 → 幕 1 锚点无出口边（拒绝级 error；
     * 匹配段须吃掉整个 e1_09 对象含其闭合 }，text block 剥离 12 空格后
     * e1_09 行 5 空格缩进——同 StoryGraphWorkshopServiceTest） */
    private static final String BROKEN_DOCK_GRAPH_JSON = VALID_GRAPH_JSON
            .replace("},\n     {\"id\": \"e1_09\", \"from\": \"n1_07\", \"to\": \"n2_01\", \"label\": \"交接\"}", "}");

    /** 覆盖性警告靶：n1_01 带变体但幕 1 未授权（warning 不阻断发布） */
    private static final String UNAUTHORIZED_VARIANT_GRAPH_JSON = VALID_GRAPH_JSON
            .replace("\"beat\": \"夜探织田邸\"}",
                    "\"beat\": \"夜探织田邸\", \"variants\": ["
                            + "{\"guard\": {\"key\": \"trust_信长\", \"op\": \">=\", \"value\": 50},"
                            + " \"beatVariant\": \"高信任变体\"}]}");

    private SharedStoryGraphRepository sharedRepo;
    private RpgStoryGraphRepository graphRepo;
    private RpgWorldSettingRepository worldRepo;
    private SharedStoryGraphService service;

    @BeforeEach
    void setUp() {
        sharedRepo = mock(SharedStoryGraphRepository.class);
        graphRepo = mock(RpgStoryGraphRepository.class);
        worldRepo = mock(RpgWorldSettingRepository.class);
        StateSchemaValidator schemaValidator = new StateSchemaValidator(new GuardEvaluator());
        service = new SharedStoryGraphService(sharedRepo, graphRepo, worldRepo,
                schemaValidator, new VariantCoverageValidator(schemaValidator), new StoryGraphValidator());
        TenantContext.set("u-a", "t-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 1. 发布快照 ====================

    @Test
    void publish_快照三件套取发布时点且独立于源图() {
        StoryGraph graph = validGraph();
        stubPublish(graph, null);
        when(sharedRepo.findPackByPublisherAndGraph("t-a", GRAPH_ID)).thenReturn(null);

        SharedStoryGraphService.PublishResult result = service.publish(GRAPH_ID, "包一", "简介");

        assertThat(result.overwrite()).isFalse();
        assertThat(result.nodeCount()).isEqualTo(4);
        ArgumentCaptor<SharedStoryGraph> cap = ArgumentCaptor.forClass(SharedStoryGraph.class);
        verify(sharedRepo).insertPack(cap.capture());
        JsonNode snapshot = readTree(cap.getValue().getSnapshotJson());
        assertThat(snapshot.path("improviseMode").asText()).isEqualTo(StoryGraph.IMPROVISE_FREE);
        assertThat(snapshot.path("stateSchema").asText()).isEqualTo(SCHEMA);
        assertThat(snapshot.path("graphJson").asText()).contains("夜探织田邸");

        // 发布后源图再改：快照是发布时点字符串级复制，不渗入（spec「发布后编辑不影响快照」）
        graph.setGraphJson("[]");
        graph.setStateSchema(null);
        JsonNode again = readTree(cap.getValue().getSnapshotJson());
        assertThat(again.path("graphJson").asText()).contains("夜探织田邸");
        assertThat(again.path("stateSchema").asText()).isEqualTo(SCHEMA);
    }

    @Test
    void publish_新包落发布者锚点与出处信息() {
        stubPublish(validGraph(), null);
        when(sharedRepo.findPackByPublisherAndGraph("t-a", GRAPH_ID)).thenReturn(null);

        service.publish(GRAPH_ID, "包一", "简介");

        ArgumentCaptor<SharedStoryGraph> cap = ArgumentCaptor.forClass(SharedStoryGraph.class);
        verify(sharedRepo).insertPack(cap.capture());
        SharedStoryGraph pack = cap.getValue();
        assertThat(pack.getId()).isNotBlank();
        assertThat(pack.getName()).isEqualTo("包一");
        assertThat(pack.getBlurb()).isEqualTo("简介");
        assertThat(pack.getPublisherTenantId()).isEqualTo("t-a");
        assertThat(pack.getPublisherUserId()).isEqualTo("u-a");
        assertThat(pack.getOriginWorldId()).isEqualTo(WORLD_ID);
        assertThat(pack.getOriginGraphId()).isEqualTo(GRAPH_ID);
        assertThat(pack.getOriginWorldName()).isEqualTo("夜雾测试世界");
        assertThat(pack.getSnapshotVersion()).isEqualTo(1);
        assertThat(pack.getNodeCount()).isEqualTo(4);
    }

    @Test
    void publish_未过整稿链拒绝且零写入() {
        StoryGraph graph = validGraph();
        graph.setGraphJson(BROKEN_DOCK_GRAPH_JSON);
        when(graphRepo.findById(GRAPH_ID)).thenReturn(graph);
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));

        assertThatThrownBy(() -> service.publish(GRAPH_ID, "包一", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未通过整稿校验")
                .hasMessageContaining("n1_07");
        verify(sharedRepo, never()).insertPack(any());
        verify(sharedRepo, never()).refreshPack(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void publish_空图拒绝() {
        StoryGraph graph = validGraph();
        graph.setGraphJson("[]");
        when(graphRepo.findById(GRAPH_ID)).thenReturn(graph);

        assertThatThrownBy(() -> service.publish(GRAPH_ID, "包一", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空图不能发布");
        verify(sharedRepo, never()).insertPack(any());
    }

    @Test
    void publish_图不存在或跨租户不可见拒绝() {
        when(graphRepo.findById("sg-x")).thenReturn(null);

        // 跨租户 id 经 repo 租户过滤天然不可见 → 统一「不存在或不属于」不泄露存在性
        assertThatThrownBy(() -> service.publish("sg-x", "包一", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不属于当前租户");
    }

    @Test
    void publish_包名空拒绝() {
        assertThatThrownBy(() -> service.publish(GRAPH_ID, "  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("包名不能为空");
    }

    @Test
    void publish_世界查不到时出处世界名留空不阻塞() {
        // 无 FK 设计：发布方世界被删（跨租户查不到）不阻塞共享库写
        stubPublish(validGraph(), null);
        when(worldRepo.findById(WORLD_ID)).thenReturn(null);
        when(sharedRepo.findPackByPublisherAndGraph("t-a", GRAPH_ID)).thenReturn(null);

        service.publish(GRAPH_ID, "包一", null);

        ArgumentCaptor<SharedStoryGraph> cap = ArgumentCaptor.forClass(SharedStoryGraph.class);
        verify(sharedRepo).insertPack(cap.capture());
        assertThat(cap.getValue().getOriginWorldName()).isNull();
    }

    // ==================== 2. 覆盖更新 ====================

    @Test
    void publish_同发布者同源图覆盖更新走refreshPack() {
        stubPublish(validGraph(), null);
        SharedStoryGraph existing = new SharedStoryGraph();
        existing.setId("pack-9");
        existing.setPublisherTenantId("t-a");
        existing.setOriginGraphId(GRAPH_ID);
        when(sharedRepo.findPackByPublisherAndGraph("t-a", GRAPH_ID)).thenReturn(existing);

        SharedStoryGraphService.PublishResult result = service.publish(GRAPH_ID, "包名新", "简介新");

        assertThat(result.packId()).isEqualTo("pack-9");
        assertThat(result.overwrite()).isTrue();
        assertThat(result.nodeCount()).isEqualTo(4);
        // 快照整体重写为新发布时点（含三件套），发布者锚点随行
        verify(sharedRepo).refreshPack(eq("pack-9"), eq("包名新"), eq("简介新"),
                contains("\"improviseMode\""), eq(4), eq("t-a"));
        verify(sharedRepo, never()).insertPack(any());
    }

    @Test
    void publish_覆盖性警告不阻断发布() {
        StoryGraph graph = validGraph();
        graph.setGraphJson(UNAUTHORIZED_VARIANT_GRAPH_JSON);
        stubPublish(graph, null);
        when(sharedRepo.findPackByPublisherAndGraph("t-a", GRAPH_ID)).thenReturn(null);

        // 幕未授权显式变体 = warning 级（即兴兜底始终可用），发布照常成功
        SharedStoryGraphService.PublishResult result = service.publish(GRAPH_ID, "包一", null);

        assertThat(result.nodeCount()).isEqualTo(4);
        verify(sharedRepo).insertPack(any());
    }

    // ==================== 3. 导入物化 ====================

    @Test
    void import_物化副本新id重绑世界三件套复制() {
        SharedStoryGraph pack = pack();
        when(sharedRepo.findVisibleById(PACK_ID)).thenReturn(pack);
        when(worldRepo.findById(TARGET_WORLD_ID)).thenReturn(world(TARGET_WORLD_ID));

        SharedStoryGraphService.ImportResult result = service.importGraph(PACK_ID, TARGET_WORLD_ID);

        assertThat(result.name()).isEqualTo("夜雾纪行");
        assertThat(result.targetWorldId()).isEqualTo(TARGET_WORLD_ID);
        ArgumentCaptor<StoryGraph> cap = ArgumentCaptor.forClass(StoryGraph.class);
        verify(graphRepo).save(cap.capture());
        StoryGraph imported = cap.getValue();
        assertThat(imported.getId()).isNotBlank().isNotEqualTo(GRAPH_ID); // 新行 id，非源图 id
        assertThat(imported.getTenantId()).isEqualTo("t-a");              // 归导入方
        assertThat(imported.getWorldId()).isEqualTo(TARGET_WORLD_ID);     // 重绑目标世界
        assertThat(imported.getName()).isEqualTo("夜雾纪行");
        assertThat(imported.getImproviseMode()).isEqualTo(StoryGraph.IMPROVISE_FREE);
        assertThat(imported.getStateSchema()).isEqualTo(SCHEMA);
        assertThat(imported.getGraphJson()).isEqualTo(VALID_GRAPH_JSON);

        // 副本是独立行对象：包后续覆盖/撤回改的是快照，已物化副本不受影响
        pack.setSnapshotJson("{\"improviseMode\":\"off\"}");
        assertThat(imported.getGraphJson()).isEqualTo(VALID_GRAPH_JSON);
    }

    @Test
    void import_包不存在或已下架404() {
        when(sharedRepo.findVisibleById("pack-x")).thenReturn(null);

        assertThatThrownBy(() -> service.importGraph("pack-x", TARGET_WORLD_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("不存在或已下架");
        verify(graphRepo, never()).save(any());
    }

    @Test
    void import_preset目标世界拒绝() {
        when(sharedRepo.findVisibleById(PACK_ID)).thenReturn(pack());

        assertThatThrownBy(() -> service.importGraph(PACK_ID, "preset-builtin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("物化预设世界");
        verify(graphRepo, never()).save(any());
    }

    @Test
    void import_目标世界无效拒绝() {
        when(sharedRepo.findVisibleById(PACK_ID)).thenReturn(pack());
        when(worldRepo.findById("world-x")).thenReturn(null);

        assertThatThrownBy(() -> service.importGraph(PACK_ID, "world-x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标世界不存在或不属于当前租户");
        verify(graphRepo, never()).save(any());
    }

    @Test
    void import_坏快照graphJson非法防御() {
        SharedStoryGraph pack = pack();
        ObjectNode broken = mapper.createObjectNode();
        broken.put("improviseMode", "free");
        broken.put("graphJson", "{非法JSON");
        pack.setSnapshotJson(broken.toString());
        when(sharedRepo.findVisibleById(PACK_ID)).thenReturn(pack);
        when(worldRepo.findById(TARGET_WORLD_ID)).thenReturn(world(TARGET_WORLD_ID));

        assertThatThrownBy(() -> service.importGraph(PACK_ID, TARGET_WORLD_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graph_json 非法");
        verify(graphRepo, never()).save(any());
    }

    @Test
    void publish_快照携带即兴深度上限() {
        // spec「锚点引力与即兴深度上限」：上限随三件套快照发布传播
        StoryGraph graph = validGraph();
        graph.setImproviseDepthLimit(8);
        stubPublish(graph, null);
        when(sharedRepo.findPackByPublisherAndGraph("t-a", GRAPH_ID)).thenReturn(null);

        service.publish(GRAPH_ID, "包一", null);

        ArgumentCaptor<SharedStoryGraph> cap = ArgumentCaptor.forClass(SharedStoryGraph.class);
        verify(sharedRepo).insertPack(cap.capture());
        assertThat(readTree(cap.getValue().getSnapshotJson()).path("improviseDepthLimit").asInt())
                .isEqualTo(8);
    }

    @Test
    void import_即兴深度上限随快照传播_旧快照缺省容错() {
        // 新快照：携带深度上限 → 导入副本透传
        SharedStoryGraph pack = pack();
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("improviseMode", StoryGraph.IMPROVISE_FREE);
        snapshot.put("improviseDepthLimit", 3);
        snapshot.put("stateSchema", SCHEMA);
        snapshot.put("graphJson", VALID_GRAPH_JSON);
        pack.setSnapshotJson(snapshot.toString());
        when(sharedRepo.findVisibleById(PACK_ID)).thenReturn(pack);
        when(worldRepo.findById(TARGET_WORLD_ID)).thenReturn(world(TARGET_WORLD_ID));

        service.importGraph(PACK_ID, TARGET_WORLD_ID);

        ArgumentCaptor<StoryGraph> cap = ArgumentCaptor.forClass(StoryGraph.class);
        verify(graphRepo).save(cap.capture());
        assertThat(cap.getValue().getImproviseDepthLimit()).isEqualTo(3);

        // 旧快照（4.6 前发布）：无该字段 → 导入副本留 null（引擎侧取缺省 5）
        org.mockito.Mockito.reset(sharedRepo, graphRepo, worldRepo);
        when(sharedRepo.findVisibleById(PACK_ID)).thenReturn(pack());
        when(worldRepo.findById(TARGET_WORLD_ID)).thenReturn(world(TARGET_WORLD_ID));

        service.importGraph(PACK_ID, TARGET_WORLD_ID);

        ArgumentCaptor<StoryGraph> cap2 = ArgumentCaptor.forClass(StoryGraph.class);
        verify(graphRepo).save(cap2.capture());
        assertThat(cap2.getValue().getImproviseDepthLimit()).isNull();
    }

    // ==================== 4. 撤回 ====================

    @Test
    void revoke_发布者撤回成功() {
        when(sharedRepo.revoke(eq(PACK_ID), eq("t-a"), any(Instant.class))).thenReturn(1);

        assertThat(service.revoke(PACK_ID)).isTrue();
    }

    @Test
    void revoke_非发布者或不存在返回false() {
        // WHERE 带发布者锚点：非本人包受影响行数 0（供控制层判 404）
        when(sharedRepo.revoke(eq(PACK_ID), eq("t-a"), any(Instant.class))).thenReturn(0);

        assertThat(service.revoke(PACK_ID)).isFalse();
    }

    // ==================== 夹具 ====================

    private StoryGraph validGraph() {
        StoryGraph graph = new StoryGraph(GRAPH_ID, "t-a", WORLD_ID, "夜雾纪行");
        graph.setImproviseMode(StoryGraph.IMPROVISE_FREE);
        graph.setStateSchema(SCHEMA);
        graph.setGraphJson(VALID_GRAPH_JSON);
        return graph;
    }

    private WorldSetting world(String worldId) {
        WorldSetting world = new WorldSetting();
        world.setId(worldId);
        world.setName("夜雾测试世界");
        return world;
    }

    private SharedStoryGraph pack() {
        SharedStoryGraph pack = new SharedStoryGraph();
        pack.setId(PACK_ID);
        pack.setName("夜雾纪行");
        pack.setPublisherTenantId("t-other");
        pack.setPublisherUserId("u-other");
        pack.setOriginWorldId("w-src");
        pack.setOriginGraphId(GRAPH_ID);
        pack.setSnapshotJson(snapshotJson());
        pack.setSnapshotVersion(1);
        pack.setNodeCount(4);
        return pack;
    }

    /** 快照三件套（发布时点形态：graphJson 为转义字符串） */
    private static String snapshotJson() {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("improviseMode", StoryGraph.IMPROVISE_FREE);
        snapshot.put("stateSchema", SCHEMA);
        snapshot.put("graphJson", VALID_GRAPH_JSON);
        return snapshot.toString();
    }

    private void stubPublish(StoryGraph graph, WorldSetting world) {
        when(graphRepo.findById(GRAPH_ID)).thenReturn(graph);
        when(worldRepo.findById(WORLD_ID)).thenReturn(world != null ? world : world(WORLD_ID));
    }

    private static JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
