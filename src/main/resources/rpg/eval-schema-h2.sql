-- ============================================================
-- 内容质量评估表（content-eval-character-consistency 变更）
-- rpg_eval_run：批跑 run 级元数据（状态机/汇总/成本）；
-- rpg_eval_record：逐条评分（三档 verdict/证据 span/复核状态）。
-- 新表直接带 tenant_id 列（多租户列隔离惯例，无存量迁移负担）。
-- 成本口径对齐 metering：amount_micro 微单位 BIGINT。
-- 幂等：脚本每次启动重跑（CREATE TABLE IF NOT EXISTS）。
-- ============================================================

CREATE TABLE IF NOT EXISTS rpg_eval_run (
    id                VARCHAR(64) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    layer             VARCHAR(10)  NOT NULL,      -- static / dynamic
    scope_json        CLOB,                       -- JSON: 输入范围（worldId / 会话集合 / all）
    judge_model       VARCHAR(100),               -- judge 模型名（judge 指纹组成一）
    rubric_version    VARCHAR(64),                -- rubric 内容 hash（judge 指纹组成二）
    status            VARCHAR(20)  NOT NULL,      -- running / done / failed
    summary_json      CLOB,                       -- JSON: 分 sub-dimension 的 pass/borderline/fail/invalid 占比
    cost_tokens       BIGINT       DEFAULT 0,     -- run 级 judge 调用 token 汇总
    cost_amount_micro BIGINT       DEFAULT 0,     -- run 级成本金额（微单位，对齐 metering amount_micro 口径）
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finished_at       TIMESTAMP                   -- 结束时间；running 中为 NULL
);

CREATE TABLE IF NOT EXISTS rpg_eval_record (
    id                  VARCHAR(64) PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL,
    tenant_id           VARCHAR(64) NOT NULL,
    dimension           VARCHAR(50)  NOT NULL,    -- character-consistency（本期唯一维度）
    sub_dimension       VARCHAR(50),               -- hard-facts / motivation / relations / speech-style；case 级作废行为 NULL
    subject             VARCHAR(100) NOT NULL,    -- 被评主体：figureKey / NPC 卡 ID
    subject_label       VARCHAR(200),             -- 人物展示名（列表免回查档案）
    unit_json           CLOB,                     -- JSON: 评估单元（静态=档案ID；动态=会话ID+窗[from,to]）
    verdict             VARCHAR(20),              -- pass / borderline / fail；invalid 行为 NULL
    evidence_json       CLOB,                     -- JSON: [{turn, span}]（judge 引用原文，落库前已核验定位）
    rationale           VARCHAR(500),             -- ≤50 字中文理由（防御超长截断）
    l1_warnings_json    CLOB,                     -- JSON: L1 确定性检查器预警清单（注入 judge 的同一份）
    invalid_reason      VARCHAR(500),             -- NULL = 有效；非空 = judge 幻觉作废/畸形降级原因
    target_fingerprint  VARCHAR(200),             -- 被评产物指纹（prompt_hash / GM 模型 / 世界）
    judge_fingerprint   VARCHAR(200),             -- judge 模型名 + rubric 版本
    is_regression       BOOLEAN DEFAULT FALSE,    -- 回归集固定用例（人工复核沉淀，后续批跑必含）
    review_status       VARCHAR(20) DEFAULT 'pending',  -- pending / confirmed / override
    review_final_verdict VARCHAR(20),             -- 人工终裁（override 时非空；机器原判保留于 verdict 列）
    divergence_group    VARCHAR(120),             -- 分歧对齐键（subject+dimension+unit 跨 run 聚合；NULL = 无分歧）
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eval_record_run FOREIGN KEY (run_id) REFERENCES rpg_eval_run(id)
);

-- 幂等保险：CREATE 已含 tenant_id，此行对齐既有 ALTER 段惯例（脚本重跑不报错）
ALTER TABLE rpg_eval_run   ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE rpg_eval_record ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

-- content-eval 7.x 前置修正：case 级作废行（judge 畸形二次失败降级）sub_dimension 落 NULL，
-- 放开建表时的 NOT NULL（新库 CREATE 即 NULL 列；存量库 ALTER 幂等重放开）
ALTER TABLE rpg_eval_record ALTER COLUMN sub_dimension VARCHAR(50) NULL;
