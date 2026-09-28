package com.luyu.agent.controller;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.config.PostEmissionStreamException;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.service.ContextInfo;
import com.luyu.agent.service.ContextInfoService;
import com.luyu.agent.service.ImageUploadService;
import com.luyu.agent.service.SessionTitleGenerator;
import com.luyu.agent.tenancy.SessionTenantGuard;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Reactor context 中存放工具事件 Sink 的 key（与 ResilientToolCallback 一致） */
    private static final String TOOL_EVENT_SINK_KEY = "toolEventSink";

    private final ChatClientRegistry chatClientRegistry;
    private final SessionService sessionService;
    private final SessionTitleGenerator sessionTitleGenerator;
    private final ContextInfoService contextInfoService;
    private final ImageUploadService imageUploadService;
    private final SessionTenantGuard sessionTenantGuard;
    private final ContentModerationGate moderationGate;
    private final QuotaGuard quotaGuard;

    /** 已触发过标题生成的 sessionId 集合，避免重复触发 */
    private final Set<String> titleTriggeredSessions = ConcurrentHashMap.newKeySet();

    public ChatStreamController(ChatClientRegistry chatClientRegistry,
                                SessionService sessionService,
                                SessionTitleGenerator sessionTitleGenerator,
                                ContextInfoService contextInfoService,
                                ImageUploadService imageUploadService,
                                SessionTenantGuard sessionTenantGuard,
                                ContentModerationGate moderationGate,
                                QuotaGuard quotaGuard) {
        this.chatClientRegistry = chatClientRegistry;
        this.sessionService = sessionService;
        this.sessionTitleGenerator = sessionTitleGenerator;
        this.contextInfoService = contextInfoService;
        this.imageUploadService = imageUploadService;
        this.sessionTenantGuard = sessionTenantGuard;
        this.moderationGate = moderationGate;
        this.quotaGuard = quotaGuard;
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
    public Flux<ServerSentEvent<Object>> streamChat(@RequestBody Map<String, String> request,
                                                      @RequestParam(value = "model", required = false) String model) {
        String message = request.get("message");
        String sessionId = request.get("sessionId");
        String imageBase64 = request.get("imageBase64");

        // 请求线程捕获租户 ID（SSE 订阅/工具回调可能切线程，ThreadLocal 不可靠）。
        // USER_ID_CONTEXT_KEY（"chat_memory_user_id"）：SessionMemoryAdvisor 原生支持
        // 请求级 userId 覆盖，非空则覆盖构建期 defaultUserId —— 记忆文件按租户隔离（第 4 层）。
        final String tenantId = TenantContext.requireTenantId();

        // 跨租户会话守卫（session-isolation spec）：会话存在且归属他租户 → 表现为不存在，
        // 拒绝发消息（否则事件/标题/图片会写进他租户会话）；会话不存在不拦（advisor 自动建）
        if (sessionTenantGuard.isForeign(sessionId)) {
            log.warn("跨租户会话访问被拒: sessionId={}, tenant={}", sessionId, tenantId);
            return Flux.just(ServerSentEvent.<Object>builder()
                    .data(Map.of("error", "会话不存在或无权访问"))
                    .build());
        }

        // 输入内容审查闸门（input-content-moderation）：调模型前同步审查用户输入原文。
        // 命中即整条拒答 + 中止，且在 SessionMemoryAdvisor 生效前提前返回 ——
        // 被拒输入不进入会话持久化链路，不写 AI_SESSION（spec「命中输入不落库」）。
        ModerationResult moderation = moderationGate.check(message);
        if (moderation.blocked()) {
            return Flux.just(
                    ServerSentEvent.<Object>builder()
                            .data(Map.of("content", moderationGate.refusalMessage()))
                            .build(),
                    ServerSentEvent.<Object>builder()
                            .data("[DONE]")
                            .build());
        }

        // 配额硬拒（tenant-token-metering / design D8）：用户发起型入口起流前事前检查。
        // 超限以 SSE error 事件返回友好提示（不进入模型调用）；配额开关关闭时 QuotaGuard 直接放行。
        try {
            quotaGuard.check(tenantId);
        } catch (QuotaExceededException qe) {
            log.info("配额超限拒绝聊天: sessionId={}, tenant={}", sessionId, tenantId);
            return Flux.just(
                    ServerSentEvent.<Object>builder()
                            .data(Map.of("error", qe.getMessage()))
                            .build(),
                    ServerSentEvent.<Object>builder()
                            .data("[DONE]")
                            .build());
        }

        // 请求级模型路由：缺省取 default，未知模型返回 400 提示
        final ChatClient chatClient;
        try {
            chatClient = chatClientRegistry.forChat(model);
        } catch (IllegalArgumentException e) {
            return Flux.just(ServerSentEvent.<Object>builder()
                    .data(Map.of("error", e.getMessage()))
                    .build());
        }

        // tool call 历史守卫【已禁用】：允许任意会话、任意时刻自由切换到任意模型，不再拦截或提示。
        // 保留原逻辑（注释形式）以便日后需要时快速恢复。注意：切到不支持 GLM tool call 格式的
        // 本地模型（如 local/qwen）且会话已有工具调用历史时，请求会真实下发给该模型并可能运行时报错。
        // boolean isNonDefaultModel = model != null && !model.isBlank()
        //         && !model.equals(chatClientRegistry.getDefaultName());
        // if (isNonDefaultModel && hasToolCallHistory(sessionId)) {
        //     return Flux.just(ServerSentEvent.<Object>builder()
        //             .data(Map.of("error", "当前会话已有工具调用历史，本地模型不兼容，请使用云端模型或新建会话"))
        //             .build());
        // }

        // 用于拼接完整回复文本（中止时持久化部分回复）
        StringBuilder fullResponse = new StringBuilder();

        // 工具事件 Sink：通过 Reactor context 传递给 ResilientToolCallback，
        // 在工具调用前后向 SSE 流注入 tool_start / tool_end 事件
        Sinks.Many<Map<String, String>> toolEventSink = Sinks.many().unicast().onBackpressureBuffer();

        // 获取当前消息索引（用于历史回显时匹配图片）
        int messageIndex = sessionService.getEvents(sessionId).size();

        // 如果有图片，保存到本地并构造多模态 UserMessage
        String imagePath = null;
        if (imageBase64 != null && !imageBase64.isBlank()) {
            try {
                imagePath = imageUploadService.saveBase64Image(imageBase64, sessionId, messageIndex);
            } catch (Exception e) {
                log.warn("图片保存失败，继续纯文本对话: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }

        final String savedImagePath = imagePath;

        // 文本 token 流：通过 advisor context 传入 sessionId，
        // .contextWrite 将 Sink 注入 Reactor context 供 ToolCallingAdvisor 读取
        Flux<ServerSentEvent<Object>> contentFlux;
        if (savedImagePath != null) {
            // 多模态消息：文本 + 图片
            final org.springframework.util.MimeType mimeType = imageUploadService.resolveMimeType(savedImagePath);
            final java.io.File imageFile = new java.io.File(savedImagePath);
            UserMessage userMessage = UserMessage.builder()
                    .text(message)
                    .media(new Media(mimeType, new FileSystemResource(imageFile)))
                    .build();
            contentFlux = chatClient.prompt()
                    .messages(userMessage)
                    .toolContext(java.util.Map.of(TenantContext.TOOL_CONTEXT_KEY, tenantId))
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, tenantId)
                            .param(com.luyu.agent.metering.MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "chat"))
                    .stream()
                    .content()
                    .map(token -> {
                        fullResponse.append(token);
                        return ServerSentEvent.<Object>builder()
                                .data(Map.of("content", token))
                                .build();
                    })
                    .doOnCancel(() -> {
                        if (fullResponse.length() > 0) {
                            sessionService.appendMessage(sessionId,
                                    new AssistantMessage(fullResponse.toString()));
                            log.info("流式中止，已保存部分回复: sessionId={}, length={}",
                                    sessionId, fullResponse.length());
                        }
                        triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                    })
                    .doOnComplete(() -> {
                        triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                    })
                    .onErrorResume(error -> {
                        boolean isChunkMergerError = isStreamAggregationError(error);
                        if (fullResponse.length() > 0) {
                            sessionService.appendMessage(sessionId,
                                    new AssistantMessage(fullResponse.toString()));
                            log.warn("流式异常但已保存部分回复: sessionId={}, length={}, 原因: {}",
                                    sessionId, fullResponse.length(),
                                    isChunkMergerError ? "ChunkMerger分片合并失败" : error.getClass().getSimpleName());
                            triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                            return Flux.empty();
                        }
                        String userMsg = extractFriendlyError(error);
                        if (isChunkMergerError) {
                            log.warn("模型分片合并失败（无内容输出）: sessionId={}, 原因: {}", sessionId, userMsg);
                        } else {
                            log.error("SSE 流式响应异常: sessionId={}, 原因: {}", sessionId, userMsg, error);
                        }
                        triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                        return Flux.just(
                                ServerSentEvent.<Object>builder()
                                        .data(Map.of("error", userMsg))
                                        .build()
                        );
                    })
                    .doFinally(signal -> toolEventSink.tryEmitComplete())
                    .contextWrite(ctx -> ctx.put(TOOL_EVENT_SINK_KEY, toolEventSink));
        } else {
            contentFlux = chatClient.prompt()
                    .user(message)
                    .toolContext(java.util.Map.of(TenantContext.TOOL_CONTEXT_KEY, tenantId))
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId)
                            .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, tenantId)
                            .param(com.luyu.agent.metering.MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "chat"))
                    .stream()
                    .content()
                    .map(token -> {
                        fullResponse.append(token);
                        return ServerSentEvent.<Object>builder()
                                .data(Map.of("content", token))
                                .build();
                    })
                    .doOnCancel(() -> {
                        if (fullResponse.length() > 0) {
                            sessionService.appendMessage(sessionId,
                                    new AssistantMessage(fullResponse.toString()));
                            log.info("流式中止，已保存部分回复: sessionId={}, length={}",
                                    sessionId, fullResponse.length());
                        }
                        triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                    })
                    .doOnComplete(() -> {
                        triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                    })
                    .onErrorResume(error -> {
                        boolean isChunkMergerError = isStreamAggregationError(error);
                        if (fullResponse.length() > 0) {
                            sessionService.appendMessage(sessionId,
                                    new AssistantMessage(fullResponse.toString()));
                            log.warn("流式异常但已保存部分回复: sessionId={}, length={}, 原因: {}",
                                    sessionId, fullResponse.length(),
                                    isChunkMergerError ? "ChunkMerger分片合并失败" : error.getClass().getSimpleName());
                            triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                            return Flux.empty();
                        }
                        String userMsg = extractFriendlyError(error);
                        if (isChunkMergerError) {
                            log.warn("模型分片合并失败（无内容输出）: sessionId={}, 原因: {}", sessionId, userMsg);
                        } else {
                            log.error("SSE 流式响应异常: sessionId={}, 原因: {}", sessionId, userMsg, error);
                        }
                        triggerTitleGenerationIfFirstRound(sessionId, tenantId);
                        return Flux.just(
                                ServerSentEvent.<Object>builder()
                                        .data(Map.of("error", userMsg))
                                        .build()
                        );
                    })
                    .doFinally(signal -> toolEventSink.tryEmitComplete())
                    .contextWrite(ctx -> ctx.put(TOOL_EVENT_SINK_KEY, toolEventSink));
        }

        // 工具事件流：将 Sink 中的 Map 事件转为 SSE
        Flux<ServerSentEvent<Object>> toolEventFlux = toolEventSink.asFlux()
                .map(event -> ServerSentEvent.<Object>builder().data(event).build());

        // 合并文本 token 流与工具事件流，末尾推送 [DONE]
        return Flux.merge(contentFlux, toolEventFlux)
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
     * 响应: [{"role":"user","content":"...","synthetic":false},...]
     *
     * 合成事件（压缩摘要）以 synthetic=true 标记返回，不再过滤
     */
    @GetMapping("/chat/history")
    public List<Map<String, Object>> getHistory(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        // 跨租户会话历史不可达：404，不泄露他租户会话存在性
        if (sessionTenantGuard.isForeign(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
        }

        List<SessionEvent> events = sessionService.getEvents(sessionId);
        return java.util.stream.IntStream.range(0, events.size())
                .mapToObj(i -> {
                    SessionEvent event = events.get(i);
                    Message msg = event.getMessage();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    String content = msg.getText();
                    entry.put("content", content != null ? content : "");

                    if (event.isSynthetic()) {
                        entry.put("role", "synthetic");
                        entry.put("synthetic", true);
                    } else if (msg instanceof UserMessage) {
                        entry.put("role", "user");
                        // 尝试加载该消息对应的图片
                        String imageBase64 = imageUploadService.readImageAsBase64(sessionId, i);
                        if (imageBase64 != null) {
                            entry.put("imageBase64", imageBase64);
                        }
                    } else if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
                        // 工具调用事件：标记 type=tool_call，携带工具名+图标+描述
                        entry.put("role", "assistant");
                        entry.put("type", "tool_call");
                        entry.put("tools", am.getToolCalls().stream()
                                .map(tc -> {
                                    Map<String, String> tool = new LinkedHashMap<>();
                                    String name = tc.name() != null ? tc.name() : "";
                                    tool.put("name", name);
                                    tool.put("id", tc.id() != null ? tc.id() : "");
                                    tool.put("icon", resolveToolIcon(name));
                                    String desc = extractToolDescription(tc);
                                    if (desc != null && !desc.isBlank()) {
                                        tool.put("description", desc);
                                    }
                                    return tool;
                                })
                                .toList());
                    } else if (msg instanceof ToolResponseMessage trm) {
                        // 工具响应事件：标记 type=tool_response，前端跳过
                        entry.put("role", "assistant");
                        entry.put("type", "tool_response");
                        entry.put("tools", trm.getResponses().stream()
                                .map(r -> {
                                    Map<String, String> tool = new LinkedHashMap<>();
                                    tool.put("name", r.name() != null ? r.name() : "");
                                    tool.put("id", r.id() != null ? r.id() : "");
                                    return tool;
                                })
                                .toList());
                    } else {
                        entry.put("role", "assistant");
                    }
                    return entry;
                })
                .toList();
    }

    /**
     * 上下文计量信息端点
     *
     * 请求: GET /chat/context-info?sessionId=xxx
     * 响应: {totalTokens, maxTokens, usagePercent, messageTokens, toolTokens, compactionCount}
     */
    @GetMapping("/chat/context-info")
    public ContextInfo getContextInfo(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (sessionTenantGuard.isForeign(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问");
        }
        return contextInfoService.getContextInfo(sessionId);
    }

    /**
     * 可用模型列表端点（供前端模型选择器动态加载）
     */
    @GetMapping("/chat/models")
    public List<String> listModels() {
        return chatClientRegistry.listChatModels();
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
        // 先识别已发内容后的流式中断信号（StreamFallbackChatModel 守卫抛出，
        // 防御性兑底：正常情况下已发内容路径在上游已保存部分回复并静默结束）
        if (PostEmissionStreamException.findIn(error) != null) {
            return "模型流式响应中断（工具调用格式不兼容），已保留已生成的部分回复，请重新提问。";
        }
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
     * tool call 历史守卫：会话历史是否已含 tool call 事件。
     * <p>
     * 本地模型（qwen）不兼容 GLM 的 tool call 格式，一旦会话产生 tool call 事件，
     * 拒绝路由到本地模型（调整点 2：跨模型历史兼容性约束）。
     * <p>
     * 【当前已停用】streamChat 中的守卫调用已注释禁用，允许任意模型自由切换；
     * 此方法保留备用，日后恢复守卫时可直接复用。
     */
    @SuppressWarnings("unused")
    private boolean hasToolCallHistory(String sessionId) {
        try {
            List<SessionEvent> events = sessionService.getEvents(sessionId);
            return events.stream().anyMatch(e -> {
                if (e.isSynthetic()) {
                    return false;
                }
                Message msg = e.getMessage();
                if (msg instanceof AssistantMessage am) {
                    return am.getToolCalls() != null && !am.getToolCalls().isEmpty();
                }
                return msg instanceof ToolResponseMessage;
            });
        } catch (Exception e) {
            log.warn("读取会话历史失败，跳过 tool call 守卫: sessionId={}", sessionId, e);
            return false;
        }
    }

    /**
     * 首轮对话完成后异步触发 AI 摘要标题生成。
     * <p>
     * 判断"首轮"以用户消息数为依据（== 1），而非事件总数。
     * 原因：一轮对话若触发工具调用，Session 事件流会包含
     * tool call / tool response / 多条 assistant 等多个事件，
     * 事件总数远超 2，用 size == 2 判断会导致标题永不生成。
     */
    private void triggerTitleGenerationIfFirstRound(String sessionId, String tenantId) {
        try {
            // 去重：同一 session 只触发一次
            if (!titleTriggeredSessions.add(sessionId)) {
                return;
            }

            List<SessionEvent> events = sessionService.getEvents(sessionId);
            List<SessionEvent> realEvents = events.stream()
                    .filter(e -> !e.isSynthetic())
                    .toList();

            long userMsgCount = realEvents.stream()
                    .filter(e -> e.getMessage() instanceof UserMessage)
                    .count();
            if (userMsgCount != 1) {
                return;
            }

            String userMsg = realEvents.stream()
                    .filter(e -> e.getMessage() instanceof UserMessage)
                    .map(e -> e.getMessage().getText())
                    .findFirst().orElse("");
            if (userMsg.isEmpty()) {
                return;
            }

            // 取最后一条非空文本的 assistant 回复作为摘要素材
            // （工具调用场景下首个 AssistantMessage 可能是 tool call，文本为空）
            String assistantMsg = realEvents.stream()
                    .filter(e -> e.getMessage() instanceof AssistantMessage)
                    .map(e -> e.getMessage().getText())
                    .filter(t -> t != null && !t.isBlank())
                    .reduce((first, second) -> second)
                    .orElse("");

            sessionTitleGenerator.generateTitleAsync(sessionId, userMsg, assistantMsg, tenantId);
        } catch (Exception e) {
            log.warn("触发标题生成失败: sessionId={}", sessionId, e);
            titleTriggeredSessions.remove(sessionId);
        }
    }

    // ===== 工具卡片历史恢复辅助方法 =====

    /**
     * 根据工具名映射图标（与 ResilientToolCallback.getToolIcon 保持一致）
     */
    private String resolveToolIcon(String toolName) {
        if (toolName == null) return "\u2699\uFE0F";
        if (toolName.startsWith("Memory")) return "\uD83D\uDCDD";
        if (toolName.equals("Skill")) return "\uD83D\uDD27";
        if (toolName.equals("Task")) return "\uD83E\uDD16";
        return "\u2699\uFE0F";
    }

    /**
     * 从工具调用参数中提取简短描述（与 ResilientToolCallback.extractDescription 逻辑一致）
     * <p>
     * 依次尝试 JSON 字段: description, task, query, name, skill
     */
    private String extractToolDescription(AssistantMessage.ToolCall tc) {
        String args = tc.arguments();
        if (args == null || args.length() < 3) return null;
        String[] fields = {"description", "task", "query", "name", "skill"};
        for (String field : fields) {
            String pattern = "\"" + field + "\"\\s*:\\s*\"([^\"]+)\"";
            java.util.regex.Pattern r = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = r.matcher(args);
            if (m.find()) {
                String desc = m.group(1);
                return desc.length() > 80 ? desc.substring(0, 80) + "..." : desc;
            }
        }
        return null;
    }
}
