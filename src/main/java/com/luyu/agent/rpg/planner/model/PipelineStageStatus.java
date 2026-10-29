package com.luyu.agent.rpg.planner.model;

/**
 * 阶段状态（spec planner-pipeline-run「阶段状态机推进」）。
 * <p>
 * 阶段的细粒度状态存于工作区 manifest（文件树为真源）；
 * 库表只存 run 级状态与当前阶段码。
 */
public enum PipelineStageStatus {

    /** 尚未触发生成 */
    NOT_STARTED,

    /** 生成进行中（SSE 流活跃，受单阶段 RUNNING 守卫保护） */
    RUNNING,

    /** 生成完成，草稿落盘，等待确认门操作 */
    AWAITING_CONFIRM,

    /** 整稿通过，产物锁定为下游唯一输入 */
    CONFIRMED,

    /** 生成失败，run 冻结在原地可重试（不自动重试、不影响已完成阶段） */
    FAILED
}
