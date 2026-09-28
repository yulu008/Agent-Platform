package com.luyu.agent.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.config.MeteringSchemaInitializer;
import com.luyu.agent.metering.model.UsageRecord;
import com.luyu.agent.metering.repository.AdminMeteringRepository;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * 计量落库与「DB 覆盖即时生效」集成测试（tasks 10.4 / 10.7 / design D6、D7）。
 * <p>
 * {@code @JdbcTest} + 真实内存 H2：{@link MeteringSchemaInitializer} 幂等建 4 张计量表，
 * 用真实 {@link MeteringRepository}/{@link AdminMeteringRepository} + {@link CostCalculator}/
 * {@link QuotaGuard}/{@link TenantUsageService}（config 默认单价）验证：
 * <ul>
 *   <li>10.4 一次调用后日桶 + 明细同事务写入，且「明细金额求和 == 日桶金额」口径一致；</li>
 *   <li>10.7 管理端改单价/预算后，{@link CostCalculator}/{@link QuotaGuard} <b>下一次调用即生效</b>
 *       （每次实时读 DB 覆盖，无缓存、无需重启）。</li>
 * </ul>
 */
@JdbcTest
@Import({MeteringSchemaInitializer.class, MeteringRepository.class, AdminMeteringRepository.class})
class MeteringPersistenceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeteringRepository meteringRepo;

    @Autowired
    private AdminMeteringRepository adminRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    private MeteringProperties properties;
    private CostCalculator calculator;
    private QuotaGuard guard;
    private TenantUsageService service;

    @BeforeEach
    void setUp() {
        properties = new MeteringProperties();
        properties.setEnabled(true);
        calculator = new CostCalculator(meteringRepo, properties);
        guard = new QuotaGuard(meteringRepo, properties);
        service = new TenantUsageService(meteringRepo, calculator, properties, txManager);
    }

    private long dailySum(String tenantId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_micro), 0) FROM tenant_usage_daily WHERE tenant_id = ?",
                Long.class, tenantId);
        return v == null ? 0L : v;
    }

    private long logSum(String tenantId) {
        Long v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_micro), 0) FROM token_usage_log WHERE tenant_id = ?",
                Long.class, tenantId);
        return v == null ? 0L : v;
    }

    // ==================== 10.4 日桶 + 明细一致 ====================

    @Test
    void 一次调用后日桶与明细均写入且金额一致() {
        service.record(new UsageRecord("t-a", "glm-5.2", "chat", "s1", 1000, 500, 0L));

        // config 标准档：1000*8 + 500*24 = 20000 微元
        assertThat(dailySum("t-a")).isEqualTo(20000L);
        assertThat(logSum("t-a")).isEqualTo(20000L);
        assertThat(dailySum("t-a")).isEqualTo(logSum("t-a"));

        Integer logRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM token_usage_log WHERE tenant_id = ?", Integer.class, "t-a");
        assertThat(logRows).isEqualTo(1);
    }

    @Test
    void 多次调用日桶按模型累加明细多行总额仍一致() {
        // 同模型两次 → 日桶单行累加；不同模型 → 日桶多行
        service.record(new UsageRecord("t-b", "glm-5.2", "chat", "s1", 1000, 500, 0L));   // 20000
        service.record(new UsageRecord("t-b", "glm-5.2", "rpg", "s1", 1000, 500, 0L));     // +20000
        service.record(new UsageRecord("t-b", "deepseek-v4-flash", "chat", "s1", 1000, 500, 0L)); // 8000

        Integer dailyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant_usage_daily WHERE tenant_id = ?", Integer.class, "t-b");
        assertThat(dailyRows).isEqualTo(2); // 两个模型两行
        Integer logRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM token_usage_log WHERE tenant_id = ?", Integer.class, "t-b");
        assertThat(logRows).isEqualTo(3);   // 三次调用三行明细

        // 明细求和 == 日桶求和（口径一致）
        assertThat(dailySum("t-b")).isEqualTo(logSum("t-b")).isEqualTo(48000L);
    }

    @Test
    void 缓存不可得降级_明细标记cache_unavailable但金额仍落库() {
        service.record(new UsageRecord("t-c", "glm-5.2", "chat", "s1", 1000, 0, null));

        Boolean flag = jdbc.queryForObject(
                "SELECT cache_unavailable FROM token_usage_log WHERE tenant_id = ?", Boolean.class, "t-c");
        assertThat(flag).isTrue();
        // 全按输入价 1000*8 = 8000
        assertThat(logSum("t-c")).isEqualTo(8000L);
        assertThat(dailySum("t-c")).isEqualTo(8000L);
    }

    // ==================== 10.7 DB 覆盖即时生效 ====================

    @Test
    void 管理端改单价后CostCalculator下一次调用即生效() {
        UsageRecord r = new UsageRecord("t", "glm-5.2", "chat", null, 1000, 500, 0L);
        // 初始走 config 默认：1000*8 + 500*24 = 20000
        assertThat(calculator.calculate(r).amountMicro()).isEqualTo(20000L);

        // 管理端覆盖 standard 单价为 1/2/3（微元/token）
        adminRepo.upsertPricing("standard", 1L, 2L, 3L);
        // 同一个 calculator 实例、无重启：立即反映 1000*1 + 500*2 = 2000
        assertThat(calculator.calculate(r).amountMicro()).isEqualTo(2000L);

        // 再改一次即时反映
        adminRepo.upsertPricing("standard", 10L, 20L, 30L);
        assertThat(calculator.calculate(r).amountMicro()).isEqualTo(1000 * 10 + 500 * 20);
    }

    @Test
    void 管理端改预算后QuotaGuard下一次检查即生效() {
        properties.getQuota().setEnabled(true);
        properties.getQuota().setDefaultBudgetYuan(20.0);
        // 写入 t-q 当期已用 6 元
        meteringRepo.accumulateDaily("t-q", RollingWindow.today(), "glm-5.2", 0, 0, 0, 6_000_000L);

        // 无覆盖：默认 20 元 → 放行
        assertThatCode(() -> guard.check("t-q")).doesNotThrowAnyException();

        // 覆盖为 5 元 → 已用 6 >= 5 → 立即硬拒（无需重启）
        adminRepo.upsertBudget("t-q", 5_000_000L);
        assertThatThrownBy(() -> guard.check("t-q")).isInstanceOf(QuotaExceededException.class);

        // 提高到 50 元 → 立即放行
        adminRepo.upsertBudget("t-q", 50_000_000L);
        assertThatCode(() -> guard.check("t-q")).doesNotThrowAnyException();

        // 清除覆盖 → 回退平台默认 20 元 → 放行
        adminRepo.deleteBudget("t-q");
        assertThat(adminRepo.findBudgetOverrideMicro("t-q")).isNull();
        assertThatCode(() -> guard.check("t-q")).doesNotThrowAnyException();
    }

    @Test
    void 单价覆盖后新计量落库金额随DB而非config() {
        adminRepo.upsertPricing("standard", 1L, 1L, 1L);
        service.record(new UsageRecord("t-d", "glm-5.2", "chat", "s1", 1000, 500, 0L));
        // 1000*1 + 500*1 = 1500（而非 config 的 20000）
        assertThat(logSum("t-d")).isEqualTo(1500L);
        assertThat(dailySum("t-d")).isEqualTo(1500L);
    }
}
