package com.luyu.agent.rpg.eval.engine;

/**
 * judge 输出畸形信号（content-eval tasks 5.4，design D4）。
 * <p>
 * {@code JudgeResponseParser} 解析失败（非 JSON / 结构非法 / verdict 非三档 /
 * 无任何可识别判分条目）时抛出——{@code JudgeClient} 捕获后重试一次，
 * 二次失败降级 case 级 invalid（三层防御：防御解析 → 重试 → 降级隔离，
 * MalformedToolCallSanitizer 哲学平移）。
 */
public class JudgeParseException extends RuntimeException {

    public JudgeParseException(String message) {
        super(message);
    }

    public JudgeParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
