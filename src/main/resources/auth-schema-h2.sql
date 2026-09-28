-- ============================================================
-- 用户认证表 DDL
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(64) PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
