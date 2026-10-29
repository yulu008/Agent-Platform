package com.luyu.agent.rpg.eval.model;

/**
 * 评估评分 record 实体（对应 rpg_eval_record 表，content-eval tasks 1.2）。
 * <p>
 * 一条 record = 一个评估单元（被评主体 × 子维度）的判分结果。verdict 三档
 * （pass/borderline/fail，design D2）；judge 幻觉作废/畸形降级的 record
 * verdict 为 NULL 且 {@code invalidReason} 非空——不计入任何汇总统计
 * （spec「无证据判分作废」）。
 * <p>
 * 复核语义（design D10）：borderline 与 judge 间分歧 case 进人工复核队列
 * （{@code reviewStatus} pending→confirmed/override）；人工终裁写
 * {@code reviewFinalVerdict}，机器原判保留于 {@code verdict} 列不覆盖。
 * {@code isRegression} = 人工终裁 fail 后沉淀的回归集固定用例（后续批跑必含）。
 */
public class EvalRecord {

    /** 维度：人物一致性（本期唯一维度，后续 change 扩剧情逻辑/选择意义感/付费点位） */
    public static final String DIMENSION_CHARACTER_CONSISTENCY = "character-consistency";

    /** 子维度：硬事实核对（档案结构化字段 vs 叙述） */
    public static final String SUB_HARD_FACTS = "hard-facts";
    /** 子维度：动机-行为一致性（npc_states 动机轨迹 vs 行为） */
    public static final String SUB_MOTIVATION = "motivation";
    /** 子维度：关系轨迹一致性（trust_* 状态键 vs 互动叙事） */
    public static final String SUB_RELATIONS = "relations";
    /** 子维度：言语风格/性格演化（情境人格面判据） */
    public static final String SUB_SPEECH_STYLE = "speech-style";

    /** verdict：合格 */
    public static final String VERDICT_PASS = "pass";
    /** verdict：存疑（不计失败率，进人工复核队列） */
    public static final String VERDICT_BORDERLINE = "borderline";
    /** verdict：崩坏 */
    public static final String VERDICT_FAIL = "fail";

    /** 复核状态：待复核（队列中） */
    public static final String REVIEW_PENDING = "pending";
    /** 复核状态：人工确认机器判分 */
    public static final String REVIEW_CONFIRMED = "confirmed";
    /** 复核状态：人工推翻机器判分（终裁见 reviewFinalVerdict） */
    public static final String REVIEW_OVERRIDE = "override";

    private String id;
    private String runId;
    private String tenantId;
    /** character-consistency */
    private String dimension;
    /** hard-facts / motivation / relations / speech-style */
    private String subDimension;
    /** 被评主体：figureKey（如 kagetora）或 NPC 卡 ID */
    private String subject;
    /** 人物展示名（列表免回查档案） */
    private String subjectLabel;
    /** JSON: 评估单元（静态=档案ID；动态=会话ID+窗[from,to]） */
    private String unitJson;
    /** pass / borderline / fail；invalid 行为 NULL */
    private String verdict;
    /** JSON: [{turn, span}]（judge 引用原文，落库前已核验可定位） */
    private String evidenceJson;
    /** ≤50 字中文理由 */
    private String rationale;
    /** JSON: L1 确定性检查器预警清单（注入 judge 的同一份） */
    private String l1WarningsJson;
    /** NULL = 有效；非空 = 作废/降级原因（judge 幻觉 / 畸形输出） */
    private String invalidReason;
    /** 被评产物指纹（prompt_hash / GM 模型 / 世界；存量近似指纹带"approx:"前缀标注） */
    private String targetFingerprint;
    /** judge 指纹（judge 模型名 + rubric 版本） */
    private String judgeFingerprint;
    /** 回归集固定用例（人工复核沉淀；后续批跑必含） */
    private Boolean isRegression;
    /** pending / confirmed / override */
    private String reviewStatus;
    /** 人工终裁 pass/borderline/fail（override 时非空） */
    private String reviewFinalVerdict;
    /** 分歧对齐键（subject+dimension+unit 跨 run 聚合；NULL = 无分歧标记） */
    private String divergenceGroup;
    private java.time.LocalDateTime createdAt;

    public EvalRecord() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public String getSubDimension() { return subDimension; }
    public void setSubDimension(String subDimension) { this.subDimension = subDimension; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getSubjectLabel() { return subjectLabel; }
    public void setSubjectLabel(String subjectLabel) { this.subjectLabel = subjectLabel; }

    public String getUnitJson() { return unitJson; }
    public void setUnitJson(String unitJson) { this.unitJson = unitJson; }

    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }

    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public String getL1WarningsJson() { return l1WarningsJson; }
    public void setL1WarningsJson(String l1WarningsJson) { this.l1WarningsJson = l1WarningsJson; }

    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String invalidReason) { this.invalidReason = invalidReason; }

    public String getTargetFingerprint() { return targetFingerprint; }
    public void setTargetFingerprint(String targetFingerprint) { this.targetFingerprint = targetFingerprint; }

    public String getJudgeFingerprint() { return judgeFingerprint; }
    public void setJudgeFingerprint(String judgeFingerprint) { this.judgeFingerprint = judgeFingerprint; }

    public Boolean getIsRegression() { return isRegression; }
    public void setIsRegression(Boolean isRegression) { this.isRegression = isRegression; }

    public String getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }

    public String getReviewFinalVerdict() { return reviewFinalVerdict; }
    public void setReviewFinalVerdict(String reviewFinalVerdict) { this.reviewFinalVerdict = reviewFinalVerdict; }

    public String getDivergenceGroup() { return divergenceGroup; }
    public void setDivergenceGroup(String divergenceGroup) { this.divergenceGroup = divergenceGroup; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
}
