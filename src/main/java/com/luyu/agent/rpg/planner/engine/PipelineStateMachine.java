package com.luyu.agent.rpg.planner.engine;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.luyu.agent.rpg.planner.model.PipelineRunStatus;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;

/**
 * 管线状态机（add-planner-pipeline design D1/D6）。
 * <p>
 * 阶段顺序与状态转换矩阵由代码决定——确定性、可测，加阶段=加枚举。
 * 本类只做纯函数校验（给定当前状态判断目标状态/推进是否合法），
 * 不持有任何可变状态；落库与 manifest 同步由 PipelineRunService 单点执行。
 * <p>
 * 阶段转换（manifest 内细粒度状态）：
 * <pre>
 * NOT_STARTED ─▶ RUNNING ─▶ AWAITING_CONFIRM ─▶ CONFIRMED
 *                  │            ▲    │            │（回退解锁：回待确认态）
 *                  ▼            │    │（返工/重试）  ▼
 *                FAILED ─▶ RUNNING◀─┘      AWAITING_CONFIRM
 * </pre>
 * 回退解锁（tasks 4.5）目标态是 AWAITING_CONFIRM 而非 RUNNING：解锁后
 * 编辑/返工/再确认都走既有出边，避免与单阶段 RUNNING 守卫冲突。
 * run 转换（库表 status）：CREATED→RUNNING↔AWAITING→COMPLETED/FAILED/ABANDONED。
 */
public final class PipelineStateMachine {

    private PipelineStateMachine() {
    }

    // ---- 阶段状态转换矩阵 ----

    private static final Map<PipelineStageStatus, Set<PipelineStageStatus>> STAGE_TRANSITIONS = Map.of(
            PipelineStageStatus.NOT_STARTED, EnumSet.of(PipelineStageStatus.RUNNING),
            PipelineStageStatus.RUNNING, EnumSet.of(
                    PipelineStageStatus.AWAITING_CONFIRM, PipelineStageStatus.FAILED),
            // AWAITING_CONFIRM → RUNNING：返工（带反馈新一轮）或重试；
            // → CONFIRMED：整稿通过（confirm-gate 确认门，tasks 4.1）
            PipelineStageStatus.AWAITING_CONFIRM, EnumSet.of(
                    PipelineStageStatus.RUNNING, PipelineStageStatus.CONFIRMED),
            // FAILED → RUNNING：失败后重试（复用已落盘中间产物）
            PipelineStageStatus.FAILED, EnumSet.of(PipelineStageStatus.RUNNING),
            // CONFIRMED → AWAITING_CONFIRM：显式回退解锁（design D4/确认稿幂等锁定；
            // tasks 4.5——解锁后可编辑/返工/再确认，confirmedVersion 存档保留）；
            // 其余 CONFIRMED 出边一律非法——锁定后不可静默改
            PipelineStageStatus.CONFIRMED, EnumSet.of(
                    PipelineStageStatus.RUNNING, PipelineStageStatus.AWAITING_CONFIRM));

    /** 阶段状态转换是否合法 */
    public static boolean canTransition(PipelineStageStatus from, PipelineStageStatus to) {
        return STAGE_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PipelineStageStatus.class)).contains(to);
    }

    /** 校验并返回目标状态；非法转换抛 IllegalStateException（含两侧状态便于诊断） */
    public static PipelineStageStatus requireTransition(PipelineStageStatus from, PipelineStageStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "非法的阶段状态转换: " + from + " → " + to + "（run 状态机被拒绝，防静默越权推进）");
        }
        return to;
    }

    // ---- 阶段顺序推进 ----

    /**
     * 某阶段确认后应推进到的下一阶段；S6 确认即终局（返回 null，由安装流程置 COMPLETED）。
     */
    public static PipelineStage nextStageAfterConfirm(PipelineStage confirmedStage) {
        return confirmedStage.next();
    }

    /**
     * 是否允许对目标阶段触发生成：仅当前阶段（currentStage）自身可生成，
     * 且其细粒度状态须为可生成态（NOT_STARTED / FAILED 重试 / AWAITING_CONFIRM 返工 / CONFIRMED 回退解锁后为 RUNNING）。
     * 跨阶段跳过被拒（spec「跳过被拒」）。
     */
    public static boolean canStartStage(PipelineStage currentStage, PipelineStage target,
                                        PipelineStageStatus targetStatus) {
        return currentStage == target && canTransition(targetStatus, PipelineStageStatus.RUNNING);
    }

    // ---- run 状态转换矩阵 ----

    private static final Map<PipelineRunStatus, Set<PipelineRunStatus>> RUN_TRANSITIONS = Map.of(
            PipelineRunStatus.CREATED, EnumSet.of(PipelineRunStatus.RUNNING, PipelineRunStatus.ABANDONED),
            PipelineRunStatus.RUNNING, EnumSet.of(
                    PipelineRunStatus.AWAITING, PipelineRunStatus.FAILED, PipelineRunStatus.ABANDONED),
            // AWAITING → RUNNING：返工或下一阶段生成；→ COMPLETED：S6 安装全部物化成功
            // （tasks 5.1，安装端点专用语义——类首注释的转换图本含此边，此处补齐矩阵）
            PipelineRunStatus.AWAITING, EnumSet.of(
                    PipelineRunStatus.RUNNING, PipelineRunStatus.COMPLETED, PipelineRunStatus.ABANDONED),
            // FAILED → RUNNING：重试；→ AWAITING：重试成功落草稿
            PipelineRunStatus.FAILED, EnumSet.of(
                    PipelineRunStatus.RUNNING, PipelineRunStatus.AWAITING, PipelineRunStatus.ABANDONED),
            // COMPLETED / ABANDONED 为终态
            PipelineRunStatus.COMPLETED, EnumSet.noneOf(PipelineRunStatus.class),
            PipelineRunStatus.ABANDONED, EnumSet.noneOf(PipelineRunStatus.class));

    /** run 状态转换是否合法 */
    public static boolean canTransition(PipelineRunStatus from, PipelineRunStatus to) {
        return RUN_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(PipelineRunStatus.class)).contains(to);
    }

    /** 校验并返回目标状态；非法转换抛 IllegalStateException */
    public static PipelineRunStatus requireTransition(PipelineRunStatus from, PipelineRunStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "非法的 run 状态转换: " + from + " → " + to);
        }
        return to;
    }
}
