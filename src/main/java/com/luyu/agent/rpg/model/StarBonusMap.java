package com.luyu.agent.rpg.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 星座 → 属性加成静态映射。
 * <p>
 * 加成幅度：Gold +15 / Silver +8 / Bronze +5 / 凤凰全+5 / 天龙耐力+10 /
 * 天罡义理+12 / 地煞力量+8 / 神斗士力+10耐力+10。
 */
public class StarBonusMap {

    /** 全属性列表 */
    public static final String[] ATTRIBUTES = {
            "strength", "agility", "wisdom", "endurance", "healing", "stamina", "righteousness"
    };

    private StarBonusMap() {
    }

    /**
     * 根据星阶和加成属性计算属性加成。
     *
     * @param starRank  星阶（Gold/Silver/Bronze/Hidden/Specter-TianGang/Specter-DiSha/GodWarrior）
     * @param bonusAttr 主加成属性名（"all" 表示全属性）
     * @return 属性 → 加成值映射
     */
    public static Map<String, Integer> getBonus(String starRank, String bonusAttr) {
        Map<String, Integer> bonus = new LinkedHashMap<>();
        int value = switch (starRank) {
            case ConstellationMap.GOLD -> 15;
            case ConstellationMap.SILVER -> 8;
            case ConstellationMap.BRONZE -> 5;
            case ConstellationMap.HIDDEN -> "all".equals(bonusAttr) ? 5 : 10;
            case "Specter-TianGang" -> 12;
            case "Specter-DiSha" -> 8;
            case "GodWarrior" -> 10;
            default -> 5;
        };
        if ("all".equals(bonusAttr)) {
            for (String attr : ATTRIBUTES) {
                bonus.put(attr, value);
            }
        } else if ("GodWarrior".equals(starRank)) {
            // 神斗士固定力+10耐力+10
            bonus.put("strength", 10);
            bonus.put("endurance", 10);
        } else {
            bonus.put(bonusAttr, value);
        }
        return bonus;
    }
}
