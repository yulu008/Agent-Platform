-- 测试数据源建表脚本（@JdbcTest 的嵌入式 H2 自动执行）
-- 内容与 src/main/resources/rpg/schema-h2.sql 的多租户控制表段保持同步
CREATE TABLE IF NOT EXISTS tenant (
    id                  VARCHAR(64) PRIMARY KEY,
    display_name        VARCHAR(200),
    status              VARCHAR(20) DEFAULT 'ACTIVE',
    api_key_encrypted   VARCHAR(512),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS app_user (
    id                  VARCHAR(64) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    email               VARCHAR(200) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,
    user_role           VARCHAR(20) DEFAULT 'admin',
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

-- ============================================================
-- 租户级 token 计量与配额表（与 src/main/resources/metering/schema-h2.sql 同步）
-- @JdbcTest 下 MeteringSchemaInitializer 的编程式 DDL 不可靠，故在此声明式建表。
-- 金额一律以「微元」存储（1 元 = 10^6 微元）。
-- ============================================================
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

CREATE TABLE IF NOT EXISTS token_usage_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    model             VARCHAR(128),
    call_type         VARCHAR(32),
    session_id        VARCHAR(64),
    prompt_tokens     BIGINT       DEFAULT 0,
    completion_tokens BIGINT       DEFAULT 0,
    cached_tokens     BIGINT       DEFAULT 0,
    amount_micro      BIGINT       DEFAULT 0,
    cache_unavailable BOOLEAN      DEFAULT FALSE,
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_usage_log_tenant_time ON token_usage_log (tenant_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_log_created ON token_usage_log (created_at);

CREATE TABLE IF NOT EXISTS tenant_quota_policy (
    tenant_id         VARCHAR(64) PRIMARY KEY,
    budget_micro      BIGINT      NOT NULL,
    updated_at        TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS pricing_policy (
    tier              VARCHAR(16) PRIMARY KEY,
    input_micro       BIGINT      NOT NULL,
    output_micro      BIGINT      NOT NULL,
    cache_micro       BIGINT      NOT NULL,
    updated_at        TIMESTAMP   DEFAULT CURRENT_TIMESTAMP
);
