package com.luyu.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

/**
 * {@link TenantScopedTaskCallback} 单元测试（tasks 10.5 / design D5「子代理跳1」）。
 * <p>
 * 验证单跳桥：子代理 {@code task} 工具在执行线程从 {@link ToolContext} 恢复发起租户到
 * {@link TenantContext}（供跳2 的 {@code RateLimitingSubagentExecutor} 捕获、最终由
 * {@code MeteringAdvisor} 归账），且遵循「已有上下文不覆盖不清理、无租户不阻断委派」语义。
 */
class TenantScopedTaskCallbackTest {

    /** 记录委派执行期间线程上的租户，模拟子代理执行体读 ThreadLocal 归账。 */
    private static final class RecordingDelegate implements ToolCallback {

        private final ToolDefinition definition = DefaultToolDefinition.builder()
                .name("task").description("委派子代理").inputSchema("{\"type\":\"object\"}").build();

        String tenantDuringCall;
        boolean presentDuringCall;
        int callCount;
        String lastInput;

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, @Nullable ToolContext toolContext) {
            this.lastInput = toolInput;
            this.callCount++;
            this.tenantDuringCall = TenantContext.getTenantId();
            this.presentDuringCall = TenantContext.isPresent();
            return "子代理执行完成";
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static ToolContext contextWithTenant(String tenantId) {
        return new ToolContext(Map.of(TenantContext.TOOL_CONTEXT_KEY, tenantId));
    }

    @Test
    void 无外层上下文_从ToolContext恢复发起租户并在调用后清理() {
        RecordingDelegate delegate = new RecordingDelegate();
        TenantScopedTaskCallback callback = new TenantScopedTaskCallback(delegate);

        String result = callback.call("{\"q\":\"x\"}", contextWithTenant("t-a"));

        assertThat(result).isEqualTo("子代理执行完成");
        // 委派执行期间子代理线程已持有发起租户 → MeteringAdvisor 可归账到 t-a
        assertThat(delegate.tenantDuringCall).isEqualTo("t-a");
        assertThat(delegate.presentDuringCall).isTrue();
        // 调用结束后清理，避免线程池复用串号
        assertThat(TenantContext.isPresent()).isFalse();
    }

    @Test
    void 已有外层上下文_不覆盖不清理() {
        // 同步非流式主聊天的工具调用发生在请求线程，TenantContext 已由 JwtTenantFilter 建立
        TenantContext.set("u-outer", "t-outer");
        RecordingDelegate delegate = new RecordingDelegate();
        TenantScopedTaskCallback callback = new TenantScopedTaskCallback(delegate);

        callback.call("{\"q\":\"x\"}", contextWithTenant("t-inner"));

        // 不被 ToolContext 的 t-inner 覆盖，仍是外层 t-outer
        assertThat(delegate.tenantDuringCall).isEqualTo("t-outer");
        // 非本层建立，故不清理（交由 Filter 的 finally 清理）
        assertThat(TenantContext.getTenantId()).isEqualTo("t-outer");
        assertThat(TenantContext.getUserId()).isEqualTo("u-outer");
    }

    @Test
    void ToolContext缺租户_不阻断委派仅无租户可归账() {
        RecordingDelegate delegate = new RecordingDelegate();
        TenantScopedTaskCallback callback = new TenantScopedTaskCallback(delegate);

        // 与 TenantScopedMemoryCallback 不同：取不到租户不拒绝（委派是模型自主行为，硬拒会破坏对话）
        String result = callback.call("{\"q\":\"x\"}", new ToolContext(Map.of("other", "v")));

        assertThat(result).isEqualTo("子代理执行完成");
        assertThat(delegate.callCount).isEqualTo(1);
        assertThat(delegate.tenantDuringCall).isNull();
        assertThat(TenantContext.isPresent()).isFalse();
    }

    @Test
    void 无ToolContext重载_直接委派() {
        RecordingDelegate delegate = new RecordingDelegate();
        TenantScopedTaskCallback callback = new TenantScopedTaskCallback(delegate);

        String result = callback.call("{\"q\":\"x\"}");

        assertThat(result).isEqualTo("子代理执行完成");
        assertThat(delegate.callCount).isEqualTo(1);
        assertThat(delegate.lastInput).isEqualTo("{\"q\":\"x\"}");
    }

    @Test
    void 工具定义与元数据透传底层() {
        RecordingDelegate delegate = new RecordingDelegate();
        TenantScopedTaskCallback callback = new TenantScopedTaskCallback(delegate);
        assertThat(callback.getToolDefinition().name()).isEqualTo("task");
        assertThat(callback.getToolMetadata()).isEqualTo(delegate.getToolMetadata());
    }
}
