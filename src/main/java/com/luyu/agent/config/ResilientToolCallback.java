package com.luyu.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/**
 * 容错 ToolCallback 包装器
 * 
 * 包装原始 ToolCallback，在 call() 方法中捕获 JSON 反序列化异常，
 * 返回友好错误提示给模型（让模型有机会重试或降级回答），
 * 而非让异常传播导致整个 SSE 流中断。
 * 
 * 典型场景：glm-5.2 等非 OpenAI 原生模型在生成 tool call 参数时
 * 可能输出不完整的 JSON（缺少闭合括号），触发 Jackson 解析失败。
 */
public class ResilientToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ResilientToolCallback.class);

    /** 工具调用调试日志（独立文件 toolcall.log） */
    private static final Logger toolCallLog = LoggerFactory.getLogger("toolcall");

    private final ToolCallback delegate;

    public ResilientToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        toolCallLog.debug("工具[{}] 调用: input={}", getToolDefinition().name(), truncate(toolInput, 500));
        try {
            String result = delegate.call(toolInput);
            toolCallLog.debug("工具[{}] 返回: result={}", getToolDefinition().name(), truncate(result, 500));
            return result;
        } catch (Exception e) {
            return handleToolException(e, toolInput);
        }
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        toolCallLog.debug("工具[{}] 调用: input={}", getToolDefinition().name(), truncate(toolInput, 500));
        try {
            String result = delegate.call(toolInput, toolContext);
            toolCallLog.debug("工具[{}] 返回: result={}", getToolDefinition().name(), truncate(result, 500));
            return result;
        } catch (Exception e) {
            return handleToolException(e, toolInput);
        }
    }

    /**
     * 统一异常处理：检测 JSON 解析错误并返回精准重试提示，
     * 其他异常返回通用错误降级消息。
     *
     * 注意：MethodToolCallback 内部可能将 IllegalStateException 包装成
     * 其他异常类型重新抛出（先 log 再 rethrow），因此不能仅靠
     * catch(IllegalStateException) 捕获，必须在 catch(Exception) 中
     * 也检测 cause 链是否包含 JSON 解析错误。
     */
    private String handleToolException(Exception e, String toolInput) {
        if (isJsonParseError(e)) {
            log.warn("工具 [{}] 参数 JSON 解析失败（模型输出截断），返回重试提示。原始输入: {}",
                    getToolDefinition().name(), truncate(toolInput, 200));
            return "工具调用失败：你提供的参数 JSON 格式不完整，请检查并重新生成完整的 JSON 参数后重试。";
        }
        log.warn("工具 [{}] 执行异常: {}", getToolDefinition().name(), e.getMessage());
        return "工具执行出错：" + e.getMessage() + "。请尝试换一种方式回答用户的问题。";
    }

    private boolean isJsonParseError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage();
            if (msg != null && (msg.contains("Conversion from JSON") ||
                    msg.contains("Unexpected end-of-input") ||
                    msg.contains("Unrecognized token") ||
                    msg.contains("JsonParseException") ||
                    msg.contains("JsonMappingException"))) {
                return true;
            }
            String className = cause.getClass().getName();
            if (className.contains("JsonParseException") ||
                    className.contains("JsonMappingException") ||
                    className.contains("UnexpectedEndOfInput")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
