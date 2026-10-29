package com.luyu.agent.rpg.model;

/**
 * 星座/星辰招式。
 * <p>
 * 每个招式包含名称、描述、来源版本和攻击类型。
 * 来源版本包括：经典系列、黄金魂、Ω、圣斗少女翔、Asgard篇、冥界篇等。
 *
 * @param name        招式名称（如"星光灭绝"、"天马流星拳"）
 * @param description 招式描述（如"以星光将敌人放逐至异次元的绝技"）
 * @param source      来源版本（如"经典系列"、"黄金魂"、"Ω"）
 * @param attackType  攻击类型（物理/空间/精神）
 */
public record StarTechnique(String name, String description, String source, AttackType attackType) {

    /**
     * 3 参数便利构造器，attackType 默认为 {@link AttackType#PHYSICAL}。
     * 适用于绝大多数物理类招式，无需显式标注第 4 参数。
     */
    public StarTechnique(String name, String description, String source) {
        this(name, description, source, AttackType.PHYSICAL);
    }
}
