package com.luyu.agent.metering;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.luyu.agent.metering.CostCalculator.CostResult;
import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.model.UsageRecord;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * 租户用量落库服务（tasks 3.4 / design D6、D7）。
 * <p>
 * 单次调用在<b>同一事务</b>内写日桶（{@code tenant_usage_daily}）+ 明细（{@code token_usage_log}），
 * 保证「明细求和 == 日桶金额」口径一致、不出现半写。金额由 {@link CostCalculator} 折算为微元整数。
 * <p>
 * <b>归账完整性</b>：租户缺失（{@link #MISSING_TENANT}）时不静默兜底、不丢量——照常落库并
 * {@code log.warn} 告警（可观测的漏传信号，供回归护栏统计）。计量落库异常一律吞掉 + 告警，
 * <b>绝不影响 LLM 响应</b>（计量是旁路，见 design D1 修订）。
 */
@Service
public class TenantUsageService {

    /** 租户缺失占位：显式记录漏传，绝不静默回落到某个默认租户。 */
    public static final String MISSING_TENANT = "<missing>";

    private static final Logger log = LoggerFactory.getLogger(TenantUsageService.class);

    private final MeteringRepository repository;
    private final CostCalculator costCalculator;
    private final MeteringProperties properties;
    private final TransactionTemplate transactionTemplate;

    public TenantUsageService(MeteringRepository repository,
                              CostCalculator costCalculator,
                              MeteringProperties properties,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.costCalculator = costCalculator;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 记录一次 LLM 调用用量：同事务写日桶 + 明细。
     * <p>
     * 计量开关关闭时直接跳过（不折算、不落库）。任何异常都被吞掉并告警，不向上抛出。
     *
     * @param record 归一化用量（tenantId 缺失时为 {@link #MISSING_TENANT}）
     */
    public void record(UsageRecord record) {
        if (record == null) {
            return;
        }
        if (!properties.isEnabled()) {
            return;
        }
        try {
            if (MISSING_TENANT.equals(record.tenantId())) {
                log.warn("计量漏传租户：callType={} model={} sessionId={} 记为 <missing>，请检查透传链路",
                        record.callType(), record.model(), record.sessionId());
            }

            CostResult cost = costCalculator.calculate(record);
            if (cost.priceMissing()) {
                log.warn("计量单价缺失：tier={} model={} 金额记 0 但用量照常落库", cost.tier(), record.model());
            }

            String tenantId = record.tenantId() == null ? MISSING_TENANT : record.tenantId();
            String model = record.model() == null ? "unknown" : record.model();
            long prompt = Math.max(0, record.promptTokens());
            long completion = Math.max(0, record.completionTokens());
            long cached = record.cachedOrZero();
            long amountMicro = cost.amountMicro();
            boolean cacheUnavailable = cost.cacheUnavailable();
            LocalDate day = RollingWindow.today();

            transactionTemplate.executeWithoutResult(status -> {
                repository.accumulateDaily(tenantId, day, model, prompt, completion, cached, amountMicro);
                repository.insertLog(tenantId, model, record.callType(), record.sessionId(),
                        prompt, completion, cached, amountMicro, cacheUnavailable);
            });
        } catch (Exception e) {
            // 计量是旁路：落库失败绝不影响 LLM 响应
            log.error("计量落库失败（已吞掉，不影响响应）：tenantId={} model={} callType={}",
                    record.tenantId(), record.model(), record.callType(), e);
        }
    }
}
