package com.luyu.agent.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/**
 * 子代理租户透传「跳1」（tasks 6.4 / design D5）。
 * <p>
 * 装饰 {@code task} ToolCallback：在工具执行线程从 {@link ToolContext} 取出 tenantId
 * （{@link TenantContext#TOOL_CONTEXT_KEY}，由主聊天/RPG 控制器在请求发起时放入），
 * {@code TenantContext.set(...)} 到当前线程，使随后 {@code RateLimitingSubagentExecutor.execute()}
 * （跳2）能在 {@code worker.submit()} 前捕获到租户，并最终让子代理 client 上的
 * {@code MeteringAdvisor} 经 ThreadLocal 归账到发起租户。
 * <p>
 * 与 {@link TenantScopedMemoryCallback} 的区别：后者取不到租户即<b>拒绝执行</b>（防孤儿文件），
 * 本包装器取不到租户<b>不阻断</b>子代理（委派是模型自主行为，硬拒会破坏对话），仅记 {@code <missing>}
 * 由计量侧告警。若当前线程已存在租户上下文（同步非流式主聊天的工具调用在请求线程），
 * 则<b>不覆盖、不清理</b>（同 {@code GameLoopService.finalizeTurn} 的恢复语义）。
 */
public class TenantScopedTaskCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(TenantScopedTaskCallback.class);

    private final ToolCallback delegate;

    public TenantScopedTaskCallback(ToolCallback delegate) {
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
        // 无 ToolContext：无法恢复租户，直接委派（计量侧记 <missing> 告警）
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String tenantId = resolveTenantId(toolContext);
        boolean restored = false;
        if (!TenantContext.isPresent() && tenantId != null && !tenantId.isBlank()) {
            TenantContext.set(tenantId, tenantId);
            restored = true;
        } else if (tenantId == null || tenantId.isBlank()) {
            log.warn("task 工具调用缺少 {}，子代理用量将记为 <missing>", TenantContext.TOOL_CONTEXT_KEY);
        }
        try {
            return delegate.call(toolInput, toolContext);
        } finally {
            if (restored) {
                TenantContext.clear();
            }
        }
    }

    private String resolveTenantId(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(TenantContext.TOOL_CONTEXT_KEY);
        return value == null ? null : value.toString();
    }
}
