package com.luyu.agent.rpg.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link TechniqueTreeMap} 与 {@link ConstellationTechniqueMap} 的漂移校验（CI 硬门禁）。
 * <p>
 * 这是 {@code rpg-technique-tree-data} 的<b>核心交付物</b>：树数据是手工维护的静态表，
 * 往招式表加招式时忘了同步建树、或把前置写成悬空引用/环，都会在这里立刻失败，
 * 而不是等到玩家某条进阶链死锁才被发现。
 * <p>
 * <b>刻意不复用生产代码的拓扑排序</b>：本测试自带一份独立的 Kahn 实现，
 * 否则「用 A 验 A」只是同义反复，验不出任何东西。
 */
class TechniqueTreeMapValidationTest {

    /** 招式表的一个条目 */
    private record Entry(String faction, String constellation, List<StarTechnique> techniques) {

        boolean multi() {
            return techniques.size() >= 2;
        }

        String label() {
            return faction + "/" + constellation;
        }

        Set<String> techniqueNames() {
            Set<String> names = new LinkedHashSet<>();
            techniques.forEach(t -> names.add(t.name()));
            return names;
        }
    }

    /** 遍历三张招式表的全部条目（saint + godwarrior + specter） */
    private static List<Entry> allEntries() {
        List<Entry> out = new ArrayList<>();
        for (String faction : TechniqueTreeMap.FACTIONS) {
            ConstellationTechniqueMap.rawTechniqueTable(faction)
                    .forEach((constellation, techs) -> out.add(new Entry(faction, constellation, techs)));
        }
        return out;
    }

    private static List<Entry> multiEntries() {
        return allEntries().stream().filter(Entry::multi).toList();
    }

    // ==================== 2.2 覆盖 ====================

    @Test
    void 覆盖_多技能条目必须建树() {
        List<String> missing = new ArrayList<>();
        for (Entry e : multiEntries()) {
            if (TechniqueTreeMap.getTree(e.constellation(), e.faction()).isEmpty()) {
                missing.add(e.label() + "（" + e.techniques().size() + " 招）");
            }
        }
        assertThat(missing)
                .as("招式数 ≥ 2 的条目必须在 TechniqueTreeMap 有树定义；漏建的条目如下")
                .isEmpty();
    }

    @Test
    void 覆盖_树节点与招式目录逐字一致() {
        // 双向校验：树里有目录没有的节点 = 拼错招式名；目录里有树没有的节点 = 漏建
        List<String> problems = new ArrayList<>();
        for (Entry e : multiEntries()) {
            List<TechniqueNode> nodes = TechniqueTreeMap.getTree(e.constellation(), e.faction());
            Set<String> catalog = e.techniqueNames();
            Set<String> inTree = new LinkedHashSet<>();
            nodes.forEach(n -> inTree.add(n.name()));

            List<String> notInCatalog = inTree.stream().filter(n -> !catalog.contains(n)).toList();
            if (!notInCatalog.isEmpty()) {
                problems.add(e.label() + " 树中节点不在招式目录内（疑似招式名写错）: " + notInCatalog);
            }
            List<String> notInTree = catalog.stream().filter(n -> !inTree.contains(n)).toList();
            if (!notInTree.isEmpty()) {
                problems.add(e.label() + " 招式未建树节点（漏建）: " + notInTree);
            }
        }
        assertThat(problems).as("树节点集合必须与招式目录完全相同").isEmpty();
    }

    @Test
    void 覆盖_不得为不存在的条目建树() {
        List<String> orphans = new ArrayList<>();
        for (String faction : TechniqueTreeMap.FACTIONS) {
            Set<String> catalogKeys = ConstellationTechniqueMap.rawTechniqueTable(faction).keySet();
            for (String key : TechniqueTreeMap.definedKeys(faction)) {
                if (!catalogKeys.contains(key)) {
                    orphans.add(faction + "/" + key);
                }
            }
        }
        assertThat(orphans).as("树表里存在招式表没有的条目 = 死数据").isEmpty();
    }

    @Test
    void 单技能条目_若建树必须是单根节点() {
        for (Entry e : allEntries()) {
            if (e.multi()) {
                continue;
            }
            List<TechniqueNode> nodes = TechniqueTreeMap.getTree(e.constellation(), e.faction());
            if (nodes.isEmpty()) {
                continue;   // 允许不建树，运行时视为单节点树
            }
            assertThat(nodes).as(e.label() + " 只有 1 招，树必须是单节点").hasSize(1);
            assertThat(nodes.get(0).name()).isEqualTo(e.techniques().get(0).name());
            assertThat(nodes.get(0).isRoot()).as(e.label() + " 单节点必须是根").isTrue();
        }
    }

    // ==================== 2.3 可解析 ====================

    @Test
    void 可解析_前置必须在同星座同表内() {
        // 天秤座与天龙座都含「庐山升龙霸」，作用域必须锁在星座内，跨条目引用判错
        List<String> dangling = new ArrayList<>();
        for (Entry e : multiEntries()) {
            Set<String> catalog = e.techniqueNames();
            for (TechniqueNode n : TechniqueTreeMap.getTree(e.constellation(), e.faction())) {
                for (String p : n.prerequisites()) {
                    if (!catalog.contains(p)) {
                        dangling.add(e.label() + " 节点「" + n.name() + "」的前置「" + p + "」无法在本条目内解析");
                    }
                }
            }
        }
        assertThat(dangling).as("前置名必须能在同星座同表的招式目录中找到").isEmpty();
    }

    @Test
    void 跨条目同名招式互不引用() {
        // spec 场景：天秤座与天龙座的「庐山升龙霸」各自作为本条目内的根节点
        for (String constellation : List.of("天秤座", "天龙座")) {
            List<String> roots = TechniqueTreeMap.getRoots(constellation, TechniqueTreeMap.FACTION_SAINT);
            assertThat(roots).as(constellation + " 的「庐山升龙霸」应为本条目内的根节点")
                    .contains("庐山升龙霸");
            assertThat(TechniqueTreeMap.getPrerequisites(constellation,
                    TechniqueTreeMap.FACTION_SAINT, "庐山升龙霸")).isEmpty();
            assertThat(TechniqueTreeMap.getTier(constellation,
                    TechniqueTreeMap.FACTION_SAINT, "庐山升龙霸")).isEqualTo(1);
        }
    }

    // ==================== 2.4 无环 ====================

    @Test
    void 无环_Kahn拓扑排序消费全部节点() {
        for (Entry e : multiEntries()) {
            List<TechniqueNode> nodes = TechniqueTreeMap.getTree(e.constellation(), e.faction());
            Map<String, Integer> indegree = indegreeOf(nodes);
            int processed = kahn(nodes, indegree);
            if (processed != nodes.size()) {
                List<String> cyclic = indegree.entrySet().stream()
                        .filter(en -> en.getValue() > 0)
                        .map(Map.Entry::getKey)
                        .toList();
                fail(e.label() + " 的招式树存在环，进阶链会死锁（玩家永远无法领悟）。环上/环后节点: " + cyclic);
            }
        }
    }

    // ==================== 2.5 有根 + 无自引用 + 无重名 ====================

    @Test
    void 有根_每棵树至少一个根节点() {
        for (Entry e : multiEntries()) {
            List<String> roots = TechniqueTreeMap.getRoots(e.constellation(), e.faction());
            assertThat(roots).as(e.label() + " 必须至少一个根节点，否则建档时无招可发").isNotEmpty();
        }
    }

    @Test
    void 无自引用与重复节点名() {
        for (Entry e : multiEntries()) {
            List<TechniqueNode> nodes = TechniqueTreeMap.getTree(e.constellation(), e.faction());
            Set<String> seen = new LinkedHashSet<>();
            for (TechniqueNode n : nodes) {
                assertThat(seen.add(n.name()))
                        .as(e.label() + " 存在重复节点名「%s」", n.name()).isTrue();
                assertThat(n.prerequisites())
                        .as(e.label() + " 节点「%s」自引用", n.name())
                        .doesNotContain(n.name());
            }
        }
    }

    // ==================== 2.6 tier 一致性 ====================

    @Test
    void tier一致性_根为1且等于拓扑深度() {
        for (Entry e : multiEntries()) {
            List<TechniqueNode> nodes = TechniqueTreeMap.getTree(e.constellation(), e.faction());
            Map<String, Integer> expected = expectedDepth(nodes);

            for (TechniqueNode n : nodes) {
                int actual = TechniqueTreeMap.getTier(e.constellation(), e.faction(), n.name());
                assertThat(actual)
                        .as("%s 节点「%s」的 tier 应等于拓扑深度", e.label(), n.name())
                        .isEqualTo(expected.get(n.name()));
                if (n.isRoot()) {
                    assertThat(actual).as("%s 根节点「%s」tier 必须为 1", e.label(), n.name()).isEqualTo(1);
                } else {
                    int maxPrereq = n.prerequisites().stream()
                            .mapToInt(expected::get).max().orElse(0);
                    assertThat(actual)
                            .as("%s 节点「%s」tier 应为 max(前置 tier)+1", e.label(), n.name())
                            .isEqualTo(maxPrereq + 1);
                }
            }
        }
    }

    @Test
    void 运行时未退化_全部条目均无损坏数据() {
        // topoResolve 对悬空前置/环/重名会静默退化为「全部视为根」，只记 warn 日志。
        // 退化不阻断启动是有意设计，但基线数据必须干净，故在此断言。
        List<String> degraded = new ArrayList<>();
        for (Entry e : multiEntries()) {
            if (TechniqueTreeMap.isDegraded(e.constellation(), e.faction())) {
                degraded.add(e.label());
            }
        }
        assertThat(degraded).as("以下条目的树数据损坏并已在运行时退化，须修复").isEmpty();
    }

    // ==================== 查询与兜底行为 ====================

    @Test
    void 未建树条目_查询返回空且tier未知() {
        // 御夫座仅「御者之鞭」一招，未建树 → 运行时视为单节点树
        assertThat(TechniqueTreeMap.getTree("御夫座", TechniqueTreeMap.FACTION_SAINT)).isEmpty();
        assertThat(TechniqueTreeMap.getRoots("御夫座", TechniqueTreeMap.FACTION_SAINT)).isEmpty();
        assertThat(TechniqueTreeMap.getTier("御夫座", TechniqueTreeMap.FACTION_SAINT, "御者之鞭"))
                .isEqualTo(TechniqueTreeMap.TIER_UNKNOWN);
        // 完全未收录的星座（走 generateFallback）同样不得抛异常
        assertThat(TechniqueTreeMap.getTree("不存在座", TechniqueTreeMap.FACTION_SAINT)).isEmpty();
        assertThat(TechniqueTreeMap.getRoots(null, null)).isEmpty();
    }

    @Test
    void 冥斗士与神斗士回退saint表时树查询同步回退() {
        // ConstellationTechniqueMap.getTechniques 对本表缺失的键会回退 saint 表；
        // getTree 必须同步回退，否则招式名与树节点对不上
        for (Entry e : allEntries()) {
            if (!TechniqueTreeMap.FACTION_SAINT.equals(e.faction())) {
                continue;
            }
            List<StarTechnique> viaSpecter =
                    ConstellationTechniqueMap.getTechniques(e.constellation(), TechniqueTreeMap.FACTION_SPECTER);
            List<TechniqueNode> treeViaSpecter =
                    TechniqueTreeMap.getTree(e.constellation(), TechniqueTreeMap.FACTION_SPECTER);
            assertThat(treeViaSpecter.isEmpty())
                    .as("saint/%s 经 specter 阵营查询时，树与招式的可得性必须一致", e.constellation())
                    .isEqualTo(viaSpecter.size() < 2);
        }
    }

    @Test
    void 天马座前置关系符合spec场景() {
        String c = "天马座";
        String f = TechniqueTreeMap.FACTION_SAINT;

        assertThat(TechniqueTreeMap.getRoots(c, f)).containsExactly("天马流星拳");
        assertThat(TechniqueTreeMap.getPrerequisites(c, f, "天马彗星拳")).containsExactly("天马流星拳");
        assertThat(TechniqueTreeMap.getPrerequisites(c, f, "天马回旋碎击拳")).containsExactly("天马流星拳");
        assertThat(TechniqueTreeMap.getPrerequisites(c, f, "天马闪光拳"))
                .containsExactly("天马彗星拳", "天马回旋碎击拳");
        assertThat(TechniqueTreeMap.getTier(c, f, "天马闪光拳")).isEqualTo(3);
    }

    @Test
    void 多角色合集星座按角色线分支而非拉直() {
        // 双子座含撒加/加隆线（经典系列）与 Ω 帕拉朵珂丝线，二者互不为前置
        String f = TechniqueTreeMap.FACTION_SAINT;
        assertThat(TechniqueTreeMap.getRoots("双子座", f))
                .contains("异次元空间", "幻胧魔皇拳", "幸运之壁");
        assertThat(TechniqueTreeMap.getPrerequisites("双子座", f, "幸运之壁")).isEmpty();
        assertThat(TechniqueTreeMap.getPrerequisites("双子座", f, "最后终点"))
                .containsExactly("世界即终结");

        // 巨蟹座含水柱拳独立支线
        assertThat(TechniqueTreeMap.getRoots("巨蟹座", f)).contains("积尸气冥界波", "水柱拳");
    }

    @Test
    void 三阵营的树条目数与多技能条目数一致() {
        Map<String, Long> multiByFaction = new LinkedHashMap<>();
        for (Entry e : multiEntries()) {
            multiByFaction.merge(e.faction(), 1L, Long::sum);
        }
        Map<String, Integer> treeByFaction = new HashMap<>();
        for (String faction : TechniqueTreeMap.FACTIONS) {
            treeByFaction.put(faction, TechniqueTreeMap.definedKeys(faction).size());
        }
        // 单技能条目允许不建树，故树条目数 <= 多技能条目数；此处断言已全部建满
        for (String faction : TechniqueTreeMap.FACTIONS) {
            assertThat(treeByFaction.get(faction))
                    .as("%s 阵营的树条目数应等于其多技能条目数", faction)
                    .isEqualTo(multiByFaction.getOrDefault(faction, 0L).intValue());
        }
    }

    // ==================== 独立的 Kahn 实现（刻意不复用生产代码） ====================

    private static Map<String, Integer> indegreeOf(List<TechniqueNode> nodes) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (TechniqueNode n : nodes) {
            indegree.put(n.name(), n.prerequisites().size());
        }
        return indegree;
    }

    /** @return 已消费的节点数；小于 {@code nodes.size()} 即存在环 */
    private static int kahn(List<TechniqueNode> nodes, Map<String, Integer> indegree) {
        Map<String, List<String>> successors = new HashMap<>();
        for (TechniqueNode n : nodes) {
            for (String p : n.prerequisites()) {
                successors.computeIfAbsent(p, k -> new ArrayList<>()).add(n.name());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((name, deg) -> {
            if (deg == 0) {
                queue.add(name);
            }
        });
        int processed = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            processed++;
            for (String next : successors.getOrDefault(cur, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        return processed;
    }

    /** 期望的拓扑深度：根 = 1，其余 = max(前置深度) + 1 */
    private static Map<String, Integer> expectedDepth(List<TechniqueNode> nodes) {
        Map<String, Integer> indegree = indegreeOf(nodes);
        Map<String, List<String>> successors = new HashMap<>();
        for (TechniqueNode n : nodes) {
            for (String p : n.prerequisites()) {
                successors.computeIfAbsent(p, k -> new ArrayList<>()).add(n.name());
            }
        }
        Map<String, Integer> depth = new LinkedHashMap<>();
        nodes.forEach(n -> depth.put(n.name(), 1));

        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((name, deg) -> {
            if (deg == 0) {
                queue.add(name);
            }
        });
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (String next : successors.getOrDefault(cur, List.of())) {
                depth.merge(next, depth.get(cur) + 1, Math::max);
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        return depth;
    }
}
