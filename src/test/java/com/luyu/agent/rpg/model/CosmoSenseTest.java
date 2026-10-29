package com.luyu.agent.rpg.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CosmoSense} 档位边界测试（rpg-cosmo-sense tasks 1.1）。
 * <p>
 * 钉住分档措辞单一真源的边界：0/1/300/301/700/701/1200/1201，以及负值夹取、render 格式。
 */
class CosmoSenseTest {

    @Test
    void 档位边界() {
        assertThat(CosmoSense.labelOf(0)).isEqualTo("不具备第六感");
        assertThat(CosmoSense.labelOf(1)).isEqualTo("第六感");
        assertThat(CosmoSense.labelOf(300)).isEqualTo("第六感");
        assertThat(CosmoSense.labelOf(301)).isEqualTo("第七感");
        assertThat(CosmoSense.labelOf(700)).isEqualTo("第七感");
        assertThat(CosmoSense.labelOf(701)).isEqualTo("第八感");
        assertThat(CosmoSense.labelOf(1200)).isEqualTo("第八感");
        assertThat(CosmoSense.labelOf(1201)).isEqualTo("第九感");
    }

    @Test
    void 第九感为开区间无上界() {
        assertThat(CosmoSense.labelOf(999999)).isEqualTo("第九感");
        assertThat(CosmoSense.of(Integer.MAX_VALUE)).isEqualTo(CosmoSense.NINTH);
    }

    @Test
    void 负值夹取到不具备第六感() {
        assertThat(CosmoSense.of(-5)).isEqualTo(CosmoSense.NOT_AWAKENED);
        assertThat(CosmoSense.labelOf(-1)).isEqualTo("不具备第六感");
    }

    @Test
    void render格式() {
        assertThat(CosmoSense.render(268)).isEqualTo("268（第六感）");
        assertThat(CosmoSense.render(500)).isEqualTo("500（第七感）");
        // 0 特例：不带数值
        assertThat(CosmoSense.render(0)).isEqualTo("不具备第六感");
    }

    @Test
    void 主角下界1永不为0() {
        assertThat(CosmoSense.PLAYER_MIN).isEqualTo(1);
        assertThat(CosmoSense.NPC_MIN).isEqualTo(0);
    }
}
