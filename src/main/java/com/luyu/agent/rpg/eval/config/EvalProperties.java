package com.luyu.agent.rpg.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 评估配置属性（绑定 application.yml 的 {@code rpg.eval} 结构，content-eval tasks 1.4）。
 * <p>
 * 评估启用语义与本类无关：judge 模型由 {@code agent.models.<条目>.roles} 含
 * {@code judge} 值决定（forRole 路由，design D1——未配置即未启用，无静默
 * fallback）；本类只承载批跑参数。
 */
@ConfigurationProperties(prefix = "rpg.eval")
public class EvalProperties {

    /** judge 调用并发度（默认 1 = 串行；本地/云端单路预算可控，design D12） */
    private int judgeConcurrency = 1;

    /** 状态轨迹断崖检测阈值（|Δ| ≥ 该值且该轮叙述无对应关键词命中 → L1 预警，design D5） */
    private int cliffThreshold = 40;

    /** 动态层滑窗轮数（早窗/晚窗默认各 15 轮，design D9） */
    private int dynamicWindowTurns = 15;

    /** L1 预警注入 judge prompt 的单 case 上限（防预警过长挤占叙述窗预算） */
    private int maxL1WarningsPerCase = 20;

    /** judge 幻觉作废率警示阈值（run 报告占比超此值提示「judge 或 rubric 需校准」，design 风险节） */
    private int invalidRateWarnThreshold = 20;

    public int getJudgeConcurrency() {
        return judgeConcurrency;
    }

    public void setJudgeConcurrency(int judgeConcurrency) {
        this.judgeConcurrency = judgeConcurrency;
    }

    public int getCliffThreshold() {
        return cliffThreshold;
    }

    public void setCliffThreshold(int cliffThreshold) {
        this.cliffThreshold = cliffThreshold;
    }

    public int getDynamicWindowTurns() {
        return dynamicWindowTurns;
    }

    public void setDynamicWindowTurns(int dynamicWindowTurns) {
        this.dynamicWindowTurns = dynamicWindowTurns;
    }

    public int getMaxL1WarningsPerCase() {
        return maxL1WarningsPerCase;
    }

    public void setMaxL1WarningsPerCase(int maxL1WarningsPerCase) {
        this.maxL1WarningsPerCase = maxL1WarningsPerCase;
    }

    public int getInvalidRateWarnThreshold() {
        return invalidRateWarnThreshold;
    }

    public void setInvalidRateWarnThreshold(int invalidRateWarnThreshold) {
        this.invalidRateWarnThreshold = invalidRateWarnThreshold;
    }
}
