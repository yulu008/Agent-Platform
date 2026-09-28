package com.luyu.agent.tenant;

/**
 * 多租户上下文持有器。
 * <p>
 * 通过 {@link ThreadLocal} 在请求线程中传递当前用户 ID。
 * 由 {@code JwtAuthFilter} 在请求入口设置，请求结束后清理。
 * <p>
 * 对于 SSE 流式响应（Reactor），userId 在 Controller 方法入口即可取到，
 * 后续传入 advisor param 和 toolContext，不依赖 Reactor context 传播。
 */
public final class TenantContext {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 设置当前请求的用户 ID。
     *
     * @param userId 用户 ID
     */
    public static void set(String userId) {
        CONTEXT.set(userId);
    }

    /**
     * 获取当前请求的用户 ID。
     *
     * @return 用户 ID，未设置时返回 {@code "default-user"} 作为兜底
     */
    public static String get() {
        String userId = CONTEXT.get();
        return userId != null ? userId : "default-user";
    }

    /**
     * 清理 ThreadLocal，防止线程池复用导致用户身份串号。
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
