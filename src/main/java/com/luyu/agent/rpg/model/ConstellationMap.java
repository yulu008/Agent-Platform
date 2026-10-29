package com.luyu.agent.rpg.model;

import java.time.LocalDate;
import java.util.List;

/**
 * 88 星座静态映射表。
 * <p>
 * 12 黄道季节组，每组 1 Gold + 2 Silver + 4 Bronze = 7，加 4 隐藏 = 88。
 * 季节索引 0=白羊 ... 11=双鱼。
 */
public class ConstellationMap {

    /** 星阶常量 */
    public static final String GOLD = "Gold";
    public static final String SILVER = "Silver";
    public static final String BRONZE = "Bronze";
    public static final String HIDDEN = "Hidden";

    // ==================== 黄道季节边界（月,日），按索引顺序 ====================
    // 每个季节的起始日期（含），结束日期为下一个季节起始日前一天
    private static final int[][] SEASON_START = {
            {3, 21}, {4, 20}, {5, 21}, {6, 22}, {7, 23}, {8, 23},
            {9, 23}, {10, 23}, {11, 22}, {12, 22}, {1, 20}, {2, 19}
    };

    // ==================== 12 Gold 星座（每季 1 个）====================
    private static final ConstellationEntry[] GOLD_STARS = {
            new ConstellationEntry("白羊座", "热情冲动、勇往直前", "strength"),
            new ConstellationEntry("金牛座", "坚韧执着、沉稳可靠", "endurance"),
            new ConstellationEntry("双子座", "机敏多变、善于交际", "agility"),
            new ConstellationEntry("巨蟹座", "坚守守护、外柔内刚", "healing"),
            new ConstellationEntry("狮子座", "骄傲威严、不屈不挠", "strength"),
            new ConstellationEntry("处女座", "严谨细致、追求完美", "wisdom"),
            new ConstellationEntry("天秤座", "公正平衡、犹豫不决", "righteousness"),
            new ConstellationEntry("天蝎座", "深沉执着、恩怨分明", "righteousness"),
            new ConstellationEntry("射手座", "自由奔放、追求真理", "agility"),
            new ConstellationEntry("摩羯座", "坚忍务实、野心勃勃", "endurance"),
            new ConstellationEntry("水瓶座", "独立创新、理想主义", "wisdom"),
            new ConstellationEntry("双鱼座", "敏感慈悲、梦幻浪漫", "healing"),
    };

    // ==================== 24 Silver 星座（每季 2 个，按 [季0-0, 季0-1, 季1-0, 季1-1, ...] 排列）====================
    private static final ConstellationEntry[] SILVER_STARS = {
            // 白羊季
            new ConstellationEntry("三角座", "锐利果决", "agility"),
            new ConstellationEntry("英仙座", "无畏英勇", "strength"),
            // 金牛季
            new ConstellationEntry("御夫座", "驾驭统御", "wisdom"),
            new ConstellationEntry("猎户座", "狩猎追踪", "agility"),
            // 双子季
            new ConstellationEntry("小犬座", "忠诚警觉", "endurance"),
            new ConstellationEntry("麒麟座", "纯洁神秘", "healing"),
            // 巨蟹季
            new ConstellationEntry("长蛇座", "隐忍绵长", "endurance"),
            new ConstellationEntry("乌鸦座", "机敏多谋", "wisdom"),
            // 狮子季
            new ConstellationEntry("牧夫座", "守护勤勉", "strength"),
            new ConstellationEntry("北冕座", "荣耀加冕", "righteousness"),
            // 室女季
            new ConstellationEntry("后发座", "纤细敏锐", "wisdom"),
            new ConstellationEntry("巨爵座", "丰盈满溢", "healing"),
            // 天秤季
            new ConstellationEntry("天琴座", "艺术优雅", "agility"),
            new ConstellationEntry("天鹰座", "锐眼疾飞", "agility"),
            // 天蝎季
            new ConstellationEntry("蛇夫座", "医道超然", "healing"),
            new ConstellationEntry("巨蛇座", "蛰伏隐忍", "endurance"),
            // 射手季
            new ConstellationEntry("盾牌座", "坚壁守护", "endurance"),
            new ConstellationEntry("海豚座", "灵巧友善", "agility"),
            // 摩羯季
            new ConstellationEntry("天马座", "自由奔腾", "agility"),
            new ConstellationEntry("小马座", "轻盈灵逸", "agility"),
            // 水瓶季
            new ConstellationEntry("仙后座", "华贵端庄", "righteousness"),
            new ConstellationEntry("蝎虎座", "攀附隐匿", "agility"),
            // 双鱼季
            new ConstellationEntry("仙王座", "威严沉稳", "endurance"),
            new ConstellationEntry("仙女座", "坚韧牺牲", "righteousness"),
    };

    // ==================== 48 Bronze 星座（每季 4 个，按 [季0-0..季0-3, 季1-0..季1-3, ...] 排列）====================
    private static final ConstellationEntry[] BRONZE_STARS = {
            // 白羊季
            new ConstellationEntry("小马座", "轻盈灵逸", "agility"),
            new ConstellationEntry("海豚座", "灵巧友善", "agility"),
            new ConstellationEntry("鹿豹座", "高远隐逸", "wisdom"),
            new ConstellationEntry("天猫座", "敏锐潜行", "agility"),
            // 金牛季
            new ConstellationEntry("猎犬座", "追踪不懈", "endurance"),
            new ConstellationEntry("小狮座", "骄傲初萌", "strength"),
            new ConstellationEntry("罗盘座", "指引方向", "wisdom"),
            new ConstellationEntry("唧筒座", "清泉涌动", "healing"),
            // 双子季
            new ConstellationEntry("巨蟹座", "守护坚韧", "healing"),
            new ConstellationEntry("船尾座", "破浪远航", "endurance"),
            new ConstellationEntry("狐狸座", "狡黠机警", "wisdom"),
            new ConstellationEntry("矮马座", "踏实稳健", "endurance"),
            // 巨蟹季
            new ConstellationEntry("六分仪座", "精密定位", "wisdom"),
            new ConstellationEntry("小狮座", "初生勇猛", "strength"),
            new ConstellationEntry("天猫座", "暗夜潜行", "agility"),
            new ConstellationEntry("船尾座", "远航不辍", "endurance"),
            // 狮子季
            new ConstellationEntry("六分仪座", "度量精准", "wisdom"),
            new ConstellationEntry("小狮座", "幼狮初啸", "strength"),
            new ConstellationEntry("罗盘座", "南向指引", "wisdom"),
            new ConstellationEntry("唧筒座", "涌泉不息", "healing"),
            // 室女季
            new ConstellationEntry("显微镜座", "洞察毫厘", "wisdom"),
            new ConstellationEntry("望远镜座", "远望前瞻", "wisdom"),
            new ConstellationEntry("天炉座", "淬炼锻造", "strength"),
            new ConstellationEntry("雕具座", "精雕细琢", "wisdom"),
            // 天秤季
            new ConstellationEntry("天马座", "自由翱翔", "agility"),
            new ConstellationEntry("仙女座", "锁链连心", "righteousness"),
            new ConstellationEntry("蝎虎座", "攀附潜行", "agility"),
            new ConstellationEntry("小狮座", "星座微光", "strength"),
            // 天蝎季
            new ConstellationEntry("天坛座", "祭典庄严", "righteousness"),
            new ConstellationEntry("南冕座", "荆冠加身", "endurance"),
            new ConstellationEntry("望远镜座", "深空凝望", "wisdom"),
            new ConstellationEntry("显微镜座", "微界探秘", "wisdom"),
            // 射手季
            new ConstellationEntry("天鹅座", "纯洁高飞", "healing"),
            new ConstellationEntry("狐狸座", "狡诈多谋", "wisdom"),
            new ConstellationEntry("矮马座", "坚韧不拔", "endurance"),
            new ConstellationEntry("南冕座", "南天荆冠", "endurance"),
            // 摩羯季
            new ConstellationEntry("双鱼座", "梦幻慈悲", "healing"),
            new ConstellationEntry("小马座", "寒冬轻灵", "agility"),
            new ConstellationEntry("雕具座", "精工细作", "wisdom"),
            new ConstellationEntry("鹿豹座", "高远寂寞", "wisdom"),
            // 水瓶季
            new ConstellationEntry("小马座", "初春灵动", "agility"),
            new ConstellationEntry("鹿豹座", "北天高悬", "wisdom"),
            new ConstellationEntry("天猫座", "冬夜潜行", "agility"),
            new ConstellationEntry("小狮座", "寒星微光", "strength"),
            // 双鱼季
            new ConstellationEntry("仙女座", "锁链守护", "righteousness"),
            new ConstellationEntry("蝎虎座", "冬末潜行", "agility"),
            new ConstellationEntry("鹿豹座", "春临近极", "wisdom"),
            new ConstellationEntry("小熊座", "北极指引", "wisdom"),
    };

    // ==================== 4 隐藏星座 ====================
    private static final ConstellationEntry[] HIDDEN_STARS = {
            new ConstellationEntry("凤凰座", "浴火重生、超越命定", "all"),
            new ConstellationEntry("天龙座", "坚韧不拔、守护永恒", "endurance"),
            new ConstellationEntry("印第安座", "隐逸超然、古老传承", "wisdom"),
            new ConstellationEntry("杜鹃座", "异域飞鸟、稀世珍禽", "agility"),
    };

    private ConstellationMap() {
    }

    /** 根据公历日期返回季节索引（0=白羊 ... 11=双鱼） */
    public static int getSeasonIndex(LocalDate birthDate) {
        int month = birthDate.getMonthValue();
        int day = birthDate.getDayOfMonth();
        // 1月/2月需要特殊处理（摩羯 12/22-1/19，水瓶 1/20-2/18，双鱼 2/19-3/20）
        for (int i = 0; i < 12; i++) {
            int startMonth = SEASON_START[i][0];
            int startDay = SEASON_START[i][1];
            int nextIdx = (i + 1) % 12;
            int endMonth = SEASON_START[nextIdx][0];
            int endDay = SEASON_START[nextIdx][1] - 1;
            if (endDay <= 0) {
                endDay = getDaysInMonth(endMonth) + endDay;
                endMonth = endMonth - 1;
                if (endMonth <= 0) endMonth = 12;
            }
            if (isInDateRange(month, day, startMonth, startDay, endMonth, endDay)) {
                return i;
            }
        }
        // 兜底：摩羯季覆盖 12/22-12/31 和 1/1-1/19
        if ((month == 12 && day >= 22) || (month == 1 && day <= 19)) return 9;
        return 0;
    }

    /** 获取 Gold 星座（每季 1 个） */
    public static ConstellationEntry getGold(int seasonIndex) {
        return GOLD_STARS[seasonIndex];
    }

    /** 获取 Silver 星座（每季 2 个，subIndex 0 或 1） */
    public static ConstellationEntry getSilver(int seasonIndex, int subIndex) {
        return SILVER_STARS[seasonIndex * 2 + subIndex];
    }

    /** 获取 Bronze 星座（每季 4 个，subIndex 0-3） */
    public static ConstellationEntry getBronze(int seasonIndex, int subIndex) {
        return BRONZE_STARS[seasonIndex * 4 + subIndex];
    }

    /** 获取隐藏星座（index 0=凤凰 1=天龙 2=印第安 3=杜鹃） */
    public static ConstellationEntry getHidden(int index) {
        return HIDDEN_STARS[index];
    }

    /** 凤凰座 */
    public static ConstellationEntry phoenix() {
        return HIDDEN_STARS[0];
    }

    /** 天龙座 */
    public static ConstellationEntry dragon() {
        return HIDDEN_STARS[1];
    }

    /**
     * 按星座名查条目（preset-destined-figures 人物预绑星命用）：遍历全 88 星座。
     * 同名星座在不同星阶/季节组有多个变体（如小马座出现在多个 Bronze 组），
     * 返回首个命中——对预绑用途（traits/bonusAttr 语义一致）足够。
     *
     * @return 未命中返回 null，由调用方降级处理
     */
    public static ConstellationEntry byName(String name) {
        if (name == null) {
            return null;
        }
        ConstellationEntry[][] groups = {GOLD_STARS, SILVER_STARS, BRONZE_STARS, HIDDEN_STARS};
        for (ConstellationEntry[] group : groups) {
            for (ConstellationEntry entry : group) {
                if (name.equals(entry.name())) {
                    return entry;
                }
            }
        }
        return null;
    }

    /** 常规星座总数（不含隐藏） */
    public static int regularCount() {
        return GOLD_STARS.length + SILVER_STARS.length + BRONZE_STARS.length;
    }

    /** 隐藏星座数 */
    public static int hiddenCount() {
        return HIDDEN_STARS.length;
    }

    /** 星座总数 */
    public static int totalCount() {
        return regularCount() + hiddenCount();
    }

    // ==================== 辅助方法 ====================

    private static boolean isInDateRange(int month, int day, int startMonth, int startDay, int endMonth, int endDay) {
        if (startMonth == endMonth) {
            return month == startMonth && day >= startDay && day <= endDay;
        }
        // 跨月范围
        if (startMonth < endMonth) {
            return (month == startMonth && day >= startDay) || (month == endMonth && day <= endDay)
                    || (month > startMonth && month < endMonth);
        } else {
            // 跨年范围（如 12/22 - 1/19）
            return (month == startMonth && day >= startDay) || (month == endMonth && day <= endDay)
                    || (month > startMonth || month < endMonth);
        }
    }

    private static int getDaysInMonth(int month) {
        return switch (month) {
            case 1, 3, 5, 7, 8, 10, 12 -> 31;
            case 4, 6, 9, 11 -> 30;
            case 2 -> 29; // 闰年兜底取 29
            default -> 30;
        };
    }
}
