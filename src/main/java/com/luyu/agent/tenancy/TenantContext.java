package com.luyu.agent.tenancy;

/**
 * 请求级租户上下文（ThreadLocal）。
 * <p>
 * 由 {@link JwtTenantFilter} 在请求进入业务代码前建立、请求结束时清理。
 * 同步调用链直接读取；SSE 流式 / 异步线程切换场景需经 ToolContext 等
 * 显式载体透传（同 gameStateId 模式），再在执行线程恢复。
 * <p>
 * 设计铁律：租户身份只来自验签后的 JWT。缺失时 {@link #requireTenantId()}
 * 快速失败，绝不静默回落默认租户。
 */
public final class TenantContext {

    /**
     * ToolContext 中携带租户 ID 的 key（SSE 流式 / 异步线程切换场景的显式载体，
     * 同 gameStateId 透传模式）：请求线程捕获后随 TurnContext 传播，执行线程恢复。
     */
    public static final String TOOL_CONTEXT_KEY = "tenantId";

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 建立上下文（Filter 专用；参数已由 JWT 验签保证合法） */
    public static void set(String userId, String tenantId) {
        USER_ID.set(userId);
        TENANT_ID.set(tenantId);
    }

    /** 清理上下文（Filter 的 finally 块调用，防线程池复用串号） */
    public static void clear() {
        USER_ID.remove();
        TENANT_ID.remove();
    }

    /** 当前租户 ID；无上下文时返回 null */
    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /** 当前用户 ID；无上下文时返回 null */
    public static String getUserId() {
        return USER_ID.get();
    }

    /**
     * 要求租户上下文存在，否则快速失败。
     * 所有租户数据操作（SQL 过滤、记忆文件读写）MUST 经由本方法取值。
     *
     * @throws TenantContextMissingException 上下文缺失
     */
    public static String requireTenantId() {
        String tenantId = TENANT_ID.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new TenantContextMissingException();
        }
        return tenantId;
    }

    /** 要求用户上下文存在，否则快速失败 */
    public static String requireUserId() {
        String userId = USER_ID.get();
        if (userId == null || userId.isBlank()) {
            throw new TenantContextMissingException();
        }
        return userId;
    }

    /** 上下文是否已建立（供守卫等做轻量探测） */
    public static boolean isPresent() {
        String tenantId = TENANT_ID.get();
        return tenantId != null && !tenantId.isBlank();
    }
}
