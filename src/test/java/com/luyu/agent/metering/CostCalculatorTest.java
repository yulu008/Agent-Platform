package com.luyu.agent.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.luyu.agent.metering.CostCalculator.CostResult;
import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.config.MeteringProperties.TierPrice;
import com.luyu.agent.metering.model.ModelTier;
import com.luyu.agent.metering.model.UsageRecord;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * {@link CostCalculator} 单元测试（tasks 10.1 / design D6）。
 * <p>
 * 用真实 {@link MeteringProperties}（config 默认单价）+ mock {@link MeteringRepository}，覆盖：
 * 档位自动判定、缓存命中差异计费、{@code BigDecimal} HALF_UP 精度、单价缺失标记、DB 覆盖优先、
 * 缓存不可得降级。金额单位为微元（1 元 = 10^6 微元；数值上「元/百万 token」==「微元/token」）。
 */
class CostCalculatorTest {

    private MeteringRepository repository;
    private MeteringProperties properties;
    private CostCalculator calculator;

    @BeforeEach
    void setUp() {
        repository = mock(MeteringRepository.class);
        properties = new MeteringProperties();
        // config 默认：flash 3/10/2，standard 8/24/2（元/百万 token == 微元/token）
        calculator = new CostCalculator(repository, properties);
    }

    private static UsageRecord record(String model, long prompt, long completion, Long cached) {
        return new UsageRecord("t1", model, "chat", "s1", prompt, completion, cached);
    }

    // ==================== 档位判定 ====================

    @Test
    void 档位判定_含flash归FLASH否则STANDARD() {
        assertThat(calculator.tierOf("deepseek-v4.1-flash")).isEqualTo(ModelTier.FLASH);
        assertThat(calculator.tierOf("DeepSeek-FLASH")).isEqualTo(ModelTier.FLASH);
        assertThat(calculator.tierOf("glm-5.2")).isEqualTo(ModelTier.STANDARD);
        assertThat(calculator.tierOf(null)).isEqualTo(ModelTier.STANDARD);
    }

    // ==================== 基础计费（config 默认单价） ====================

    @Test
    void 标准档_无缓存_按输入8输出24计费() {
        CostResult r = calculator.calculate(record("glm-5.2", 1000, 500, 0L));
        // 1000*8 + 500*24 = 8000 + 12000
        assertThat(r.amountMicro()).isEqualTo(20000L);
        assertThat(r.tier()).isEqualTo(ModelTier.STANDARD);
        assertThat(r.priceMissing()).isFalse();
        assertThat(r.cacheUnavailable()).isFalse();
    }

    @Test
    void flash档_无缓存_按输入3输出10计费() {
        CostResult r = calculator.calculate(record("deepseek-v4.1-flash", 1000, 500, 0L));
        // 1000*3 + 500*10 = 3000 + 5000
        assertThat(r.amountMicro()).isEqualTo(8000L);
        assertThat(r.tier()).isEqualTo(ModelTier.FLASH);
    }

    // ==================== 缓存命中差异计费 ====================

    @Test
    void 标准档_缓存命中_命中部分按缓存价2计费() {
        CostResult r = calculator.calculate(record("glm-5.2", 1000, 0, 400L));
        // 非缓存输入 600*8 + 缓存命中 400*2 = 4800 + 800
        assertThat(r.amountMicro()).isEqualTo(5600L);
        assertThat(r.cacheUnavailable()).isFalse();
    }

    @Test
    void 缓存超过prompt_被钳制到prompt不产生负输入() {
        CostResult r = calculator.calculate(record("glm-5.2", 100, 0, 400L));
        // cached 钳制为 100，非缓存输入 0：100*2 = 200
        assertThat(r.amountMicro()).isEqualTo(200L);
    }

    // ==================== 缓存不可得降级 ====================

    @Test
    void 缓存不可得_按0缓存降级并标记cacheUnavailable() {
        CostResult r = calculator.calculate(record("glm-5.2", 1000, 0, null));
        // 全按输入价：1000*8 = 8000
        assertThat(r.amountMicro()).isEqualTo(8000L);
        assertThat(r.cacheUnavailable()).isTrue();
    }

    // ==================== HALF_UP 精度 ====================

    @Test
    void 金额_HALF_UP取整_点五进位() {
        // 自定义单价制造小数：standard 输入 0.5 微元/token
        properties.getPricing().setStandard(new TierPrice(0.5, 0, 0));
        CostResult r = calculator.calculate(record("glm-5.2", 5, 0, 0L));
        // 5*0.5 = 2.5 → HALF_UP → 3
        assertThat(r.amountMicro()).isEqualTo(3L);
        assertThat(r.priceMissing()).isFalse();
    }

    @Test
    void 负token被钳制为0不产生负金额() {
        CostResult r = calculator.calculate(record("glm-5.2", -100, -50, 0L));
        assertThat(r.amountMicro()).isEqualTo(0L);
    }

    // ==================== 单价缺失标记 ====================

    @Test
    void 单价全为0_标记priceMissing金额为0但不抛异常() {
        when(repository.findPricingOverride("standard")).thenReturn(new long[]{0L, 0L, 0L});
        CostResult r = calculator.calculate(record("glm-5.2", 1000, 500, 100L));
        assertThat(r.priceMissing()).isTrue();
        assertThat(r.amountMicro()).isEqualTo(0L);
        assertThat(r.tier()).isEqualTo(ModelTier.STANDARD);
    }

    // ==================== DB 覆盖优先 ====================

    @Test
    void DB单价覆盖优先于config() {
        when(repository.findPricingOverride("standard")).thenReturn(new long[]{1L, 2L, 3L});
        CostResult r = calculator.calculate(record("glm-5.2", 1000, 500, 200L));
        // 非缓存 800*1 + 缓存 200*3 + 输出 500*2 = 800 + 600 + 1000
        assertThat(r.amountMicro()).isEqualTo(2400L);
        assertThat(r.priceMissing()).isFalse();
    }

    @Test
    void DB覆盖为flash档时按flash键查询() {
        when(repository.findPricingOverride("flash")).thenReturn(new long[]{5L, 5L, 5L});
        CostResult r = calculator.calculate(record("x-flash", 100, 100, 0L));
        // 100*5 + 100*5 = 1000
        assertThat(r.amountMicro()).isEqualTo(1000L);
        assertThat(r.tier()).isEqualTo(ModelTier.FLASH);
    }
}
