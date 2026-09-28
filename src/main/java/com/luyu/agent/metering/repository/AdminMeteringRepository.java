package com.luyu.agent.metering.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 管理端计量仓储（tasks 8.2/8.3/8.4）：跨租户用量查询 + 预算/单价维护。
 * <p>
 * 计量表（{@code tenant_usage_daily}/{@code token_usage_log}/{@code tenant_quota_policy}/
 * {@code pricing_policy}）与 {@code tenant}/{@code app_user} 均<b>不在</b>
 * {@code TenantGuardedJdbcTemplate} 守卫名单，故可跨租户聚合查询；tenant_id 一律作为显式参数。
 * <p>
 * 列别名统一用双引号包裹以在 H2 保留 camelCase 大小写（否则未加引号的标识符会被 H2 转大写），
 * 便于 Controller 直接透传给前端。
 */
@Repository
public class AdminMeteringRepository {

    private final JdbcTemplate jdbc;

    public AdminMeteringRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 8.2 用量看板查询 ====================

    /**
     * 跨租户当期用量概览：窗口内（day >= fromDay）按租户聚合金额/用量，附租户显示名。
     * 按金额降序（成本大户置顶）。
     */
    public List<Map<String, Object>> usageOverview(LocalDate fromDay) {
        return jdbc.queryForList(
                "SELECT d.tenant_id AS \"tenantId\", t.display_name AS \"displayName\", "
                        + "COALESCE(SUM(d.prompt_tokens), 0) AS \"promptTokens\", "
                        + "COALESCE(SUM(d.completion_tokens), 0) AS \"completionTokens\", "
                        + "COALESCE(SUM(d.cached_tokens), 0) AS \"cachedTokens\", "
                        + "COALESCE(SUM(d.amount_micro), 0) AS \"amountMicro\" "
                        + "FROM tenant_usage_daily d LEFT JOIN tenant t ON t.id = d.tenant_id "
                        + "WHERE d.usage_day >= ? "
                        + "GROUP BY d.tenant_id, t.display_name "
                        + "ORDER BY SUM(d.amount_micro) DESC",
                Date.valueOf(fromDay));
    }

    /** 单租户按模型分解（窗口内，来自日桶）。 */
    public List<Map<String, Object>> breakdownByModel(String tenantId, LocalDate fromDay) {
        return jdbc.queryForList(
                "SELECT model AS \"model\", "
                        + "COALESCE(SUM(prompt_tokens), 0) AS \"promptTokens\", "
                        + "COALESCE(SUM(completion_tokens), 0) AS \"completionTokens\", "
                        + "COALESCE(SUM(cached_tokens), 0) AS \"cachedTokens\", "
                        + "COALESCE(SUM(amount_micro), 0) AS \"amountMicro\" "
                        + "FROM tenant_usage_daily WHERE tenant_id = ? AND usage_day >= ? "
                        + "GROUP BY model ORDER BY SUM(amount_micro) DESC",
                tenantId, Date.valueOf(fromDay));
    }

    /** 单租户按调用类型分解（窗口内，来自明细日志）。 */
    public List<Map<String, Object>> breakdownByCallType(String tenantId, LocalDateTime fromTime) {
        return jdbc.queryForList(
                "SELECT COALESCE(call_type, 'unknown') AS \"callType\", COUNT(*) AS \"calls\", "
                        + "COALESCE(SUM(amount_micro), 0) AS \"amountMicro\" "
                        + "FROM token_usage_log WHERE tenant_id = ? AND created_at >= ? "
                        + "GROUP BY call_type ORDER BY SUM(amount_micro) DESC",
                tenantId, Timestamp.valueOf(fromTime));
    }

    /** 单租户明细分页（按时间倒序）。 */
    public List<Map<String, Object>> detailLogs(String tenantId, int limit, int offset) {
        return jdbc.queryForList(
                "SELECT id AS \"id\", model AS \"model\", call_type AS \"callType\", "
                        + "session_id AS \"sessionId\", prompt_tokens AS \"promptTokens\", "
                        + "completion_tokens AS \"completionTokens\", cached_tokens AS \"cachedTokens\", "
                        + "amount_micro AS \"amountMicro\", cache_unavailable AS \"cacheUnavailable\", "
                        + "created_at AS \"createdAt\" "
                        + "FROM token_usage_log WHERE tenant_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                tenantId, limit, offset);
    }

    /** 单租户明细总行数（分页用）。 */
    public long countLogs(String tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM token_usage_log WHERE tenant_id = ?", Long.class, tenantId);
        return count == null ? 0L : count;
    }

    // ==================== 8.3 租户预算维护 ====================

    /** 预算覆盖 upsert（微元）：UPDATE 无行则 INSERT，撞主键再 UPDATE。 */
    public void upsertBudget(String tenantId, long budgetMicro) {
        int updated = jdbc.update(
                "UPDATE tenant_quota_policy SET budget_micro = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ?",
                budgetMicro, tenantId);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO tenant_quota_policy (tenant_id, budget_micro) VALUES (?, ?)",
                        tenantId, budgetMicro);
            } catch (DuplicateKeyException race) {
                jdbc.update(
                        "UPDATE tenant_quota_policy SET budget_micro = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ?",
                        budgetMicro, tenantId);
            }
        }
    }

    /** 清除租户预算覆盖（回退平台默认），返回删除行数。 */
    public int deleteBudget(String tenantId) {
        return jdbc.update("DELETE FROM tenant_quota_policy WHERE tenant_id = ?", tenantId);
    }

    /** 租户预算覆盖（微元）；无覆盖返回 {@code null}。 */
    public Long findBudgetOverrideMicro(String tenantId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT budget_micro FROM tenant_quota_policy WHERE tenant_id = ?", Long.class, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================== 8.4 档位单价维护 ====================

    /** 全部档位单价覆盖（微元/token）；空表表示全走 config 默认。 */
    public List<Map<String, Object>> listPricing() {
        return jdbc.queryForList(
                "SELECT tier AS \"tier\", input_micro AS \"inputMicro\", "
                        + "output_micro AS \"outputMicro\", cache_micro AS \"cacheMicro\" "
                        + "FROM pricing_policy ORDER BY tier");
    }

    /** 档位单价覆盖 upsert（微元/token）。 */
    public void upsertPricing(String tier, long inputMicro, long outputMicro, long cacheMicro) {
        int updated = jdbc.update(
                "UPDATE pricing_policy SET input_micro = ?, output_micro = ?, cache_micro = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE tier = ?",
                inputMicro, outputMicro, cacheMicro, tier);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO pricing_policy (tier, input_micro, output_micro, cache_micro) "
                        + "VALUES (?, ?, ?, ?)", tier, inputMicro, outputMicro, cacheMicro);
            } catch (DuplicateKeyException race) {
                jdbc.update(
                        "UPDATE pricing_policy SET input_micro = ?, output_micro = ?, cache_micro = ?, "
                                + "updated_at = CURRENT_TIMESTAMP WHERE tier = ?",
                        inputMicro, outputMicro, cacheMicro, tier);
            }
        }
    }
}
