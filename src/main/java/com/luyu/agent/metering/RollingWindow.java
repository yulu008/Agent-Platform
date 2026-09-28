package com.luyu.agent.metering;

import java.time.LocalDate;

/**
 * 滚动窗口工具（tasks 2.6 / design D7）。
 * <p>
 * 配额周期为<b>滚动 N 天</b>（默认 30，非自然月）：当期已用 = {@code SUM(amount_micro) WHERE day >= fromDay}。
 * 窗口含当天，故 30 天窗口的起始日为 {@code today - 29}。配额检查与用量聚合共用此边界，避免口径漂移。
 */
public final class RollingWindow {

    private RollingWindow() {
    }

    /** 当天（日桶 day key）。 */
    public static LocalDate today() {
        return LocalDate.now();
    }

    /**
     * 滚动窗口起始日（含当天）。
     *
     * @param windowDays 窗口天数（&lt;=1 时即当天）
     */
    public static LocalDate fromDay(int windowDays) {
        int days = Math.max(1, windowDays);
        return LocalDate.now().minusDays(days - 1L);
    }
}
