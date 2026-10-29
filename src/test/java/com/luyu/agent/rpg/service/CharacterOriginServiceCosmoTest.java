package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.ConstellationMap;
import com.luyu.agent.rpg.model.OriginResult;
import com.luyu.agent.rpg.model.SpecterStarMap;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CharacterOriginService} 小宇宙初值掷骰测试（rpg-cosmo-sense tasks 1.3 / 2.1）。
 * <p>
 * 钉住三件事：各星阶组区间边界（黄金 [301,700] / 精英 [250,300] / 白银 [150,300] / 其余 [1,300]）、
 * 同输入同 seed 的确定性、两遍计算（origin 展示 / start 落档）一致，以及创角不变量 ∈ [1,700]。
 */
class CharacterOriginServiceCosmoTest {

    private static final LocalDate BIRTH = LocalDate.of(1991, 5, 17);

    /** 遍历多个 seed，断言某星阶的掷骰值恒落在 [min,max]，且至少出现两种不同值（带内分布）。 */
    private void assertRangeAcrossSeeds(String starRank, String faction, int min, int max) {
        int distinct = 0;
        Integer prev = null;
        for (int seed = 0; seed < 200; seed++) {
            int v = CharacterOriginService.rollCosmoIntensity("卢煜", BIRTH, 6, faction, starRank, seed);
            assertThat(v).as("starRank=%s seed=%d", starRank, seed).isBetween(min, max);
            if (prev == null || prev != v) distinct++;
            prev = v;
        }
        assertThat(distinct).as("starRank=%s 应带内分布而非恒定", starRank).isGreaterThan(1);
    }

    @Test
    void 黄金组区间301到700() {
        assertRangeAcrossSeeds(ConstellationMap.GOLD, "saint", 301, 700);
    }

    @Test
    void 三巨头预留星阶同黄金区间() {
        assertRangeAcrossSeeds("Specter-ThreeGreats", "specter", 301, 700);
    }

    @Test
    void 天罡神斗士海斗士精英区间250到300() {
        assertRangeAcrossSeeds(SpecterStarMap.TIAN_GANG, "specter", 250, 300);
        assertRangeAcrossSeeds("GodWarrior", "godwarrior", 250, 300);
        assertRangeAcrossSeeds("Marine", "saint", 250, 300);
    }

    @Test
    void 白银区间150到300() {
        assertRangeAcrossSeeds(ConstellationMap.SILVER, "saint", 150, 300);
    }

    @Test
    void 其余星阶区间1到300() {
        assertRangeAcrossSeeds(ConstellationMap.BRONZE, "saint", 1, 300);
        assertRangeAcrossSeeds(ConstellationMap.HIDDEN, "saint", 1, 300);
        assertRangeAcrossSeeds(SpecterStarMap.DI_SHA, "specter", 1, 300);
    }

    @Test
    void 同输入同seed确定性() {
        int a = CharacterOriginService.rollCosmoIntensity("卢煜", BIRTH, 6, "saint", ConstellationMap.GOLD, 7);
        int b = CharacterOriginService.rollCosmoIntensity("卢煜", BIRTH, 6, "saint", ConstellationMap.GOLD, 7);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void 重roll换seed值改变但仍在区间内() {
        int seed0 = CharacterOriginService.rollCosmoIntensity("卢煜", BIRTH, 6, "saint", ConstellationMap.GOLD, 0);
        int seed1 = CharacterOriginService.rollCosmoIntensity("卢煜", BIRTH, 6, "saint", ConstellationMap.GOLD, 1);
        assertThat(seed1).isBetween(301, 700);
        // 极小概率相等，故只断言区间；分布性由 assertRangeAcrossSeeds 覆盖
        assertThat(seed0).isBetween(301, 700);
    }

    @Test
    void 两遍计算同seed产出同cosmoIntensity() {
        CharacterOriginService service = new CharacterOriginService();
        // timeIndex=6（午时）→ Gold 星阶
        OriginResult first = service.calculate("卢煜", "male", BIRTH, 6, "saint", 3);
        OriginResult second = service.calculate("卢煜", "male", BIRTH, 6, "saint", 3);
        assertThat(first.getCosmoIntensity()).isNotNull();
        assertThat(first.getCosmoIntensity()).isEqualTo(second.getCosmoIntensity());
        // 创角不变量 ∈ [1,700]，起始感阶至多第七感
        assertThat(first.getCosmoIntensity()).isBetween(1, 700);
    }

    @Test
    void calculate无seed重载等价seed0() {
        CharacterOriginService service = new CharacterOriginService();
        OriginResult noSeed = service.calculate("卢煜", "male", BIRTH, 6, "saint");
        OriginResult seed0 = service.calculate("卢煜", "male", BIRTH, 6, "saint", 0);
        assertThat(noSeed.getCosmoIntensity()).isEqualTo(seed0.getCosmoIntensity());
    }
}
