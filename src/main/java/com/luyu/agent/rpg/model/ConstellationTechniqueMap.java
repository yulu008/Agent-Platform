package com.luyu.agent.rpg.model;

import java.util.List;
import java.util.Map;

/**
 * 星座/星辰招式静态映射表。
 * <p>
 * 参考圣斗士星矢各版本（经典系列、黄金魂、Ω、圣斗少女翔、Asgard 篇、冥界篇、冥王神话 LC），
 * 为每个星座/星辰尽可能收录其标志性招式（数量不限）。
 * <p>
 * 未收录的星座/星辰自动生成主题化招式（基于星座名 + 加成属性）。
 */
public class ConstellationTechniqueMap {

    private ConstellationTechniqueMap() {
    }

    // ==================== 圣斗士 88 星座招式 ====================

    private static final Map<String, List<StarTechnique>> SAINT_TECHNIQUES = Map.ofEntries(
            // ---------- 12 Gold ----------
            Map.entry("白羊座", List.of(
                    new StarTechnique("星光灭绝", "以星光将敌人放逐至异次元的绝技", "经典系列", AttackType.SPATIAL),
                    new StarTechnique("水晶墙", "生成无形水晶屏障，弹回一切攻击", "经典系列"),
                    new StarTechnique("水晶网", "水晶墙的变形招式，束缚对手动作", "经典系列"),
                    new StarTechnique("星屑旋转", "操纵星屑形成漩涡攻击", "经典系列"))),

            Map.entry("金牛座", List.of(
                    new StarTechnique("巨型号角", "以光速挥出双臂形成巨大冲击波", "经典系列"),
                    new StarTechnique("泰坦爆发", "将地面能量集中于一点引发大地震", "黄金魂"),
                    new StarTechnique("泰坦新星", "燃烧极限小宇宙释放的究极爆发", "冥王神话LC"),
                    new StarTechnique("最大号角", "积蓄全部小宇宙发出的终极冲击波", "Ω"),
                    new StarTechnique("暗影号角", "哈兵杰使出的暗属性号角冲击波", "Ω"))),

            Map.entry("双子座", List.of(
                    new StarTechnique("银河星爆", "凝聚银河能量爆发的究极奥义", "经典系列"),
                    new StarTechnique("异次元空间", "撕裂时空将敌人投入异次元", "经典系列", AttackType.SPATIAL),
                    new StarTechnique("幻胧魔皇拳", "侵入敌人精神，操纵其意志", "经典系列", AttackType.MENTAL),
                    new StarTechnique("起死回星", "以星之力令万物复苏或毁灭的奥义", "冥王神话LC"),
                    new StarTechnique("幸运之壁", "帕拉朵珂丝的风系防御技能，展开守护之壁", "Ω"),
                    new StarTechnique("歧路幻景", "在亚空间向对手展示无数抉择分裂出的数十亿未来", "Ω", AttackType.MENTAL),
                    new StarTechnique("绯红雅努斯", "以双面神雅努斯之名的绯红攻击", "Ω"),
                    new StarTechnique("世界即终结", "里人格释放的终结世界的攻击", "Ω"),
                    new StarTechnique("最后终点", "帕拉朵珂丝的最终奥义", "Ω"),
                    new StarTechnique("银河终结", "银河星爆的改良版，威力更胜一筹", "Ω"))),

            Map.entry("巨蟹座", List.of(
                    new StarTechnique("积尸气冥界波", "召唤冥界积尸气的波动攻击", "经典系列"),
                    new StarTechnique("积尸气鬼苍炎", "积尸气化为鬼苍火焰灼烧敌人", "冥王神话LC"),
                    new StarTechnique("积尸气魂舞", "以积尸气操纵死者灵魂起舞攻击", "冥王神话LC", AttackType.MENTAL),
                    new StarTechnique("冥界波·改", "强化版冥界波，可同时波及多名敌人", "Ω"),
                    new StarTechnique("积尸气冥界轮舞", "席勒的最大奥义，以积尸气召唤冥界轮舞吞噬敌人", "Ω"),
                    new StarTechnique("冥土凋落", "使敌人的生命如冥土之花般凋零", "Ω"),
                    new StarTechnique("冥土引导", "以积尸气引导灵魂通往冥界", "Ω", AttackType.MENTAL),
                    new StarTechnique("积尸之棺", "以积尸气凝聚成棺封住敌人", "Ω"),
                    new StarTechnique("水柱拳", "化作水柱冲击的光速拳", "Ω"),
                    new StarTechnique("压榨之墓", "以墓之重压碾碎敌人", "Ω"))),

            Map.entry("狮子座", List.of(
                    new StarTechnique("闪电光速拳", "以光速发出无数雷拳", "经典系列"),
                    new StarTechnique("雷光放电", "将雷电凝聚于拳尖释放", "经典系列"),
                    new StarTechnique("狮子之牙", "将雷电化为獠牙撕裂敌人", "黄金魂"),
                    new StarTechnique("雷鸣·百兽之王", "以万兽之王咆哮震碎一切", "冥王神话LC"),
                    new StarTechnique("王者咆哮", "迈锡尼以百兽之王咆哮震碎敌人", "Ω"),
                    new StarTechnique("王者纹章", "以狮子座王者纹章释放的能量攻击", "Ω"),
                    new StarTechnique("天狮爆破", "以狮子座α星轩辕十四之力爆破", "Ω"),
                    new StarTechnique("野兽天降", "化猛兽之势自天而降的扑击", "Ω"))),

            Map.entry("处女座", List.of(
                    new StarTechnique("天舞宝轮", "封闭敌人六感的最强奥义", "经典系列", AttackType.MENTAL),
                    new StarTechnique("六道轮回", "将敌人灵魂投入六道轮回", "经典系列", AttackType.MENTAL),
                    new StarTechnique("天魔降伏", "以天魔之力降伏一切敌人", "经典系列", AttackType.MENTAL),
                    new StarTechnique("神之眼", "开启神之眼洞察并封印敌人一切行动", "冥王神话LC", AttackType.MENTAL),
                    new StarTechnique("诸行断罪", "以左手金刚锁束缚邪恶、断罪敌人", "Ω"),
                    new StarTechnique("叱呵破障", "以右手智慧之剑将束缚转为杀伤的破障技", "Ω"),
                    new StarTechnique("不动明王咒", "诵咒召唤地狱业火灼烧对手", "Ω"),
                    new StarTechnique("明王来临", "召唤五大明王，自其眼射出七彩光枪重创敌人", "Ω"),
                    new StarTechnique("生死即涅槃", "将敌人置于清净世界令其失去战意沉醉而逝", "Ω", AttackType.MENTAL),
                    new StarTechnique("菩提证悟", "处女座觉悟之力的究极奥义", "Ω", AttackType.MENTAL))),

            Map.entry("天秤座", List.of(
                    new StarTechnique("庐山升龙霸", "升龙之势挥出的右拳绝技", "经典系列"),
                    new StarTechnique("庐山龙飞翔", "以全身化为金龙高速移动攻击", "经典系列"),
                    new StarTechnique("庐山亢龙霸", "舍身跃至极限高空以龙形斗气下冲", "经典系列"),
                    new StarTechnique("庐山百龙霸", "释放百条龙形斗气同时攻击", "经典系列"),
                    new StarTechnique("庐山真武拳", "玄武自创的真武之拳", "Ω"),
                    new StarTechnique("庐山上帝霸", "庐山系究极霸道之拳", "Ω"),
                    new StarTechnique("庐山升天霸", "升龙霸的上位，化龙升天的绝技", "Ω"))),

            Map.entry("天蝎座", List.of(
                    new StarTechnique("猩红毒针", "以指力发出致命毒针，命中要穴", "经典系列"),
                    new StarTechnique("钳制念动波", "以念力束缚对手行动的限制技", "经典系列", AttackType.MENTAL),
                    new StarTechnique("安达里斯", "天蝎心穴之针，一击致命的终招", "经典系列"))),

            Map.entry("射手座", List.of(
                    new StarTechnique("原子闪电光速拳", "以原子级雷电化为光速拳", "经典系列"),
                    new StarTechnique("黄金箭", "射出蕴含射手座全部小宇宙的光箭", "经典系列"),
                    new StarTechnique("晨星之矢", "以晨星之光凝聚的追踪光矢", "冥王神话LC"),
                    new StarTechnique("天马流星拳", "星矢身着射手座黄金圣衣时使出的流星拳", "Ω"),
                    new StarTechnique("宇宙星矢", "射手座星矢的终极光矢奥义", "Ω"))),

            Map.entry("摩羯座", List.of(
                    new StarTechnique("圣剑", "右手化为可斩裂万物的光之圣剑", "经典系列"),
                    new StarTechnique("超绝飞翔", "跃至空中劈下的强化圣剑", "经典系列"),
                    new StarTechnique("圣剑·修罗", "将小宇宙集中于刀刃化为究极圣剑", "冥王神话LC"),
                    new StarTechnique("支配语言", "以支配之言叶操控敌人心智与小宇宙", "Ω", AttackType.MENTAL))),

            Map.entry("水瓶座", List.of(
                    new StarTechnique("极光处刑", "冻结一切的究极奥义", "经典系列"),
                    new StarTechnique("钻石星尘", "释放零下数百度的冰冻粒子", "经典系列"),
                    new StarTechnique("冰之圆盘", "形成旋转冰盘切割敌人", "经典系列"),
                    new StarTechnique("冻结之棺", "将敌人封入永冻冰棺之中", "冥王神话LC"),
                    new StarTechnique("时光处刑", "时贞操纵时间对敌人施以的时之处刑", "Ω", AttackType.SPATIAL),
                    new StarTechnique("时间拳", "时贞操纵时间打出的时之拳", "Ω"),
                    new StarTechnique("时间逆行", "令局部时间倒流的术法", "Ω", AttackType.SPATIAL),
                    new StarTechnique("时光统治", "支配一定范围时间的领域技", "Ω", AttackType.SPATIAL))),

            Map.entry("双鱼座", List.of(
                    new StarTechnique("皇家恶魔玫瑰", "掷出汲取生命的毒玫瑰", "经典系列"),
                    new StarTechnique("食人鱼玫瑰", "可吞噬敌人小宇宙的攻击玫瑰", "经典系列"),
                    new StarTechnique("血腥玫瑰", "贯穿心脏后绽放吸取全部生命", "经典系列"),
                    new StarTechnique("深红荆棘", "以荆棘化为深红毒刺贯穿全身", "冥王神话LC"),
                    new StarTechnique("寂静之水", "阿莫尔释放的寂静之水将敌人分隔封锁", "Ω"),
                    new StarTechnique("监禁审判", "以光之枷锁锁定敌人进行拘禁审判", "Ω"),
                    new StarTechnique("轮回结晶", "以结晶封锁敌人的轮回", "Ω"),
                    new StarTechnique("血腥圆舞曲", "如圆舞曲般绽放的血腥玫瑰连击", "Ω"),
                    new StarTechnique("血腥卡农曲", "卡农式的连续血腥玫瑰攻击", "Ω"),
                    new StarTechnique("重力协奏曲", "以重力协奏曲碾压敌人", "Ω"),
                    new StarTechnique("血腥芭蕾", "如芭蕾舞步绽放的血腥玫瑰终曲", "Ω"))),

            // ---------- 4 Hidden ----------
            Map.entry("凤凰座", List.of(
                    new StarTechnique("凤凰幻魔拳", "燃烧敌人内心恐惧的幻魔拳", "经典系列", AttackType.MENTAL),
                    new StarTechnique("凤翼天翔", "展翅飞冲以火焰灼烧一切", "经典系列"),
                    new StarTechnique("不死鸟之翼", "浴火重生后释放的终极火焰之翼", "Ω"))),

            Map.entry("天龙座", List.of(
                    new StarTechnique("庐山升龙霸", "升龙之势挥出的右拳绝技", "经典系列"),
                    new StarTechnique("庐山龙飞翔", "升龙霸的上位变体招式", "经典系列"),
                    new StarTechnique("庐山亢龙霸", "舍身跃至极限高空以龙形斗气下冲", "经典系列"),
                    new StarTechnique("庐山百龙霸", "百龙齐发的究极奥义", "黄金魂"),
                    new StarTechnique("庐山千龙霸", "百龙霸的上位，释放千条龙形斗气齐攻", "Ω"),
                    new StarTechnique("水发劲", "以水属性小宇宙发出的柔韧暗劲", "Ω"),
                    new StarTechnique("水龙圆舞", "化水为龙形旋转缠绕攻击", "Ω"),
                    new StarTechnique("镜花水月", "以水之幻影迷惑敌人的回避防御技", "Ω", AttackType.MENTAL),
                    new StarTechnique("明镜止水", "心如明镜止水般感知并化解一切攻击", "Ω"))),

            Map.entry("印第安座", List.of(
                    new StarTechnique("大地之灵", "呼唤大地精灵之力化为攻防一体的术法", "主题原创"),
                    new StarTechnique("灵魂之箭", "以精神力凝聚的无形箭矢穿透意志", "主题原创"))),

            Map.entry("杜鹃座", List.of(
                    new StarTechnique("异域之羽", "以异域飞羽化为利刃风暴", "主题原创"),
                    new StarTechnique("飞鸟疾风", "化身疾风的高速突进攻击", "主题原创"))),

            // ---------- 24 Silver（有 canonical 的） ----------
            Map.entry("天琴座", List.of(
                    new StarTechnique("绝响·死灵之曲", "以琴弦奏出引导死灵的镇魂曲", "冥界篇", AttackType.MENTAL),
                    new StarTechnique("幻奏", "以琴音制造幻象扰乱敌人感知", "经典系列", AttackType.MENTAL))),

            Map.entry("天鹰座", List.of(
                    new StarTechnique("鹰之爪", "以鹰爪之势撕裂敌人", "经典系列"),
                    new StarTechnique("爆裂旋风", "高速旋转身体积蓄风力后射出小型龙卷", "Ω"),
                    new StarTechnique("天鹰闪光暴风", "觉醒第七感将小宇宙凝于双腿踢出鹰形气流化为巨大龙卷", "Ω"),
                    new StarTechnique("神圣龙卷风", "于腿部积蓄风力踢出花瓣状龙卷风", "Ω"),
                    new StarTechnique("天鹰旋翔猎杀", "大跳跃高速回旋如彗星般俯冲踢击敌人头部", "Ω"),
                    new StarTechnique("暴风龙卷风", "以自身高速旋转制造强力龙卷风", "Ω"),
                    new StarTechnique("天鹰飞翔龙卷风", "神圣龙卷风的强化版，倒立展腿制造龙卷后冲向敌人", "Ω"),
                    new StarTechnique("天鹰暴烈切割", "以风之刃高速切割敌人的猛击", "Ω"))),

            Map.entry("仙女座", List.of(
                    new StarTechnique("星云锁链", "以锁链形成星云图案攻击", "经典系列"),
                    new StarTechnique("旋转防御", "高速旋转锁链形成防御壁", "经典系列"),
                    new StarTechnique("星云气流", "以星云气流限制敌人行动", "经典系列"),
                    new StarTechnique("星云风暴", "以星云气流化为破坏风暴", "经典系列"))),

            Map.entry("英仙座", List.of(
                    new StarTechnique("美杜莎之盾", "以盾中蛇发妖术石化敌人", "经典系列", AttackType.MENTAL),
                    new StarTechnique("蛇发妖术", "操纵蛇发进行攻击与束缚", "经典系列"))),

            Map.entry("天马座", List.of(
                    new StarTechnique("天马流星拳", "以光速挥出无数流星般的拳", "经典系列"),
                    new StarTechnique("天马彗星拳", "将全部拳力集中于一点的终招", "经典系列"),
                    new StarTechnique("天马回旋碎击拳", "抱起对手旋转飞上高空摔落粉碎", "经典系列"),
                    new StarTechnique("天马闪光拳", "光属性小宇宙聚于拳，命中瞬间爆发致盲闪光与破坏力", "Ω"))),

            Map.entry("御夫座", List.of(
                    new StarTechnique("御者之鞭", "以光速挥出的能量之鞭", "经典系列"))),

            Map.entry("猎户座", List.of(
                    new StarTechnique("猎户绝灭", "以猎户座全部小宇宙释放的灭绝一击", "Ω"),
                    new StarTechnique("雷光电返", "伊甸以雷电返还之势反击敌人", "Ω"),
                    new StarTechnique("舞之雷", "如舞蹈般跃动连击的雷之技", "Ω"),
                    new StarTechnique("怒穹之雷", "自愤怒苍穹降下的狂雷", "Ω"),
                    new StarTechnique("雷电龙卷风", "以雷电凝聚成龙卷风席卷敌人", "Ω"),
                    new StarTechnique("猎户螺旋雷电", "螺旋状雷电贯穿敌人的猎户绝技", "Ω"))),

            Map.entry("蛇夫座", List.of(
                    new StarTechnique("毒蛇之牙", "以毒牙之势贯穿敌人防御", "经典系列"),
                    new StarTechnique("雷电钩爪", "莎尔娜以雷电缠绕的钩爪撕裂敌人", "Ω"))),

            Map.entry("仙王座", List.of(
                    new StarTechnique("星之锁", "以星光化为锁链束缚敌人", "经典系列"))),

            Map.entry("仙后座", List.of(
                    new StarTechnique("王后之冠", "以冠形光轮切割敌人", "主题原创"))),

            Map.entry("北冕座", List.of(
                    new StarTechnique("荆冠之光", "以荆冠化为光刃缠绕攻击", "主题原创"))),

            Map.entry("海豚座", List.of(
                    new StarTechnique("海豚水爆弹", "古尼以水凝成的海豚形爆弹冲击敌人", "Ω"))),

            Map.entry("巨蛇座", List.of(
                    new StarTechnique("蛇之缠绕", "以斗气化为巨蛇缠绕绞杀", "主题原创"))),

            Map.entry("小犬座", List.of(
                    new StarTechnique("警犬之牙", "以猎犬之牙之势快速撕咬", "主题原创"))),

            // ---------- 48 Bronze（主要的 + 主题原创） ----------
            Map.entry("白鸟座", List.of(
                    new StarTechnique("钻石星尘", "释放零下数百度的冰冻粒子", "经典系列"),
                    new StarTechnique("极光雷电攻击", "双手发出蕴含雷电的巨大冻气龙卷风", "经典系列"),
                    new StarTechnique("冰结环", "全身散发冻气化为巨大冰环冻结并粉碎敌人", "经典系列"),
                    new StarTechnique("极光处刑", "冻结一切的究极奥义", "经典系列"))),

            Map.entry("麒麟座", List.of(
                    new StarTechnique("麒麟之蹄", "以圣兽之蹄踏碎敌人防御", "主题原创"))),

            Map.entry("长蛇座", List.of(
                    new StarTechnique("海蛇千毒牙", "市以海蛇千毒牙注入剧毒", "Ω"),
                    new StarTechnique("毒牙攻击", "以毒牙撕咬并向敌人注入毒液", "Ω"))),

            Map.entry("乌鸦座", List.of(
                    new StarTechnique("风之壁障", "约翰以风构成的防御壁障", "Ω"),
                    new StarTechnique("风之扳机", "以风为扳机射出的锐利风弹", "Ω"))),

            Map.entry("牧夫座", List.of(
                    new StarTechnique("牧者之杖", "以光之杖横扫一切", "主题原创"))),

            Map.entry("巨爵座", List.of(
                    new StarTechnique("圣杯之水", "以圣杯之水净化并攻击", "主题原创"))),

            Map.entry("后发座", List.of(
                    new StarTechnique("发丝之缚", "以星光发丝缠绕束缚敌人", "主题原创"))),

            Map.entry("盾牌座", List.of(
                    new StarTechnique("坚盾之壁", "展开不可破的光之盾壁", "主题原创"))),

            Map.entry("小马座", List.of(
                    new StarTechnique("昴宿星团冲击", "昴打出七颗星辰轰击对手", "Ω"),
                    new StarTechnique("昴宿星团暴风雨", "昴打出无数星辰如暴风雨般轰击对手", "Ω"),
                    new StarTechnique("超新星爆发", "创造一颗超新星掷向敌人引爆", "Ω"),
                    new StarTechnique("时光永恒终结", "昴觉醒萨图恩神格后停止全宇宙时间的终极技", "Ω", AttackType.SPATIAL))),

            Map.entry("猎犬座", List.of(
                    new StarTechnique("大地之牙", "米盖尔自大地唤起的巨牙撕咬敌人", "Ω"),
                    new StarTechnique("大地波涛", "以大地之力掀起的波涛冲击", "Ω"),
                    new StarTechnique("大地终章", "米盖尔的大地系终结奥义", "Ω"))),

            Map.entry("小狮座", List.of(
                    new StarTechnique("幼狮炽焰", "以火属性小宇宙燃起炽焰攻击", "Ω"),
                    new StarTechnique("幼狮轰炸", "化身火狮突进引爆的撞击技", "Ω"),
                    new StarTechnique("幼狮爆炎", "将火焰小宇宙压缩后引发的爆炎", "Ω"),
                    new StarTechnique("烈焰狂徒", "以火焰小宇宙化作狂暴火流冲击敌人", "Ω"),
                    new StarTechnique("幼狮闪焰暴风轰炸", "结合闪焰与暴风的强化轰炸技", "Ω"),
                    new StarTechnique("幼狮爆焰轰炸", "爆焰版幼狮轰炸，可与同伴小宇宙合体释放", "Ω"))),

            Map.entry("豺狼座", List.of(
                    new StarTechnique("十文字岩石崩", "将大地化为十字手里剑射出", "Ω"),
                    new StarTechnique("咆哮天狼崩", "以土构成双半球夹击并召唤狼头陨石连续轰击", "Ω"),
                    new StarTechnique("白狼拳·斗牙疾走", "觉醒第七感发出巨狼型波动吞噬并拘束一切", "Ω"),
                    new StarTechnique("白狼拳·天神无双", "白狼拳的另一形态，以无双之势轰出巨狼波动", "Ω"),
                    new StarTechnique("狼爪", "以狼爪之势撕裂敌人", "Ω"),
                    new StarTechnique("狼牙攻击", "化狼群之势突进撕咬的连击", "Ω"),
                    new StarTechnique("狼群咬杀", "如狼群围咬般的连续撕咬攻击", "Ω"),
                    new StarTechnique("狼牙拳·罗刹旋风刃", "化罗刹旋风的狼牙拳刃", "Ω"),
                    new StarTechnique("天狼摇滚死亡咆哮", "觉醒Ω圣衣后释放毁灭级音波的终极技", "Ω"))),

            Map.entry("天燕座", List.of(
                    new StarTechnique("台风之刃", "以风之刃化作台风斩击敌人", "Ω"))),

            Map.entry("剑鱼座", List.of(
                    new StarTechnique("剑鱼斩", "斯皮亚以剑鱼之势斩出的水刃", "Ω"))),

            Map.entry("圆规座", List.of(
                    new StarTechnique("大地刺针", "胡克自大地唤起的尖刺之针", "Ω"))),

            Map.entry("孔雀座", List.of(
                    new StarTechnique("寒光箭雨", "帕芙琳射出的寒光箭雨", "Ω"),
                    new StarTechnique("孔雀暴风雪", "帕芙琳以孔雀之姿卷起的暴风雪", "Ω"))),

            Map.entry("罗盘座", List.of(
                    new StarTechnique("南向指引", "以罗盘之力锁定并引导攻击", "主题原创"))),

            Map.entry("唧筒座", List.of(
                    new StarTechnique("涌泉之力", "以清泉之力冲击净化", "主题原创"))),

            Map.entry("鹿豹座", List.of(
                    new StarTechnique("高远之跃", "以长腿跃起从高处冲击", "主题原创"))),

            Map.entry("天猫座", List.of(
                    new StarTechnique("山猫之箭", "米拉伯罗斯以风射出的山猫之箭", "Ω"))),

            Map.entry("六分仪座", List.of(
                    new StarTechnique("精密定位", "锁定敌人弱点进行精确打击", "主题原创"))),

            Map.entry("船尾座", List.of(
                    new StarTechnique("破浪之击", "以破浪之势发出冲击波", "主题原创"))),

            Map.entry("狐狸座", List.of(
                    new StarTechnique("狡狐之术", "以幻影迷惑后突袭", "主题原创", AttackType.MENTAL))),

            Map.entry("矮马座", List.of(
                    new StarTechnique("坚韧之蹄", "以耐力为根基的持久攻击", "主题原创"))),

            Map.entry("显微镜座", List.of(
                    new StarTechnique("微界之眼", "洞察敌人微观弱点并放大攻击", "主题原创"))),

            Map.entry("望远镜座", List.of(
                    new StarTechnique("远望之矢", "以远距精确制导的光矢攻击", "主题原创"))),

            Map.entry("天炉座", List.of(
                    new StarTechnique("淬炼之焰", "以炉火锻造的炽热斗气攻击", "主题原创"))),

            Map.entry("雕具座", List.of(
                    new StarTechnique("精雕之刃", "以精密如雕的刀刃切割", "主题原创"))),

            Map.entry("蝎虎座", List.of(
                    new StarTechnique("攀附之影", "以壁虎之势贴身缠绕攻击", "主题原创"))),

            Map.entry("南冕座", List.of(
                    new StarTechnique("南天荆冠", "以荆冠之力束缚并攻击", "主题原创"))),

            Map.entry("天鹅座", List.of(
                    new StarTechnique("白鸟之翼", "以天鹅之翼发出冰冻冲击", "主题原创"))),

            Map.entry("天坛座", List.of(
                    new StarTechnique("祭典之焰", "以祭坛之火化为神圣火焰", "主题原创"))),

            Map.entry("小熊座", List.of(
                    new StarTechnique("北极指引", "以北极星之力引导攻击轨迹", "主题原创")))
    );

    // ==================== 神斗士 7 主星招式 ====================

    private static final Map<String, List<StarTechnique>> GOD_WARRIOR_TECHNIQUES = Map.ofEntries(
            Map.entry("天枢星", List.of(
                    new StarTechnique("维京猛虎爪", "以猛虎之势挥出的冰之爪", "Asgard篇"),
                    new StarTechnique("巴鲁蒙之剑", "以神剑之力劈裂大地", "Asgard篇"))),

            Map.entry("天璇星", List.of(
                    new StarTechnique("极光之击", "以极光之力化为冰火双击", "Asgard篇"),
                    new StarTechnique("魔火之拳", "以暗炎缠绕的连击拳", "Asgard篇"))),

            Map.entry("天玑星", List.of(
                    new StarTechnique("巨人之雷", "以雷神之力释放的巨型雷电", "Asgard篇"),
                    new StarTechnique("机关之针", "以精巧机关发射的毒针", "Asgard篇"))),

            Map.entry("天权星", List.of(
                    new StarTechnique("权衡之刃", "以均衡之力斩出的光刃", "Asgard篇"),
                    new StarTechnique("琥珀之结", "以琥珀之力封印敌人行动", "Asgard篇"))),

            Map.entry("玉衡星", List.of(
                    new StarTechnique("狼牙·疾风斩", "以狼牙之势的高速斩击", "Asgard篇"),
                    new StarTechnique("北狼之牙", "以北方狼群之力释放的群体攻击", "Asgard篇"))),

            Map.entry("开阳星", List.of(
                    new StarTechnique("影之双虎爪", "以双影之力同时发出的两记爪击", "Asgard篇"),
                    new StarTechnique("光明之盾", "以光之盾反弹一切攻击", "Asgard篇"))),

            Map.entry("摇光星", List.of(
                    new StarTechnique("弦之音·破灭", "以琴弦之音震碎敌人意志", "Asgard篇", AttackType.MENTAL),
                    new StarTechnique("动荡之雷", "以动荡之力释放的乱雷", "主题原创")))
    );

    // ==================== 冥斗士主要冥星招式 ====================

    private static final Map<String, List<StarTechnique>> SPECTER_TECHNIQUES = Map.ofEntries(
            // ---------- 原有冥星（增强） ----------
            Map.entry("天魁星", List.of(
                    new StarTechnique("天魁·冥王之令", "以冥王之名发出的统御之令", "冥界篇"),
                    new StarTechnique("冥王之剑", "以冥王之力凝聚的究极之剑", "冥王神话LC"))),
    
            Map.entry("天罡星", List.of(
                    new StarTechnique("地狱之锁", "以冥界锁链束缚敌人灵魂", "冥界篇"),
                    new StarTechnique("星尘旋转", "以冥力操纵星尘形成漩涡攻击", "冥王神话LC"))),
    
            Map.entry("天机星", List.of(
                    new StarTechnique("死灵之书", "操纵死灵之力化为攻击术法", "冥界篇"))),
    
            Map.entry("天闲星", List.of(
                    new StarTechnique("幽冥之壁", "召唤冥界之壁防御并反击", "冥界篇"))),
    
            Map.entry("地魁星", List.of(
                    new StarTechnique("碎裂之拳", "以冥力强化的碎裂拳击", "冥界篇"))),
    
            Map.entry("地煞星", List.of(
                    new StarTechnique("暗影侵蚀", "以暗影之力侵蚀敌人小宇宙", "冥界篇"))),
    
            // ---------- 冥王神话 LC 新增天罡星 ----------
            Map.entry("天暴星", List.of(
                    new StarTechnique("冥界炎", "以冥界之炎灼烧一切的业火", "冥王神话LC"),
                    new StarTechnique("暗黑日蚀", "释放暗黑之日蚀吞噬光明", "冥王神话LC"))),
    
            Map.entry("天罪星", List.of(
                    new StarTechnique("罪业·万魔穿心", "以万魔之力贯穿罪人之心", "冥王神话LC"),
                    new StarTechnique("暗黑之翼", "展开暗黑之翼席卷一切", "冥王神话LC"))),
    
            Map.entry("天哭星", List.of(
                    new StarTechnique("哭泣之波", "以悲泣之波动摧毁敌人意志", "冥王神话LC", AttackType.MENTAL))),
    
            Map.entry("天异星", List.of(
                    new StarTechnique("幻胧魔皇拳", "侵入精神操纵意志的冥皇拳", "冥王神话LC", AttackType.MENTAL))),
    
            // 冥界三巨头 ① 天猛星·拉达曼迪斯
            Map.entry("天猛星", List.of(
                    new StarTechnique("最大警戒", "放出巨大暗黑能量波的前方范围攻击", "冥界篇"),
                    new StarTechnique("翼龙咆哮", "以翼龙咆哮释放充满宇宙能量的震动波震碎一切", "冥界篇"))),
    
            Map.entry("天勇星", List.of(
                    new StarTechnique("勇者之盾", "以冥力凝聚不可破之盾", "冥王神话LC"),
                    new StarTechnique("英雄冲击", "以英雄之势全力冲击", "冥王神话LC"))),
    
            Map.entry("天威星", List.of(
                    new StarTechnique("威光之波", "以威严之光化为冲击波", "冥王神话LC"),
                    new StarTechnique("震天之啸", "以哮吼震碎天空", "冥王神话LC"))),
    
            // 冥界三巨头 ② 天雄星·艾亚哥斯
            Map.entry("天雄星", List.of(
                    new StarTechnique("迦楼罗振翅", "将敌人抛上高空再坠落大地", "冥界篇"),
                    new StarTechnique("宇宙大幻觉", "将敌人拖入布满魔眼的幻觉空间以精神攻击击杀", "冥界篇", AttackType.MENTAL),
                    new StarTechnique("银河魔眼炎葬", "献祭灵魂召唤魔眼释放灼烧神经的光线致死", "冥王神话LC", AttackType.MENTAL),
                    new StarTechnique("因陀罗天殛洗礼", "升起巨大火焰柱化为无数火焰羽毛焚烧一切", "冥王神话LC"))),

            // 冥界三巨头 ③ 天贵星·米诺斯
            Map.entry("天贵星", List.of(
                    new StarTechnique("星辰傀儡线", "以冥力操控敌人神经系统如提线木偶", "冥界篇", AttackType.MENTAL),
                    new StarTechnique("巨翼翔风", "震动冥衣双翼刮起强烈飓风摧毁身边一切", "冥王神话LC"))),
    
            Map.entry("天暗星", List.of(
                    new StarTechnique("暗黑之眼", "以暗黑之眼凝视封印敌人", "冥王神话LC", AttackType.MENTAL),
                    new StarTechnique("暗域", "展开暗域吞噬一切光明", "冥王神话LC"))),
    
            Map.entry("天孤星", List.of(
                    new StarTechnique("孤狼之牙", "以孤狼之势撕裂敌人", "冥王神话LC"),
                    new StarTechnique("孤独之影", "化为孤独之影潜行暗杀", "冥王神话LC"))),
    
            Map.entry("天伤星", List.of(
                    new StarTechnique("伤痕之刃", "以伤痕化为利刃攻击", "冥王神话LC"),
                    new StarTechnique("悲怆之击", "以悲怆之力重击敌人", "冥王神话LC"))),
    
            Map.entry("天立星", List.of(
                    new StarTechnique("立刻之斩", "以果断之势一击斩裂", "冥王神话LC"),
                    new StarTechnique("不屈之志", "以不屈意志强化冥力", "冥王神话LC"))),
    
            Map.entry("天捷星", List.of(
                    new StarTechnique("捷足之雷", "以迅雷之势突进攻击", "冥王神话LC"))),
    
            Map.entry("天空星", List.of(
                    new StarTechnique("虚空之刃", "以虚空之力化为无形之刃", "冥王神话LC"))),
    
            Map.entry("天杀星", List.of(
                    new StarTechnique("杀意之拳", "以纯粹杀意凝聚的冥力之拳", "冥王神话LC"))),
    
            Map.entry("天究星", List.of(
                    new StarTechnique("究极之击", "以究极冥力释放的毁灭一击", "冥王神话LC"))),
    
            Map.entry("天剑星", List.of(
                    new StarTechnique("剑气纵横", "以冥力凝聚的剑气纵横交错", "冥王神话LC"))),
    
            Map.entry("天寿星", List.of(
                    new StarTechnique("寿命剥夺", "以冥力剥夺敌人生命力", "冥王神话LC"),
                    new StarTechnique("天寿之裁", "以天寿之力裁决敌人寿命尽头", "冥王神话LC"))),
    
            Map.entry("天损星", List.of(
                    new StarTechnique("损灭之击", "以损己为代价的毁灭之击", "冥王神话LC"))),
            
            Map.entry("天英星", List.of(
                    new StarTechnique("英魂之剑", "以英灵之剑斩裂敌人", "冥王神话LC"),
                    new StarTechnique("英气冲霄", "将英气化为冲天光柱攻击", "冥王神话LC"))),
            
            Map.entry("天富星", List.of(
                    new StarTechnique("黄金冲击", "以黄金之力发出重击", "冥王神话LC"),
                    new StarTechnique("富国之壁", "以冥力凝聚不可破之金壁", "冥王神话LC"))),
            
            Map.entry("天满星", List.of(
                    new StarTechnique("圆满之阵", "展开圆满冥阵封锁一切", "冥王神话LC"),
                    new StarTechnique("福泽之波", "以福泽之力化为治愈与破坏之波", "冥王神话LC"))),
            
            Map.entry("天佑星", List.of(
                    new StarTechnique("庇佑之盾", "以冥力凝聚护持之盾", "冥王神话LC"),
                    new StarTechnique("护持圣光", "以护持之光净化并攻击", "冥王神话LC"))),
            
            Map.entry("天速星", List.of(
                    new StarTechnique("瞬闪之击", "瞬间移动后突袭的冥力之击", "冥王神话LC"),
                    new StarTechnique("疾风迅雷", "以疾风与迅雷同时攻击", "冥王神话LC"))),
            
            Map.entry("天微星", List.of(
                    new StarTechnique("微尘之术", "将冥力化为微尘侵蚀敌人", "冥王神话LC"),
                    new StarTechnique("精微之针", "以精密冥力凝聚无形之针", "冥王神话LC"))),
            
            Map.entry("天退星", List.of(
                    new StarTechnique("退击之阵", "以退为进的反击冥阵", "冥王神话LC"),
                    new StarTechnique("淡泊之刃", "以淡泊之心凝聚无形之刃", "冥王神话LC"))),
            
            Map.entry("天平星", List.of(
                    new StarTechnique("均衡之裁", "以天平之力均衡裁决敌人", "冥王神话LC"),
                    new StarTechnique("天平之秤", "以冥秤衡量敌人罪业并制裁", "冥王神话LC"))),
            
            Map.entry("天败星", List.of(
                    new StarTechnique("百战之躯", "以百战锤炼的不屈冥躯强化", "冥王神话LC"),
                    new StarTechnique("不败之魂", "以不败之魂释放的反击", "冥王神话LC"))),
            
            Map.entry("天牢星", List.of(
                    new StarTechnique("牢笼之缚", "以冥力凝聚牢笼束缚敌人", "冥王神话LC"),
                    new StarTechnique("困兽之击", "以困兽之势释放绝望之击", "冥王神话LC"))),
            
            Map.entry("天慧星", List.of(
                    new StarTechnique("洞察之眼", "以慧眼洞察敌人弱点并封印", "冥王神话LC", AttackType.MENTAL),
                    new StarTechnique("天机之裁", "以天机之力裁决敌人命运", "冥王神话LC"))),
            
            Map.entry("天巧星", List.of(
                    new StarTechnique("巧匠之术", "以巧匠之技构造冥力兵器", "冥王神话LC"),
                    new StarTechnique("精妙之击", "以精妙之技精准打击弱点", "冥王神话LC"))),
            
            // ---------- 冥王神话 LC 新增地煞星 ----------
            Map.entry("地狂星", List.of(
                    new StarTechnique("狂暴之拳", "以狂暴之力释放的连击拳", "冥王神话LC"))),
    
            Map.entry("地暴星", List.of(
                    new StarTechnique("暴风之击", "以暴风之势发出冲击波", "冥王神话LC"))),
    
            Map.entry("地暗星", List.of(
                    new StarTechnique("暗影渗透", "以暗影之力渗透侵蚀敌人", "冥王神话LC"))),
    
            Map.entry("地飞星", List.of(
                    new StarTechnique("飞翔之刃", "以飞翔之势斩出光刃", "冥王神话LC"))),
    
            Map.entry("地走星", List.of(
                    new StarTechnique("疾走之击", "以疾走之力突进冲击", "冥王神话LC"))),
    
            Map.entry("地雄星", List.of(
                    new StarTechnique("雄浑之击", "以雄浑冥力重击敌人", "冥王神话LC"))),
    
            Map.entry("地猛星", List.of(
                    new StarTechnique("猛虎之击", "以猛虎之势全力出击", "冥王神话LC"))),
    
            Map.entry("地威星", List.of(
                    new StarTechnique("威压之波", "以威压之力发出冲击波", "冥王神话LC"))),
    
            Map.entry("地杰星", List.of(
                    new StarTechnique("杰出之刃", "以杰出之技斩出光刃", "冥王神话LC"))),

            // ---------- 地煞星补全（按地支分组） ----------
            // 子组
            Map.entry("地勇星", List.of(
                    new StarTechnique("勇武之拳", "以勇武之力挥出的重拳", "冥王神话LC"))),

            // 丑组
            Map.entry("地英星", List.of(
                    new StarTechnique("英武之击", "以英武之力突进攻击", "冥王神话LC"))),

            Map.entry("地奇星", List.of(
                    new StarTechnique("奇谋之阵", "以奇谋化为冥阵困敌", "冥王神话LC"))),

            Map.entry("地健星", List.of(
                    new StarTechnique("健步之雷", "以健步之力突进释放迅雷", "冥王神话LC"))),

            // 寅组
            Map.entry("地会星", List.of(
                    new StarTechnique("会合之术", "以会合之力聚集冥气攻击", "冥王神话LC"))),

            Map.entry("地佐星", List.of(
                    new StarTechnique("辅佐之盾", "以辅佐之力凝聚护持之盾", "冥王神话LC"))),

            Map.entry("地佑星", List.of(
                    new StarTechnique("庇佑之光", "以庇佑之力化为净化之光", "冥王神话LC"))),

            Map.entry("地灵星", List.of(
                    new StarTechnique("灵动之击", "以灵巧身法突进攻击", "冥王神话LC"))),

            Map.entry("地微星", List.of(
                    new StarTechnique("微妙之刃", "以微末冥力凝聚无形之刃", "冥王神话LC"))),

            // 卯组
            Map.entry("地默星", List.of(
                    new StarTechnique("默杀之拳", "以沉默之劲释放无声之拳", "冥王神话LC"))),

            Map.entry("地巧星", List.of(
                    new StarTechnique("巧艺之击", "以巧艺构造冥力兵器攻击", "冥王神话LC"))),

            Map.entry("地明星", List.of(
                    new StarTechnique("明光之波", "以光明之力发出冲击波", "冥王神话LC"))),

            // 辰组
            Map.entry("地进星", List.of(
                    new StarTechnique("进击之刃", "以进击之势斩出光刃", "冥王神话LC"))),

            Map.entry("地退星", List.of(
                    new StarTechnique("退守之壁", "以退守之力凝聚防御壁", "冥王神话LC"))),

            Map.entry("地满星", List.of(
                    new StarTechnique("圆满之阵", "展开圆满冥阵封锁敌人", "冥王神话LC"))),

            Map.entry("地遂星", List.of(
                    new StarTechnique("遂行之击", "以顺遂之力流畅连击", "冥王神话LC"))),

            Map.entry("地周星", List.of(
                    new StarTechnique("周密之阵", "以周密冥力编织包围之阵", "冥王神话LC"))),

            Map.entry("地隐星", List.of(
                    new StarTechnique("隐杀之刃", "以隐匿之术暗杀敌人", "冥王神话LC"))),

            // 巳组
            Map.entry("地异星", List.of(
                    new StarTechnique("异端之术", "以异端冥力释放诡异攻击", "冥王神话LC"))),

            Map.entry("地理星", List.of(
                    new StarTechnique("地理之阵", "操纵地形化为攻击冥阵", "冥王神话LC"))),

            Map.entry("地俊星", List.of(
                    new StarTechnique("俊秀之击", "以俊秀之力优雅斩击", "冥王神话LC"))),

            Map.entry("地乐星", List.of(
                    new StarTechnique("乐天之波", "以乐天之力释放欢愉冲击", "冥王神话LC"))),

            Map.entry("地捷星", List.of(
                    new StarTechnique("捷足之雷", "以捷足之势突进释放迅雷", "冥王神话LC"))),

            Map.entry("地速星", List.of(
                    new StarTechnique("疾速之击", "以疾速之力连续突击", "冥王神话LC"))),

            // 午组
            Map.entry("地镇星", List.of(
                    new StarTechnique("镇守之壁", "以镇守之力凝聚坚不可摧之壁", "冥王神话LC"))),

            Map.entry("地羁星", List.of(
                    new StarTechnique("羁绊之锁", "以羁绊之力束缚敌人行动", "冥王神话LC"))),

            Map.entry("地魔星", List.of(
                    new StarTechnique("魔力之击", "以深沉魔力释放暗黑之击", "冥王神话LC"))),

            Map.entry("地妖星", List.of(
                    new StarTechnique("妖异之术", "以妖异之力变化攻击形态", "冥王神话LC"))),

            Map.entry("地幽星", List.of(
                    new StarTechnique("幽冥之击", "以幽深冥力无声侵蚀敌人", "冥王神话LC"))),

            Map.entry("地伏星", List.of(
                    new StarTechnique("伏击之刃", "以伏击之势暗杀突袭", "冥王神话LC"))),

            // 未组
            Map.entry("地僻星", List.of(
                    new StarTechnique("僻静之壁", "以偏僻之力凝聚防御壁", "冥王神话LC"))),

            Map.entry("地空星", List.of(
                    new StarTechnique("虚空之刃", "以空灵之力化为无形之刃", "冥王神话LC"))),

            Map.entry("地藏星", List.of(
                    new StarTechnique("藏锋之击", "以藏匿之锋突袭攻击", "冥王神话LC"))),

            Map.entry("地平星", List.of(
                    new StarTechnique("平和之波", "以平和之力发出净化冲击", "冥王神话LC"))),

            Map.entry("地损星", List.of(
                    new StarTechnique("损灭之击", "以损人之力侵蚀敌人小宇宙", "冥王神话LC"))),

            Map.entry("地奴星", List.of(
                    new StarTechnique("奴役之锁", "以奴役之力操控束缚敌人", "冥王神话LC", AttackType.MENTAL))),

            // 申组
            Map.entry("地察星", List.of(
                    new StarTechnique("察明之眼", "以明察之力洞察并封印弱点", "冥王神话LC", AttackType.MENTAL))),

            Map.entry("地恶星", List.of(
                    new StarTechnique("恶煞之拳", "以恶煞之力释放凶猛之拳", "冥王神话LC"))),

            Map.entry("地丑星", List.of(
                    new StarTechnique("粗犷之击", "以粗犷之力重击敌人", "冥王神话LC"))),

            Map.entry("地数星", List.of(
                    new StarTechnique("术数之阵", "以术数之力编织命运冥阵", "冥王神话LC"))),

            Map.entry("地阴星", List.of(
                    new StarTechnique("阴柔之刃", "以阴柔之力无形斩击", "冥王神话LC"))),

            Map.entry("地刑星", List.of(
                    new StarTechnique("刑罚之击", "以刑罚之力制裁敌人", "冥王神话LC"))),

            // 酉组
            Map.entry("地壮星", List.of(
                    new StarTechnique("壮烈之击", "以壮烈之力全力重击", "冥王神话LC"))),

            Map.entry("地劣星", List.of(
                    new StarTechnique("桀骜之拳", "以桀骜之力释放狂暴连击", "冥王神话LC"))),

            Map.entry("地贼星", List.of(
                    new StarTechnique("鬼祟之刃", "以鬼祟之势暗杀突袭", "冥王神话LC"))),

            Map.entry("地戚星", List.of(
                    new StarTechnique("悲戚之波", "以悲戚之力释放哀恸冲击", "冥王神话LC"))),

            Map.entry("地狗星", List.of(
                    new StarTechnique("忠犬之牙", "以忠犬之势撕咬敌人", "冥王神话LC"))),

            // 戌组
            Map.entry("地文星", List.of(
                    new StarTechnique("文华之刃", "以文华之力化为光刃", "冥王神话LC"))),

            Map.entry("地秀星", List.of(
                    new StarTechnique("秀雅之击", "以秀雅之力精准斩击", "冥王神话LC"))),

            Map.entry("地正星", List.of(
                    new StarTechnique("正义之裁", "以正义之力裁决敌人", "冥王神话LC"))),

            Map.entry("地阔星", List.of(
                    new StarTechnique("阔达之波", "以阔达之力释放宽广冲击", "冥王神话LC"))),

            Map.entry("地阖星", List.of(
                    new StarTechnique("阖合之阵", "以阖合之力封锁包围敌人", "冥王神话LC"))),

            Map.entry("地强星", List.of(
                    new StarTechnique("强韧之击", "以强韧之力持续重击", "冥王神话LC"))),

            // 亥组
            Map.entry("地弱星", List.of(
                    new StarTechnique("柔水之刃", "以柔水之力无形渗透斩击", "冥王神话LC"))),

            Map.entry("地畸星", List.of(
                    new StarTechnique("畸变之术", "以畸变之力释放诡异攻击", "冥王神话LC"))),

            Map.entry("地密星", List.of(
                    new StarTechnique("密封之壁", "以密封之力困锁一切", "冥王神话LC"))),

            Map.entry("地端星", List.of(
                    new StarTechnique("端正之裁", "以端正之力裁决制裁", "冥王神话LC"))),

            Map.entry("地紧星", List.of(
                    new StarTechnique("紧缚之锁", "以紧缚之力束缚封印敌人", "冥王神话LC")))
    );

    // ==================== 查询方法 ====================

    /**
     * 根据星座/星辰名和阵营获取招式列表。
     *
     * @param constellation 星座/星辰名
     * @param faction       阵营（saint/specter/godwarrior）
     * @return 招式列表（至少包含 1 个招式）
     */
    public static List<StarTechnique> getTechniques(String constellation, String faction) {
        Map<String, List<StarTechnique>> map = switch (faction) {
            case "specter" -> SPECTER_TECHNIQUES;
            case "godwarrior" -> GOD_WARRIOR_TECHNIQUES;
            default -> SAINT_TECHNIQUES;
        };
        List<StarTechnique> techs = map.get(constellation);
        if (techs != null && !techs.isEmpty()) {
            return techs;
        }
        // 未收录的星座/星辰：尝试 saint 表兜底（冥斗士/神斗士也可能有 saint 表中的同名星座）
        if (!"saint".equals(faction)) {
            techs = SAINT_TECHNIQUES.get(constellation);
            if (techs != null && !techs.isEmpty()) {
                return techs;
            }
        }
        // 仍无匹配：生成主题化兜底招式
        return List.of(generateFallback(constellation, faction));
    }

    /**
     * 暴露某阵营的原始招式表（只读，不做 saint 兜底、不生成 fallback）。
     * <p>
     * 仅供<b>开发期</b>用途：{@code TechniqueTreeGenerator} 枚举待建树的多技能条目、
     * {@code TechniqueTreeMapValidationTest} 校验招式表与树表的漂移。
     * 运行时查询一律走 {@link #getTechniques}，因为它才有三层兜底。
     * <p>
     * 返回的是 {@code Map.ofEntries} 构造的不可变 Map，调用方无法篡改。
     *
     * @param faction 阵营（saint/specter/godwarrior），未知值按 saint 处理
     */
    public static Map<String, List<StarTechnique>> rawTechniqueTable(String faction) {
        return switch (faction == null ? "saint" : faction) {
            case "specter" -> SPECTER_TECHNIQUES;
            case "godwarrior" -> GOD_WARRIOR_TECHNIQUES;
            default -> SAINT_TECHNIQUES;
        };
    }

    /**
     * 为未收录的星座/星辰生成主题化兜底招式。
     * 基于星座名和阵营生成一个通用招式。
     */
    private static StarTechnique generateFallback(String constellation, String faction) {
        String factionLabel = switch (faction) {
            case "specter" -> "冥";
            case "godwarrior" -> "神";
            default -> "星";
        };
        return new StarTechnique(
                constellation + "·" + factionLabel + "之技",
                "以" + constellation + "之力释放的" + factionLabel + "命招式",
                "主题原创");
    }
}
