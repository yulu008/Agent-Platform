package com.luyu.agent.metering;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * 配额硬拒守卫（tasks 7.1 / design D8）。
 * <p>
 * 在<b>用户发起型入口</b>（主聊天起流前、RPG 回合起流前、工坊同步生成前）事前检查：读该租户滚动窗口
 * （默认 30 天）内 {@code tenant_usage_daily} 金额合计，与预算比较，{@code 已用 >= 预算} 即抛
 * {@link QuotaExceededException}。
 * <p>
 * <b>预算来源</b>：{@code tenant_quota_policy} 租户覆盖优先，无覆盖回退平台默认
 * （{@code agent.metering.quota.default-budget-yuan} × 10^6 微元）。改后下一次检查即生效（无需重启）。
 * <p>
 * <b>开关</b>：{@code agent.metering.quota.enabled}（迁移期默认关，仅计量不拦截）。关闭时 {@link #check}
 * 直接放行。<b>系统触发的收尾内部调用（state_delta/压缩）不经本守卫</b>——只在三入口调用，避免状态损坏。
 * <p>
 * 租户缺失（{@code <missing>}/空）时不硬拒（无归属无法限额），仅告警放行，交由计量侧归账护栏发现漏传。
 */
@Component
public class QuotaGuard {

    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);

    /** 1 元 = 10^6 微元。 */
    private static final long MICRO_PER_YUAN = 1_000_000L;

    private final MeteringRepository repository;
    private final MeteringProperties properties;

    public QuotaGuard(MeteringRepository repository, MeteringProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 事前配额检查：超限抛 {@link QuotaExceededException}。
     *
     * @param tenantId 发起租户（用户发起型入口在请求线程已解析）
     */
    public void check(String tenantId) {
        MeteringProperties.Quota quota = properties.getQuota();
        if (!quota.isEnabled()) {
            return;
        }
        if (tenantId == null || tenantId.isBlank() || TenantUsageService.MISSING_TENANT.equals(tenantId)) {
            log.warn("配额检查跳过：租户缺失（{}），无归属无法限额", tenantId);
            return;
        }

        LocalDate fromDay = RollingWindow.fromDay(quota.getWindowDays());
        long usedMicro = repository.sumAmountMicroSince(tenantId, fromDay);
        long budgetMicro = resolveBudgetMicro(tenantId, quota);

        if (usedMicro >= budgetMicro) {
            log.info("配额硬拒：tenantId={} 已用={} 微元 >= 预算={} 微元（窗口 {} 天，起始 {}）",
                    tenantId, usedMicro, budgetMicro, quota.getWindowDays(), fromDay);
            throw new QuotaExceededException(usedMicro, budgetMicro);
        }
    }

    /**
     * 解析租户预算（微元）：DB 覆盖优先，无覆盖回退平台默认。
     */
    private long resolveBudgetMicro(String tenantId, MeteringProperties.Quota quota) {
        Long override = repository.findBudgetOverrideMicro(tenantId);
        if (override != null) {
            return override;
        }
        return Math.round(quota.getDefaultBudgetYuan() * MICRO_PER_YUAN);
    }
}
