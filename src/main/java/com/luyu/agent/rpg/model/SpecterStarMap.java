package com.luyu.agent.rpg.model;

/**
 * 108 冥星静态映射表。
 * <p>
 * 天罡 36 星按天干（甲乙丙丁戊己庚辛壬癸）分 10 组，每组约 3-4 星；
 * 地煞 72 星按地支（子丑寅卯辰巳午未申酉戌亥）分 12 组，每组 6 星。
 * 年柱天干 → 天罡组，日柱地支 → 地煞组，交叉定位唯一冥星。
 * <p>
 * 天罡星名称取自水浒传三十六天罡星，地煞星取自七十二地煞星。
 */
public class SpecterStarMap {

    public static final String FACTION = "specter";
    public static final String TIAN_GANG = "Specter-TianGang";
    public static final String DI_SHA = "Specter-DiSha";

    // ==================== 天干索引（甲=0 ... 癸=9）====================
    public static final String[] TIAN_GAN = {
            "甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"
    };

    // ==================== 地支索引（子=0 ... 亥=11）====================
    public static final String[] DI_ZHI = {
            "子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"
    };

    // ==================== 36 天罡星（按天干分 10 组，索引 = 天干序号 * 3 ~ *4 范围）====================
    // 为了均匀分配，采用：甲组4星，其余各组3-4星，总计36
    // 每组起始索引和数量通过 TIAN_GANG_GROUP_SIZES 定义
    private static final int[] TIAN_GANG_GROUP_SIZES = {4, 4, 4, 3, 4, 3, 4, 3, 4, 3}; // 总和=36

    private static final String[] TIAN_GANG_STARS = {
            // 甲组（4星）
            "天魁星", "天罡星", "天机星", "天闲星",
            // 乙组（4星）
            "天勇星", "天雄星", "天猛星", "天威星",
            // 丙组（4星）
            "天英星", "天贵星", "天富星", "天满星",
            // 丁组（3星）
            "天孤星", "天伤星", "天立星",
            // 戊组（4星）
            "天捷星", "天暗星", "天佑星", "天空星",
            // 己组（3星）
            "天速星", "天异星", "天杀星",
            // 庚组（4星）
            "天微星", "天究星", "天退星", "天寿星",
            // 辛组（3星）
            "天剑星", "天平星", "天罪星",
            // 壬组（4星）
            "天损星", "天败星", "天牢星", "天慧星",
            // 癸组（3星）
            "天暴星", "天哭星", "天巧星"
    };

    // 天罡星性格倾向（与星名一一对应）
    private static final String[] TIAN_GANG_PERSONALITIES = {
            // 甲组
            "义薄云天、领袖群伦", "刚正不阿、威严庄重", "智谋深远、运筹帷幄", "闲云野鹤、超然物外",
            // 乙组
            "勇猛无畏、冲锋陷阵", "豪迈刚烈、力压群雄", "猛烈如虎、一往无前", "威震四方、气势磅礴",
            // 丙组
            "英姿飒爽、才华横溢", "尊贵典雅、气度不凡", "富甲一方、财运亨通", "圆满如意、福泽深厚",
            // 丁组
            "孤傲清高、独立特行", "悲壮凄凉、以身殉道", "特立独行、不随流俗",
            // 戊组
            "敏捷矫健、行动如风", "韬光养晦、暗中蓄力", "庇佑苍生、护持正道", "空灵虚无、超脱尘世",
            // 己组
            "迅如闪电、来去如风", "异于常人、特立独行", "杀伐果断、铁血无情",
            // 庚组
            "精微入妙、细致入微", "追根究底、探求真理", "功成身退、淡泊名利", "寿元绵长、福寿双全",
            // 辛组
            "剑心通明、锋芒毕露", "公平正直、不偏不倚", "罪孽深重、负荆前行",
            // 壬组
            "损己利人、自我牺牲", "败而不馁、百折不挠", "困守牢笼、等待时机", "慧心明澈、洞察天机",
            // 癸组
            "暴烈如火、难以自制", "悲天悯人、泪洒苍生", "巧夺天工、匠心独运"
    };

    // ==================== 72 地煞星（按地支分 12 组，每组 6 星）====================
    private static final String[] DI_SHA_STARS = {
            // 子组（6星）
            "地魁星", "地煞星", "地勇星", "地杰星", "地威星", "地雄星",
            // 丑组（6星）
            "地猛星", "地英星", "地奇星", "地猛星", "地健星", "地暗星",
            // 寅组（6星）
            "地会星", "地佐星", "地佑星", "地灵星", "地微星", "地暴星",
            // 卯组（6星）
            "地默星", "地狂星", "地飞星", "地走星", "地巧星", "地明星",
            // 辰组（6星）
            "地进星", "地退星", "地满星", "地遂星", "地周星", "地隐星",
            // 巳组（6星）
            "地异星", "地理星", "地俊星", "地乐星", "地捷星", "地速星",
            // 午组（6星）
            "地镇星", "地羁星", "地魔星", "地妖星", "地幽星", "地伏星",
            // 未组（6星）
            "地僻星", "地空星", "地藏星", "地平星", "地损星", "地奴星",
            // 申组（6星）
            "地察星", "地恶星", "地丑星", "地数星", "地阴星", "地刑星",
            // 酉组（6星）
            "地壮星", "地劣星", "地健星", "地贼星", "地戚星", "地狗星",
            // 戌组（6星）
            "地文星", "地秀星", "地正星", "地阔星", "地阖星", "地强星",
            // 亥组（6星）
            "地弱星", "地畸星", "地巧星", "地密星", "地端星", "地紧星"
    };

    // 地煞星性格倾向
    private static final String[] DI_SHA_PERSONALITIES = {
            // 子组
            "魁梧奇伟、一夫当关", "煞气凛然、不可侵犯", "勇武过人、胆识超群",
            "人杰地灵、英才勃发", "威风凛凛、震慑敌胆", "雄浑有力、气吞山河",
            // 丑组
            "猛烈果敢、勇冠三军", "英武不凡、智勇双全", "奇谋妙计、出人意表",
            "猛如烈虎、势不可挡", "健步如飞、精力充沛", "暗中行事、隐秘莫测",
            // 寅组
            "善于交际、八面玲珑", "辅佐贤能、从善如流", "庇佑同伴、守护同行",
            "灵动机敏、随机应变", "微末之间见真章", "暴风骤雨、雷厉风行",
            // 卯组
            "沉默寡言、以行动证明", "狂放不羁、蔑视礼法", "飞檐走壁、身轻如燕",
            "行走如风、奔走不息", "心灵手巧、技艺精湛", "光明磊落、正大光明",
            // 辰组
            "进取不息、步步向前", "退而有度、知进知退", "圆满自足、丰盈充实",
            "顺遂如意、一帆风顺", "周密严谨、面面俱到", "隐姓埋名、低调行事",
            // 巳组
            "异于常人、独树一帜", "精于地理、辨识方位", "俊朗出众、才貌双全",
            "乐观豁达、乐天知命", "捷足先登、反应迅捷", "速如疾风、行动果决",
            // 午组
            "镇守一方、稳如磐石", "羁绊难解、牵系众生", "魔力深沉、神秘莫测",
            "妖异诡谲、变化多端", "幽深静谧、深不可测", "伏击暗杀、出其不意",
            // 未组
            "偏居一隅、独善其身", "空灵虚无、超然物外", "藏锋敛锐、深藏不露",
            "平易近人、和蔼可亲", "损人利己、自私无情", "奴役驱使、统御手下",
            // 申组
            "明察秋毫、洞察一切", "恶煞临门、令人畏惧", "丑陋粗犷、外表惊人",
            "精于术数、通晓天机", "阴柔内敛、以柔克刚", "刑律严明、执法如山",
            // 酉组
            "壮硕魁伟、力大无穷", "劣性难驯、桀骜不驯", "健硕有力、体能充沛",
            "贼眉鼠眼、鬼祟行事", "戚然悲切、忧心忡忡", "忠诚护主、如犬随行",
            // 戌组
            "文采斐然、才华横溢", "秀外慧中、清雅脱俗", "正大光明、刚正不阿",
            "心胸开阔、包容万象", "阖家团圆、和合美满", "强健有力、不屈不挠",
            // 亥组
            "柔弱似水、以柔克刚", "畸零怪异、不拘一格", "心灵手巧、别出心裁",
            "密不透风、守口如瓶", "端正方直、品行端正", "紧守规矩、严于律己"
    };

    private SpecterStarMap() {
    }

    // ==================== 天罡星查询 ====================

    /** 天干序号（甲=0 ... 癸=9） */
    public static int tianGanIndex(String gan) {
        for (int i = 0; i < TIAN_GAN.length; i++) {
            if (TIAN_GAN[i].equals(gan)) return i;
        }
        return 0;
    }

    /** 获取天罡组的起始索引 */
    private static int tianGangGroupStart(int ganIndex) {
        int start = 0;
        for (int i = 0; i < ganIndex; i++) {
            start += TIAN_GANG_GROUP_SIZES[i];
        }
        return start;
    }

    /** 获取天罡组的星数 */
    public static int tianGangGroupSize(int ganIndex) {
        return TIAN_GANG_GROUP_SIZES[ganIndex];
    }

    /**
     * 根据年柱天干和日柱天干索引获取天罡星。
     *
     * @param ganName   年柱天干（甲乙丙丁戊己庚辛壬癸）
     * @param subIndex  组内索引（0 ~ groupSize-1），由日柱天干序号 mod groupSize 得到
     * @return 天罡星名称
     */
    public static String getTianGangStar(String ganName, int subIndex) {
        int ganIdx = tianGanIndex(ganName);
        int groupSize = TIAN_GANG_GROUP_SIZES[ganIdx];
        int idx = tianGangGroupStart(ganIdx) + (subIndex % groupSize);
        return TIAN_GANG_STARS[idx];
    }

    /** 获取天罡星对应的性格倾向 */
    public static String getTianGangPersonality(String ganName, int subIndex) {
        int ganIdx = tianGanIndex(ganName);
        int groupSize = TIAN_GANG_GROUP_SIZES[ganIdx];
        int idx = tianGangGroupStart(ganIdx) + (subIndex % groupSize);
        return TIAN_GANG_PERSONALITIES[idx];
    }

    // ==================== 地煞星查询 ====================

    /** 地支序号（子=0 ... 亥=11） */
    public static int diZhiIndex(String zhi) {
        for (int i = 0; i < DI_ZHI.length; i++) {
            if (DI_ZHI[i].equals(zhi)) return i;
        }
        return 0;
    }

    /**
     * 根据日柱地支获取地煞星。
     *
     * @param zhiName   日柱地支（子丑寅卯辰巳午未申酉戌亥）
     * @param subIndex  组内索引（0-5），由日柱天干序号 mod 6 得到
     * @return 地煞星名称
     */
    public static String getDiShaStar(String zhiName, int subIndex) {
        int zhiIdx = diZhiIndex(zhiName);
        int idx = zhiIdx * 6 + (subIndex % 6);
        return DI_SHA_STARS[idx];
    }

    /** 获取地煞星对应的性格倾向 */
    public static String getDiShaPersonality(String zhiName, int subIndex) {
        int zhiIdx = diZhiIndex(zhiName);
        int idx = zhiIdx * 6 + (subIndex % 6);
        return DI_SHA_PERSONALITIES[idx];
    }

    /**
     * 按天罡星名查性格倾向（preset-destined-figures 人物预绑星命用）。
     *
     * @return 非天罡星名（地煞/未知）返回 null，由调用方降级处理
     */
    public static String tianGangPersonalityByStar(String starName) {
        if (starName == null) {
            return null;
        }
        for (int i = 0; i < TIAN_GANG_STARS.length; i++) {
            if (TIAN_GANG_STARS[i].equals(starName)) {
                return TIAN_GANG_PERSONALITIES[i];
            }
        }
        return null;
    }

    // ==================== 统计方法 ====================

    public static int tianGangCount() {
        return TIAN_GANG_STARS.length;
    }

    public static int diShaCount() {
        return DI_SHA_STARS.length;
    }

    public static int totalCount() {
        return tianGangCount() + diShaCount();
    }
}
