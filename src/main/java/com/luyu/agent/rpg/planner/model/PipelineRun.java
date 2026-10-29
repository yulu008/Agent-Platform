package com.luyu.agent.rpg.planner.model;

import java.time.LocalDateTime;

/**
 * 策划管线 run（对应 rpg_pipeline_run 表，add-planner-pipeline design D3）。
 * <p>
 * 库表只存 run 元数据（列表页与守卫查询）；产物真源在租户工作区文件树
 * {@code ~/.agent/tenants/<tid>/planner-runs/<runId>/}（manifest 为阶段状态唯一源）。
 * 字段用字符串存枚举码（对齐 DestinedFigure 惯例），枚举解析由服务层完成。
 */
public class PipelineRun {

    private String id;
    private String tenantId;
    /** 列表展示名（IP 名或原文截断） */
    private String name;
    /** text / ip_name（{@link SourceMode#code()}） */
    private String sourceMode;
    /** faithful / remix（{@link StyleMode#code()}） */
    private String styleMode;
    /** 当前阶段码 S1..S6（{@link PipelineStage#code()}） */
    private String currentStage;
    /** run 整体状态（{@link PipelineRunStatus#name()}） */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getStyleMode() {
        return styleMode;
    }

    public void setStyleMode(String styleMode) {
        this.styleMode = styleMode;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
