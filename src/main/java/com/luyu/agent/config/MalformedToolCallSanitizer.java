package com.luyu.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 畸形 Tool Call 净化 Advisor
 * 
 * 双向净化：在请求前清洗历史中的畸形 tool call，在响应后清洗模型新生成的畸形 tool call。
 * 
 * before()：清洗 SessionMemoryAdvisor 加载的会话历史
 *   - AssistantMessage 中全部 tool call 名称为空 → 替换为纯文本，移除后续 ToolResponseMessage
 *   - AssistantMessage 中部分 tool call 名称为空 → 仅保留有效的，同步精简 ToolResponseMessage
 *   - 孤立的 ToolResponseMessage（前一条不是含 tool call 的 AssistantMessage）→ 移除
 * 
 * after()：清洗模型实时响应
 *   - 全部 tool call 名称为空 → 替换为纯文本，阻止 ToolCallingAdvisor 执行
 */
public class MalformedToolCallSanitizer implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MalformedToolCallSanitizer.class);

    /** 工具调用调试日志（独立文件 toolcall.log） */
    private static final Logger toolCallLog = LoggerFactory.getLogger("toolcall");

    /** 内层 advisor：after() 先于 ToolCallingAdvisor 看到模型原始响应 */
    private static final int ORDER = 350;

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ==================== 请求阶段：清洗会话历史 ====================

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        Prompt prompt = request.prompt();
        if (prompt == null) {
            return request;
        }
        List<Message> messages = prompt.getInstructions();
        if (messages == null || messages.isEmpty()) {
            return request;
        }

        boolean modified = false;
        List<Message> sanitized = new ArrayList<>(messages.size());

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
                List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
                // 先尝试合并碎片化参数（GLM-5.2 会把一个 tool call 的 args 拆成多个条目）
                List<AssistantMessage.ToolCall> mergedCalls = mergeFragmentedToolCalls(toolCalls);
                long malformedCount = mergedCalls.stream()
                        .filter(tc -> tc.name() == null || tc.name().isBlank())
                        .count();

                if (malformedCount > 0 || mergedCalls.size() != toolCalls.size()) {
                    List<AssistantMessage.ToolCall> validCalls = mergedCalls.stream()
                            .filter(tc -> tc.name() != null && !tc.name().isBlank())
                            .toList();

                    if (validCalls.isEmpty()) {
                        // 全部畸形 → 替换为纯文本
                        String text = am.getText();
                        if (text == null || text.isBlank()) {
                            text = "(工具调用已跳过)";
                        }
                        sanitized.add(new AssistantMessage(text));
                        log.warn("会话历史: AssistantMessage 全部 {} 个 tool call 畸形，已替换为纯文本", malformedCount);

                        // 移除后续 ToolResponseMessage
                        if (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage) {
                            i++;
                            log.warn("会话历史: 同步移除对应的 ToolResponseMessage");
                        }
                    } else {
                        // 部分畸形 → 仅保留有效的
                        sanitized.add(AssistantMessage.builder()
                                .content(am.getText())
                                .toolCalls(validCalls)
                                .build());
                        log.warn("会话历史: AssistantMessage 中 {} 个 tool call 畸形，已移除，保留 {} 个",
                                malformedCount, validCalls.size());

                        // 精简后续 ToolResponseMessage（移除对应的响应）
                        if (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage trm) {
                            List<String> validIds = validCalls.stream()
                                    .map(AssistantMessage.ToolCall::id)
                                    .toList();
                            List<ToolResponseMessage.ToolResponse> validResponses = trm.getResponses().stream()
                                    .filter(r -> validIds.contains(r.id()))
                                    .toList();
                            if (validResponses.size() < trm.getResponses().size()) {
                                i++;
                                sanitized.add(ToolResponseMessage.builder()
                                        .responses(validResponses)
                                        .build());
                                log.warn("会话历史: 同步精简 ToolResponseMessage ({} -> {})",
                                        trm.getResponses().size(), validResponses.size());
                            }
                        }
                    }
                    modified = true;
                    continue;
                }
            }

            // 检测孤立的 ToolResponseMessage（前一条不是含 tool call 的 AssistantMessage）
            if (msg instanceof ToolResponseMessage trm) {
                Message prev = sanitized.isEmpty() ? null : sanitized.get(sanitized.size() - 1);
                if (!(prev instanceof AssistantMessage pa && pa.hasToolCalls())) {
                    log.warn("会话历史: 检测到孤立的 ToolResponseMessage，已移除");
                    modified = true;
                    continue;
                }
            }

            sanitized.add(msg);
        }

        if (modified) {
            Prompt cleanPrompt = new Prompt(sanitized, prompt.getOptions());
            return request.mutate()
                    .prompt(cleanPrompt)
                    .build();
        }
        return request;
    }

    // ==================== 响应阶段：清洗模型实时响应 ====================

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResults() == null) {
            return response;
        }

        List<Generation> results = chatResponse.getResults();
        boolean modified = false;
        Generation[] sanitized = new Generation[results.size()];

        for (int i = 0; i < results.size(); i++) {
            Generation gen = results.get(i);
            AssistantMessage msg = gen.getOutput();

            if (msg != null && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                // 先尝试合并碎片化参数
                List<AssistantMessage.ToolCall> mergedCalls = mergeFragmentedToolCalls(msg.getToolCalls());

                // 记录合并后的 tool call（只记录有 name 的）
                for (AssistantMessage.ToolCall tc : mergedCalls) {
                    if (tc.name() != null && !tc.name().isBlank()) {
                        toolCallLog.debug("模型ToolCall: name={}, args={}, id={}",
                                tc.name(), tc.arguments(), tc.id());
                    }
                }

                boolean allMalformed = mergedCalls.stream()
                        .allMatch(tc -> tc.name() == null || tc.name().isBlank());

                if (allMalformed) {
                    log.warn("模型响应: 全部 tool call 名称畸形，已清除，阻止工具执行循环");
                    String text = msg.getText();
                    if (text == null || text.isBlank()) {
                        text = "抱歉，我刚才的工具调用格式有误，让我直接回答你的问题。";
                    }
                    AssistantMessage cleanMsg = new AssistantMessage(text);
                    sanitized[i] = new Generation(cleanMsg, gen.getMetadata());
                    modified = true;
                } else if (mergedCalls.size() != msg.getToolCalls().size()) {
                    // 合并后 tool call 数量变化 → 用合并后的替换原始
                    AssistantMessage cleanMsg = AssistantMessage.builder()
                            .content(msg.getText())
                            .toolCalls(mergedCalls)
                            .build();
                    sanitized[i] = new Generation(cleanMsg, gen.getMetadata());
                    modified = true;
                } else {
                    sanitized[i] = gen;
                }
            } else {
                sanitized[i] = gen;
            }
        }

        if (modified) {
            ChatResponse cleanResponse = new ChatResponse(List.of(sanitized), chatResponse.getMetadata());
            return ChatClientResponse.builder()
                    .chatResponse(cleanResponse)
                    .context(response.context())
                    .build();
        }
        return response;
    }

    // ==================== 碎片化参数合并 ====================

    /**
     * 合并碎片化 tool call 参数。
     * <p>
     * GLM-5.2 在单次响应中会将一个 tool call 的参数拆成多个 tool call 条目：
     * 第 1 个有 name 和 id，args 只有部分（如 {@code "{"}）；
     * 后续条目 name 为空、id 为空，args 是参数的后续片段
     * （如 {@code "\"path\": "}, {@code "\"MEMORY.md\""}, {@code "}"}）。
     * <p>
     * 本方法将这些碎片合并为完整的 tool call，使 ToolCallingAdvisor 能正确执行。
     *
     * @param toolCalls 模型返回的原始 tool call 列表
     * @return 合并后的 tool call 列表（可能数量减少）
     */
    private List<AssistantMessage.ToolCall> mergeFragmentedToolCalls(
            List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls.size() <= 1) {
            return toolCalls;
        }

        boolean hasFragments = toolCalls.stream()
                .anyMatch(tc -> tc.name() == null || tc.name().isBlank());
        if (!hasFragments) {
            return toolCalls;
        }

        List<AssistantMessage.ToolCall> merged = new ArrayList<>();
        String curId = null;
        String curType = null;
        String curName = null;
        StringBuilder curArgs = null;

        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (tc.name() != null && !tc.name().isBlank()) {
                // 有 name 的条目 — 先完成之前正在累积的
                if (curName != null) {
                    merged.add(new AssistantMessage.ToolCall(
                            curId, curType, curName, curArgs.toString()));
                }
                curId = tc.id();
                curType = tc.type();
                curName = tc.name();
                curArgs = new StringBuilder(
                        tc.arguments() != null ? tc.arguments() : "");
            } else {
                // 空 name 的碎片 — 追加 args 到当前累积
                if (curArgs != null && tc.arguments() != null
                        && !tc.arguments().isEmpty()) {
                    curArgs.append(tc.arguments());
                }
            }
        }
        if (curName != null) {
            merged.add(new AssistantMessage.ToolCall(
                    curId, curType, curName, curArgs.toString()));
        }

        if (merged.size() != toolCalls.size()) {
            log.info("工具调用参数合并: {} 个原始 -> {} 个合并后",
                    toolCalls.size(), merged.size());
            for (AssistantMessage.ToolCall tc : merged) {
                toolCallLog.debug("合并后ToolCall: name={}, args={}, id={}",
                        tc.name(), tc.arguments(), tc.id());
            }
        }
        return merged;
    }
}
