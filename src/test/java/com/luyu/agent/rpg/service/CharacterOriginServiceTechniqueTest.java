package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.config.RpgTechniqueProperties;
import com.luyu.agent.rpg.model.ConstellationMap;
import com.luyu.agent.rpg.model.ConstellationTechniqueMap;
import com.luyu.agent.rpg.model.OriginResult;
import com.luyu.agent.rpg.model.SpecterStarMap;
import com.luyu.agent.rpg.model.StarTechnique;
import com.luyu.agent.rpg.model.TechniqueState;
import com.luyu.agent.rpg.model.TechniqueTreeMap;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 建档初始招式发放测试（rpg-technique-tree-data tasks 4.5）。
 * <p>
 * 钉住四件事：三阵营六星阶的发放分档（精英档全根 400 / 新人档抽 1 根 200）、
 * 单节点树恒已学、SHA-256 种子确定性（同生辰同姓名重复调用完全一致）、
 * 经典系列首选集生效（青铜天马座必中「天马流星拳」、巨蟹座必中「积尸气冥界波」、
 * 双子座新人档永不抽中 Ω 根「幸运之壁」）。
 */
class CharacterOriginServiceTechniqueTest {

    private static final LocalDate BIRTH = LocalDate.of(1991, 5, 17);

    private static List<TechniqueState> grant(String constellation, String faction, String rank, String seedKey) {
        List<StarTechnique> catalog = ConstellationTechniqueMap.getTechniques(constellation, faction);
        return CharacterOriginService.grantInitialTechniques(catalog, constellation, faction, rank,
                seedKey, new RpgTechniqueProperties());
    }

    private static TechniqueState find(List<TechniqueState> states, String name) {
        return states.stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("招式不在目录: " + name));
    }

    private static long learnedCount(List<TechniqueState> states) {
        return states.stream().filter(TechniqueState::learned).count();
    }

    @Test
    void 黄金圣斗士起手通晓全部根节点小成() {
        List<TechniqueState> states = grant("双子座", "saint", ConstellationMap.GOLD, "1991-05-17|6|saint|卢煜");
        // 三根节点全已学 + 400（小成）
        for (String root : List.of("异次元空间", "幻胧魔皇拳", "幸运之壁")) {
            TechniqueState t = find(states, root);
            assertThat(t.learned()).as(root).isTrue();
            assertThat(t.proficiency()).as(root).isEqualTo(400);
        }
        // 非根节点未学 + 0，且携带树结构字段
        TechniqueState galaxy = find(states, "银河星爆");
        assertThat(galaxy.learned()).isFalse();
        assertThat(galaxy.proficiency()).isZero();
        assertThat(galaxy.tier()).isEqualTo(2);
        assertThat(galaxy.prerequisites()).containsExactly("异次元空间", "幻胧魔皇拳");
        assertThat(learnedCount(states)).isEqualTo(3);
    }

    @Test
    void 天罡冥斗士与神斗士同档黄金() {
        List<TechniqueState> specter = grant("天魁星", "specter", SpecterStarMap.TIAN_GANG, "1991-05-17|6|specter|卢煜");
        assertThat(find(specter, "天魁·冥王之令").learned()).isTrue();
        assertThat(find(specter, "天魁·冥王之令").proficiency()).isEqualTo(400);
        assertThat(find(specter, "冥王之剑").learned()).isFalse();

        List<TechniqueState> god = grant("天枢星", "godwarrior", "GodWarrior", "1991-05-17|6|godwarrior|卢煜");
        assertThat(find(god, "维京猛虎爪").learned()).isTrue();
        assertThat(find(god, "维京猛虎爪").proficiency()).isEqualTo(400);
        assertThat(find(god, "巴鲁蒙之剑").learned()).isFalse();
    }

    @Test
    void 青铜白银隐藏地煞新人档只抽一根入门() {
        List<TechniqueState> bronze = grant("天马座", "saint", ConstellationMap.BRONZE, "1991-05-17|1|saint|卢煜");
        assertThat(learnedCount(bronze)).isEqualTo(1);
        TechniqueState picked = bronze.stream().filter(TechniqueState::learned).findFirst().orElseThrow();
        // 经典系列首选集生效：天马座唯一根即招牌招
        assertThat(picked.name()).isEqualTo("天马流星拳");
        assertThat(picked.proficiency()).isEqualTo(200);

        // 巨蟹座两根（积尸气冥界波=经典 / 水柱拳=Ω）：首选集单元素必中招牌招
        List<TechniqueState> silver = grant("巨蟹座", "saint", ConstellationMap.SILVER, "1991-05-17|3|saint|卢煜");
        assertThat(learnedCount(silver)).isEqualTo(1);
        assertThat(find(silver, "积尸气冥界波").learned()).isTrue();
        assertThat(find(silver, "积尸气冥界波").proficiency()).isEqualTo(200);

        // 隐藏星座与地煞冥斗士同新人档
        List<TechniqueState> hidden = grant("凤凰座", "saint", ConstellationMap.HIDDEN, "1991-05-17|11|saint|卢煜");
        assertThat(learnedCount(hidden)).isEqualTo(1);
        assertThat(hidden.stream().filter(TechniqueState::learned).findFirst().orElseThrow().proficiency())
                .isEqualTo(200);
        List<TechniqueState> diSha = grant("天魁星", "specter", SpecterStarMap.DI_SHA, "1991-05-17|1|specter|卢煜");
        assertThat(learnedCount(diSha)).isEqualTo(1);
        assertThat(find(diSha, "天魁·冥王之令").proficiency()).isEqualTo(200);
    }

    @Test
    void 双子座新人档永不抽中Ω根且不同姓名能落不同根() {
        Set<String> picked = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            List<TechniqueState> states = grant("双子座", "saint", ConstellationMap.BRONZE,
                    "1991-05-17|1|saint|候选" + i);
            String name = states.stream().filter(TechniqueState::learned).map(TechniqueState::name)
                    .findFirst().orElse("");
            // 经典系列首选集：Ω 根「幸运之壁」永不入起手
            assertThat(name).isIn("异次元空间", "幻胧魔皇拳");
            picked.add(name);
        }
        // 60 个种子覆盖两根（概率上必然），钉住「不同姓名能落到不同根节点」
        assertThat(picked).containsExactlyInAnyOrder("异次元空间", "幻胧魔皇拳");
    }

    @Test
    void 单节点树恒为已学精英档() {
        // 御夫座仅「御者之鞭」一招、无树定义：视为单节点树，新人档星阶也恒已学 + 400
        List<TechniqueState> states = grant("御夫座", "saint", ConstellationMap.BRONZE, "1991-05-17|1|saint|卢煜");
        assertThat(states).hasSize(1);
        assertThat(states.get(0).learned()).isTrue();
        assertThat(states.get(0).proficiency()).isEqualTo(400);
        assertThat(states.get(0).tier()).isEqualTo(1);
        assertThat(states.get(0).prerequisites()).isEmpty();
    }

    @Test
    void 同生辰同姓名重复调用结果完全一致() {
        List<TechniqueState> first = grant("双子座", "saint", ConstellationMap.BRONZE, "1991-05-17|1|saint|卢煜");
        List<TechniqueState> second = grant("双子座", "saint", ConstellationMap.BRONZE, "1991-05-17|1|saint|卢煜");
        assertThat(first).isEqualTo(second);

        // 端到端：calculate 两遍（origin 展示 / start 落档）招式分布一致
        CharacterOriginService service = new CharacterOriginService();
        OriginResult a = service.calculate("卢煜", "male", BIRTH, 6, "saint");
        OriginResult b = service.calculate("卢煜", "male", BIRTH, 6, "saint");
        assertThat(a.getTechniques()).isNotNull();
        assertThat(a.getTechniques()).isEqualTo(b.getTechniques());
    }

    @Test
    void 种子取SHA256前四字节无符号int() {
        // 钉住 D5 种子算法本身：同输入恒同值、不同输入不同值（分布由上一测试覆盖）
        long a = CharacterOriginService.sha256Seed("1991-05-17|1|saint|卢煜");
        long b = CharacterOriginService.sha256Seed("1991-05-17|1|saint|卢煜");
        assertThat(a).isEqualTo(b);
        assertThat(a).isBetween(0L, 4294967295L);
        assertThat(CharacterOriginService.sha256Seed("1991-05-17|1|saint|卢煜煜")).isNotEqualTo(a);
    }

    @Test
    void 精英档判定只认三星阶() {
        assertThat(CharacterOriginService.isEliteRank(ConstellationMap.GOLD)).isTrue();
        assertThat(CharacterOriginService.isEliteRank(SpecterStarMap.TIAN_GANG)).isTrue();
        assertThat(CharacterOriginService.isEliteRank("GodWarrior")).isTrue();
        assertThat(CharacterOriginService.isEliteRank(ConstellationMap.SILVER)).isFalse();
        assertThat(CharacterOriginService.isEliteRank(ConstellationMap.BRONZE)).isFalse();
        assertThat(CharacterOriginService.isEliteRank(ConstellationMap.HIDDEN)).isFalse();
        assertThat(CharacterOriginService.isEliteRank(SpecterStarMap.DI_SHA)).isFalse();
    }

    @Test
    void 树根集合与发放已学集合一致() {
        // 黄金档已学集合 MUST 恰为树根集合（双子座三根，含 Ω 根「幸运之壁」）
        List<TechniqueState> states = grant("双子座", "saint", ConstellationMap.GOLD, "seed");
        List<String> roots = TechniqueTreeMap.getRoots("双子座", "saint");
        assertThat(states.stream().filter(TechniqueState::learned).map(TechniqueState::name).toList())
                .containsExactlyElementsOf(roots);
    }
}
