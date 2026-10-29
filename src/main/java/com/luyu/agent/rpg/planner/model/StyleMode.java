package com.luyu.agent.rpg.planner.model;

/**
 * 拆解风格模式（design D9：run 全局参数，贯穿 S1-S6 每个 prompt 约束）。
 */
public enum StyleMode {

    /** 忠实拆解：贴设定约束 + S1 产物带出处标注 */
    FAITHFUL("faithful"),

    /** 自由二创：放开约束，鼓励混搭改造；S1 只附灵感源清单 */
    REMIX("remix");

    private final String code;

    StyleMode(String code) {
        this.code = code;
    }

    /** 库表/manifest 中存储的码值 */
    public String code() {
        return code;
    }

    /** 按码值解析；未知码抛 IllegalArgumentException */
    public static StyleMode fromCode(String code) {
        for (StyleMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("未知的拆解风格模式: " + code);
    }
}
