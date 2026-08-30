package com.luyu.agent.config;

import com.luyu.agent.service.TokenEstimator;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionTrigger;

import java.util.List;

/**
 * 基于 token 阈值的压缩触发器
 * <p>
 * 当会话上下文的估算 token 总量达到阈值（默认 89,600 = 128k × 70%）时触发压缩，
 * 替代固定轮数的 {@code TurnCountTrigger}。
 */
public class TokenThresholdCompactionTrigger implements CompactionTrigger {

    private final int threshold;

    /**
     * @param threshold token 阈值，达到此值时触发压缩
     */
    public TokenThresholdCompactionTrigger(int threshold) {
        this.threshold = threshold;
    }

    /**
     * 使用默认阈值（128k × 70% = 89,600）
     */
    public TokenThresholdCompactionTrigger() {
        this(TokenEstimator.COMPACTION_THRESHOLD);
    }

    /**
     * 判断是否应该触发压缩
     * <p>
     * 遍历所有事件的消息文本，累计 token 估算值，
     * 达到阈值返回 true。
     */
    @Override
    public boolean shouldCompact(CompactionRequest request) {
        List<SessionEvent> events = request.events();
        int totalTokens = 0;
        for (SessionEvent event : events) {
            String text = event.getMessage().getText();
            totalTokens += TokenEstimator.estimateTokens(text);
            if (totalTokens >= threshold) {
                return true;
            }
        }
        return false;
    }
}
