package com.luyu.agent.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话标题异步生成器
 * 首轮对话完成后，调用 AI 生成 5-15 字的会话摘要标题
 * 失败时 fallback 为首条 user 消息截断前 15 字
 * 
 * 标题存储在 Session.metadata["title"] 中
 */
@Service
public class SessionTitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(SessionTitleGenerator.class);

    private static final String TITLE_SYSTEM_PROMPT =
            "你是标题生成器。用5-15个字概括以下对话的主题，只输出标题本身，不要加引号或标点。";

    private final ChatClient titleClient;
    private final SessionRepository sessionRepository;

    public SessionTitleGenerator(ChatClient.Builder chatClientBuilder,
                                 SessionRepository sessionRepository) {
        // 标题生成使用无 advisor 的纯净 ChatClient，避免上下文干扰
        this.titleClient = chatClientBuilder.build();
        this.sessionRepository = sessionRepository;
    }

    /**
     * 异步生成会话标题
     * 在首轮对话完成后由 ChatStreamController 调用
     *
     * @param sessionId     会话 ID
     * @param userMsg       首条用户消息
     * @param assistantMsg  首条 AI 回复
     */
    @Async
    public void generateTitleAsync(String sessionId, String userMsg, String assistantMsg) {
        try {
            String prompt = String.format("用户: %s\n助手: %s", userMsg, assistantMsg);
            String title = titleClient.prompt()
                    .system(TITLE_SYSTEM_PROMPT)
                    .user(prompt)
                    .call()
                    .content();

            if (title != null && !title.isBlank()) {
                // 清理标题：去除首尾空白和可能的引号
                title = title.trim().replaceAll("^[\"'「]|['\"」]$", "");
                // 限制长度
                if (title.length() > 15) {
                    title = title.substring(0, 15);
                }
                updateTitle(sessionId, title);
                log.info("AI 摘要标题生成成功: sessionId={}, title={}", sessionId, title);
            } else {
                fallbackTitle(sessionId, userMsg);
            }
        } catch (Exception e) {
            log.warn("AI 摘要标题生成失败，使用 fallback: sessionId={}, error={}", sessionId, e.getMessage());
            fallbackTitle(sessionId, userMsg);
        }
    }

    /**
     * Fallback：截取首条 user 消息前 15 字作为标题
     */
    private void fallbackTitle(String sessionId, String userMsg) {
        String fallback = userMsg.length() > 15 ? userMsg.substring(0, 15) : userMsg;
        updateTitle(sessionId, fallback);
        log.info("使用 fallback 标题: sessionId={}, title={}", sessionId, fallback);
    }

    /**
     * 更新会话标题（写入 Session.metadata["title"]）
     */
    private void updateTitle(String sessionId, String title) {
        Session session = sessionRepository.findById(sessionId);
        if (session != null) {
            Map<String, Object> newMetadata = new HashMap<>(
                    session.metadata() != null ? session.metadata() : Map.of());
            newMetadata.put("title", title);

            Session updated = Session.builder()
                    .id(session.id())
                    .userId(session.userId())
                    .createdAt(session.createdAt())
                    .expiresAt(session.expiresAt())
                    .metadata(newMetadata)
                    .build();
            sessionRepository.save(updated);
        }
    }
}
