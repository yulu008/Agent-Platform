package com.luyu.agent.tenancy;

/**
 * 租户上下文缺失：业务数据操作在无法确定租户时快速失败，
 * 绝不以空值或默认租户静默继续（见 spec tenancy/tenant-context）。
 */
public class TenantContextMissingException extends RuntimeException {

    public TenantContextMissingException() {
        super("租户上下文缺失：当前调用链未携带租户标识（应由 JWT 认证建立，或经显式载体透传）");
    }

    public TenantContextMissingException(String message) {
        super(message);
    }
}
