package com.luyu.agent.rpg.story.engine;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StoryGraphStitcher} 跨幕拼接引擎测试（story-graph-authoring
 * tasks 2.5 / spec「幕内边生成与跨幕边引擎拼接」）。
 * <p>
 * 钉住：出口锚→下幕入口确定性生成（乱序输入按幕号排序）、<b>幂等</b>
 * （spec「引擎拼接跨幕边幂等」——重复拼接边集合完全一致、旧拼接边
 * 清除重算无残留）、<b>模型越权跨幕边丢弃告警</b>（spec「模型越权跨幕
 * 边被丢弃」——含 to 恰为下幕入口的越权边）、无锚点/单幕/非末幕
 * ending 的告警与跳过。
 */
@DisplayName("跨幕边引擎拼接")
class StoryGraphStitcherTest {

    private final StoryGraphStitcher stitcher = new StoryGraphStitcher();

    // ==================== 夹具 ====================

    private static StoryAct act(int number, String theme, StoryNode... nodes) {
        StoryAct act = new StoryAct();
        act.setId("act" + number);
        act.setTheme(theme);
        List<StoryNode> list = new ArrayList<>(List.of(nodes));
        act.setNodes(list);
        act.setEdges(new ArrayList<>());
        return act;
    }

    private static StoryNode node(String id, String type) {
        StoryNode node = new StoryNode();
        node.setId(id);
        node.setType(type);
        return node;
    }

    private static StoryEdge edge(String id, String from, String to) {
        StoryEdge edge = new StoryEdge();
        edge.setId(id);
        edge.setFrom(from);
        edge.setTo(to);
        edge.setLabel("模型边");
        return edge;
    }

    /** 标准三幕夹具（乱序传入钉排序：act1 锚 n1_05 / act2 锚 n2_04 / act3 ending） */
    private List<StoryAct> threeActs() {
        StoryAct act1 = act(1, "潜入与结识",
                node("n1_01", StoryNode.TYPE_SCENE), node("n1_05", StoryNode.TYPE_ANCHOR));
        act1.getEdges().add(edge("e1_01", "n1_01", "n1_05"));
        StoryAct act2 = act(2, "分裂与抉择",
                node("n2_01", StoryNode.TYPE_SCENE), node("n2_04", StoryNode.TYPE_ANCHOR));
        act2.getEdges().add(edge("e2_01", "n2_01", "n2_04"));
        StoryAct act3 = act(3, "终局对决",
                node("n3_01", StoryNode.TYPE_SCENE), node("n3_02", StoryNode.TYPE_ENDING));
        act3.getEdges().add(edge("e3_01", "n3_01", "n3_02"));
        // 乱序传入（拼接按幕号排序，不动调用方列表）
        return new ArrayList<>(List.of(act3, act1, act2));
    }

    /** 幕内拼接边（x 命名空间）提取 */
    private static List<StoryEdge> crossEdgesOf(StoryAct act) {
        List<StoryEdge> cross = new ArrayList<>();
        for (StoryEdge e : act.getEdges()) {
            if (e.getId() != null && e.getId().startsWith("x")) {
                cross.add(e);
            }
        }
        return cross;
    }

    // ==================== 基础拼接 ====================

    @Test
    void 出口锚到下幕入口确定性生成且按幕号排序() {
        List<StoryAct> acts = threeActs();

        StitchResult result = stitcher.stitch(acts);

        assertThat(result.getCrossEdges()).hasSize(2);
        assertThat(result.getWarnings()).isEmpty();
        // 幕 1 锚 n1_05 → 幕 2 入口 n2_01（入口 = 幕内 ID 序号最小节点）
        StoryAct act1 = acts.stream().filter(a -> a.getId().equals("act1")).findFirst().orElseThrow();
        List<StoryEdge> act1Cross = crossEdgesOf(act1);
        assertThat(act1Cross).hasSize(1);
        assertThat(act1Cross.get(0).getId()).isEqualTo("x1_01");
        assertThat(act1Cross.get(0).getFrom()).isEqualTo("n1_05");
        assertThat(act1Cross.get(0).getTo()).isEqualTo("n2_01");
        // label 确定性携带目标幕号与主题
        assertThat(act1Cross.get(0).getLabel()).isEqualTo("进入第 2 幕「分裂与抉择」");
        assertThat(act1Cross.get(0).getQuote()).isEqualTo("继续前进");
        // 幕 2 锚 n2_04 → 幕 3 入口 n3_01（无主题变体：label 只带幕号）
        StoryAct act2 = acts.stream().filter(a -> a.getId().equals("act2")).findFirst().orElseThrow();
        List<StoryEdge> act2Cross = crossEdgesOf(act2);
        assertThat(act2Cross).hasSize(1);
        assertThat(act2Cross.get(0).getId()).isEqualTo("x2_01");
        assertThat(act2Cross.get(0).getTo()).isEqualTo("n3_01");
        assertThat(act2Cross.get(0).getLabel()).isEqualTo("进入第 3 幕「终局对决」");
        // 末幕（act3）无出幕衔接：ending 不拼接
        StoryAct act3 = acts.stream().filter(a -> a.getId().equals("act3")).findFirst().orElseThrow();
        assertThat(crossEdgesOf(act3)).isEmpty();
        // 幕内模型边不动
        assertThat(act1.getEdges()).hasSize(2);   // e1_01 + x1_01
        assertThat(act3.getEdges()).hasSize(1);   // e3_01 原样
    }

    @Test
    void 每个锚点各生成一条拼接边且序号确定性递增() {
        StoryAct act1 = act(1, null,
                node("n1_01", StoryNode.TYPE_SCENE),
                node("n1_03", StoryNode.TYPE_ANCHOR),
                node("n1_07", StoryNode.TYPE_ANCHOR));
        StoryAct act2 = act(2, null, node("n2_01", StoryNode.TYPE_SCENE));

        StitchResult result = stitcher.stitch(new ArrayList<>(List.of(act1, act2)));

        assertThat(result.getCrossEdges()).hasSize(2);
        List<StoryEdge> cross = crossEdgesOf(act1);
        assertThat(cross).extracting(StoryEdge::getId)
                .containsExactly("x1_01", "x1_02");   // 锚点序号升序 → 序号确定性
        assertThat(cross).extracting(StoryEdge::getFrom)
                .containsExactly("n1_03", "n1_07");
        assertThat(cross).allSatisfy(e -> assertThat(e.getTo()).isEqualTo("n2_01"));
        // theme 为空时 label 只带幕号
        assertThat(cross.get(0).getLabel()).isEqualTo("进入第 2 幕");
    }

    // ==================== 幂等（spec「引擎拼接跨幕边幂等」） ====================

    @Test
    void 重复拼接边集合完全一致() {
        List<StoryAct> acts = threeActs();
        StitchResult first = stitcher.stitch(acts);

        // 第二次拼接（同输入重入：确认门锁定拦截重入，回退解锁后再确认即此场景）
        StitchResult second = stitcher.stitch(acts);

        // 两次生成的跨幕边集合完全一致（确定性，重复执行无副作用）
        assertThat(second.getCrossEdges()).hasSize(first.getCrossEdges().size());
        assertThat(edgesKey(second.getCrossEdges()))
                .containsExactlyElementsOf(edgesKey(first.getCrossEdges()));
        // 无重复挂载：源幂 x 边数量不翻倍（末幂 act3 无出幂衔接恒 0）
        for (StoryAct act : acts) {
            int expected = "act3".equals(act.getId()) ? 0 : 1;
            assertThat(crossEdgesOf(act)).hasSize(expected);
        }
        assertThat(second.getWarnings()).isEmpty();
    }

    @Test
    void 旧拼接边清除重算无残留() {
        List<StoryAct> acts = threeActs();
        stitcher.stitch(acts);
        // 幕 1 手工残留一条旧拼接边（上一轮拼接的过期目标 n2_99）
        StoryAct act1 = acts.stream().filter(a -> a.getId().equals("act1")).findFirst().orElseThrow();
        act1.getEdges().add(edge("x1_99", "n1_05", "n2_99"));

        StitchResult result = stitcher.stitch(acts);

        // 旧拼接边被清除，重算边唯一（无残留、无重复）
        List<StoryEdge> cross = crossEdgesOf(act1);
        assertThat(cross).extracting(StoryEdge::getId).containsExactly("x1_01");
        assertThat(act1.getEdges()).hasSize(2);
        assertThat(result.getCrossEdges()).hasSize(2);
    }

    // ==================== 越权跨幕边丢弃告警（spec「模型越权跨幕边被丢弃」） ====================

    @Test
    void 模型越权跨幕边被丢弃并告警() {
        StoryAct act3 = act(3, "终局",
                node("n3_01", StoryNode.TYPE_SCENE), node("n3_04", StoryNode.TYPE_ANCHOR));
        // 越权边 1：to 指向幕 4 普通节点（spec Scenario 原型：n4_02）
        act3.getEdges().add(edge("e3_09", "n3_01", "n4_02"));
        // 越权边 2：to 恰为下幕入口（D2 语义：模型画的任何跨幕边都算越权）
        act3.getEdges().add(edge("e3_10", "n3_04", "n4_01"));
        // 越权边 3：from 属其他幕（对称防御）
        act3.getEdges().add(edge("e3_11", "n2_01", "n3_04"));
        // 幕内边（对照：不动）
        act3.getEdges().add(edge("e3_01", "n3_01", "n3_04"));
        StoryAct act4 = act(4, "余烬",
                node("n4_01", StoryNode.TYPE_SCENE), node("n4_06", StoryNode.TYPE_ANCHOR));
        StoryAct act1 = act(1, "潜入",
                node("n1_01", StoryNode.TYPE_SCENE), node("n1_05", StoryNode.TYPE_ANCHOR));
        StoryAct act2 = act(2, "分裂",
                node("n2_01", StoryNode.TYPE_SCENE), node("n2_04", StoryNode.TYPE_ANCHOR));

        StitchResult result = stitcher.stitch(
                new ArrayList<>(List.of(act1, act2, act3, act4)));

        // 三条越权边全部丢弃
        assertThat(act3.getEdges()).extracting(StoryEdge::getId)
                .containsExactly("e3_01", "x3_01");   // 幕内边 + 引擎拼接边
        // 告警逐条定位（spec「丢弃并记录告警」）
        assertThat(result.getWarnings()).anySatisfy(w -> {
            assertThat(w).contains("e3_09").contains("n4_02").contains("越权");
        }).anySatisfy(w -> {
            assertThat(w).contains("e3_10").contains("n4_01").contains("越权");
        }).anySatisfy(w -> {
            assertThat(w).contains("e3_11").contains("n2_01").contains("越权");
        });
        // 幕 3→4 衔接由引擎在拼接时生成（spec：越权边被替代）
        List<StoryEdge> cross = crossEdgesOf(act3);
        assertThat(cross).hasSize(1);
        assertThat(cross.get(0).getId()).isEqualTo("x3_01");
        assertThat(cross.get(0).getFrom()).isEqualTo("n3_04");
        assertThat(cross.get(0).getTo()).isEqualTo("n4_01");
    }

    // ==================== 异常结构与边界 ====================

    @Test
    void 无锚点幕与非末幕ending告警跳过() {
        StoryAct act1 = act(1, "潜入", node("n1_01", StoryNode.TYPE_SCENE));   // 无锚点
        StoryAct act2 = act(2, "分裂",
                node("n2_01", StoryNode.TYPE_SCENE),
                node("n2_04", StoryNode.TYPE_ANCHOR),
                node("n2_09", StoryNode.TYPE_ENDING));                          // 非末幕 ending
        StoryAct act3 = act(3, "终局", node("n3_01", StoryNode.TYPE_SCENE));

        StitchResult result = stitcher.stitch(new ArrayList<>(List.of(act1, act2, act3)));

        // 幕 1 无锚点：跳过 1→2 衔接并告警（拒绝级判定归 validateGraph，本层告警）
        assertThat(result.getWarnings()).anySatisfy(w -> {
            assertThat(w).contains("act1").contains("无 anchor");
        });
        assertThat(crossEdgesOf(act1)).isEmpty();
        // 幕 2：非末幕 ending 告警（不为 ending 生成出边），anchor 照常拼接 2→3
        assertThat(result.getWarnings()).anySatisfy(w -> {
            assertThat(w).contains("n2_09").contains("ending");
        });
        List<StoryEdge> cross = crossEdgesOf(act2);
        assertThat(cross).hasSize(1);
        assertThat(cross.get(0).getFrom()).isEqualTo("n2_04");
        assertThat(cross.get(0).getTo()).isEqualTo("n3_01");
    }

    @Test
    void 单幕无跨幕对零边返回() {
        StoryAct act1 = act(1, "独幕", node("n1_01", StoryNode.TYPE_ANCHOR));
        act1.getEdges().add(edge("e1_01", "n1_01", "n1_01"));

        StitchResult result = stitcher.stitch(List.of(act1));

        assertThat(result.getCrossEdges()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
        assertThat(act1.getEdges()).hasSize(1);   // 幂等清理无 x 边，原边不动
    }

    // ==================== 工具 ====================

    /** 边指纹（id|from|to|label|quote——幂等集合一致性断言键） */
    private static List<String> edgesKey(List<StoryEdge> edges) {
        List<String> keys = new ArrayList<>(edges.size());
        for (StoryEdge e : edges) {
            keys.add(e.getId() + "|" + e.getFrom() + "|" + e.getTo()
                    + "|" + e.getLabel() + "|" + e.getQuote());
        }
        return keys;
    }
}
