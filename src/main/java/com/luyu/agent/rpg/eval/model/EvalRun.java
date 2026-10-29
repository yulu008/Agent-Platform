package com.luyu.agent.rpg.eval.model;

/**
 * 评估批跑 run 实体（对应 rpg_eval_run 表，content-eval tasks 1.2）。
 * <p>
 * 一次批跑一条 run：状态机 {@code running → done / failed}；record 逐条落库
 * （非整批事务），批跑中断时已落 record 保留且 run 标记 failed（spec「批跑
 * 中断保留部分结果」——再次触发创建新 run，不覆盖旧 run）。
 * <p>
 * 成本口径对齐 metering：{@code costAmountMicro} 微单位 BIGINT（judge 调用
 * 逐次计量入 metering，run 级汇总）。
 */
public class EvalRun {

    /** run 状态：执行中（触发后同步返回 run id 时的初始态） */
    public static final String STATUS_RUNNING = "running";
    /** run 状态：完成（含部分 record invalid 的正常完成） */
    public static final String STATUS_DONE = "done";
    /** run 状态：失败（中断/异常；已落 record 保留） */
    public static final String STATUS_FAILED = "failed";

    /** 层级：静态层（设定自洽，无会话数据） */
    public static final String LAYER_STATIC = "static";
    /** 层级：动态层（存量会话回捞，表现对照设定） */
    public static final String LAYER_DYNAMIC = "dynamic";

    private String id;
    private String tenantId;
    /** static / dynamic */
    private String layer;
    /** JSON: 输入范围（worldId / 会话集合 / all） */
    private String scopeJson;
    /** judge 模型名（judge 指纹组成一；forRole("judge") 装配序首个条目） */
    private String judgeModel;
    /** rubric 内容 hash（judge 指纹组成二） */
    private String rubricVersion;
    /** running / done / failed */
    private String status;
    /** JSON: 分 sub-dimension 的 pass/borderline/fail/invalid 占比 */
    private String summaryJson;
    private Long costTokens;
    private Long costAmountMicro;
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime finishedAt;

    public EvalRun() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }

    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }

    public String getJudgeModel() { return judgeModel; }
    public void setJudgeModel(String judgeModel) { this.judgeModel = judgeModel; }

    public String getRubricVersion() { return rubricVersion; }
    public void setRubricVersion(String rubricVersion) { this.rubricVersion = rubricVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }

    public Long getCostTokens() { return costTokens; }
    public void setCostTokens(Long costTokens) { this.costTokens = costTokens; }

    public Long getCostAmountMicro() { return costAmountMicro; }
    public void setCostAmountMicro(Long costAmountMicro) { this.costAmountMicro = costAmountMicro; }

    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }

    public java.time.LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(java.time.LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
