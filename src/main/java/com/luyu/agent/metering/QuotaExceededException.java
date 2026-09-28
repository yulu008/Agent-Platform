package com.luyu.agent.metering;

/**
 * 配额超限异常（tasks 7.1 / design D8）。
 * <p>
 * 由 {@link QuotaGuard#check(String)} 在用户发起型入口事前检查发现「滚动窗口累计消费 &ge; 预算」时抛出。
 * 携带已用/预算（微元）供各入口渲染友好提示：主聊天/RPG 以 SSE error 事件返回，工坊以 HTTP 状态返回。
 * <p>
 * 仅在消费<b>发起点</b>抛出（事前拦截）；系统触发的收尾内部调用不设卡，避免状态损坏（design D8）。
 */
public class QuotaExceededException extends RuntimeException {

    private final long usedMicro;
    private final long budgetMicro;

    public QuotaExceededException(long usedMicro, long budgetMicro) {
        super("配额已用尽：滚动窗口累计消费 " + toYuan(usedMicro) + " 元，已达预算 " + toYuan(budgetMicro) + " 元");
        this.usedMicro = usedMicro;
        this.budgetMicro = budgetMicro;
    }

    /** 当期已用（微元）。 */
    public long getUsedMicro() {
        return usedMicro;
    }

    /** 预算（微元）。 */
    public long getBudgetMicro() {
        return budgetMicro;
    }

    /** 微元 → 元（保留两位小数字符串，仅用于展示）。 */
    private static String toYuan(long micro) {
        return java.math.BigDecimal.valueOf(micro)
                .movePointLeft(6)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }
}
