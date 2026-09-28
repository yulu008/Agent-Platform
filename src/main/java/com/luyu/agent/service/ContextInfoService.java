package com.luyu.agent.service;

import com.luyu.agent.config.ChatClientRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.EventFilter;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文计量服务
 * <p>
 * 聚合当前会话的 token 使用情况：消息 token + 工具定义开销 + 压缩统计。
 * 所有 token 值为字符近似法估算结果（±20% 精度）。
 */
@Service
public class ContextInfoService {

    private static final Logger log = LoggerFactory.getLogger(ContextInfoService.class);

    private final SessionService sessionService;
    private final ChatClientRegistry chatClientRegistry;

    public ContextInfoService(SessionService sessionService, ChatClientRegistry chatClientRegistry) {
        this.sessionService = sessionService;
        this.chatClientRegistry = chatClientRegistry;
    }

    /**
     * 获取指定会话的上下文计量信息
     * <p>
     * 口径对齐模型真实上下文：使用 {@link EventFilter#active()} 只统计未归档事件。
     * compact() 仅将旧事件标记为 archived（行仍保留在 AI_SESSION_EVENT），
     * SessionMemoryAdvisor 加载上下文时排除归档事件，若不过滤则进度条压缩后不下降。
     *
     * @param sessionId 会话 ID
     * @return 上下文计量信息（totalTokens、usagePercent 等）
     */
    public ContextInfo getContextInfo(String sessionId) {
        List<SessionEvent> events = sessionService.getEvents(sessionId, EventFilter.active());

        int toolTokens = chatClientRegistry.getCachedToolTokens();
        int messageTotal = 0;
        int compactionCount = 0;
        List<ContextInfo.MessageTokenInfo> messageTokenInfos = new ArrayList<>();

        for (SessionEvent event : events) {
            Message msg = event.getMessage();
            String text = msg.getText();
            int tokens = TokenEstimator.estimateTokens(text);

            if (event.isSynthetic()) {
                // 合成事件 = 压缩摘要
                compactionCount++;
                messageTotal += tokens;
                String preview = truncate(text, 50);
                messageTokenInfos.add(new ContextInfo.MessageTokenInfo("synthetic", tokens, preview));
            } else if (msg instanceof UserMessage) {
                messageTotal += tokens;
                String preview = truncate(text, 50);
                messageTokenInfos.add(new ContextInfo.MessageTokenInfo("user", tokens, preview));
            } else if (msg instanceof AssistantMessage) {
                messageTotal += tokens;
                String preview = truncate(text, 50);
                messageTokenInfos.add(new ContextInfo.MessageTokenInfo("assistant", tokens, preview));
            }
            // ToolResponseMessage 等不计入用户可见的消息 token
        }

        int totalTokens = messageTotal + toolTokens;
        int maxTokens = TokenEstimator.CONTEXT_WINDOW_SIZE;
        double usagePercent = maxTokens > 0 ? Math.min(100.0, (totalTokens * 100.0) / maxTokens) : 0.0;

        return new ContextInfo(totalTokens, maxTokens, usagePercent, messageTokenInfos, toolTokens, compactionCount);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
