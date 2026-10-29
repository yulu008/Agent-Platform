package com.luyu.agent.rpg.eval.model;

import java.util.List;

/**
 * 单 EvalCase 的 judge 裁决结果（content-eval tasks 5.3，design D4）。
 * <p>
 * 两态：
 * <ul>
 *   <li><b>解析成功</b>：{@code caseInvalidReason} = null，{@code judgments}
 *       非空——个别条目可能证据核验失败（该条 {@code invalidReason} 非空，
 *       按 record 粒度作废，其余条目正常落库）；</li>
 *   <li><b>case 级作废</b>：{@code caseInvalidReason} 非空（judge 输出畸形
 *       重试一次仍失败 / judge 调用异常），{@code judgments} 为空——编排层
 *       落 1 条 case 级 invalid record（subDimension 为 null），批跑继续
 *       后续 case 不中断（三层防御的降级隔离终点）。</li>
 * </ul>
 */
public record JudgeOutcome(
        /** null = 解析成功；非空 = case 级作废原因（畸形二次失败/调用异常） */
        String caseInvalidReason,
        /** 判分条目（解析成功时非空；个别条目 invalidReason 非空 = 证据核验失败） */
        List<JudgeJudgment> judgments) {

    /** case 级作废（畸形降级/调用失败） */
    public static JudgeOutcome invalid(String reason) {
        return new JudgeOutcome(reason, List.of());
    }
}
