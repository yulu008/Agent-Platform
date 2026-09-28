package com.luyu.agent.tenancy;

/**
 * 租户隔离违例：运行时守卫拦截到无租户过滤的 rpg_* 表 SQL，
 * 或涉及租户表的语句执行时租户上下文缺失（design D5）。
 * <p>
 * 定位：兜任何路径的漏网（含动态拼接 SQL），把事故从静默泄漏变为响亮失败；
 * 不追求 SQL 语义级解析，字符串级启发式足够。
 */
public class TenantIsolationViolationException extends RuntimeException {

    public TenantIsolationViolationException(String message) {
        super(message);
    }
}
