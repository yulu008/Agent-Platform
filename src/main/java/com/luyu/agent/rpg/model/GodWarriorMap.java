package com.luyu.agent.rpg.model;

/**
 * 神斗士 7 北斗主星映射表。
 * <p>
 * 按公历出生月份直接分配：1月→主星1, ..., 7月→主星7, 8月→主星1 循环。
 */
public class GodWarriorMap {

    public static final String FACTION = "godwarrior";

    /** 7 主星数据（名称、英文、特质、加成属性） */
    private static final ConstellationEntry[] STARS = {
            new ConstellationEntry("天枢星", "Dubhe", "统帅果决、一往无前", "strength"),
            new ConstellationEntry("天璇星", "Merak", "沉稳深思、以静制动", "endurance"),
            new ConstellationEntry("天玑星", "Phecda", "洞察先机、谋定后动", "wisdom"),
            new ConstellationEntry("天权星", "Megrez", "权衡利弊、均衡之道", "righteousness"),
            new ConstellationEntry("玉衡星", "Alioth", "锐利精准、一击必中", "agility"),
            new ConstellationEntry("开阳星", "Mizar", "光明磊落、破邪显正", "healing"),
            new ConstellationEntry("摇光星", "Alkaid", "动荡不安、破釜沉舟", "strength"),
    };

    private GodWarriorMap() {
    }

    /** 按出生月份（1-12）分配主星，8月起循环 */
    public static ConstellationEntry getByMonth(int month) {
        int index = (month - 1) % 7;
        return STARS[index];
    }

    /** 按索引获取（0-6） */
    public static ConstellationEntry getByIndex(int index) {
        return STARS[index % 7];
    }

    public static int count() {
        return STARS.length;
    }
}
