package com.luyu.agent.rpg.planner.model;

/**
 * 管线输入载体模式（spec planner-pipeline-run「管线 run 创建向导」）。
 */
public enum SourceMode {

    /** 贴原文：S1 多轮提炼归纳，无需联网 */
    TEXT("text"),

    /** IP 名字：S1 受限 agentic 搜索循环（联网查资料） */
    IP_NAME("ip_name");

    private final String code;

    SourceMode(String code) {
        this.code = code;
    }

    /** 库表/manifest 中存储的码值 */
    public String code() {
        return code;
    }

    /** 按码值解析；未知码抛 IllegalArgumentException */
    public static SourceMode fromCode(String code) {
        for (SourceMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知的管线载体模式: " + code);
    }
}
