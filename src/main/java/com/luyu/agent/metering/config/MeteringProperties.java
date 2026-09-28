package com.luyu.agent.metering.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 计量与配额配置（tenant-token-metering）。
 * <p>
 * 单价以「元/百万 token」表达（与运营口径一致）；由于 1 元/百万 token 恰等于 1 微元/token
 * （1 元 = 10^6 微元，1 百万 token = 10^6 token），故数值上「元/百万 token」== 「微元/token」，
 * {@link com.luyu.agent.metering.CostCalculator} 据此把用量折算为微元整数金额。
 * <p>
 * 这些是 config 默认值；管理端可经 {@code pricing_policy} / {@code tenant_quota_policy} 表覆盖，
 * DB 覆盖优先于本配置（无需重启即生效）。
 */
@ConfigurationProperties(prefix = "agent.metering")
public class MeteringProperties {

    /** 计量总开关：false 时 MeteringAdvisor 完全旁路（不写库、不计费）。默认开。 */
    private boolean enabled = true;

    /** 明细 token_usage_log 保留天数，超期由定时任务清理。 */
    private int detailRetentionDays = 90;

    private Quota quota = new Quota();

    private Pricing pricing = new Pricing();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDetailRetentionDays() { return detailRetentionDays; }
    public void setDetailRetentionDays(int detailRetentionDays) { this.detailRetentionDays = detailRetentionDays; }

    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota; }

    public Pricing getPricing() { return pricing; }
    public void setPricing(Pricing pricing) { this.pricing = pricing; }

    /** 配额硬拒配置。 */
    public static class Quota {
        /** 配额开关：迁移期默认关（仅计量不拦截），观察数据完整后再开。 */
        private boolean enabled = false;
        /** 平台默认预算（元/账号），滚动窗口内累计消费超过即硬拒。 */
        private double defaultBudgetYuan = 20.0;
        /** 滚动窗口天数（非自然月）。 */
        private int windowDays = 30;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getDefaultBudgetYuan() { return defaultBudgetYuan; }
        public void setDefaultBudgetYuan(double defaultBudgetYuan) { this.defaultBudgetYuan = defaultBudgetYuan; }
        public int getWindowDays() { return windowDays; }
        public void setWindowDays(int windowDays) { this.windowDays = windowDays; }
    }

    /** 档位单价（元/百万 token）。 */
    public static class Pricing {
        private TierPrice flash = new TierPrice(3, 10, 2);
        private TierPrice standard = new TierPrice(8, 24, 2);

        public TierPrice getFlash() { return flash; }
        public void setFlash(TierPrice flash) { this.flash = flash; }
        public TierPrice getStandard() { return standard; }
        public void setStandard(TierPrice standard) { this.standard = standard; }
    }

    /** 单一档位的输入/输出/缓存命中单价（元/百万 token）。 */
    public static class TierPrice {
        private double inputYuanPerMillion;
        private double outputYuanPerMillion;
        private double cacheYuanPerMillion;

        public TierPrice() { }
        public TierPrice(double input, double output, double cache) {
            this.inputYuanPerMillion = input;
            this.outputYuanPerMillion = output;
            this.cacheYuanPerMillion = cache;
        }

        public double getInputYuanPerMillion() { return inputYuanPerMillion; }
        public void setInputYuanPerMillion(double v) { this.inputYuanPerMillion = v; }
        public double getOutputYuanPerMillion() { return outputYuanPerMillion; }
        public void setOutputYuanPerMillion(double v) { this.outputYuanPerMillion = v; }
        public double getCacheYuanPerMillion() { return cacheYuanPerMillion; }
        public void setCacheYuanPerMillion(double v) { this.cacheYuanPerMillion = v; }
    }
}
