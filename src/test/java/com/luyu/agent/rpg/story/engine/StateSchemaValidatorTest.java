package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.ConvergeOrbit;
import com.luyu.agent.rpg.story.model.GuardClause;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StateSchemaValidator} 单元测试（story-graph-model 任务 1.7）。
 * <p>
 * 覆盖 spec Scenario：guard 引用词汇表外键被拒（报出边 ID 与非法键名）、
 * effects 写入词汇表外键被拒（报出节点 ID）、合法骨架全命中通过、
 * schema 缺失/坏 JSON 报错、简化形态解析、类型-op 错配。
 */
class StateSchemaValidatorTest {

    private final StateSchemaValidator validator = new StateSchemaValidator(new GuardEvaluator());

    private static final String SCHEMA = """
            {"trust_信长": {"type": "number", "min": 0, "max": 100},
             "阵营": {"type": "string", "values": ["织田", "武田"]},
             "已见过影分身": {"type": "flag"}}
            """;

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

    private StoryAct actWith(StoryNode extraNode, StoryEdge extraEdge) {
        StoryAct act = new StoryAct();
        act.setId("act1");
        act.setTheme("主题");
        act.setConvergeTo(List.of(new ConvergeOrbit("信任线",
                new GuardClause("trust_信长", ">=", 60))));
        List<StoryNode> nodes = new ArrayList<>(List.of(
                node("n1_01", "scene"), node("n1_02", "anchor")));
        List<StoryEdge> edges = new ArrayList<>(List.of(edge("e1_01", "n1_01", "n1_02")));
        if (extraNode != null) {
            nodes.add(extraNode);
        }
        if (extraEdge != null) {
            edges.add(extraEdge);
        }
        act.setNodes(nodes);
        act.setEdges(edges);
        return act;
    }

    @Test
    void 合法骨架词汇表全命中通过() {
        StoryEdge guardedEdge = edge("e1_02", "n1_02", "n1_01");
        guardedEdge.setGuard(new GuardClause("阵营", "==", "织田"));
        guardedEdge.setEffects(Map.of("trust_信长", 5));
        StoryNode enterNode = node("n1_03", "scene");
        enterNode.setOnEnter(Map.of("已见过影分身", true));

        List<String> errors = validator.validate(List.of(actWith(enterNode, guardedEdge)), SCHEMA);

        assertThat(errors).as("errors: %s", errors).isEmpty();
    }

    @Test
    void guard引用词汇表外键被拒并报出边ID与非法键名() {
        StoryEdge badEdge = edge("e1_02", "n1_02", "n1_01");
        badEdge.setGuard(new GuardClause("loyalty_家康", ">=", 50));

        List<String> errors = validator.validate(List.of(actWith(null, badEdge)), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("e1_02") && e.contains("loyalty_家康")
                && e.contains("词汇表外键"));
    }

    @Test
    void effects写入词汇表外键被拒并报出节点ID() {
        StoryNode badNode = node("n1_03", "scene");
        badNode.setOnEnter(Map.of("loyalty_家康", 5));

        List<String> errors = validator.validate(List.of(actWith(badNode, null)), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("n1_03") && e.contains("loyalty_家康")
                && e.contains("词汇表外键"));
    }

    @Test
    void 边effects外键同样被拒() {
        StoryEdge badEdge = edge("e1_02", "n1_02", "n1_01");
        badEdge.setEffects(Map.of("loyalty_家康", 5));

        List<String> errors = validator.validate(List.of(actWith(null, badEdge)), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("e1_02") && e.contains("词汇表外键"));
    }

    @Test
    void 词汇表缺失时报错() {
        List<String> errors = validator.validate(List.of(actWith(null, null)), null);

        assertThat(errors).anyMatch(e -> e.contains("state_schema 词汇表缺失"));
    }

    @Test
    void 坏JSON词汇表报解析错误() {
        List<String> errors = validator.validate(List.of(actWith(null, null)), "{not json");

        assertThat(errors).anyMatch(e -> e.contains("state_schema JSON 解析失败"));
    }

    @Test
    void 幕入口守卫与轨道表外键被拒() {
        StoryAct act = actWith(null, null);
        act.setEntryGuard(new GuardClause("loyalty_家康", ">=", 50));
        act.setConvergeTo(List.of(new ConvergeOrbit("外键轨道",
                new GuardClause("loyalty_秀吉", ">=", 50))));

        List<String> errors = validator.validate(List.of(act), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("act1 entry_guard") && e.contains("loyalty_家康"));
        assertThat(errors).anyMatch(e -> e.contains("converge_to[外键轨道]") && e.contains("loyalty_秀吉"));
    }

    @Test
    void 类型错配_flag键不允许数值比较op() {
        StoryEdge badEdge = edge("e1_02", "n1_02", "n1_01");
        badEdge.setGuard(new GuardClause("已见过影分身", ">=", 1));

        List<String> errors = validator.validate(List.of(actWith(null, badEdge)), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("类型错配") && e.contains("flag"));
    }

    @Test
    void 类型错配_string键不允许数值比较op() {
        StoryEdge badEdge = edge("e1_02", "n1_02", "n1_01");
        badEdge.setGuard(new GuardClause("阵营", ">", "织田"));

        List<String> errors = validator.validate(List.of(actWith(null, badEdge)), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("类型错配") && e.contains("string"));
    }

    @Test
    void 效果值类型不符被拒() {
        StoryNode badNode = node("n1_03", "scene");
        badNode.setOnEnter(Map.of("trust_信长", "很多")); // number 键效果值非数字

        List<String> errors = validator.validate(List.of(actWith(badNode, null)), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("n1_03") && e.contains("必须为数字"));
    }

    @Test
    void 简化形态类型字符串可解析() {
        List<String> parseErrors = new ArrayList<>();
        StateSchemaValidator validator = new StateSchemaValidator(new GuardEvaluator());
        var schema = validator.parseSchema("""
                {"trust_信长": "number", "阵营": "string", "已见过影分身": "flag"}
                """, parseErrors);

        assertThat(parseErrors).isEmpty();
        assertThat(schema.contains("trust_信长")).isTrue();
        assertThat(schema.contains("阵营")).isTrue();
        assertThat(schema.contains("已见过影分身")).isTrue();
    }

    @Test
    void 变体守卫外键被拒() {
        StoryNode variantNode = node("n1_03", "anchor");
        com.luyu.agent.rpg.story.model.StoryVariant variant = new com.luyu.agent.rpg.story.model.StoryVariant();
        variant.setGuard(new GuardClause("loyalty_家康", ">=", 50));
        variant.setBeatVariant("变体拍子");
        variantNode.setVariants(List.of(variant));

        List<String> errors = validator.validate(List.of(actWith(variantNode, null)), SCHEMA);

        assertThat(errors).anyMatch(e -> e.contains("n1_03") && e.contains("variant") && e.contains("loyalty_家康"));
    }
}
