package com.luyu.agent.rpg.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 招式技能树（DAG）静态映射表。
 * <p>
 * 与 {@link ConstellationTechniqueMap} 同键的三张表：键为星座/星辰名，值为该条目的节点定义列表。
 * 每个节点携带<b>前置招式名集合</b>，集合为空即根节点 —— 建档时可无条件起手掌握，
 * 非根节点必须先把全部前置练到解锁阈值才能领悟（阈值判定属 {@code rpg-technique-gate}）。
 * <p>
 * <b>刻意不内嵌进 {@link StarTechnique}</b>：那张 record 有 339 个构造点，加字段会让招式表
 * 从 790 行膨胀到 1200+ 行；代价是两表可能漂移，由 {@code TechniqueTreeMapValidationTest}
 * 的覆盖/可解析断言在 CI 兜住。
 * <p>
 * <b>层级 tier 不手写</b>，由拓扑深度推导（根 = 1，其余 = max(前置 tier) + 1），
 * 少一个可写错的字段。
 * <p>
 * <b>损坏数据不阻断启动</b>：环检测放在单元测试而非静态初始化块 —— 静态块抛异常会导致
 * 应用无法启动，而一棵写坏的树不该有这种杀伤力。运行时遇到悬空前置/成环/重名，
 * 该条目<b>退化为「全部节点视为根」</b>（等价于旧版全量发放），只记 warn 日志。
 */
public final class TechniqueTreeMap {

    private static final Logger log = LoggerFactory.getLogger(TechniqueTreeMap.class);

    /** 阵营标识，与 {@code ConstellationTechniqueMap.getTechniques} 的 switch 分支逐字一致 */
    public static final String FACTION_SAINT = "saint";
    public static final String FACTION_GOD_WARRIOR = "godwarrior";
    public static final String FACTION_SPECTER = "specter";

    /** 全部阵营，供校验测试与离线生成器枚举 */
    public static final List<String> FACTIONS =
            List.of(FACTION_SAINT, FACTION_GOD_WARRIOR, FACTION_SPECTER);

    /** 未知招式名的 tier 返回值（区别于根节点的 1） */
    public static final int TIER_UNKNOWN = -1;

    private TechniqueTreeMap() {
    }

    // ==================== 静态工厂 ====================

    /**
     * 构造一个树节点。
     *
     * @param name          招式名，MUST 与招式表中该条目的名字逐字一致
     * @param prerequisites 前置招式名；不传即根节点
     */
    public static TechniqueNode node(String name, String... prerequisites) {
        return new TechniqueNode(name, List.of(prerequisites));
    }

    /** 构造一棵树（节点声明顺序即渲染顺序，不影响 tier 推导）。 */
    public static List<TechniqueNode> tree(TechniqueNode... nodes) {
        return List.of(nodes);
    }

    // ==================== 圣斗士 88 星座招式树 ====================

    private static final Map<String, List<TechniqueNode>> SAINT_TREES = Map.ofEntries(
            // ---------- 12 Gold ----------
            // 穆：水晶墙是根基，水晶网/星屑旋转由其衍生，星光灭绝为奥义
            Map.entry("白羊座", tree(
                    node("水晶墙"),
                    node("水晶网", "水晶墙"),
                    node("星屑旋转", "水晶墙"),
                    node("星光灭绝", "水晶网", "星屑旋转"))),

            // 阿鲁迪巴线（经典→黄金魂→LC→Ω）与 Ω 哈兵杰的暗属性支线并行
            Map.entry("金牛座", tree(
                    node("巨型号角"),
                    node("最大号角", "巨型号角"),
                    node("泰坦爆发", "巨型号角"),
                    node("泰坦新星", "泰坦爆发"),
                    node("暗影号角"))),

            // 多角色合集：撒加/加隆线 与 Ω 帕拉朵珂丝线各自成支，不可按列表顺序拉直
            Map.entry("双子座", tree(
                    node("异次元空间"),
                    node("幻胧魔皇拳"),
                    node("银河星爆", "异次元空间", "幻胧魔皇拳"),
                    node("起死回星", "银河星爆"),
                    node("银河终结", "银河星爆"),
                    node("幸运之壁"),
                    node("歧路幻景", "幸运之壁"),
                    node("绯红雅努斯", "歧路幻景"),
                    node("世界即终结", "绯红雅努斯"),
                    node("最后终点", "世界即终结"))),

            // 多角色合集：迪斯马斯克/席勒的积尸气主线 与 水柱拳独立支线
            Map.entry("巨蟹座", tree(
                    node("积尸气冥界波"),
                    node("冥界波·改", "积尸气冥界波"),
                    node("积尸气鬼苍炎", "积尸气冥界波"),
                    node("积尸气魂舞", "积尸气鬼苍炎"),
                    node("冥土引导", "积尸气魂舞"),
                    node("积尸之棺", "冥土引导"),
                    node("压榨之墓", "积尸之棺"),
                    node("冥土凋落", "压榨之墓"),
                    node("积尸气冥界轮舞", "冥界波·改", "冥土凋落"),
                    node("水柱拳"))),

            // 艾奥里亚的雷电主线；Ω 迈锡尼的王者系挂在其末端
            Map.entry("狮子座", tree(
                    node("闪电光速拳"),
                    node("雷光放电", "闪电光速拳"),
                    node("狮子之牙", "雷光放电"),
                    node("野兽天降", "狮子之牙"),
                    node("雷鸣·百兽之王", "狮子之牙"),
                    node("王者咆哮", "雷鸣·百兽之王"),
                    node("王者纹章", "王者咆哮"),
                    node("天狮爆破", "王者纹章"))),

            // 多角色合集：沙加线 与 Ω 不动明王线各自成支
            Map.entry("处女座", tree(
                    node("六道轮回"),
                    node("天魔降伏", "六道轮回"),
                    node("天舞宝轮", "天魔降伏"),
                    node("神之眼", "天舞宝轮"),
                    node("诸行断罪"),
                    node("叱呵破障", "诸行断罪"),
                    node("不动明王咒", "叱呵破障"),
                    node("明王来临", "不动明王咒"),
                    node("生死即涅槃", "明王来临"),
                    node("菩提证悟", "生死即涅槃"))),

            // 庐山系正传（童虎/紫龙共用名，但作用域锁在本条目内）+ Ω 玄武自创的真武拳支线
            Map.entry("天秤座", tree(
                    node("庐山升龙霸"),
                    node("庐山龙飞翔", "庐山升龙霸"),
                    node("庐山亢龙霸", "庐山龙飞翔"),
                    node("庐山百龙霸", "庐山亢龙霸"),
                    node("庐山升天霸", "庐山百龙霸"),
                    node("庐山上帝霸", "庐山升天霸"),
                    node("庐山真武拳"))),

            // 米罗：猩红毒针主线 → 安达里斯（心穴终针）；钳制念动波为并行限制技
            Map.entry("天蝎座", tree(
                    node("猩红毒针"),
                    node("钳制念动波"),
                    node("安达里斯", "猩红毒针"))),

            // 艾俄洛斯线 与 Ω 星矢（着射手黄金圣衣）线；宇宙星矢跨支汇聚两者
            Map.entry("射手座", tree(
                    node("原子闪电光速拳"),
                    node("黄金箭", "原子闪电光速拳"),
                    node("晨星之矢", "黄金箭"),
                    node("天马流星拳"),
                    node("宇宙星矢", "天马流星拳", "黄金箭"))),

            // 修罗的圣剑主线 + Ω 独立的精神系支线
            Map.entry("摩羯座", tree(
                    node("圣剑"),
                    node("超绝飞翔", "圣剑"),
                    node("圣剑·修罗", "超绝飞翔"),
                    node("支配语言"))),

            // 多角色合集：卡妙的冰冻线 与 Ω 时贞的时间线各自成支
            Map.entry("水瓶座", tree(
                    node("钻石星尘"),
                    node("冰之圆盘", "钻石星尘"),
                    node("冻结之棺", "冰之圆盘"),
                    node("极光处刑", "冻结之棺"),
                    node("时间拳"),
                    node("时间逆行", "时间拳"),
                    node("时光处刑", "时间逆行"),
                    node("时光统治", "时光处刑"))),

            // 多角色合集：阿布罗狄的玫瑰线（含 Ω 血腥乐曲三部曲）与 Ω 阿莫尔的审判线
            Map.entry("双鱼座", tree(
                    node("皇家恶魔玫瑰"),
                    node("食人鱼玫瑰", "皇家恶魔玫瑰"),
                    node("血腥玫瑰", "食人鱼玫瑰"),
                    node("深红荆棘", "血腥玫瑰"),
                    node("血腥圆舞曲", "深红荆棘"),
                    node("血腥卡农曲", "血腥圆舞曲"),
                    node("血腥芭蕾", "血腥卡农曲"),
                    node("寂静之水"),
                    node("监禁审判", "寂静之水"),
                    node("轮回结晶", "监禁审判"),
                    node("重力协奏曲", "轮回结晶"))),

            // ---------- 4 Hidden ----------
            Map.entry("凤凰座", tree(
                    node("凤凰幻魔拳"),
                    node("凤翼天翔"),
                    node("不死鸟之翼", "凤凰幻魔拳", "凤翼天翔"))),

            // 紫龙的庐山系 与 Ω 的水系新派；「庐山升龙霸」与天秤座同名但互不相关
            Map.entry("天龙座", tree(
                    node("庐山升龙霸"),
                    node("庐山龙飞翔", "庐山升龙霸"),
                    node("庐山亢龙霸", "庐山龙飞翔"),
                    node("庐山百龙霸", "庐山亢龙霸"),
                    node("庐山千龙霸", "庐山百龙霸"),
                    node("水发劲"),
                    node("水龙圆舞", "水发劲"),
                    node("镜花水月", "水龙圆舞"),
                    node("明镜止水", "镜花水月"))),

            Map.entry("印第安座", tree(
                    node("大地之灵"),
                    node("灵魂之箭", "大地之灵"))),

            Map.entry("杜鹃座", tree(
                    node("异域之羽"),
                    node("飞鸟疾风", "异域之羽"))),

            // ---------- Silver ----------
            Map.entry("天琴座", tree(
                    node("幻奏"),
                    node("绝响·死灵之曲", "幻奏"))),

            // 天鹰座的风系进阶：爪 → 切割 → 旋风 → 龙卷，末端为觉醒第七感的闪光暴风
            Map.entry("天鹰座", tree(
                    node("鹰之爪"),
                    node("天鹰暴烈切割", "鹰之爪"),
                    node("爆裂旋风", "天鹰暴烈切割"),
                    node("暴风龙卷风", "爆裂旋风"),
                    node("神圣龙卷风", "暴风龙卷风"),
                    node("天鹰飞翔龙卷风", "神圣龙卷风"),
                    node("天鹰旋翔猎杀", "天鹰飞翔龙卷风"),
                    node("天鹰闪光暴风", "天鹰旋翔猎杀"))),

            Map.entry("仙女座", tree(
                    node("星云锁链"),
                    node("旋转防御", "星云锁链"),
                    node("星云气流", "旋转防御"),
                    node("星云风暴", "星云气流"))),

            Map.entry("英仙座", tree(
                    node("蛇发妖术"),
                    node("美杜莎之盾", "蛇发妖术"))),

            // 星矢：流星拳为根，彗星拳/回旋碎击拳并行，闪光拳（Ω）需二者皆成
            Map.entry("天马座", tree(
                    node("天马流星拳"),
                    node("天马彗星拳", "天马流星拳"),
                    node("天马回旋碎击拳", "天马流星拳"),
                    node("天马闪光拳", "天马彗星拳", "天马回旋碎击拳"))),

            Map.entry("猎户座", tree(
                    node("舞之雷"),
                    node("雷光电返", "舞之雷"),
                    node("怒穹之雷", "雷光电返"),
                    node("雷电龙卷风", "怒穹之雷"),
                    node("猎户螺旋雷电", "雷电龙卷风"),
                    node("猎户绝灭", "猎户螺旋雷电"))),

            Map.entry("蛇夫座", tree(
                    node("毒蛇之牙"),
                    node("雷电钩爪", "毒蛇之牙"))),

            // ---------- Bronze ----------
            Map.entry("白鸟座", tree(
                    node("钻石星尘"),
                    node("冰结环", "钻石星尘"),
                    node("极光雷电攻击", "冰结环"),
                    node("极光处刑", "极光雷电攻击"))),

            Map.entry("长蛇座", tree(
                    node("毒牙攻击"),
                    node("海蛇千毒牙", "毒牙攻击"))),

            Map.entry("乌鸦座", tree(
                    node("风之壁障"),
                    node("风之扳机", "风之壁障"))),

            Map.entry("小马座", tree(
                    node("昴宿星团冲击"),
                    node("昴宿星团暴风雨", "昴宿星团冲击"),
                    node("超新星爆发", "昴宿星团暴风雨"),
                    node("时光永恒终结", "超新星爆发"))),

            Map.entry("猎犬座", tree(
                    node("大地之牙"),
                    node("大地波涛", "大地之牙"),
                    node("大地终章", "大地波涛"))),

            Map.entry("小狮座", tree(
                    node("幼狮炽焰"),
                    node("幼狮爆炎", "幼狮炽焰"),
                    node("幼狮轰炸", "幼狮爆炎"),
                    node("烈焰狂徒", "幼狮轰炸"),
                    node("幼狮闪焰暴风轰炸", "烈焰狂徒"),
                    node("幼狮爆焰轰炸", "幼狮闪焰暴风轰炸"))),

            // 豺狼座：狼爪基础 → 狼群连击 → 罗刹旋风刃 → 岩石/天狼崩 → 白狼拳两形态 → Ω 圣衣终招
            Map.entry("豺狼座", tree(
                    node("狼爪"),
                    node("狼牙攻击", "狼爪"),
                    node("狼群咬杀", "狼牙攻击"),
                    node("狼牙拳·罗刹旋风刃", "狼群咬杀"),
                    node("十文字岩石崩", "狼牙拳·罗刹旋风刃"),
                    node("咆哮天狼崩", "十文字岩石崩"),
                    node("白狼拳·斗牙疾走", "咆哮天狼崩"),
                    node("白狼拳·天神无双", "白狼拳·斗牙疾走"),
                    node("天狼摇滚死亡咆哮", "白狼拳·天神无双"))),

            Map.entry("孔雀座", tree(
                    node("寒光箭雨"),
                    node("孔雀暴风雪", "寒光箭雨")))
    );

    // ==================== 神斗士 7 主星招式树 ====================

    private static final Map<String, List<TechniqueNode>> GOD_WARRIOR_TREES = Map.ofEntries(
            Map.entry("天枢星", tree(
                    node("维京猛虎爪"),
                    node("巴鲁蒙之剑", "维京猛虎爪"))),

            Map.entry("天璇星", tree(
                    node("极光之击"),
                    node("魔火之拳", "极光之击"))),

            Map.entry("天玑星", tree(
                    node("巨人之雷"),
                    node("机关之针", "巨人之雷"))),

            Map.entry("天权星", tree(
                    node("权衡之刃"),
                    node("琥珀之结", "权衡之刃"))),

            Map.entry("玉衡星", tree(
                    node("狼牙·疾风斩"),
                    node("北狼之牙", "狼牙·疾风斩"))),

            Map.entry("开阳星", tree(
                    node("影之双虎爪"),
                    node("光明之盾", "影之双虎爪"))),

            Map.entry("摇光星", tree(
                    node("弦之音·破灭"),
                    node("动荡之雷", "弦之音·破灭")))
    );

    // ==================== 冥斗士冥星招式树 ====================

    private static final Map<String, List<TechniqueNode>> SPECTER_TREES = Map.ofEntries(
            Map.entry("天魁星", tree(
                    node("天魁·冥王之令"),
                    node("冥王之剑", "天魁·冥王之令"))),

            Map.entry("天罡星", tree(
                    node("地狱之锁"),
                    node("星尘旋转", "地狱之锁"))),

            Map.entry("天暴星", tree(
                    node("冥界炎"),
                    node("暗黑日蚀", "冥界炎"))),

            Map.entry("天罪星", tree(
                    node("罪业·万魔穿心"),
                    node("暗黑之翼", "罪业·万魔穿心"))),

            // 冥界三巨头 ① 拉达曼迪斯
            Map.entry("天猛星", tree(
                    node("最大警戒"),
                    node("翼龙咆哮", "最大警戒"))),

            Map.entry("天勇星", tree(
                    node("勇者之盾"),
                    node("英雄冲击", "勇者之盾"))),

            Map.entry("天威星", tree(
                    node("威光之波"),
                    node("震天之啸", "威光之波"))),

            // 冥界三巨头 ② 艾亚哥斯：振翅 → 幻觉 → 炎葬 → 天殛洗礼
            Map.entry("天雄星", tree(
                    node("迦楼罗振翅"),
                    node("宇宙大幻觉", "迦楼罗振翅"),
                    node("银河魔眼炎葬", "宇宙大幻觉"),
                    node("因陀罗天殛洗礼", "银河魔眼炎葬"))),

            // 冥界三巨头 ③ 米诺斯
            Map.entry("天贵星", tree(
                    node("星辰傀儡线"),
                    node("巨翼翔风", "星辰傀儡线"))),

            Map.entry("天暗星", tree(
                    node("暗黑之眼"),
                    node("暗域", "暗黑之眼"))),

            Map.entry("天孤星", tree(
                    node("孤狼之牙"),
                    node("孤独之影", "孤狼之牙"))),

            Map.entry("天伤星", tree(
                    node("伤痕之刃"),
                    node("悲怆之击", "伤痕之刃"))),

            Map.entry("天立星", tree(
                    node("立刻之斩"),
                    node("不屈之志", "立刻之斩"))),

            Map.entry("天寿星", tree(
                    node("寿命剥夺"),
                    node("天寿之裁", "寿命剥夺"))),

            Map.entry("天英星", tree(
                    node("英魂之剑"),
                    node("英气冲霄", "英魂之剑"))),

            Map.entry("天富星", tree(
                    node("黄金冲击"),
                    node("富国之壁", "黄金冲击"))),

            Map.entry("天满星", tree(
                    node("圆满之阵"),
                    node("福泽之波", "圆满之阵"))),

            Map.entry("天佑星", tree(
                    node("庇佑之盾"),
                    node("护持圣光", "庇佑之盾"))),

            Map.entry("天速星", tree(
                    node("瞬闪之击"),
                    node("疾风迅雷", "瞬闪之击"))),

            Map.entry("天微星", tree(
                    node("微尘之术"),
                    node("精微之针", "微尘之术"))),

            Map.entry("天退星", tree(
                    node("退击之阵"),
                    node("淡泊之刃", "退击之阵"))),

            Map.entry("天平星", tree(
                    node("均衡之裁"),
                    node("天平之秤", "均衡之裁"))),

            Map.entry("天败星", tree(
                    node("百战之躯"),
                    node("不败之魂", "百战之躯"))),

            Map.entry("天牢星", tree(
                    node("牢笼之缚"),
                    node("困兽之击", "牢笼之缚"))),

            Map.entry("天慧星", tree(
                    node("洞察之眼"),
                    node("天机之裁", "洞察之眼"))),

            Map.entry("天巧星", tree(
                    node("巧匠之术"),
                    node("精妙之击", "巧匠之术")))
    );

    // ==================== 解析结果缓存 ====================

    /**
     * 一次拓扑解析的结果。
     *
     * @param tiers    招式名 → 层级（根为 1）
     * @param prereqs  招式名 → 生效前置（退化时为空列表）
     * @param roots    根节点名，按声明顺序（退化时为全部节点）
     * @param degraded 是否因数据损坏而退化
     */
    private record ResolvedTree(Map<String, Integer> tiers,
                                Map<String, List<String>> prereqs,
                                List<String> roots,
                                boolean degraded) {

        static final ResolvedTree EMPTY = new ResolvedTree(Map.of(), Map.of(), List.of(), false);
    }

    /** 键为 {@code faction|constellation}；解析结果恒定，故可安全跨请求缓存 */
    private static final Map<String, ResolvedTree> RESOLVED_CACHE = new ConcurrentHashMap<>();

    // ==================== 查询方法 ====================

    /**
     * 取某星座/星辰在指定阵营下的招式树。
     * <p>
     * 查表顺序与 {@link ConstellationTechniqueMap#getTechniques} 完全一致（本表 → saint 表兜底），
     * 否则冥斗士回退到 saint 招式时树会查不到、招式名与节点对不上。
     *
     * @return 节点列表（声明顺序）；该条目未建树时返回<b>空列表</b>，调用方应视为单节点树
     */
    public static List<TechniqueNode> getTree(String constellation, String faction) {
        if (constellation == null || constellation.isBlank()) {
            return List.of();
        }
        List<TechniqueNode> nodes = tableOf(faction).get(constellation);
        if (nodes != null && !nodes.isEmpty()) {
            return nodes;
        }
        if (!FACTION_SAINT.equals(faction)) {
            nodes = SAINT_TREES.get(constellation);
            if (nodes != null && !nodes.isEmpty()) {
                return nodes;
            }
        }
        return List.of();
    }

    /**
     * 取根节点名（建档时无条件可掌握的招式）。
     * <p>
     * 树数据损坏时退化为「全部节点都是根」，等价于旧版的一次性全量发放 —— 宁可少一层门禁，
     * 也不能让玩家无招可用。
     */
    public static List<String> getRoots(String constellation, String faction) {
        return resolve(constellation, faction).roots();
    }

    /**
     * 取某招式的生效前置名。
     *
     * @return 前置名列表；招式不在树中或树已退化时返回空列表
     */
    public static List<String> getPrerequisites(String constellation, String faction, String techniqueName) {
        return resolve(constellation, faction).prereqs().getOrDefault(techniqueName, List.of());
    }

    /**
     * 取某招式的拓扑层级。
     *
     * @return 根节点为 1，其余为 max(前置 tier) + 1；招式不在树中返回 {@link #TIER_UNKNOWN}
     */
    public static int getTier(String constellation, String faction, String techniqueName) {
        return resolve(constellation, faction).tiers().getOrDefault(techniqueName, TIER_UNKNOWN);
    }

    /** 该条目的树是否因数据损坏（悬空前置/环/重名）而退化为全根。供日志与测试断言用。 */
    public static boolean isDegraded(String constellation, String faction) {
        return resolve(constellation, faction).degraded();
    }

    /** 指定阵营下已建树的条目键集合（不做 saint 兜底），供校验测试与生成器枚举。 */
    public static Set<String> definedKeys(String faction) {
        return tableOf(faction).keySet();
    }

    private static Map<String, List<TechniqueNode>> tableOf(String faction) {
        if (faction == null) {
            return SAINT_TREES;
        }
        return switch (faction) {
            case FACTION_SPECTER -> SPECTER_TREES;
            case FACTION_GOD_WARRIOR -> GOD_WARRIOR_TREES;
            default -> SAINT_TREES;
        };
    }

    private static ResolvedTree resolve(String constellation, String faction) {
        List<TechniqueNode> nodes = getTree(constellation, faction);
        if (nodes.isEmpty()) {
            return ResolvedTree.EMPTY;
        }
        String key = (faction == null ? FACTION_SAINT : faction) + "|" + constellation;
        return RESOLVED_CACHE.computeIfAbsent(key, k -> topoResolve(k, nodes));
    }

    // ==================== 拓扑解析 ====================

    /**
     * Kahn 拓扑排序，一趟同时完成环检测与 tier 推导。
     * <p>
     * 任一步不合法即返回退化结果，<b>绝不抛异常</b> —— 本方法在类加载后的首次查询里执行，
     * 抛出去会变成玩家侧的 500，而树写坏只是数据问题。
     */
    private static ResolvedTree topoResolve(String key, List<TechniqueNode> nodes) {
        // 1) 节点名唯一（同名会让前置引用产生歧义）
        Set<String> names = new LinkedHashSet<>();
        for (TechniqueNode n : nodes) {
            if (!names.add(n.name())) {
                log.warn("招式树 [{}] 存在重复节点名「{}」，退化为全部节点视为根", key, n.name());
                return degrade(nodes);
            }
        }

        // 2) 前置必须能在本条目内解析，且不得自引用
        for (TechniqueNode n : nodes) {
            for (String p : n.prerequisites()) {
                if (p.equals(n.name())) {
                    log.warn("招式树 [{}] 节点「{}」自引用，退化为全部节点视为根", key, n.name());
                    return degrade(nodes);
                }
                if (!names.contains(p)) {
                    log.warn("招式树 [{}] 节点「{}」的前置「{}」不在本条目内（悬空引用），退化为全部节点视为根",
                            key, n.name(), p);
                    return degrade(nodes);
                }
            }
        }

        // 3) Kahn：入度为 0 者入队，出队时其 tier 已定，用它松弛后继
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, Integer> tiers = new LinkedHashMap<>();
        Map<String, List<String>> prereqs = new LinkedHashMap<>();
        for (TechniqueNode n : nodes) {
            indegree.put(n.name(), n.prerequisites().size());
            tiers.put(n.name(), 1);
            prereqs.put(n.name(), n.prerequisites());
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

        List<String> roots = new ArrayList<>(queue);
        int processed = 0;
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            processed++;
            int curTier = tiers.get(cur);
            for (String next : successors.getOrDefault(cur, List.of())) {
                tiers.merge(next, curTier + 1, Math::max);
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }

        if (processed != names.size()) {
            List<String> cyclic = indegree.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .toList();
            log.warn("招式树 [{}] 存在环，涉及节点 {}，退化为全部节点视为根", key, cyclic);
            return degrade(nodes);
        }

        return new ResolvedTree(Map.copyOf(tiers), Map.copyOf(prereqs), List.copyOf(roots), false);
    }

    /** 退化：全部节点视为根、tier 恒为 1、前置清空。 */
    private static ResolvedTree degrade(List<TechniqueNode> nodes) {
        Map<String, Integer> tiers = new LinkedHashMap<>();
        Map<String, List<String>> prereqs = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (TechniqueNode n : nodes) {
            tiers.put(n.name(), 1);
            prereqs.put(n.name(), List.of());
            roots.add(n.name());
        }
        return new ResolvedTree(Map.copyOf(tiers), Map.copyOf(prereqs), List.copyOf(roots), true);
    }
}
