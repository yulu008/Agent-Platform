-- ============================================================
-- 沙盒RPG H2 表结构 DDL
-- 11 张 RPG 专用表，复用 jdbc:h2:file:./data/agent-platform 实例
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
-- 冥星武将录世界书（add-rpg-star-lorebook 变更）
-- 世界级静态名录：md 真源物化时按 world_id 全量重写；
-- 存档级激活/抢星状态：对齐 rpg_trigger_runtime 惯例（每存档独立）。
-- 新表直接带 tenant_id 列（多租户列隔离惯例，无存量迁移负担）。
-- ============================================================
CREATE TABLE IF NOT EXISTS rpg_star_incarnation (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64),
    world_id        VARCHAR(64) NOT NULL,
    star_name       VARCHAR(50) NOT NULL,     -- 冥星名（如天魁星）
    warlord_name    VARCHAR(100) NOT NULL,    -- 命定武将（如织田信长）
    lore            TEXT,                     -- 一句命定叙事
    aliases         VARCHAR(500),             -- 逗号分隔别名（关键词触发集）
    rollable        BOOLEAN DEFAULT TRUE,     -- false=NPC专属星：不入可推演池、永不可被抢
    sort_order      INTEGER DEFAULT 0,        -- md 内出现顺序
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_incarnation_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id)
);

CREATE TABLE IF NOT EXISTS rpg_star_incarnation_runtime (
    id                  VARCHAR(64) PRIMARY KEY,
    tenant_id           VARCHAR(64),
    game_state_id       VARCHAR(64) NOT NULL,
    incarnation_id      VARCHAR(64),             -- 物化重写会换 id，仅作参考
    star_name           VARCHAR(50) NOT NULL,    -- 冗余稳定键：抗物化全量重写断链
    activated_turn      INTEGER DEFAULT 0,       -- 0=开局抢星起点；>0=关键词命中轮次
    claimed_by_player   BOOLEAN DEFAULT FALSE,   -- 抢星：此局该武将不再持此冥衣
    CONSTRAINT fk_sir_game FOREIGN KEY (game_state_id) REFERENCES rpg_game_state(id)
);

-- ============================================================
-- 命定人物表（preset-destined-figures 变更）
-- 世界级静态档案：md 真源（## 命定人物）物化时按 world_id 全量重写；
-- figure_key 为跨物化稳定键；星命三字段（faction/constellation/star_rank）
-- 为 NULL = 自由推演模式（星命由玩家生辰推演产出）。
-- 新表直接带 tenant_id 列（多租户列隔离惯例，无存量迁移负担）。
-- ============================================================
CREATE TABLE IF NOT EXISTS rpg_destined_figure (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64),
    world_id        VARCHAR(64) NOT NULL,
    figure_key      VARCHAR(32) NOT NULL,     -- 稳定键：kagetora/harunobu/nobunaga/nobukatsu
    display_name    VARCHAR(100) NOT NULL,    -- 人物名（如长尾景虎）
    faction         VARCHAR(32),              -- NULL=自由推演
    constellation   VARCHAR(50),              -- NULL=自由推演
    star_rank       VARCHAR(32),              -- NULL=自由推演
    era_note        VARCHAR(500),             -- 1556 年史实处境一句话
    identity        VARCHAR(500),             -- 三件套底稿：身份
    personality     VARCHAR(1000),            -- 三件套底稿：性格
    background      TEXT,                     -- 三件套底稿：背景
    aliases         VARCHAR(500),             -- 逗号分隔别名
    sort_order      INTEGER DEFAULT 0,        -- md 内出现顺序
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_figure_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id)
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

-- 世界叙事规则列（add-planner-pipeline 变更，tasks 5.1）：S4 剧情结构稿
-- 「## 叙事规则」小节安装物化落位；NULL = 无世界定制（仅全局 GM 叙事规范）。
-- 同段幂等重跑（CREATE TABLE IF NOT EXISTS 不补列，必须显式 ALTER）。
ALTER TABLE rpg_world_setting      ADD COLUMN IF NOT EXISTS narrative_rules TEXT;

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

-- ============================================================
-- 世界封面列（world-card-gallery 变更）
-- cover 存封面文件名（data/covers/ 持久区，{uuid}.{ext}）；NULL = 无封面，
-- 前端卡片回落 CSS 渐变 + 世界名首字。幂等：脚本每次启动重跑。
-- ============================================================
ALTER TABLE rpg_world_setting ADD COLUMN IF NOT EXISTS cover VARCHAR(255);

-- ============================================================
-- 通用世界书（add-rpg-worldbook 变更）
-- 世界级可编辑条目：工坊 CRUD 维护，关键词触发瞬时注入 GM 上下文。
-- 瞬时注入无跨轮状态，故无 runtime 表（区别于冥星武将录 rpg_star_incarnation 双表）。
-- 新表直接带 tenant_id 列（多租户列隔离惯例，无存量迁移负担）。
-- ============================================================
CREATE TABLE IF NOT EXISTS rpg_worldbook_entry (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64),
    world_id    VARCHAR(64) NOT NULL,
    title       VARCHAR(200) NOT NULL,     -- 条目标题/主名词（展示名，同时作为关键词参与匹配）
    keywords    VARCHAR(500),              -- 逗号分隔关键词触发集（子串匹配）
    content     TEXT,                      -- 命中时注入 GM 的设定正文
    enabled     BOOLEAN DEFAULT TRUE,      -- 软开关：false 时扫描跳过（保留数据不删）
    sort_order  INTEGER DEFAULT 0,         -- 展示/注入排序
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_worldbook_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id)
);

-- 幂等保险：CREATE 已含 tenant_id，此行对齐既有 ALTER 段惯例（脚本重跑不报错）
ALTER TABLE rpg_worldbook_entry ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- ============================================================
-- 世界书副本层（add-rpg-lorebook-sets 变更）
-- rpg_lorebook_set：副本元数据（开局复制/继承 fork/孤儿化保留）；
-- game_state_id 可空：NULL = 孤儿 set（存档已删但世界书留下，仍可作继承来源）。
-- 两条目表加可空 set_id / source_ref：set_id NULL = 世界模板行，非空 = set 行；
-- source_ref：set 行指向模板出处（武将录轨 = star_name，通用轨 = 模板行 id），
-- 原生新增行为 NULL；复制/fork 保留以支撑导入候选跨继承链去重。
-- ============================================================
CREATE TABLE IF NOT EXISTS rpg_lorebook_set (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64),
    world_id        VARCHAR(64) NOT NULL,
    name            VARCHAR(200),             -- 展示名（世界默认 / 继承自「X」）
    origin          VARCHAR(500),             -- 出处描述（弹窗抬头出处行）
    game_state_id   VARCHAR(64),              -- 引用存档；NULL = 孤儿
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_lbs_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id)
);

ALTER TABLE rpg_lorebook_set ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_star_incarnation ADD COLUMN IF NOT EXISTS set_id VARCHAR(64);
ALTER TABLE rpg_star_incarnation ADD COLUMN IF NOT EXISTS source_ref VARCHAR(200);
ALTER TABLE rpg_worldbook_entry ADD COLUMN IF NOT EXISTS set_id VARCHAR(64);
ALTER TABLE rpg_worldbook_entry ADD COLUMN IF NOT EXISTS source_ref VARCHAR(64);

-- ============================================================
-- 共享世界书发布库（add-shared-worldbook-publish 变更）
-- 跨租户共享内容：读全平台（画廊无租户过滤）/ 写按发布者（读写不对称，design D2）。
-- shared_ 前缀刻意区别于 rpg_* 租户隔离域：TenantGuardedJdbcTemplate 的
-- GUARDED_TABLES 以“SQL 含 rpg_* 表名则必须含 tenant_id 字样”做字符串守卫，
-- 画廊读天然无租户过滤——三张 shared_ 表名均不含任何 GUARDED_TABLES 表名
-- 的子串（无 rpg_ 字样，GUARDED_TABLES 零改动、守卫零拦截，design D3）。
-- publisher_tenant_id 是发布者归属与撤回/覆盖权限锚点，非隔离过滤列。
-- 幂等：脚本每次启动重跑。
-- ============================================================
CREATE TABLE IF NOT EXISTS shared_lorebook_pack (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,     -- 包名（发布弹窗填写）
    blurb               VARCHAR(500),              -- 简介
    publisher_tenant_id VARCHAR(64) NOT NULL,      -- 发布者租户（发布/覆盖/撤回权限锚点）
    publisher_user_id   VARCHAR(64),               -- 发布者用户（署名）
    origin_world_id     VARCHAR(64),               -- 发布方世界 id（同租户同世界重复发布→覆盖定位键；无 FK，防跨租户删世界阻塞共享库写）
    origin_world_name   VARCHAR(200),              -- 出处世界名（仅展示，跨租户不引用 world_id）
    entry_count         INTEGER DEFAULT 0,         -- 条目总数（覆盖时重算）
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_at          TIMESTAMP                  -- 软下架标记：NULL = 在架
);

CREATE TABLE IF NOT EXISTS shared_worldbook_entry (
    id          VARCHAR(64) PRIMARY KEY,           -- 快照行 id（发布时生成）
    pack_id     VARCHAR(64) NOT NULL,
    title       VARCHAR(200) NOT NULL,             -- 快照自发布方模板行
    keywords    VARCHAR(500),
    content     TEXT,
    enabled     BOOLEAN DEFAULT TRUE,
    sort_order  INTEGER DEFAULT 0,
    CONSTRAINT fk_swe_pack FOREIGN KEY (pack_id) REFERENCES shared_lorebook_pack(id)
);

CREATE TABLE IF NOT EXISTS shared_star_entry (
    id            VARCHAR(64) PRIMARY KEY,         -- 快照行 id（发布时生成）
    pack_id       VARCHAR(64) NOT NULL,
    star_name     VARCHAR(50) NOT NULL,             -- 揭示/抢星 runtime 稳定关联键，导入时保留
    warlord_name  VARCHAR(100) NOT NULL,
    lore          TEXT,
    aliases       VARCHAR(500),
    rollable      BOOLEAN DEFAULT TRUE,             -- false = NPC 专属星
    sort_order    INTEGER DEFAULT 0,
    CONSTRAINT fk_sse_pack FOREIGN KEY (pack_id) REFERENCES shared_lorebook_pack(id)
);

-- ============================================================
-- 共享世界发布库（works-view-and-world-publish 变更）
-- 世界卡全家桶快照：读全平台（橱窗无租户过滤）/ 写按发布者（读写不对称，
-- design D3/D4 与 shared_lorebook_pack 同约）。snapshot_json 单列存全家桶
-- 文档（world/characters/locations/relationships/triggers/worldbookEntries/
-- starIncarnations），snapshot_version 预留 schema 演进；包级列（name/blurb/
-- cover/entry_count）供橱窗渲染免解析整包。cover 存共享区文件名
-- （data/covers/shared/），发布时复制而非引用发布方私有文件（design D6）。
-- shared_ 前缀刻意区别于 rpg_* 租户隔离域（守卫零拦截，同 D3 惯例）。
-- 幂等：脚本每次启动重跑。
-- ============================================================
CREATE TABLE IF NOT EXISTS shared_world (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,     -- 包名（快照自源世界名）
    blurb               VARCHAR(500),              -- 简介（快照自 setting_desc 压平）
    cover               VARCHAR(255),              -- 共享区封面文件名（data/covers/shared/）；NULL = 无封面
    publisher_tenant_id VARCHAR(64) NOT NULL,      -- 发布者租户（发布/覆盖/撤回权限锚点）
    publisher_user_id   VARCHAR(64),               -- 发布者用户（署名）
    origin_world_id     VARCHAR(64),               -- 发布方世界 id（同发布者同世界重复发布→覆盖定位键；无 FK，防跨租户删世界阻塞）
    origin_world_name   VARCHAR(200),              -- 出处世界名（仅展示，跨租户不引用 world_id）
    snapshot_json       CLOB,                      -- 全家桶快照文档 v1
    snapshot_version    INTEGER DEFAULT 1,         -- 快照 schema 版本（导入端按版本分支反序列化）
    entry_count         INTEGER DEFAULT 0,         -- 子数据总数（覆盖时重算）
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_at          TIMESTAMP                  -- 软下架标记：NULL = 在架
);

-- ============================================================
-- 工坊软删除基线（workshop-soft-delete-cascade 变更）
-- 7 张工坊实体/模板层表加 deleted_at：NULL = 未删。
-- 软删除只写工坊实体行与模板层行（set_id IS NULL）；
-- set 层（游戏内条目删除，硬删）与共享包（revoked_at）维持既有行为。
-- 通用世界书模板行从世界级联中豁免，不参与级联软删。
-- 幂等：脚本每次启动重跑。
-- ============================================================
ALTER TABLE rpg_world_setting     ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE rpg_character_card    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE rpg_location          ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE rpg_relationship      ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE rpg_trigger           ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE rpg_star_incarnation  ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE rpg_worldbook_entry   ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;

-- ============================================================
-- GM 气泡富组件（rpg-hud-bubble-components 变更）
-- rpg_event_log.session_event_id：state_change 事件与本局 assistant
-- 消息的 session eventId 精确对齐键（历史回填组件数据源；NULL = 旧行无对齐）。
-- rpg_world_setting.theme_tokens：作者主题 token JSON（CSS 变量白名单 +
-- hudStyle 枚举；NULL = 组件默认主题）。幂等：脚本每次启动重跑。
-- ============================================================
ALTER TABLE rpg_event_log      ADD COLUMN IF NOT EXISTS session_event_id VARCHAR(64);
ALTER TABLE rpg_world_setting  ADD COLUMN IF NOT EXISTS theme_tokens CLOB;

-- ============================================================
-- 作者自定义 HUD 内容块（rpg-hud-custom-blocks 变更）
-- rpg_world_setting.custom_blocks：作者声明的内容块 JSON 数组
-- （元素 {title, field, accent?}，≤5 块；NULL = 无自定义块）。
-- 幂等：脚本每次启动重跑。
-- ============================================================
ALTER TABLE rpg_world_setting ADD COLUMN IF NOT EXISTS custom_blocks CLOB;

-- ============================================================
-- 策划管线 run（add-planner-pipeline 变更）
-- 库表只存 run 元数据（列表页/守卫查询）；产物真源在租户工作区文件树
-- ~/.agent/tenants/<tid>/planner-runs/<runId>/（manifest.yaml 为阶段状态唯一源）。
-- 新表直接带 tenant_id 列（多租户列隔离惯例，无存量迁移负担）。
-- 幂等：脚本每次启动重跑。
-- ============================================================
CREATE TABLE IF NOT EXISTS rpg_pipeline_run (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    name            VARCHAR(200) NOT NULL,    -- 列表展示名（IP 名或原文截断）
    source_mode     VARCHAR(20)  NOT NULL,    -- text / ip_name
    style_mode      VARCHAR(20)  NOT NULL,    -- faithful / remix
    current_stage   VARCHAR(8)   NOT NULL,    -- S1..S6
    status          VARCHAR(20)  NOT NULL,    -- CREATED/RUNNING/AWAITING/COMPLETED/FAILED/ABANDONED
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 分支叙事图（story-graph-authoring 变更）
-- rpg_story_graph：world 模板层作品资产（一 world 多图）。graph_json 存幕
-- 全量（acts 容器：节点/边/轨道表/变体）；improvise_mode/state_schema 拆
-- 独立列（运行期常读免解析整图）。新表直接带 tenant_id 列（多租户列
-- 隔离惯例，无存量迁移负担）。
-- rpg_story_run：存档级运行副本（design D8）——玩家游走只写本表（当前
-- 节点/已走边/物化缓存/即兴深度），不污染模板层；回溯走确定性重放。
-- 幂等：脚本每次启动重跑。
-- ============================================================
CREATE TABLE IF NOT EXISTS rpg_story_graph (
    id              VARCHAR(64) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    world_id        VARCHAR(64) NOT NULL,        -- 模板层归属世界（物化正文要世界观/角色卡注入轨素材）
    name            VARCHAR(200) NOT NULL,       -- 图展示名（同 world 多图区分）
    improvise_mode  VARCHAR(10)  DEFAULT 'bounded',  -- off / bounded / free（design D6 三档）
    improvise_depth_limit INTEGER,                    -- bounded 即兴深度上限（4.6：null=缺省 5，超限注入收敛指令）
    state_schema    CLOB,                        -- JSON 词汇表：{key: {type,...}}（guard/effects 引用白名单）
    graph_json      CLOB,                        -- JSON: acts[]（节点/边/轨道表/变体，落库前必过校验链）
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sg_world FOREIGN KEY (world_id) REFERENCES rpg_world_setting(id)
);

CREATE TABLE IF NOT EXISTS rpg_story_run (
    id                  VARCHAR(64) PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    game_state_id       VARCHAR(64) NOT NULL,    -- 关联存档（一存档一运行副本）
    graph_id            VARCHAR(64) NOT NULL,    -- 模板图引用（模板不可变语义由校验链保证）
    current_node        VARCHAR(64),             -- 当前节点；NULL = 开局前
    walked_edges        CLOB,                    -- JSON: ["e1_01",...]（按游走序，确定性重放的结算序列）
    materialized_bodies CLOB,                    -- JSON: {nodeId: {pathHash, narration}}（路径一致才命中缓存）
    improvise_depth     INTEGER DEFAULT 0,       -- 连续纯即兴轮计数（走图边即重置）
    rematerialize       BOOLEAN DEFAULT FALSE,   -- 多周目：true 强制重新物化
    pending_gate        CLOB,                    -- 轨道闸收敛指令暂存（4.7：跨幕发散写入，物化消费即清）
    initial_player_states CLOB,                  -- 开局基线（4.8 回溯确定性重放起点；仅开局写入不可变）
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sr_game FOREIGN KEY (game_state_id) REFERENCES rpg_game_state(id),
    CONSTRAINT fk_sr_graph FOREIGN KEY (graph_id) REFERENCES rpg_story_graph(id)
);

-- ============================================================
-- 共享故事图发布库（story-graph-authoring 变更，任务 3.3）
-- 发布 = 快照复制（不可变），导入 = 物化为导入方租户模板层副本——对齐
-- shared_lorebook_pack 三层模式（读写不对称，shared_ 前缀区别 rpg_* 租户
-- 隔离域，守卫零拦截）。snapshot_json 单列存全图快照（含 state_schema/
-- improvise_mode/acts）。幂等：脚本每次启动重跑。
-- ============================================================
CREATE TABLE IF NOT EXISTS shared_story_graph (
    id                  VARCHAR(64) PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,    -- 发布名（快照自源图名）
    blurb               VARCHAR(500),             -- 简介
    publisher_tenant_id VARCHAR(64) NOT NULL,     -- 发布者租户（发布/覆盖/撤回权限锚点）
    publisher_user_id   VARCHAR(64),              -- 发布者用户（署名）
    origin_world_id     VARCHAR(64),              -- 发布方世界 id（同发布者同图重复发布→覆盖定位键；无 FK 防跨租户删世界阻塞）
    origin_graph_id     VARCHAR(64),              -- 发布方源图 id（展示/审计，跨租户不引用）
    origin_world_name   VARCHAR(200),             -- 出处世界名（仅展示）
    snapshot_json       CLOB,                     -- 全图快照（state_schema/improvise_mode/acts）
    snapshot_version    INTEGER DEFAULT 1,        -- 快照 schema 版本（导入端按版本分支反序列化）
    node_count          INTEGER,                  -- 节点总数（画廊卡片展示免解析整包；覆盖时重算）
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_at          TIMESTAMP                 -- 软下架标记：NULL = 在架
);
-- 3.3 定稿补列（1.1 建表时未含）：幂等 ALTER 兑现存量库，画廊列 node_count 免解析
ALTER TABLE shared_story_graph ADD COLUMN IF NOT EXISTS node_count INTEGER;
-- 4.6 定稿补列：bounded 即兴深度上限（存量库幂等兑现；null = 缺省 5）
ALTER TABLE rpg_story_graph ADD COLUMN IF NOT EXISTS improvise_depth_limit INTEGER;
-- 4.7 轨道闸补列：收敛指令暂存（存量库幂等兑现）
ALTER TABLE rpg_story_run ADD COLUMN IF NOT EXISTS pending_gate CLOB;
-- 4.8 图上回溯补列：开局基线（存量库幂等兑现；旧行缺省 null → 重放容错空态）
ALTER TABLE rpg_story_run ADD COLUMN IF NOT EXISTS initial_player_states CLOB;

-- ============================================================
-- 设定集条目自定义分类（add-star-lorebook-category 变更）
-- 条目级自由分类（tag-as-string，≤20 字符；NULL = 未分类）。不建分类
-- 管理实体/去重约束，历史分类由前端 datalist 聚合建议。两表列语义：
-- rpg_star_incarnation.category：模板行（set_id NULL）与 set 副本行同列
-- 携带；预设世界 md 物化列清单不含 category（落 NULL），重物化仅重写
-- 模板行，set 副本分类保留（既有物化隔离语义）。
-- shared_star_entry.category：发布时点快照携带（旧包无字段落 NULL）。
-- 幂等：脚本每次启动重跑。
-- ============================================================
ALTER TABLE rpg_star_incarnation ADD COLUMN IF NOT EXISTS category VARCHAR(20);
ALTER TABLE shared_star_entry ADD COLUMN IF NOT EXISTS category VARCHAR(20);
