package com.luyu.agent.metering;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.config.MeteringProperties.TierPrice;
import com.luyu.agent.metering.model.ModelTier;
import com.luyu.agent.metering.model.UsageRecord;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * 计费折算（tasks 3.1-3.3 / design D6）。
 * <p>
 * 金额以<b>微元</b>（1 元 = 10^6 微元）整数表示。单价来源优先级：DB({@code pricing_policy}) 覆盖
 * &gt; config 默认（{@code agent.metering.pricing.*}）。档位由模型名自动判定（含 {@code flash} → flash 档）。
 * <p>
 * 单次金额（微元）= 非缓存输入 token × 输入价 + 缓存命中输入 token × 缓存价 + 输出 token × 输出价，
 * 其中「非缓存输入 = promptTokens − cachedTokens」。全程 {@link BigDecimal}，末尾 HALF_UP 取整，禁止浮点累加。
 * <p>
 * 降级：某次 {@code cachedTokens} 不可得（{@code null}）时按 0 计（输入全按档位输入价），并标记
 * {@code cacheUnavailable}；单价无法解析（输入价与输出价均 &lt;= 0）时金额记 0 并标记 {@code priceMissing}，
 * 但用量照常落库（不丢量）。
 * <p>
 * 关键换算：1 元/百万 token == 1 微元/token（分子分母同为 10^6），故 config 的「元/百万 token」数值
 * 直接等于「微元/token」。DB 覆盖存的即微元/token 整数。
 */
@Component
public class CostCalculator {

    private final MeteringRepository repository;
    private final MeteringProperties properties;

    public CostCalculator(MeteringRepository repository, MeteringProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 折算结果。 */
    public record CostResult(long amountMicro, ModelTier tier, boolean cacheUnavailable, boolean priceMissing) {
    }

    /** 判档：模型名含 flash（不区分大小写）→ FLASH，否则 STANDARD。 */
    public ModelTier tierOf(String modelName) {
        return ModelTier.of(modelName);
    }

    /**
     * 折算单次调用金额。
     *
     * @param record 归一化用量（model 用于判档）
     */
    public CostResult calculate(UsageRecord record) {
        ModelTier tier = ModelTier.of(record.model());
        BigDecimal[] price = resolvePriceMicroPerToken(tier);
        BigDecimal inputMicro = price[0];
        BigDecimal outputMicro = price[1];
        BigDecimal cacheMicro = price[2];

        boolean priceMissing = inputMicro.signum() <= 0 && outputMicro.signum() <= 0;
        boolean cacheUnavailable = record.cacheUnavailable();

        if (priceMissing) {
            return new CostResult(0L, tier, cacheUnavailable, true);
        }

        long prompt = Math.max(0, record.promptTokens());
        long completion = Math.max(0, record.completionTokens());
        long cached = cacheUnavailable ? 0L : Math.min(Math.max(0, record.cachedOrZero()), prompt);
        long nonCachedInput = prompt - cached;

        BigDecimal amount = BigDecimal.valueOf(nonCachedInput).multiply(inputMicro)
                .add(BigDecimal.valueOf(cached).multiply(cacheMicro))
                .add(BigDecimal.valueOf(completion).multiply(outputMicro));

        long amountMicro = amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new CostResult(amountMicro, tier, cacheUnavailable, false);
    }

    /**
     * 解析档位单价（微元/token）：{@code [input, output, cache]}。DB 覆盖优先于 config 默认。
     */
    private BigDecimal[] resolvePriceMicroPerToken(ModelTier tier) {
        long[] override = repository.findPricingOverride(tier.key());
        if (override != null) {
            return new BigDecimal[]{
                    BigDecimal.valueOf(override[0]),
                    BigDecimal.valueOf(override[1]),
                    BigDecimal.valueOf(override[2])
            };
        }
        TierPrice tp = tier == ModelTier.FLASH
                ? properties.getPricing().getFlash()
                : properties.getPricing().getStandard();
        return new BigDecimal[]{
                BigDecimal.valueOf(tp.getInputYuanPerMillion()),
                BigDecimal.valueOf(tp.getOutputYuanPerMillion()),
                BigDecimal.valueOf(tp.getCacheYuanPerMillion())
        };
    }
}
