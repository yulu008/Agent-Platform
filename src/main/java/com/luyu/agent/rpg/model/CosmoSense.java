package com.luyu.agent.rpg.model;

/**
 * 小宇宙强度感阶（rpg-cosmo-sense）与档位措辞的统一映射。
 * <p>
 * 档位词是引擎产出的<b>单一真源</b>（同 {@link TechniqueProficiency} 哲学）：GM 上下文星命行、
 * 前端状态面板、步骤②结果卡都从这里取词，不允许 GM 自行发明形容词（否则同一数值在不同轮次
 * 会有「小宇宙初燃」「第七感觉醒」等互相矛盾的描述，玩家无法判断自己到底进步了多少）。
 * <p>
 * 数值本身由创角掷骰产出初值、GM 以「本轮变化量」写入、引擎累加并 clamp（累加与回落属
 * {@code GameLoopService} 结算旁路，本枚举只负责区间 → 措辞）。
 * <p>
 * 分档（design D3）：0=不具备第六感（仅 NPC 语义）/ 1-300 第六感 / 301-700 第七感 /
 * 701-1200 第八感 / 1201+ 第九感（无上界，靠单轮 400 截断防飞）。
 */
public enum CosmoSense {

    /** 0：不具备第六感，仅 NPC 语义（普通人）；主角结算下界为 1，永不产出 0 */
    NOT_AWAKENED(0, 0, "不具备第六感"),
    /** 1-300：第六感 */
    SIXTH(1, 300, "第六感"),
    /** 301-700：第七感（黄金鸿沟），创角上限所在档 */
    SEVENTH(301, 700, "第七感"),
    /** 701-1200：第八感，仅濒死剧情可达 */
    EIGHTH(701, 1200, "第八感"),
    /** 1201+：第九感，仅神级剧情可达（开区间无上界） */
    NINTH(1201, Integer.MAX_VALUE, "第九感");

    /** 主角小宇宙下界（含）：永不为 0 */
    public static final int PLAYER_MIN = 1;

    /** NPC 小宇宙下界（含）：0 即不具备第六感 */
    public static final int NPC_MIN = 0;

    private final int from;
    private final int to;
    private final String label;

    CosmoSense(int from, int to, String label) {
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
     * 数值 → 档位。
     * <p>
     * 负值夹到 0（不具备第六感）；无上界，第九感为开区间。
     *
     * @param cosmo 小宇宙强度
     * @return 对应档位枚举，恒非 null
     */
    public static CosmoSense of(int cosmo) {
        int v = Math.max(0, cosmo);
        for (CosmoSense tier : values()) {
            if (v >= tier.from && v <= tier.to) {
                return tier;
            }
        }
        // 区间已完整覆盖 0~MAX，走到这里只可能是枚举被改坏；退化到 NOT_AWAKENED 而非抛异常
        return NOT_AWAKENED;
    }

    /** 数值 → 档位词（如 268 → 「第六感」）。 */
    public static String labelOf(int cosmo) {
        return of(cosmo).label();
    }

    /**
     * 渲染用紧凑串：{@code N（X感）}（如「268（第六感）」）。
     * <p>
     * 0 是特例 —— 直接渲染「不具备第六感」而不带数值，因为「0（不具备第六感）」冗余。
     */
    public static String render(int cosmo) {
        int v = Math.max(0, cosmo);
        if (v == 0) {
            return NOT_AWAKENED.label();
        }
        return v + "（" + labelOf(v) + "）";
    }
}
