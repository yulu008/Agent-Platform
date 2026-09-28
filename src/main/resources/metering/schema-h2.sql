-- ============================================================
-- 租户级 token 计量与配额 H2 表结构 DDL（tenant-token-metering 变更）
-- 4 张计量表，复用 jdbc:h2:file:./data/agent-platform 实例。
-- 金额一律以「微元」存储（1 元 = 10^6 微元），此单位下每 token 单价恰为整数。
-- 幂等：脚本每次启动重跑，CREATE TABLE IF NOT EXISTS 保证可重入。
-- 说明：这些表【不】纳入 TenantGuardedJdbcTemplate 守卫名单——
--       计量写入发生在 reactor/虚拟线程（TenantContext 可能缺失），
--       tenant_id 由 MeteringAdvisor 显式解析后作为参数传入，不依赖 ThreadLocal。
-- ============================================================

-- 按天聚合桶（滚动 30 天窗口求和用）：当期已用 = SUM(amount_micro) WHERE usage_day >= today-29
-- 注：列名用 usage_day 而非 day——day 是 H2 2.x 保留关键字，直接作列名会建表失败。
CREATE TABLE IF NOT EXISTS tenant_usage_daily (
    tenant_id         VARCHAR(64)  NOT NULL,
    usage_day         DATE         NOT NULL,
    model             VARCHAR(128) NOT NULL,
    prompt_tokens     BIGINT       DEFAULT 0,
    completion_tokens BIGINT       DEFAULT 0,
    cached_tokens     BIGINT       DEFAULT 0,
    amount_micro      BIGINT       DEFAULT 0,
    CONSTRAINT pk_tenant_usage_daily PRIMARY KEY (tenant_id, usage_day, model)
);

-- 单次调用明细（审计/账单用，保留 90 天后由定时任务清理）
CREATE TABLE IF NOT EXISTS token_usage_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    model             VARCHAR(128),
    call_type         VARCHAR(32),           -- chat / rpg / workshop / compaction / state_delta / title / subagent / unknown
    session_id        VARCHAR(64),
    prompt_tokens     BIGINT       DEFAULT 0,
    completion_tokens BIGINT       DEFAULT 0,
    cached_tokens     BIGINT       DEFAULT 0,
    amount_micro      BIGINT       DEFAULT 0,
    cache_unavailable BOOLEAN      DEFAULT FALSE,  -- 缓存 token 不可得而降级计费时置真
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_usage_log_tenant_time ON token_usage_log (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_log_created ON token_usage_log (created_at);

-- 租户级预算覆盖（平台默认 20 元走配置；本表仅存被覆盖的租户）
CREATE TABLE IF NOT EXISTS tenant_quota_policy (
    tenant_id         VARCHAR(64) PRIMARY KEY,
    budget_micro      BIGINT      NOT NULL,
    updated_at        TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

-- 档位单价的管理端覆盖（空表时全走 config 默认；单位：微元/token）
CREATE TABLE IF NOT EXISTS pricing_policy (
    tier              VARCHAR(16) PRIMARY KEY,   -- flash / standard
    input_micro       BIGINT      NOT NULL,       -- 每输入 token 微元
    output_micro      BIGINT      NOT NULL,       -- 每输出 token 微元
    cache_micro       BIGINT      NOT NULL,       -- 每缓存命中输入 token 微元
    updated_at        TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
