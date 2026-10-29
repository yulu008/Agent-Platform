package com.luyu.agent.rpg.model;

/**
 * 招式熟练度（0~1000）与档位措辞的统一映射。
 * <p>
 * 档位词是引擎产出的<b>单一真源</b>：GM 上下文渲染、前端状态面板都从这里取词，
 * 不允许 GM 自行发明形容词（否则同一数值在不同轮次会有「略有小成」「渐入佳境」等
 * 互相矛盾的描述，玩家无法判断自己到底进步了多少）。
 * <p>
 * 数值本身由 GM 以「本轮变化量」写入、引擎累加（与 {@code playerStates.money} 同款约定），
 * 累加与门禁判定属 {@code rpg-technique-gate} 变更，本枚举只负责区间 → 措辞。
 */
public enum TechniqueProficiency {

    /** 0：尚未领悟，MUST NOT 出现在「已会」段 */
    NOT_LEARNED(0, 0, "未学会"),
    /** 1-199：刚领悟或功力受损后的残存火候 */
    GLIMPSE(1, 199, "初窥门径"),
    /** 200-399：青铜/白银建档时的起手档位 */
    INITIATE(200, 399, "入门"),
    /** 400-599：黄金/天罡/神斗士建档时的起手档位，也是默认解锁阈值所在档 */
    ADEPT(400, 599, "小成"),
    /** 600-799 */
    MASTER(600, 799, "大成"),
    /** 800-999 */
    PERFECT(800, 999, "圆满"),
    /** 1000：究极奥义，熟练度上限 */
    ULTIMATE(1000, 1000, "究极奥义");

    /** 熟练度下界（含） */
    public static final int MIN = 0;

    /** 熟练度上界（含） */
    public static final int MAX = 1000;

    private final int from;
    private final int to;
    private final String label;

    TechniqueProficiency(int from, int to, String label) {
        this.from = from;
        this.to = to;
        this.label = label;
    }

    public int from() {
        return from;
    }

    public int to() {
        return to;
    }

    public String label() {
        return label;
    }

    /**
     * 把熟练度夹到 {@link #MIN}~{@link #MAX}。
     * <p>
     * 供累加侧调用：LLM 写出的增量经累加后可能越界，夹取比拒绝更友好（拒绝会丢失玩家成长）。
     */
    public static int clamp(int value) {
        return Math.max(MIN, Math.min(MAX, value));
    }

    /**
     * 数值 → 档位。
     *
     * @param proficiency 熟练度，越界值先夹取再判档
     * @return 对应档位枚举，恒非 null
     */
    public static TechniqueProficiency of(int proficiency) {
        int v = clamp(proficiency);
        for (TechniqueProficiency tier : values()) {
            if (v >= tier.from && v <= tier.to) {
                return tier;
            }
        }
        // 区间已完整覆盖 0~1000，走到这里只可能是枚举被改坏；退化到 NOT_LEARNED 而非抛异常
        return NOT_LEARNED;
    }

    /** 数值 → 档位词（如 620 → 「大成」）。 */
    public static String labelOf(int proficiency) {
        return of(proficiency).label();
    }

    /**
     * 渲染用紧凑串：{@code 数值/档位}（如「620/大成」）。
     * <p>
     * 熟练度 0 是特例 —— 直接渲染「未学会」而不带数值，因为「0/未学会」既冗余又容易被
     * GM 误读成「学过但归零」。
     */
    public static String render(int proficiency) {
        int v = clamp(proficiency);
        if (v == MIN) {
            return NOT_LEARNED.label();
        }
        return v + "/" + labelOf(v);
    }
}
