package com.luyu.agent.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 子Agent执行审计日志
 * 使用独立 logger "subagent.audit" 输出到独立文件（logback-spring.xml 路由），
 * 每条记录通过 MDC 携带 subagentType / taskId / parentId / duration 上下文。
 * 各方法自包含 MDC 设置与清理，避免上下文泄漏到主日志。
 */
@Component
public class SubagentAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("subagent.audit");

    /**
     * 记录子Agent执行开始
     */
    public void logStart(String subagentType, String taskId, String parentId) {
        putMdc(subagentType, taskId, parentId, null);
        try {
            log.info("子Agent执行开始 subagentType={} taskId={}", subagentType, taskId);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 记录子Agent执行完成（成功或正常结束）
     */
    public void logEnd(String subagentType, String taskId, String parentId, long durationMs, String result) {
        putMdc(subagentType, taskId, parentId, durationMs);
        try {
            log.info("子Agent执行完成 subagentType={} taskId={} durationMs={} result={}",
                    subagentType, taskId, durationMs, result);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 记录子Agent执行超时
     */
    public void logTimeout(String subagentType, String taskId, String parentId, long durationMs) {
        putMdc(subagentType, taskId, parentId, durationMs);
        try {
            log.warn("子Agent执行超时 subagentType={} taskId={} durationMs={}",
                    subagentType, taskId, durationMs);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 记录子Agent委派被拒（并行度超限，尚未分配 taskId）
     */
    public void logRejected(String subagentType, String parentId) {
        putMdc(subagentType, null, parentId, null);
        try {
            log.warn("子Agent委派被拒(并行度超限) subagentType={}", subagentType);
        } finally {
            MDC.clear();
        }
    }

    /**
     * 记录子Agent执行异常
     */
    public void logError(String subagentType, String taskId, String parentId, long durationMs, Throwable ex) {
        putMdc(subagentType, taskId, parentId, durationMs);
        try {
            log.error("子Agent执行异常 subagentType={} taskId={} durationMs={}",
                    subagentType, taskId, durationMs, ex);
        } finally {
            MDC.clear();
        }
    }

    private void putMdc(String subagentType, String taskId, String parentId, Long durationMs) {
        if (subagentType != null) {
            MDC.put("subagentType", subagentType);
        }
        if (taskId != null) {
            MDC.put("taskId", taskId);
        }
        if (parentId != null) {
            MDC.put("parentId", parentId);
        }
        if (durationMs != null) {
            MDC.put("duration", String.valueOf(durationMs));
        }
    }
}
