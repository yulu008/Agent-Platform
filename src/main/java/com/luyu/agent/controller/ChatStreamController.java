package com.luyu.agent.controller;

import com.luyu.agent.chat.service.SessionTitleGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 聊天流式端点 + 历史查询端点
 * 
 * - POST /chat/stream: SSE 流式对话，逐 token 推送模型响应
 * - GET  /chat/history: 查询指定会话的历史消息
 * 
 * 多轮上下文由 SessionMemoryAdvisor 自动管理（加载历史、追加消息、触发压缩）
 */
@RestController
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    private final ChatClient chatClient;
    private final SessionService sessionService;
    private final SessionTitleGenerator sessionTitleGenerator;

    public ChatStreamController(ChatClient chatClient,
                                SessionService sessionService,
                                SessionTitleGenerator sessionTitleGenerator) {
        this.chatClient = chatClient;
        this.sessionService = sessionService;
        this.sessionTitleGenerator = sessionTitleGenerator;
    }

    /**
     * SSE 流式对话端点
     * 
     * 请求体: {"message":"你好","sessionId":"uuid"}
     * 响应: text/event-stream，逐 token 推送 {"content":"token"}，末尾推送 [DONE]
     *
     * SessionMemoryAdvisor 自动管理：
     * - 会话不存在时自动创建
     * - 加载历史上下文
     * - 追加用户消息和助手回复
     * - 达到轮次阈值时自动压缩
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamChat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");

        // 用于拼接完整回复文本（中止时持久化部分回复）
        StringBuilder fullResponse = new StringBuilder();

        // 流式调用模型，通过 advisor context 传入 sessionId
        return chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                .stream()
                .content()
                .map(token -> {
                    fullResponse.append(token);
                    return ServerSentEvent.<Object>builder()
                            .data(Map.of("content", token))
                            .build();
                })
                .doOnCancel(() -> {
                    // 客户端主动中止：持久化已拼接的部分回复
                    if (fullResponse.length() > 0) {
                        sessionService.appendMessage(sessionId,
                                new AssistantMessage(fullResponse.toString()));
                        log.info("流式中止，已保存部分回复: sessionId={}, length={}",
                                sessionId, fullResponse.length());
                    }
                    triggerTitleGenerationIfFirstRound(sessionId);
                })
                .doOnComplete(() -> {
                    // 首轮对话完成后触发 AI 摘要标题生成
                    triggerTitleGenerationIfFirstRound(sessionId);
                })
                .onErrorResume(error -> {
                    // 流式分片合并异常（ChunkMerger）时，已发出的内容仍有价值
                    boolean isChunkMergerError = isStreamAggregationError(error);
                    if (fullResponse.length() > 0) {
                        // 已有流式内容：保存部分回复，不向前端报错
                        sessionService.appendMessage(sessionId,
                                new AssistantMessage(fullResponse.toString()));
                        log.warn("流式异常但已保存部分回复: sessionId={}, length={}, 原因: {}",
                                sessionId, fullResponse.length(),
                                isChunkMergerError ? "ChunkMerger分片合并失败" : error.getClass().getSimpleName());
                        triggerTitleGenerationIfFirstRound(sessionId);
                        return Flux.empty();
                    }
                    // 无内容输出：向前端发送友好错误提示
                    String userMsg = extractFriendlyError(error);
                    if (isChunkMergerError) {
                        log.warn("模型分片合并失败（无内容输出）: sessionId={}, 原因: {}", sessionId, userMsg);
                    } else {
                        log.error("SSE 流式响应异常: sessionId={}, 原因: {}", sessionId, userMsg, error);
                    }
                    triggerTitleGenerationIfFirstRound(sessionId);
                    return Flux.just(
                            ServerSentEvent.<Object>builder()
                                    .data(Map.of("error", userMsg))
                                    .build()
                    );
                })
                .concatWith(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data("[DONE]")
                                .build()
                ));
    }

    /**
     * 历史消息查询端点
     *
     * 请求: GET /chat/history?sessionId=xxx
     * 响应: [{"role":"user","content":"..."},...]
     *
     * 从 SessionService 获取事件列表，转换为前端期望的格式
     */
    @GetMapping("/chat/history")
    public List<Map<String, String>> getHistory(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }

        List<SessionEvent> events = sessionService.getEvents(sessionId);
        return events.stream()
                .filter(event -> !event.isSynthetic())
                .map(event -> {
                    Message msg = event.getMessage();
                    String role = (msg instanceof UserMessage) ? "user" : "assistant";
                    String content = msg.getText();
                    return Map.of("role", role, "content", content != null ? content : "");
                })
                .toList();
    }

    /**
     * 判断是否为流式分片合并异常（Spring AI ChunkMerger 内部 bug）
     */
    private boolean isStreamAggregationError(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof NoSuchElementException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && (msg.contains("ChunkMerger") || msg.contains("Aggregation"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 将底层异常转换为用户友好的错误提示
     */
    private String extractFriendlyError(Throwable error) {
        // 先按异常类型检测（遍历整个 cause 链）
        if (isStreamAggregationError(error)) {
            return "模型响应分片异常（工具调用格式不兼容），请重新提问或新建会话。";
        }

        // 再按消息文本检测（遍历整个 cause 链收集所有消息）
        String allMsgs = collectCauseMessages(error);

        // 模型服务端返回 400（通常是畸形 tool call 污染历史）
        if (allMsgs.contains("400") || allMsgs.contains("BadRequest")) {
            return "模型服务返回错误，可能是上下文异常。请尝试新建会话重试。";
        }
        // 网络超时
        if (allMsgs.contains("timeout") || allMsgs.contains("Stream failed")) {
            return "模型响应超时，请稍后重试。";
        }
        // JSON 解析失败
        if (allMsgs.contains("Conversion from JSON") || allMsgs.contains("Unexpected end-of-input")) {
            return "模型工具调用格式异常，已自动跳过。请重新提问。";
        }
        // 默认：取第一个非空消息
        String firstMsg = firstNonBlankMessage(error);
        return "服务异常: " + firstMsg;
    }

    /**
     * 收集异常链中所有消息文本（用于模式匹配）
     */
    private String collectCauseMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable t = error;
        while (t != null) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" | ");
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    /**
     * 获取异常链中第一个非空消息
     */
    private String firstNonBlankMessage(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                return t.getMessage();
            }
            t = t.getCause();
        }
        return error.getClass().getSimpleName();
    }

    // ===== 私有辅助方法 =====

    /**
     * 首轮对话完成后（事件数 == 2：1条user + 1条assistant），异步触发 AI 摘要标题生成
     */
    private void triggerTitleGenerationIfFirstRound(String sessionId) {
        try {
            List<SessionEvent> events = sessionService.getEvents(sessionId);
            List<SessionEvent> realEvents = events.stream()
                    .filter(e -> !e.isSynthetic())
                    .toList();

            if (realEvents.size() == 2) {
                String userMsg = realEvents.stream()
                        .filter(e -> e.getMessage() instanceof UserMessage)
                        .map(e -> e.getMessage().getText())
                        .findFirst().orElse("");
                String assistantMsg = realEvents.stream()
                        .filter(e -> e.getMessage() instanceof AssistantMessage)
                        .map(e -> e.getMessage().getText())
                        .findFirst().orElse("");

                if (!userMsg.isEmpty()) {
                    sessionTitleGenerator.generateTitleAsync(sessionId, userMsg, assistantMsg);
                }
            }
        } catch (Exception e) {
            log.warn("触发标题生成失败: sessionId={}", sessionId, e);
        }
    }
}
