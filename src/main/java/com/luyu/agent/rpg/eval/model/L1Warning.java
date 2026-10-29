package com.luyu.agent.rpg.eval.model;

/**
 * L1 确定性检查预警（content-eval tasks 4.x，design D5）。
 * <p>
 * 仅作为 L2 judge prompt 的输入部分（「规则引擎发现以下疑似矛盾，请核对原文后
 * 裁决」——L1 只筛不判，最终 verdict 由 judge 裁决）；落库于
 * rpg_eval_record.l1_warnings_json。
 *
 * @param checker 检查器标识：hard-facts / state-trajectory / kanji-reference / hook-assertions
 * @param turn    定位轮次（出场轮序号）；静态层字段级预警用 {@link #TURN_NONE}
 * @param message 中文预警句（人/LLM 双可读，注入 judge prompt）
 * @param span    证据文本片段（叙述原文摘录；judge 核对用）
 */
public record L1Warning(String checker, int turn, String message, String span) {

    /** 非轮次定位（静态层字段级预警，无叙述轮可指） */
    public static final int TURN_NONE = -1;

    /** checker 标识：硬事实核对（档案结构化字段矛盾检索） */
    public static final String CHECKER_HARD_FACTS = "hard-facts";
    /** checker 标识：状态轨迹断崖检测 */
    public static final String CHECKER_STATE_TRAJECTORY = "state-trajectory";
    /** checker 标识：汉字指称正则检查（世界规则禁止罗马音与纯英文名） */
    public static final String CHECKER_KANJI_REFERENCE = "kanji-reference";
    /** checker 标识：钩子断言枚举核对（classpath 人工维护清单） */
    public static final String CHECKER_HOOK_ASSERTIONS = "hook-assertions";
}
