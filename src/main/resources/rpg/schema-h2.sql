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

-- 游戏状态（独立于 session，可跨会话加载）
CREATE TABLE IF NOT EXISTS rpg_game_state (
    id              VARCHAR(64) PRIMARY KEY,
    session_id      VARCHAR(64),            -- 可变，支持跨会话
    world_id        VARCHAR(64) NOT NULL,
    player_char_id  VARCHAR(64) NOT NULL,
    current_location VARCHAR(200),
    turn_count      INTEGER DEFAULT 0,
    flags           CLOB,                   -- JSON: {"见过盗贼": true, ...}
    npc_states      CLOB,                   -- JSON: {"盗贼-001": {status, mood, ...}}
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
