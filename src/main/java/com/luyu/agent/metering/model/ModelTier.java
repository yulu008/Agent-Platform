package com.luyu.agent.metering.model;

/**
 * 模型计费档位（design D6）。
 * <p>
 * 档位由模型名自动判定：名称含 {@code flash}（不区分大小写）归 {@link #FLASH}，否则归 {@link #STANDARD}。
 * 无需在配置中手工标注 tier，避免遗漏。spike 实测：请求名 {@code deepseek-v4-flash} 的响应实际模型名为
 * {@code deepseek-v4.1-flash}，两者均含 flash，故用响应模型名判档同样正确。
 */
public enum ModelTier {

    FLASH("flash"),
    STANDARD("standard");

    /** 持久化到 pricing_policy.tier 的键值。 */
    private final String key;

    ModelTier(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * 按模型名判档：含 {@code flash}（不区分大小写）→ {@link #FLASH}，否则 {@link #STANDARD}。
     * 名称为空时保守归标准档（价高，避免漏计成本）。
     */
    public static ModelTier of(String modelName) {
        if (modelName != null && modelName.toLowerCase().contains("flash")) {
            return FLASH;
        }
        return STANDARD;
    }

    /** 由持久化键值反查档位；未知键归标准档。 */
    public static ModelTier fromKey(String key) {
        if (FLASH.key.equalsIgnoreCase(key)) {
            return FLASH;
        }
        return STANDARD;
    }
}
