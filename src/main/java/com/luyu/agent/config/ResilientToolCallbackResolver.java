package com.luyu.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * 容错 ToolCallbackResolver
 * 
 * 包装原始 resolver，当模型返回空/null 工具名时不抛异常，
 * 而是返回一个 fallback ToolCallback，其 call() 返回错误提示给模型。
 * 
 * 典型场景：glm-5.2 等非 OpenAI 原生模型偶尔生成 name 为空的 tool_call，
 * 导致 DelegatingToolCallbackResolver 抛出 IllegalArgumentException。
 */
public class ResilientToolCallbackResolver implements ToolCallbackResolver {

    private static final Logger log = LoggerFactory.getLogger(ResilientToolCallbackResolver.class);

    private final ToolCallbackResolver delegate;

    /** 空名称时的 fallback 回调 */
    private static final ToolCallback FALLBACK_CALLBACK = new ToolCallback() {
        private static final ToolDefinition DEF = DefaultToolDefinition.builder()
                .name("__fallback__")
                .description("fallback for malformed tool calls")
                .inputSchema("{}")
                .build();

        @Override
        public ToolDefinition getToolDefinition() { return DEF; }

        @Override
        public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }

        @Override
        public String call(String toolInput) {
            return "工具调用失败：你生成的工具名称为空，请检查并重新生成有效的工具调用。";
        }
    };

    public ResilientToolCallbackResolver(ToolCallbackResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolCallback resolve(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            log.warn("模型返回空工具名，使用 fallback 回调（不中断流）");
            return FALLBACK_CALLBACK;
        }
        ToolCallback resolved = delegate.resolve(toolName);
        if (resolved == null) {
            log.warn("未找到工具 [{}]，使用 fallback 回调", toolName);
            return FALLBACK_CALLBACK;
        }
        return resolved;
    }
}
