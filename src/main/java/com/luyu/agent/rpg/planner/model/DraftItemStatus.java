package com.luyu.agent.rpg.planner.model;

/**
 * L2 门候选元素的候选级状态（add-planner-pipeline design D4 / spec planner-confirm-gates）。
 * <p>
 * 状态转换（单向不可复活）：PENDING → CONFIRMED / REJECTED；CONFIRMED → REJECTED
 * （确认后改毙允许）；REJECTED 无出边——已毙候选不复活，重生成走返工排除表（D5）。
 * <ul>
 *   <li>PENDING：生成落盘后的初始态（待创作者逐卡确认或毙掉）</li>
 *   <li>CONFIRMED：创作者已逐卡确认</li>
 *   <li>REJECTED：已毙（进返工 prompt 显式排除表，禁止近似变体复活）</li>
 * </ul>
 * 与阶段级 {@link PipelineStageStatus} 的 CONFIRMED（整稿通过锁定）区分：本枚举只在
 * L2 阶段（S3/S5）的候选集合内使用，整稿通过的前置是集合内无 PENDING。
 */
public enum DraftItemStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}
