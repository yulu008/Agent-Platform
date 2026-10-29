package com.luyu.agent.rpg.eval.model;

import java.util.List;

/**
 * judge 单子维度判分（content-eval tasks 5.2/5.4，design D2/D3/D4）。
 * <p>
 * 一条 = 一个子维度的三档判分：{@code subDimension} 限四值
 * （{@link EvalRecord#SUB_HARD_FACTS} 等）；{@code verdict} 限三档；
 * {@code evidence} = 引用原文 span 数组（落库前经 {@code EvidenceVerifier}
 * 核验可定位——D3 无证据/无法定位的判分作废）。
 * <p>
 * {@code invalidReason}：null = 该条有效可落库；非空 = 证据核验失败原因
 * （该条 record 作废，verdict 不落——spec「无证据判分作废」按子维度条目
 * 粒度隔离，作废不扩散到同 case 其余子维度）。
 */
public record JudgeJudgment(
        /** hard-facts / motivation / relations / speech-style */
        String subDimension,
        /** pass / borderline / fail */
        String verdict,
        /** ≤50 字中文理由 */
        String rationale,
        /** 引用原文证据（judge 幻觉防御锚点） */
        List<Evidence> evidence,
        /** null = 有效；非空 = 证据核验失败原因（该条作废） */
        String invalidReason) {

    /**
     * 证据 span：turn = 叙述轮次（动态层须为窗内真实轮次；静态层/档案字段
     * 引用为 -1，对齐 {@link L1Warning#TURN_NONE}）。
     */
    public record Evidence(int turn, String span) {
    }
}
