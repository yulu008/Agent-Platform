package com.luyu.agent.rpg.model;

/**
 * 星座条目：名称 + 英文名 + 特质 + 加成属性。
 * 英文名可为空字符串（非所有星座都有英文名）。
 */
public record ConstellationEntry(String name, String englishName, String traits, String bonusAttr) {

    /** 三参便捷构造（英文名为空） */
    public ConstellationEntry(String name, String traits, String bonusAttr) {
        this(name, "", traits, bonusAttr);
    }
}
