package com.luyu.agent.rpg.model;

import java.util.List;

/**
 * 存档 / 星命推演中单个招式的掌握状态（rpg-technique-tree-data D6）。
 * <p>
 * 七个字段与 {@code playerStates.techniques[]} 的写入结构一一对应；星命推演预览
 * （{@link OriginResult#getTechniques()}）的元素类型也是本 record —— 预览与存档同构，
 * 玩家预览到的起手招式即落档招式（{@code /rpg/workshop/start} 重算同构结果）。
 * <p>
 * 构造时统一夹取：{@code proficiency} 走 {@link TechniqueProficiency#clamp}，{@code tier} 下界 1
 * （根节点即 1，非法值不污染渲染）。档位措辞<b>不</b>做字段：渲染一律走
 * {@link TechniqueProficiency#render} 单一真源（前端 game.js 有镜像分档表）。
 */
public record TechniqueState(String name, String description, String source,
                             int tier, List<String> prerequisites,
                             boolean learned, int proficiency) {

    public TechniqueState {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        tier = Math.max(1, tier);
        proficiency = TechniqueProficiency.clamp(proficiency);
    }
}
