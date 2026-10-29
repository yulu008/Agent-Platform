package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.GuardClause;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GuardEvaluator} 单元测试（story-graph-model 任务 1.7）。
 * <p>
 * 覆盖六算子确定性求值、防御语义（缺键/类型不可比/null 一律 false）、
 * 宽松等值（数值跨类型）、语法校验（op 值域与 value 类型匹配）。
 */
class GuardEvaluatorTest {

    private final GuardEvaluator evaluator = new GuardEvaluator();

    private final Map<String, Object> state = Map.of(
            "trust_信长", 65L,
            "阵营", "织田",
            "已见过影分身", true,
            "金钱", 120);

    @Test
    void 数值比较四算子() {
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", ">=", 60), state)).isTrue();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", ">=", 66), state)).isFalse();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", "<=", 65), state)).isTrue();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", ">", 65), state)).isFalse();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", "<", 66), state)).isTrue();
    }

    @Test
    void 数值比较字符串值可解析() {
        // LLM 有时把数字写成字符串：宽容数值化
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", ">=", "60"), state)).isTrue();
        assertThat(evaluator.evaluate(new GuardClause("金钱", "==", "120"), state)).isTrue();
    }

    @Test
    void 等值算子跨类型宽松等() {
        assertThat(evaluator.evaluate(new GuardClause("阵营", "==", "织田"), state)).isTrue();
        assertThat(evaluator.evaluate(new GuardClause("阵营", "==", "武田"), state)).isFalse();
        assertThat(evaluator.evaluate(new GuardClause("已见过影分身", "==", true), state)).isTrue();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", "==", 65), state)).isTrue();
    }

    @Test
    void in算子数组成员命中判定() {
        assertThat(evaluator.evaluate(new GuardClause("阵营", "in", List.of("武田", "织田")), state)).isTrue();
        assertThat(evaluator.evaluate(new GuardClause("阵营", "in", List.of("武田", "上杉")), state)).isFalse();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", "in", List.of(60, 65)), state)).isTrue();
    }

    @Test
    void 缺键与null守卫一律false() {
        assertThat(evaluator.evaluate(new GuardClause("不存在的键", ">=", 1), state)).isFalse();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", ">=", 60), Map.of())).isFalse();
        assertThat(evaluator.evaluate(new GuardClause("trust_信长", ">=", 60), null)).isFalse();
        assertThat(evaluator.evaluate(null, state)).isFalse();
        assertThat(evaluator.evaluate(new GuardClause(null, ">=", 1), state)).isFalse();
    }

    @Test
    void 类型不可比返回false不抛异常() {
        // string 键配数值比较 op：不可数值化 → false（类型错配在校验链报）
        assertThat(evaluator.evaluate(new GuardClause("阵营", ">=", 1), state)).isFalse();
        // in 的 value 非数组：防御 false
        assertThat(evaluator.evaluate(new GuardClause("阵营", "in", "织田"), state)).isFalse();
    }

    @Test
    void 同输入重复求值结果一致() {
        for (int i = 0; i < 3; i++) {
            assertThat(evaluator.evaluate(new GuardClause("trust_信长", ">=", 60), state)).isTrue();
            assertThat(evaluator.evaluate(new GuardClause("阵营", "in", List.of("织田")), state)).isTrue();
        }
    }

    @Test
    void 语法校验_非法op() {
        List<String> errors = evaluator.validate(new GuardClause("trust_信长", "!=", 60), "边 e1_03");
        assertThat(errors).anyMatch(e -> e.contains("op 非法") && e.contains("边 e1_03"));
    }

    @Test
    void 语法校验_数值op的value必须为数字() {
        List<String> errors = evaluator.validate(new GuardClause("trust_信长", ">=", "高"), "节点 n1_01");
        assertThat(errors).anyMatch(e -> e.contains("必须为数字") && e.contains("节点 n1_01"));
    }

    @Test
    void 语法校验_in必须为数组且等值必须为标量() {
        assertThat(evaluator.validate(new GuardClause("阵营", "in", "织田"), "边 e1_01"))
                .anyMatch(e -> e.contains("in 的 value 必须为数组"));
        assertThat(evaluator.validate(new GuardClause("阵营", "==", List.of("织田")), "边 e1_02"))
                .anyMatch(e -> e.contains("== 的 value 必须为标量"));
    }

    @Test
    void 语法校验_合法谓词无错误() {
        assertThat(evaluator.validate(new GuardClause("trust_信长", ">=", 60), "边 e1_03")).isEmpty();
        assertThat(evaluator.validate(new GuardClause("阵营", "in", List.of("织田")), "边 e1_03")).isEmpty();
        assertThat(evaluator.validate(null, "边 e1_03")).isNotEmpty();
    }
}
