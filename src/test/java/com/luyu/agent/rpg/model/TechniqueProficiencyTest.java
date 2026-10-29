package com.luyu.agent.rpg.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TechniqueProficiency} 档位映射的单元测试。
 * <p>
 * 覆盖 spec 的两个场景（620 → 「620/大成」、未学会不显示档位）与全部区间边界 ——
 * 边界值是最容易写错的地方（如 399 与 400 分属「入门」与「小成」，而 400 正是默认解锁阈值）。
 */
class TechniqueProficiencyTest {

    @Test
    void 数值映射为档位词() {
        // spec Scenario: 数值映射为档位词
        assertThat(TechniqueProficiency.labelOf(620)).isEqualTo("大成");
        assertThat(TechniqueProficiency.render(620)).isEqualTo("620/大成");
    }

    @Test
    void 未学会不显示数值() {
        // spec Scenario: 未学会不显示档位 —— 「0/未学会」既冗余又易被 GM 误读成「学过但归零」
        assertThat(TechniqueProficiency.labelOf(0)).isEqualTo("未学会");
        assertThat(TechniqueProficiency.render(0)).isEqualTo("未学会");
        assertThat(TechniqueProficiency.of(0)).isEqualTo(TechniqueProficiency.NOT_LEARNED);
    }

    @Test
    void 全部区间边界判档正确() {
        assertThat(TechniqueProficiency.labelOf(1)).isEqualTo("初窥门径");
        assertThat(TechniqueProficiency.labelOf(199)).isEqualTo("初窥门径");
        assertThat(TechniqueProficiency.labelOf(200)).isEqualTo("入门");
        assertThat(TechniqueProficiency.labelOf(399)).isEqualTo("入门");
        assertThat(TechniqueProficiency.labelOf(400)).isEqualTo("小成");
        assertThat(TechniqueProficiency.labelOf(599)).isEqualTo("小成");
        assertThat(TechniqueProficiency.labelOf(600)).isEqualTo("大成");
        assertThat(TechniqueProficiency.labelOf(799)).isEqualTo("大成");
        assertThat(TechniqueProficiency.labelOf(800)).isEqualTo("圆满");
        assertThat(TechniqueProficiency.labelOf(999)).isEqualTo("圆满");
        assertThat(TechniqueProficiency.labelOf(1000)).isEqualTo("究极奥义");
    }

    @Test
    void 建档起手值落在预期档位() {
        // 精英档 400 = 小成（与默认解锁阈值同档，意味着起手就能被当作前置解锁下一招）
        assertThat(TechniqueProficiency.render(400)).isEqualTo("400/小成");
        // 新人档 200 = 入门（低于阈值，须继续打磨才能领悟后继）
        assertThat(TechniqueProficiency.render(200)).isEqualTo("200/入门");
        // 刚领悟的 1 = 初窥门径
        assertThat(TechniqueProficiency.render(1)).isEqualTo("1/初窥门径");
    }

    @Test
    void 越界值先夹取再判档() {
        assertThat(TechniqueProficiency.clamp(-500)).isEqualTo(TechniqueProficiency.MIN);
        assertThat(TechniqueProficiency.clamp(5000)).isEqualTo(TechniqueProficiency.MAX);
        assertThat(TechniqueProficiency.labelOf(-500)).isEqualTo("未学会");
        assertThat(TechniqueProficiency.render(5000)).isEqualTo("1000/究极奥义");
    }

    @Test
    void 区间完整覆盖零到一千无空隙() {
        // 若将来增删档位导致区间出现空隙，of() 会退化到 NOT_LEARNED，此断言即失败
        for (int v = TechniqueProficiency.MIN; v <= TechniqueProficiency.MAX; v++) {
            TechniqueProficiency tier = TechniqueProficiency.of(v);
            assertThat(tier.label()).as("熟练度 %d 应落在某个档位内", v).isNotBlank();
            assertThat(v).isBetween(tier.from(), tier.to());
            if (v > 0) {
                assertThat(tier).as("熟练度 %d 不应被判为未学会", v)
                        .isNotEqualTo(TechniqueProficiency.NOT_LEARNED);
            }
        }
    }
}
