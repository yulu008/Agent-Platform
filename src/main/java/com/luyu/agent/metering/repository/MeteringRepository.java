package com.luyu.agent.metering.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 计量运行时仓储（tenant-token-metering）：日桶累加、明细写入、滚动窗口求和、预算/单价读取、过期清理。
 * <p>
 * 计量表<b>不在</b> {@code TenantGuardedJdbcTemplate} 守卫名单内，故注入的 {@link JdbcTemplate}
 * （即守卫版）不会拦截；tenant_id 一律作为<b>显式参数</b>传入（计量写入发生在 reactor/虚拟线程，
 * ThreadLocal 不可靠，见 design D1 修订 / D5）。
 * <p>
 * 日桶累加用「先 UPDATE x=x+?，0 行则 INSERT，撞主键再 UPDATE」保证并发下不丢量。
 */
@Repository
public class MeteringRepository {

    private final JdbcTemplate jdbc;

    public MeteringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 写入：日桶 + 明细 ====================

    /**
     * 日桶原子累加（tenant_id, day, model 为主键）。
     * 先尝试 UPDATE 累加；无行则 INSERT；并发下 INSERT 撞主键则回退再 UPDATE 一次。
     */
    public void accumulateDaily(String tenantId, LocalDate day, String model,
                                long promptTokens, long completionTokens,
                                long cachedTokens, long amountMicro) {
        Date daySql = Date.valueOf(day);
        String safeModel = model == null ? "unknown" : model;
        int updated = jdbc.update(
                "UPDATE tenant_usage_daily SET prompt_tokens = prompt_tokens + ?, "
                        + "completion_tokens = completion_tokens + ?, cached_tokens = cached_tokens + ?, "
                        + "amount_micro = amount_micro + ? WHERE tenant_id = ? AND usage_day = ? AND model = ?",
                promptTokens, completionTokens, cachedTokens, amountMicro, tenantId, daySql, safeModel);
        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO tenant_usage_daily (tenant_id, usage_day, model, prompt_tokens, "
                                + "completion_tokens, cached_tokens, amount_micro) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        tenantId, daySql, safeModel, promptTokens, completionTokens, cachedTokens, amountMicro);
            } catch (DuplicateKeyException race) {
                jdbc.update(
                        "UPDATE tenant_usage_daily SET prompt_tokens = prompt_tokens + ?, "
                                + "completion_tokens = completion_tokens + ?, cached_tokens = cached_tokens + ?, "
                                + "amount_micro = amount_micro + ? WHERE tenant_id = ? AND usage_day = ? AND model = ?",
                        promptTokens, completionTokens, cachedTokens, amountMicro, tenantId, daySql, safeModel);
            }
        }
    }

    /** 写入单次调用明细。 */
    public void insertLog(String tenantId, String model, String callType, String sessionId,
                          long promptTokens, long completionTokens, long cachedTokens,
                          long amountMicro, boolean cacheUnavailable) {
        jdbc.update(
                "INSERT INTO token_usage_log (tenant_id, model, call_type, session_id, prompt_tokens, "
                        + "completion_tokens, cached_tokens, amount_micro, cache_unavailable) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenantId, model, callType, sessionId, promptTokens, completionTokens,
                cachedTokens, amountMicro, cacheUnavailable);
    }

    // ==================== 读取：滚动窗口用量 / 预算 / 单价 ====================

    /** 滚动窗口内（day >= fromDay）该租户累计消费金额（微元）。 */
    public long sumAmountMicroSince(String tenantId, LocalDate fromDay) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_micro), 0) FROM tenant_usage_daily WHERE tenant_id = ? AND usage_day >= ?",
                Long.class, tenantId, Date.valueOf(fromDay));
        return sum == null ? 0L : sum;
    }

    /** 租户预算覆盖（微元）；无覆盖返回 {@code null}（由调用方回退平台默认）。 */
    public Long findBudgetOverrideMicro(String tenantId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT budget_micro FROM tenant_quota_policy WHERE tenant_id = ?", Long.class, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 档位单价覆盖（微元/token）：返回 {@code [input, output, cache]}；无覆盖返回 {@code null}。
     *
     * @param tierKey flash / standard
     */
    public long[] findPricingOverride(String tierKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT input_micro, output_micro, cache_micro FROM pricing_policy WHERE tier = ?", tierKey);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        return new long[]{
                toLong(r.get("INPUT_MICRO")),
                toLong(r.get("OUTPUT_MICRO")),
                toLong(r.get("CACHE_MICRO"))
        };
    }

    // ==================== 清理：明细 90 天 ====================

    /** 删除 created_at 早于 cutoff 的明细行，返回删除行数。 */
    public int deleteLogsBefore(LocalDateTime cutoff) {
        return jdbc.update("DELETE FROM token_usage_log WHERE created_at < ?",
                java.sql.Timestamp.valueOf(cutoff));
    }

    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
