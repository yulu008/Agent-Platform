package com.luyu.agent.rpg.eval.engine;

/**
 * 内容评估未启用异常（content-eval tasks 5.3，design D1）。
 * <p>
 * {@code agent.models} 无任何条目 roles 含 {@code judge} 值时抛出——
 * 未配置即未启用，绝不静默 fallback 到 default 对话模型（评分来源必须
 * 可追溯）。上层端点据此返回明确错误（HTTP 503 语义 + 配置指引文案），
 * 不发起任何 LLM 调用、不落 run。
 */
public class EvalNotEnabledException extends RuntimeException {

    public static final String CONFIG_HINT =
            "内容评估未启用：agent.models 无任何条目配置 judge 角色。"
                    + "请在 application.yml 的 agent.models 任一条目 roles 中加入 'judge' 值"
                    + "（如 roles: [chat, judge]）以指定裁判模型。";

    public EvalNotEnabledException() {
        super(CONFIG_HINT);
    }
}
