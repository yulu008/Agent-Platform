package com.luyu.agent.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.luyu.agent.metering.RollingWindow;
import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.config.MeteringProperties.TierPrice;
import com.luyu.agent.metering.model.ModelTier;
import com.luyu.agent.metering.repository.AdminMeteringRepository;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * 管理端计量 REST API（tasks 8.2/8.3/8.4 / design D9）。
 * <p>
 * 全部端点挂在 {@code /api/admin/**}，由 {@link AdminAccessInterceptor} 保证仅 {@code platform_admin} 可达。
 * 金额一律以「元」对外呈现（内部微元整数经 {@code movePointLeft(6)} 换算），单价以「元/百万 token」呈现
 * （数值上等于「微元/token」）。预算/单价改动即时落库，{@code QuotaGuard}/{@code CostCalculator}
 * 每次实时读 DB 覆盖，无需重启即生效（tasks 8.6）。
 */
@RestController
@RequestMapping("/api/admin/metering")
public class AdminMeteringController {

    private static final long MICRO_PER_YUAN = 1_000_000L;

    private final AdminMeteringRepository adminRepo;
    private final MeteringRepository meteringRepo;
    private final MeteringProperties properties;

    public AdminMeteringController(AdminMeteringRepository adminRepo,
                                   MeteringRepository meteringRepo,
                                   MeteringProperties properties) {
        this.adminRepo = adminRepo;
        this.meteringRepo = meteringRepo;
        this.properties = properties;
    }

    // ==================== 8.2 用量看板 ====================

    /** 跨租户当期用量概览（默认滚动窗口取自配额配置）。 */
    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(required = false) Integer windowDays) {
        int days = resolveWindowDays(windowDays);
        LocalDate fromDay = RollingWindow.fromDay(days);
        List<Map<String, Object>> tenants = new ArrayList<>();
        for (Map<String, Object> row : adminRepo.usageOverview(fromDay)) {
            tenants.add(enrichAmount(row));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("windowDays", days);
        resp.put("fromDay", fromDay.toString());
        resp.put("defaultBudgetYuan", properties.getQuota().getDefaultBudgetYuan());
        resp.put("quotaEnabled", properties.getQuota().isEnabled());
        resp.put("tenants", tenants);
        return resp;
    }

    /** 单租户分解：按模型 + 按调用类型 + 当期已用/预算。 */
    @GetMapping("/usage/{tenantId}")
    public Map<String, Object> usageDetail(@PathVariable String tenantId,
                                           @RequestParam(required = false) Integer windowDays) {
        int days = resolveWindowDays(windowDays);
        LocalDate fromDay = RollingWindow.fromDay(days);

        List<Map<String, Object>> byModel = new ArrayList<>();
        for (Map<String, Object> row : adminRepo.breakdownByModel(tenantId, fromDay)) {
            byModel.add(enrichAmount(row));
        }
        List<Map<String, Object>> byCallType = new ArrayList<>();
        for (Map<String, Object> row : adminRepo.breakdownByCallType(tenantId, fromDay.atStartOfDay())) {
            byCallType.add(enrichAmount(row));
        }

        long usedMicro = meteringRepo.sumAmountMicroSince(tenantId, fromDay);
        Long overrideMicro = adminRepo.findBudgetOverrideMicro(tenantId);
        long budgetMicro = overrideMicro != null
                ? overrideMicro
                : Math.round(properties.getQuota().getDefaultBudgetYuan() * MICRO_PER_YUAN);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tenantId", tenantId);
        resp.put("windowDays", days);
        resp.put("fromDay", fromDay.toString());
        resp.put("usedYuan", microToYuan(usedMicro));
        resp.put("budgetYuan", microToYuan(budgetMicro));
        resp.put("budgetOverrideYuan", overrideMicro == null ? null : microToYuan(overrideMicro));
        resp.put("byModel", byModel);
        resp.put("byCallType", byCallType);
        return resp;
    }

    /** 单租户明细分页。 */
    @GetMapping("/usage/{tenantId}/logs")
    public Map<String, Object> logs(@PathVariable String tenantId,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        long total = adminRepo.countLogs(tenantId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : adminRepo.detailLogs(tenantId, safeSize, safePage * safeSize)) {
            items.add(enrichAmount(row));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tenantId", tenantId);
        resp.put("page", safePage);
        resp.put("size", safeSize);
        resp.put("total", total);
        resp.put("items", items);
        return resp;
    }

    // ==================== 8.3 租户预算维护 ====================

    /** 读取租户预算（覆盖优先，含当期已用）。 */
    @GetMapping("/budget/{tenantId}")
    public Map<String, Object> getBudget(@PathVariable String tenantId) {
        Long overrideMicro = adminRepo.findBudgetOverrideMicro(tenantId);
        long usedMicro = meteringRepo.sumAmountMicroSince(
                tenantId, RollingWindow.fromDay(properties.getQuota().getWindowDays()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tenantId", tenantId);
        resp.put("defaultBudgetYuan", properties.getQuota().getDefaultBudgetYuan());
        resp.put("overrideBudgetYuan", overrideMicro == null ? null : microToYuan(overrideMicro));
        resp.put("effectiveBudgetYuan", microToYuan(overrideMicro != null
                ? overrideMicro
                : Math.round(properties.getQuota().getDefaultBudgetYuan() * MICRO_PER_YUAN)));
        resp.put("usedYuan", microToYuan(usedMicro));
        return resp;
    }

    /** 设置租户预算覆盖（元）。 */
    @PutMapping("/budget/{tenantId}")
    public ResponseEntity<Map<String, Object>> setBudget(@PathVariable String tenantId,
                                                         @RequestBody Map<String, Object> body) {
        Double budgetYuan = toDouble(body.get("budgetYuan"));
        if (budgetYuan == null || budgetYuan < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "budgetYuan 非法（须为非负数）"));
        }
        long budgetMicro = Math.round(budgetYuan * MICRO_PER_YUAN);
        adminRepo.upsertBudget(tenantId, budgetMicro);
        return ResponseEntity.ok(Map.of("tenantId", tenantId, "budgetYuan", microToYuan(budgetMicro)));
    }

    /** 清除租户预算覆盖（回退平台默认）。 */
    @DeleteMapping("/budget/{tenantId}")
    public ResponseEntity<Map<String, Object>> clearBudget(@PathVariable String tenantId) {
        adminRepo.deleteBudget(tenantId);
        return ResponseEntity.ok(Map.of(
                "tenantId", tenantId,
                "defaultBudgetYuan", properties.getQuota().getDefaultBudgetYuan()));
    }

    // ==================== 8.4 档位单价维护 ====================

    /** 读取档位单价（DB 覆盖优先，标注来源）。单位：元/百万 token。 */
    @GetMapping("/pricing")
    public Map<String, Object> getPricing() {
        Map<String, long[]> overrides = new LinkedHashMap<>();
        for (Map<String, Object> row : adminRepo.listPricing()) {
            String tier = String.valueOf(row.get("tier"));
            overrides.put(tier, new long[]{
                    num(row.get("inputMicro")), num(row.get("outputMicro")), num(row.get("cacheMicro"))
            });
        }
        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(pricingRow(ModelTier.FLASH, overrides.get(ModelTier.FLASH.key()), properties.getPricing().getFlash()));
        tiers.add(pricingRow(ModelTier.STANDARD, overrides.get(ModelTier.STANDARD.key()), properties.getPricing().getStandard()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("unit", "元/百万 token（数值等于 微元/token）");
        resp.put("tiers", tiers);
        return resp;
    }

    /** 设置档位单价覆盖（元/百万 token；内部按整数微元/token 存储）。 */
    @PutMapping("/pricing/{tier}")
    public ResponseEntity<Map<String, Object>> setPricing(@PathVariable String tier,
                                                          @RequestBody Map<String, Object> body) {
        ModelTier modelTier = ModelTier.fromKey(tier);
        if (!modelTier.key().equalsIgnoreCase(tier)) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知档位: " + tier));
        }
        Double input = toDouble(body.get("inputYuanPerMillion"));
        Double output = toDouble(body.get("outputYuanPerMillion"));
        Double cache = toDouble(body.get("cacheYuanPerMillion"));
        if (input == null || output == null || cache == null
                || input < 0 || output < 0 || cache < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "单价非法（须为非负数）"));
        }
        long inputMicro = Math.round(input);
        long outputMicro = Math.round(output);
        long cacheMicro = Math.round(cache);
        adminRepo.upsertPricing(modelTier.key(), inputMicro, outputMicro, cacheMicro);
        return ResponseEntity.ok(Map.of(
                "tier", modelTier.key(),
                "inputYuanPerMillion", inputMicro,
                "outputYuanPerMillion", outputMicro,
                "cacheYuanPerMillion", cacheMicro));
    }

    // ==================== 辅助 ====================

    private Map<String, Object> pricingRow(ModelTier tier, long[] override, TierPrice configDefault) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tier", tier.key());
        if (override != null) {
            row.put("input", override[0]);
            row.put("output", override[1]);
            row.put("cache", override[2]);
            row.put("source", "db");
        } else {
            row.put("input", configDefault.getInputYuanPerMillion());
            row.put("output", configDefault.getOutputYuanPerMillion());
            row.put("cache", configDefault.getCacheYuanPerMillion());
            row.put("source", "config");
        }
        return row;
    }

    private int resolveWindowDays(Integer windowDays) {
        return windowDays != null && windowDays > 0 ? windowDays : properties.getQuota().getWindowDays();
    }

    /** 复制原行并追加 amountYuan（由 amountMicro 换算）。 */
    private static Map<String, Object> enrichAmount(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        Object micro = row.get("amountMicro");
        copy.put("amountYuan", microToYuan(micro == null ? 0L : ((Number) micro).longValue()));
        return copy;
    }

    /** 微元整数 → 元（保留 4 位小数，HALF_UP）。 */
    private static BigDecimal microToYuan(long micro) {
        return BigDecimal.valueOf(micro).movePointLeft(6).setScale(4, RoundingMode.HALF_UP);
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static Double toDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
