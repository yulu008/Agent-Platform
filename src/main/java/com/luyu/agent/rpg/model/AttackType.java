package com.luyu.agent.rpg.model;

/**
 * 招式攻击类型分类。
 * <p>
 * PHYSICAL — 物理攻击：含肉体攻击、小宇宙外放、毒液、武器
 * SPATIAL  — 空间攻击：扭曲/操纵次元、传送、空间绞杀
 * MENTAL   — 精神攻击：直接作用于脑神经、制造幻觉、支配意志、音乐扰乱
 */
public enum AttackType {
    /** 物理攻击：肉体攻击、小宇宙外放（冲击波/冻气/火焰/雷电）、毒液、武器 */
    PHYSICAL,
    /** 空间攻击：扭曲或操纵次元、传送、空间绞杀 */
    SPATIAL,
    /** 精神攻击：直接作用于脑神经、制造幻觉、支配意志、音乐扰乱 */
    MENTAL
}
