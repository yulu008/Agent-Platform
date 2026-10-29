package com.luyu.agent.rpg.planner.model;

/**
 * run 整体状态（spec planner-pipeline-run「阶段状态机推进」）。
 * <p>
 * 存于 {@code rpg_pipeline_run.status} 列，列表页徽标与守卫查询的数据源。
 * gate 之间的等待是持久化状态而非连接挂起（design D6）——AWAITING 可能跨小时/跨天。
 */
public enum PipelineRunStatus {

    /** 已创建，S1 尚未触发生成 */
    CREATED,

    /** 有阶段正在生成中 */
    RUNNING,

    /** 停在某个确认门等待创作者（跨天可续） */
    AWAITING,

    /** S6 安装全部物化成功 */
    COMPLETED,

    /** 最近一次阶段生成失败（run 冻结可重试） */
    FAILED,

    /** 创作者主动放弃；工作区文件树保留 */
    ABANDONED
}
