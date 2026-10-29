package com.luyu.agent.tenancy;

import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.story.engine.GuardEvaluator;
import com.luyu.agent.rpg.story.engine.StateSchemaValidator;
import com.luyu.agent.rpg.story.engine.StoryGraphFinalizeReport;
import com.luyu.agent.rpg.story.engine.StoryGraphValidator;
import com.luyu.agent.rpg.story.engine.VariantCoverageValidator;
import com.luyu.agent.rpg.story.model.SharedStoryGraph;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.RpgStoryRunRepository;
import com.luyu.agent.rpg.story.repository.SharedStoryGraphRepository;
import com.luyu.agent.rpg.story.service.SharedStoryGraphService;
import com.luyu.agent.rpg.story.service.StoryGraphWorkshopService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 共享故事图端到端流转测试（story-graph-authoring tasks 3.4，参照
 * {@code SharedPackEndToEndTest}：@JdbcTest + 真实 H2 + 真实仓储/校验链/服务，
 * 以 runAs 模拟 JwtTenantFilter 的 set → 业务 → finally clear 生命周期）。
 * <p>
 * 钉住两组端到端语义：
 * <ol>
 *   <li><b>手工建图走同一校验链</b>（model/generation spec「发布门槛 = 整稿通过」）：
 *       工坊 finalize 报跨幕对接断裂的图，publish 以同一错误拒绝、共享库零写入；
 *       工坊修复后 finalize 通过 → publish 放行（同一组件两端一致）；</li>
 *   <li><b>发布后编辑不影响快照与既有导入副本</b>（model spec Scenario）：发布方
 *       改模板层 beat → 已发布快照不变、既有导入方副本不变、新导入方获得的仍是
 *       发布时点快照；撤回后画廊 404 而已导入副本照存（快照哲学，无回链）。</li>
 * </ol>
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        RpgStoryGraphRepository.class, RpgStoryRunRepository.class,
        RpgWorldSettingRepository.class, SharedStoryGraphRepository.class,
        GuardEvaluator.class, StateSchemaValidator.class, VariantCoverageValidator.class,
        StoryGraphValidator.class, StoryGraphWorkshopService.class, SharedStoryGraphService.class})
class SharedStoryGraphEndToEndTest {

    private static final String SCHEMA = "{\"trust_信长\": \"number\"}";

    /** 合法两幕图（含拼接边 e1_09，4 节点，beat「夜探织田邸」为发布时点锚文本） */
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

    /** 跨幕对接断裂靶：去掉拼接边 e1_09（save 免拓扑放行草稿，finalize/publish 报
     * 幕 1 锚点无出口边；匹配段吃掉整个 e1_09 对象含其闭合 }，text block 剥离
     * 12 空格后 e1_09 行 5 空格缩进） */
    private static final String BROKEN_DOCK_GRAPH_JSON = VALID_GRAPH_JSON
            .replace("},\n     {\"id\": \"e1_09\", \"from\": \"n1_07\", \"to\": \"n2_01\", \"label\": \"交接\"}", "}");

    /** 发布后编辑靶：模板层 beat 改「夜探织田邸」→「夜探织田邸v2」（发布后编辑） */
    private static final String EDITED_GRAPH_JSON = VALID_GRAPH_JSON
            .replace("夜探织田邸", "夜探织田邸v2");

    @Autowired
    private StoryGraphWorkshopService workshopService;

    @Autowired
    private SharedStoryGraphService sharedService;

    @Autowired
    private RpgStoryGraphRepository graphRepo;

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @Autowired
    private SharedStoryGraphRepository sharedRepo;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 模拟 JwtTenantFilter：验签通过后 set → 业务 → finally clear */
    private void runAs(String userId, String tenantId, Runnable action) {
        TenantContext.set(userId, tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    private <T> T runAs(String userId, String tenantId, Supplier<T> action) {
        TenantContext.set(userId, tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    // ==================== 辅助构造 ====================

    private static WorldSetting world(String id, String name) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName(name);
        w.setSettingDesc("desc");
        return w;
    }

    /** 手工建图全链：工坊 create → save（即时校验，草稿允许未成形拓扑） */
    private String buildGraphViaWorkshop(String worldId, String graphJson) {
        return runAs("u-a", "t-a", () -> {
            StoryGraph created = workshopService.create(worldId, "夜雾纪行", null, null, SCHEMA);
            workshopService.save(created.getId(), null, null, null, null, graphJson);
            return created.getId();
        });
    }

    /** 手工建图全链 + 整稿通过（发布前置态）；不可嵌套调 buildGraphViaWorkshop——
     * 内层 runAs 的 finally clear 会清掉外层上下文 */
    private String buildFinalizedGraph(String worldId) {
        return runAs("u-a", "t-a", () -> {
            StoryGraph created = workshopService.create(worldId, "夜雾纪行", null, null, SCHEMA);
            workshopService.save(created.getId(), null, null, null, null, VALID_GRAPH_JSON);
            StoryGraphFinalizeReport report = workshopService.finalizeGraph(created.getId());
            assertThat(report.getErrors()).isEmpty();
            return created.getId();
        });
    }

    // ==================== 1. 手工建图走同一校验链 ====================

    @Test
    void 手工建图走同一校验链_未过整稿的图不能进共享库() {
        runAs("u-a", "t-a", () -> worldRepo.save(world("w-a", "世界A")));
        String graphId = buildGraphViaWorkshop("w-a", BROKEN_DOCK_GRAPH_JSON);

        // 工坊整稿：同一校验链报跨幕对接断裂（图未落库）
        runAs("u-a", "t-a", () -> {
            StoryGraphFinalizeReport report = workshopService.finalizeGraph(graphId);
            assertThat(report.getErrors()).anyMatch(e -> e.contains("n1_07"));
            assertThat(report.getGraph()).isNull();
        });

        // 发布门槛 = 整稿通过：同一错误拒绝发布，共享库零写入
        runAs("u-a", "t-a", () -> {
            assertThatThrownBy(() -> sharedService.publish(graphId, "坏包", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("n1_07");
            assertThat(sharedService.listVisible()).isEmpty();
        });

        // 工坊修复 → 整稿通过 → 发布放行（同一链两端一致）
        runAs("u-a", "t-a", () -> {
            workshopService.save(graphId, null, null, null, null, VALID_GRAPH_JSON);
            assertThat(workshopService.finalizeGraph(graphId).getErrors()).isEmpty();
            SharedStoryGraphService.PublishResult result = sharedService.publish(graphId, "包一", null);
            assertThat(result.overwrite()).isFalse();
            assertThat(result.nodeCount()).isEqualTo(4);
            assertThat(sharedService.listVisible()).hasSize(1);
        });
    }

    // ==================== 2. 发布后编辑不影响快照与既有导入副本 ====================

    @Test
    void 发布后编辑不影响快照与既有导入副本() {
        runAs("u-a", "t-a", () -> worldRepo.save(world("w-a", "世界A")));
        runAs("u-b", "t-b", () -> {
            worldRepo.save(world("w-b", "世界B"));
            worldRepo.save(world("w-b2", "世界B2"));
        });

        String graphId = buildFinalizedGraph("w-a");
        String packId = runAs("u-a", "t-a", () -> sharedService.publish(graphId, "包一", null).packId());

        // 既有导入方：B 导入到自己模板层 w-b
        String importedId = runAs("u-b", "t-b",
                () -> sharedService.importGraph(packId, "w-b").graphId());

        // 发布后编辑：A 改模板层 beat（发布后模板变化）
        runAs("u-a", "t-a",
                () -> workshopService.save(graphId, null, null, null, null, EDITED_GRAPH_JSON));

        // 已发布快照不变（读侧任意登录用户可验）
        runAs("u-b", "t-b", () -> {
            SharedStoryGraph pack = sharedService.loadPack(packId);
            assertThat(pack.getSnapshotJson()).contains("夜探织田邸");
            assertThat(pack.getSnapshotJson()).doesNotContain("夜探织田邸v2");
        });

        // 既有导入方副本不变（独立行，无回链）
        runAs("u-b", "t-b", () -> {
            StoryGraph copy = graphRepo.findById(importedId);
            assertThat(copy).isNotNull();
            assertThat(copy.getGraphJson()).contains("夜探织田邸");
        });

        // 新导入方获得的是发布时点快照（而非编辑后的模板）
        runAs("u-b", "t-b", () -> {
            String freshId = sharedService.importGraph(packId, "w-b2").graphId();
            assertThat(graphRepo.findById(freshId).getGraphJson()).contains("夜探织田邸");
        });

        // 撤回：画廊 404，已导入副本照存（快照哲学）
        runAs("u-a", "t-a", () -> assertThat(sharedService.revoke(packId)).isTrue());
        runAs("u-b", "t-b", () -> {
            assertThat(sharedService.loadPack(packId)).isNull();
            assertThat(sharedService.listVisible()).isEmpty();
            assertThat(graphRepo.findById(importedId)).isNotNull();
        });
    }
}
