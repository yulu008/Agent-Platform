-- ============================================================
-- 沙盒RPG H2 表结构 DDL
-- 8 张 RPG 专用表，复用 jdbc:h2:file:./data/agent-platform 实例
-- ============================================================

-- 世界观设定
CREATE TABLE IF NOT EXISTS rpg_world_setting (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    setting_desc    TEXT,
    era             VARCHAR(100),
    rules           CLOB,          -- JSON 数组: ["魔法退化", "蒸汽动力", ...]
    locations       CLOB,          -- JSON 数组: 地点 ID 列表
    atmosphere      TEXT,
    opening_template TEXT,          -- 开场白模板（含 {{}} 占位符）
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 地点
CREATE TABLE IF NOT EXISTS rpg_location (
    id              VARCHAR(64) PRIMARY KEY,
    world_id        VARCHAR(64) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    npc_ids         CLOB,          -- JSON 数组: NPC ID 列表
    trigger_ids     CLOB,          -- JSON 数组: 触发器 ID 列表
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_location_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id)
);

-- 角色卡（player / npc）
CREATE TABLE IF NOT EXISTS rpg_character_card (
    id              VARCHAR(64) PRIMARY KEY,
    world_id        VARCHAR(64) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    type            VARCHAR(20) NOT NULL,    -- player / npc
    identity        VARCHAR(200),
    personality     TEXT,
    background      TEXT,
    motivation      TEXT,
    speech_style    TEXT,
    knowledge       CLOB,          -- JSON 数组: 知识领域列表
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_char_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id)
);

-- 角色间关系
CREATE TABLE IF NOT EXISTS rpg_relationship (
    id              VARCHAR(64) PRIMARY KEY,
    char_a_id       VARCHAR(64) NOT NULL,
    char_b_id       VARCHAR(64) NOT NULL,
    attitude        INTEGER DEFAULT 0,       -- -100 ~ 100
    trust           INTEGER DEFAULT 0,       -- 0 ~ 100
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_relationship UNIQUE (char_a_id, char_b_id),
    CONSTRAINT fk_rel_char_a FOREIGN KEY (char_a_id) REFERENCES rpg_character_card(id),
    CONSTRAINT fk_rel_char_b FOREIGN KEY (char_b_id) REFERENCES rpg_character_card(id)
);

-- 触发器定义
CREATE TABLE IF NOT EXISTS rpg_trigger (
    id              VARCHAR(64) PRIMARY KEY,
    world_id        VARCHAR(64) NOT NULL,
    type            VARCHAR(20) NOT NULL,    -- event / motivation
    npc_id          VARCHAR(64),             -- 关联 NPC
    hard_conditions CLOB,                    -- JSON: 硬条件列表
    soft_condition  TEXT,                    -- 软条件自然语言描述
    action          VARCHAR(100),            -- greet / rob / ...
    cooldown        INTEGER DEFAULT 0,       -- 冷却轮次
    probability     DOUBLE DEFAULT 1.0,      -- 触发概率
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trigger_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id),
    CONSTRAINT fk_trigger_npc FOREIGN KEY (npc_id) REFERENCES rpg_character_card(id)
);

-- 游戏状态（每回合自动落库，历史剧情按 session_id 存于 AI_SESSION_EVENT，可跨会话重放）
CREATE TABLE IF NOT EXISTS rpg_game_state (
    id              VARCHAR(64) PRIMARY KEY,
    session_id      VARCHAR(64),            -- 运行时与存档 1:1 绑定；换绑不迁移旧 session 的历史事件
    world_id        VARCHAR(64) NOT NULL,
    player_char_id  VARCHAR(64) NOT NULL,
    current_location VARCHAR(200),
    turn_count      INTEGER DEFAULT 0,
    flags           CLOB,                   -- JSON: {"见过盗贼": true, ...}
    npc_states      CLOB,                   -- JSON: {"盗贼-001": {status, mood, ...}}
    player_states   CLOB,                   -- JSON: {"money": 70, "abilities": {...}, "titles": [...], ...}（PC 结构化状态）
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_game_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id),
    CONSTRAINT fk_game_player FOREIGN KEY (player_char_id) REFERENCES rpg_character_card(id)
);

-- 触发器运行时状态（每轮扫描用）
CREATE TABLE IF NOT EXISTS rpg_trigger_runtime (
    id                      VARCHAR(64) PRIMARY KEY,
    game_state_id           VARCHAR(64) NOT NULL,
    trigger_id              VARCHAR(64) NOT NULL,
    last_triggered_turn     INTEGER DEFAULT 0,
    is_active               BOOLEAN DEFAULT TRUE,
    CONSTRAINT fk_rt_game FOREIGN KEY (game_state_id) REFERENCES rpg_game_state(id),
    CONSTRAINT fk_rt_trigger FOREIGN KEY (trigger_id) REFERENCES rpg_trigger(id)
);

-- 事件日志（审计 + 软条件评估用）
CREATE TABLE IF NOT EXISTS rpg_event_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    game_state_id   VARCHAR(64) NOT NULL,
    turn            INTEGER NOT NULL,
    event_type      VARCHAR(50),           -- player_action / npc_action / state_change / trigger_fired
    content         TEXT,
    state_delta     CLOB,                  -- JSON: 该轮的状态变更快照
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_log_game FOREIGN KEY (game_state_id) REFERENCES rpg_game_state(id)
);

-- 游戏状态快照（回溯用：每轮 prepareTurn 开始时存 turn=N-1 的状态副本）
CREATE TABLE IF NOT EXISTS rpg_game_state_snapshot (
    game_state_id     VARCHAR(64) NOT NULL,
    turn              INTEGER NOT NULL,
    current_location  VARCHAR(200),
    flags             CLOB,                   -- JSON: 与 rpg_game_state.flags 同构
    npc_states        CLOB,                   -- JSON: 与 rpg_game_state.npc_states 同构
    player_states     CLOB,                   -- JSON: 与 rpg_game_state.player_states 同构
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_snapshot PRIMARY KEY (game_state_id, turn),
    CONSTRAINT fk_snap_game FOREIGN KEY (game_state_id) REFERENCES rpg_game_state(id)
);

-- ============================================================
-- 存量库幂等迁移（rpg-player-memory 变更）
-- CREATE TABLE IF NOT EXISTS 不会为既有表补列，必须显式 ALTER；
-- 脚本每次启动重跑，ADD COLUMN IF NOT EXISTS 保证幂等。
-- ============================================================
ALTER TABLE rpg_game_state ADD COLUMN IF NOT EXISTS player_states CLOB;
ALTER TABLE rpg_game_state_snapshot ADD COLUMN IF NOT EXISTS player_states CLOB;

-- ============================================================
-- 多租户列隔离：租户列补齐与存量回填（multi-tenant-column-isolation 变更）
-- 9 张 RPG 表全部加 tenant_id 列；幂等：脚本每次启动重跑。
-- 存量行回填 'default' 租户，升级后原使用者登录 default 账号行为不变。
-- tenant_guard_whitelist: DDL/迁移段，静态扫描与运行时守卫均豁免
-- ============================================================
ALTER TABLE rpg_world_setting      ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_location           ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_character_card     ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_relationship       ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_trigger            ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_game_state         ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_trigger_runtime    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_event_log          ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_game_state_snapshot ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

UPDATE rpg_world_setting       SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_location            SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_character_card      SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_relationship        SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_trigger             SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_game_state          SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_trigger_runtime     SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_event_log           SET tenant_id = 'default' WHERE tenant_id IS NULL;
UPDATE rpg_game_state_snapshot SET tenant_id = 'default' WHERE tenant_id IS NULL;

-- ============================================================
-- 多租户控制表（multi-tenant-column-isolation 变更）
-- tenant=个人用户 1:1；app_user 密码 bcrypt 哈希。
-- api_key_encrypted 为计费隔离预留列（第一版全局共享 key，不写入）。
-- ============================================================
CREATE TABLE IF NOT EXISTS tenant (
    id                  VARCHAR(64) PRIMARY KEY,
    display_name        VARCHAR(200),
    status              VARCHAR(20) DEFAULT 'ACTIVE',   -- PENDING / ACTIVE / SUSPENDED
    api_key_encrypted   VARCHAR(512),                  -- 预留：每租户模型 key（将来计费隔离）
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS app_user (
    id                  VARCHAR(64) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    email               VARCHAR(200) NOT NULL,
    password_hash       VARCHAR(100) NOT NULL,         -- bcrypt（60 字符）
    user_role           VARCHAR(20) DEFAULT 'admin',   -- 租户管理员（租户=个人用户）；列名避开 H2 关键字 role
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);
