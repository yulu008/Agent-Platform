package com.luyu.agent.rpg.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 日柱天干地支查表工具。
 * <p>
 * 基于 60 甲子循环算法：以已知基准日为起点，
 * 计算目标日期与基准日的天数差，对 60 取模得到干支序号。
 * <p>
 * 基准日：2000-01-01 = 戊午日（甲子循环第 55 位，索引 54）。
 * 天干：甲乙丙丁戊己庚辛壬癸（10 循环）
 * 地支：子丑寅卯辰巳午未申酉戌亥（12 循环）
 */
public class DayPillarTable {

    public static final String[] TIAN_GAN = {
            "甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"
    };

    public static final String[] DI_ZHI = {
            "子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"
    };

    /** 基准日：2000-01-01 */
    private static final LocalDate BASE_DATE = LocalDate.of(2000, 1, 1);

    /** 基准日的甲子序号（0-based），戊午 = 天干戊(4) 地支午(6) → 序号通过查表得 54 */
    private static final int BASE_GAN_ZHI_INDEX = 54;

    /** 60 甲子组合（天干 + 地支），按顺序排列 */
    private static final String[] GAN_ZHI_60 = build60JiaZi();

    private DayPillarTable() {
    }

    /** 构建 60 甲子序列 */
    private static String[] build60JiaZi() {
        String[] result = new String[60];
        for (int i = 0; i < 60; i++) {
            result[i] = TIAN_GAN[i % 10] + DI_ZHI[i % 12];
        }
        return result;
    }

    /**
     * 计算公历日期的日柱天干地支。
     *
     * @param date 公历日期
     * @return 二元素数组 [天干, 地支]，如 ["戊", "午"]
     */
    public static String[] getDayPillar(LocalDate date) {
        long daysDiff = ChronoUnit.DAYS.between(BASE_DATE, date);
        int ganZhiIndex = (int) (((daysDiff % 60) + BASE_GAN_ZHI_INDEX % 60 + 60) % 60);
        String ganZhi = GAN_ZHI_60[ganZhiIndex];
        return new String[]{ganZhi.substring(0, 1), ganZhi.substring(1)};
    }

    /** 获取日柱天干 */
    public static String getDayGan(LocalDate date) {
        return getDayPillar(date)[0];
    }

    /** 获取日柱地支 */
    public static String getDayZhi(LocalDate date) {
        return getDayPillar(date)[1];
    }

    /** 获取完整的日柱（如"戊午"） */
    public static String getDayPillarStr(LocalDate date) {
        String[] parts = getDayPillar(date);
        return parts[0] + parts[1];
    }

    /**
     * 根据年份获取年柱天干。
     * 公式：(year - 4) % 10 → 天干序号（甲=0）
     * 例：1984年 → (1984-4)%10 = 0 → 甲
     */
    public static String getYearGan(int year) {
        int idx = ((year - 4) % 10 + 10) % 10;
        return TIAN_GAN[idx];
    }

    /**
     * 根据年份获取年柱地支。
     * 公式：(year - 4) % 12 → 地支序号（子=0）
     * 例：1984年 → (1984-4)%12 = 0 → 子
     */
    public static String getYearZhi(int year) {
        int idx = ((year - 4) % 12 + 12) % 12;
        return DI_ZHI[idx];
    }

    /** 获取完整的年柱（如"甲子"） */
    public static String getYearPillar(int year) {
        return getYearGan(year) + getYearZhi(year);
    }

    /** 天干序号（甲=0 ... 癸=9） */
    public static int ganIndex(String gan) {
        for (int i = 0; i < TIAN_GAN.length; i++) {
            if (TIAN_GAN[i].equals(gan)) return i;
        }
        return 0;
    }

    /** 地支序号（子=0 ... 亥=11） */
    public static int zhiIndex(String zhi) {
        for (int i = 0; i < DI_ZHI.length; i++) {
            if (DI_ZHI[i].equals(zhi)) return i;
        }
        return 0;
    }
}
