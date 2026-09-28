package com.luyu.agent.moderation;

/**
 * 内容审查受限类别（spec「审查类别与引擎」）。
 * <p>
 * 本轮仅覆盖脏话 / 歧视 / 色情三类；政治敏感、暴力等不在范围（见 proposal Non-Goals）。
 * {@code key} 用于词表分节标记与配置，{@code label} 用于审计日志展示。
 */
public enum ModerationCategory {

    PROFANITY("profanity", "脏话"),
    DISCRIMINATION("discrimination", "歧视"),
    PORNOGRAPHY("pornography", "色情");

    private final String key;
    private final String label;

    ModerationCategory(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 词表分节标记 / 配置键，如 {@code profanity}。 */
    public String key() {
        return key;
    }

    /** 面向日志的中文类别名。 */
    public String label() {
        return label;
    }

    /**
     * 按 key 反查类别（不区分大小写）。
     *
     * @param key 类别键
     * @return 匹配的类别；未知 key 返回 {@code null}
     */
    public static ModerationCategory fromKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase();
        for (ModerationCategory category : values()) {
            if (category.key.equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
