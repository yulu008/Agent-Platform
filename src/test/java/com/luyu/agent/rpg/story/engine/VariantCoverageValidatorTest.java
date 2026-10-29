package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.ConvergeOrbit;
import com.luyu.agent.rpg.story.model.GuardClause;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryVariant;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VariantCoverageValidator} 单元测试（story-graph-model 任务 1.7）。
 * <p>
 * 覆盖 spec Scenario：幂级授权外的变体被忽略并提示、覆盖缺口仅警告不阻断
 * （trust &gt;= 60 之外的低信任轨道）、flag 双值覆盖、string 枚举覆盖、
 * 无 guard 变体（无条件兜底）无缺口。
 */
class VariantCoverageValidatorTest {

    private final VariantCoverageValidator validator = new VariantCoverageValidator(
            new StateSchemaValidator(new GuardEvaluator()));

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

    private StoryVariant variant(String key, String op, Object value, String beatVariant) {
        StoryVariant v = new StoryVariant();
        v.setGuard(new GuardClause(key, op, value));
        v.setBeatVariant(beatVariant);
        return v;
    }

    private StoryAct actWithVariants(Boolean allowExplicitVariants, List<StoryVariant> variants) {
        StoryAct act = new StoryAct();
        act.setId("act1");
        act.setTheme("主题");
        act.setAllowExplicitVariants(allowExplicitVariants);
        act.setConvergeTo(List.of(new ConvergeOrbit("信任线", new GuardClause("trust_信长", ">=", 60))));
        StoryNode anchorNode = node("n1_05", "anchor");
        anchorNode.setVariants(variants);
        act.setNodes(new ArrayList<>(List.of(node("n1_01", "scene"), anchorNode)));
        act.setEdges(new ArrayList<>(List.of(edge("e1_01", "n1_01", "n1_05"))));
        return act;
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

    @Test
    void 幂级授权外的变体被忽略并提示() {
        List<String> warnings = validator.validate(
                List.of(actWithVariants(false, List.of(variant("trust_信长", ">=", 60, "高信任")))),
                SCHEMA);

        assertThat(warnings).anyMatch(w -> w.contains("act1") && w.contains("n1_05")
                && w.contains("未授权显式变体") && w.contains("将被忽略"));
    }

    @Test
    void 覆盖缺口仅警告不阻断() {
        // spec Scenario：变体 guard 只覆盖 trust_信长 >= 60，可达区间含低于 60 的轨道
        List<String> warnings = validator.validate(
                List.of(actWithVariants(true, List.of(variant("trust_信长", ">=", 60, "高信任")))),
                SCHEMA);

        assertThat(warnings).anyMatch(w -> w.contains("n1_05") && w.contains("trust_信长")
                && w.contains("覆盖缺口"));
    }

    @Test
    void 双侧数值守卫全覆盖无缺口() {
        List<String> warnings = validator.validate(
                List.of(actWithVariants(true, List.of(
                        variant("trust_信长", ">=", 60, "高信任"),
                        variant("trust_信长", "<", 60, "低信任")))),
                SCHEMA);

        assertThat(warnings).noneMatch(w -> w.contains("trust_信长") && w.contains("覆盖缺口"));
    }

    @Test
    void flag键双值覆盖检查() {
        List<String> warnings = validator.validate(
                List.of(actWithVariants(true, List.of(
                        variant("已见过影分身", "==", true, "见过")))),
                SCHEMA);

        assertThat(warnings).anyMatch(w -> w.contains("已见过影分身") && w.contains("覆盖缺口") && w.contains("false"));
    }

    @Test
    void string键枚举覆盖检查() {
        List<String> warnings = validator.validate(
                List.of(actWithVariants(true, List.of(
                        variant("阵营", "==", "织田", "织田线")))),
                SCHEMA);

        assertThat(warnings).anyMatch(w -> w.contains("阵营") && w.contains("枚举覆盖缺口")
                && w.contains("武田"));
    }

    @Test
    void 无guard变体视为无条件兜底无缺口() {
        StoryVariant unconditional = new StoryVariant();
        unconditional.setBeatVariant("默认变体");
        List<String> warnings = validator.validate(
                List.of(actWithVariants(true, List.of(unconditional))),
                SCHEMA);

        assertThat(warnings).noneMatch(w -> w.contains("覆盖缺口"));
    }

    @Test
    void 词汇表缺失时跳过覆盖检查不报错() {
        List<String> warnings = validator.validate(
                List.of(actWithVariants(true, List.of(variant("trust_信长", ">=", 60, "高信任")))),
                null);

        // schema 缺失是 StateSchemaValidator 的 error（1.4 已测），此处不重复报
        assertThat(warnings).isEmpty();
    }
}
