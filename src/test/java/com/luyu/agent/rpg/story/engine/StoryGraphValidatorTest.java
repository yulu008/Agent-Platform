package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.ConvergeOrbit;
import com.luyu.agent.rpg.story.model.GuardClause;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StoryGraphValidator} 单元测试（story-graph-model 任务 1.7）。
 * <p>
 * 覆盖 spec Scenario：断边确定性改指锚点（n2_99 → n2_07，重复执行一致）、
 * 跨幕引用被丢弃重连、不可达节点剔除（n4_11 + 报告清单）、缺失必需字段
 * 被拒（converge_to / 锚点）、环深度上限、跨幕对接断裂、修复确定性。
 */
class StoryGraphValidatorTest {

    private final StoryGraphValidator validator = new StoryGraphValidator();

    private StoryNode node(String id, String type) {
        StoryNode n = new StoryNode();
        n.setId(id);
        n.setBeat("拍子" + id);
        n.setType(type);
        return n;
    }

    private StoryEdge edge(String id, String from, String to) {
        StoryEdge e = new StoryEdge();
        e.setId(id);
        e.setFrom(from);
        e.setTo(to);
        e.setLabel("前进");
        e.setQuote("继续");
        return e;
    }

    /** 标准合法幕：a-1 → a-2 → … → a-N（尾节点 anchor），链式边 */
    private StoryAct healthyAct(String actId, int nodeCount) {
        return healthyAct(actId, nodeCount, true);
    }

    private StoryAct healthyAct(String actId, int nodeCount, boolean withConverge) {
        StoryAct act = new StoryAct();
        act.setId(actId);
        act.setTheme("主题");
        if (withConverge) {
            act.setConvergeTo(List.of(new ConvergeOrbit("信任线",
                    new GuardClause("trust_信长", ">=", 60))));
        }
        int actNum = Integer.parseInt(actId.replaceAll("\\D", ""));
        List<StoryNode> nodes = new ArrayList<>();
        for (int i = 1; i <= nodeCount; i++) {
            nodes.add(node("n" + actNum + "_" + String.format("%02d", i), i == nodeCount ? "anchor" : "scene"));
        }
        List<StoryEdge> edges = new ArrayList<>();
        for (int i = 1; i < nodeCount; i++) {
            edges.add(edge("e" + actNum + "_" + String.format("%02d", i),
                    "n" + actNum + "_" + String.format("%02d", i),
                    "n" + actNum + "_" + String.format("%02d", i + 1)));
        }
        act.setNodes(nodes);
        act.setEdges(edges);
        return act;
    }

    @Test
    void 断边确定性改指最近同幕锚点() {
        // spec Scenario：幕 2 某边 to 指向不存在节点 n2_99，且幕 2 存在锚点 n2_07
        StoryAct act = healthyAct("act2", 7);
        StoryEdge broken = edge("e2_50", "n2_03", "n2_99");
        act.getEdges().add(broken);

        TopologyResult result = validator.validateAct(act);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(broken.getTo()).isEqualTo("n2_07");
        assertThat(result.getRepairs()).anyMatch(r -> r.contains("n2_99") && r.contains("n2_07"));
    }

    @Test
    void 断边修复同输入重复执行结果一致() {
        // 修复确定性：同输入两份独立构建，各执行一次，结果逐字一致
        TopologyResult first = validator.validateAct(buildBrokenAct());
        TopologyResult second = validator.validateAct(buildBrokenAct());

        assertThat(second.getRepairs()).isEqualTo(first.getRepairs());
        assertThat(second.getErrors()).isEqualTo(first.getErrors());
        assertThat(second.getWarnings()).isEqualTo(first.getWarnings());
    }

    private StoryAct buildBrokenAct() {
        StoryAct act = healthyAct("act2", 7);
        act.getEdges().add(edge("e2_50", "n2_03", "n2_99"));
        return act;
    }

    @Test
    void 跨幕引用被丢弃并留下修复记录() {
        // spec Scenario：幕 1 直连幕 3 节点的边被丢弃（跨幕边由引擎拼接替代）
        StoryAct act = healthyAct("act1", 4);
        StoryEdge crossActEdge = edge("e1_50", "n1_02", "n3_01");
        act.getEdges().add(crossActEdge);

        TopologyResult result = validator.validateAct(act);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(act.getEdges()).noneMatch(e -> e.getId().equals("e1_50"));
        assertThat(result.getRepairs()).anyMatch(r -> r.contains("e1_50") && r.contains("跨幕引用"));
    }

    @Test
    void 不可达节点被剔除且报告列出清单() {
        // spec Scenario：幕 4 存在从任何入口均不可达的节点 n4_11
        StoryAct act = healthyAct("act4", 5);
        act.getNodes().add(node("n4_11", "scene"));

        TopologyResult result = validator.validateAct(act);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(act.getNodes()).noneMatch(n -> n.getId().equals("n4_11"));
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("n4_11") && w.contains("剔除"));
    }

    @Test
    void 幕缺失converge轨道表被拒() {
        // spec Scenario：提交某幕无 converge_to 轨道表的骨架 → 拒绝并指出具体幕
        StoryAct act = healthyAct("act1", 3, false);

        TopologyResult result = validator.validateAct(act);

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("act1") && e.contains("converge_to"));
    }

    @Test
    void 锚点全部被剔后幕被拒() {
        // 锚点 n1_02 不可达被剔除 → 幕缺 anchor 出口节点 → 拒绝
        StoryAct act = healthyAct("act1", 3);
        // 断开入口到锚点的链：删掉入边，锚点不可达
        act.setEdges(new ArrayList<>(List.of(edge("e1_50", "n1_01", "n1_01")))); // 自环且不到锚点

        TopologyResult result = validator.validateAct(act);

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("act1") && e.contains("anchor"));
    }

    @Test
    void 环深度超限被拒() {
        // 9 边环（> MAX_CYCLE_EDGES=8）：A→B→…→I→A
        StoryAct act = healthyAct("act1", 11);
        List<StoryEdge> cycleEdges = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            String from = "n1_" + String.format("%02d", i);
            String to = i == 9 ? "n1_01" : "n1_" + String.format("%02d", i + 1);
            cycleEdges.add(edge("c" + i, from, to));
        }
        act.getEdges().addAll(cycleEdges);

        TopologyResult result = validator.validateAct(act);

        assertThat(result.getErrors()).anyMatch(e -> e.contains("环深度超限") && e.contains("9"));
    }

    @Test
    void 合法小环不报错() {
        // 4 边环（≤ 上限）允许：绕路玩法
        StoryAct act = healthyAct("act1", 6);
        act.getEdges().addAll(List.of(
                edge("c1", "n1_01", "n1_02"),
                edge("c2", "n1_02", "n1_03"),
                edge("c3", "n1_03", "n1_04"),
                edge("c4", "n1_04", "n1_01")));

        TopologyResult result = validator.validateAct(act);

        assertThat(result.getErrors()).noneMatch(e -> e.contains("环深度"));
    }

    @Test
    void 幕序号重复被拒() {
        TopologyResult result = validator.validateGraph(
                List.of(healthyAct("act1", 3), healthyAct("act1", 3)));

        assertThat(result.getErrors()).anyMatch(e -> e.contains("幕序号重复"));
    }

    @Test
    void 全图校验_非末幕锚点无出口边报跨幕对接断裂() {
        // act1 锚点 n1_03 的出边（拼接边）缺失 → 跨幕对接断裂
        StoryAct act1 = healthyAct("act1", 3);
        StoryAct act2 = healthyAct("act2", 3);
        // 拼接边缺失（n1_03 无出边）

        TopologyResult result = validator.validateGraph(List.of(act1, act2));

        assertThat(result.getErrors()).anyMatch(e -> e.contains("n1_03") && e.contains("跨幕对接断裂"));
    }

    @Test
    void 全图校验_拼接边存在时通过() {
        StoryAct act1 = healthyAct("act1", 3);
        StoryAct act2 = healthyAct("act2", 3);
        // 引擎拼接边：act1 出口锚 → act2 入口
        StoryEdge stitch = edge("e1_99", "n1_03", "n2_01");
        act1.getEdges().add(stitch);

        TopologyResult result = validator.validateGraph(List.of(act1, act2));

        assertThat(result.getErrors()).as("errors: %s", result.getErrors()).isEmpty();
        // 拼接边保留（指向下一幕入口合法）
        assertThat(act1.getEdges()).anyMatch(e -> e.getId().equals("e1_99"));
    }

    @Test
    void 死端节点无法到达锚点仅警告不阻断() {
        // n1_03 scene 分支死端（无出边且不在锚点通路上）
        StoryAct act = healthyAct("act1", 4);
        act.getNodes().add(node("n1_20", "scene"));
        act.getEdges().add(edge("e1_50", "n1_01", "n1_20")); // 进得去出不来

        TopologyResult result = validator.validateAct(act);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("n1_20") && w.contains("无法到达任何锚点"));
    }

    @Test
    void 修复流水格式含原值与新值() {
        StoryAct act = healthyAct("act2", 7);
        act.getEdges().add(edge("e2_50", "n2_03", "n2_99"));

        TopologyResult result = validator.validateAct(act);

        assertThat(result.getRepairs()).anyMatch(r ->
                r.contains("n2_99") && r.contains("->") && r.contains("n2_07") && r.contains("断边改指"));
    }
}
