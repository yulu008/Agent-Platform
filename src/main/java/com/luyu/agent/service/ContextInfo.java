package com.luyu.agent.service;

import java.util.List;

/**
 * 上下文计量信息（只读 record）
 *
 * @param totalTokens      当前上下文总 token 数（消息 + 工具 + 摘要）
 * @param maxTokens        模型上下文窗口上限（128,000）
 * @param usagePercent     使用百分比（0.0 ~ 100.0）
 * @param messageTokens    每条消息的 token 估算列表（role + tokens）
 * @param toolTokens       工具定义的 token 开销（固定缓存值）
 * @param compactionCount  该会话已执行的压缩次数（合成事件计数）
 */
public record ContextInfo(
        int totalTokens,
        int maxTokens,
        double usagePercent,
        List<MessageTokenInfo> messageTokens,
        int toolTokens,
        int compactionCount
) {

    /**
     * 单条消息的 token 信息
     *
     * @param role    消息角色（user / assistant / synthetic）
     * @param tokens  估算 token 数
     * @param content 消息内容前 50 字符（用于前端标识）
     */
    public record MessageTokenInfo(String role, int tokens, String content) {}
}
