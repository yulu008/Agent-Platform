package com.luyu.agent.governance;

import org.slf4j.MDC;
import org.springaicommunity.agent.common.task.subagent.SubagentDefinition;
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.common.task.subagent.TaskCall;

import com.luyu.agent.logger.SubagentAuditLogger;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子Agent执行层限流装饰器
 * 基于 SubagentExecutor SPI 装饰被委托执行器，实现：
 *   - 最大并行度：Semaphore 控制同时执行的子Agent数量
 *   - 存活时长：Future.get(lifespan) 超时则 cancel(true)
 *   - 分离审计：SubagentAuditLogger 写独立日志 + MDC 上下文
 * 超限/超时返回友好错误文本（不抛异常），符合 Task 工具返回 String 给模型的契约。
 */
public class RateLimitingSubagentExecutor implements SubagentExecutor {

    private final SubagentExecutor delegate;
    private final Semaphore semaphore;
    private final int maxParallelism;
    private final Duration lifespan;
    private final SubagentAuditLogger auditLogger;
    /** 用于在独立线程运行被装饰执行器，以便 Future.get 超时控制 */
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public RateLimitingSubagentExecutor(SubagentExecutor delegate, int maxParallelism,
                                        Duration lifespan, SubagentAuditLogger auditLogger) {
        this(delegate, new Semaphore(maxParallelism), maxParallelism, lifespan, auditLogger);
    }

    public RateLimitingSubagentExecutor(SubagentExecutor delegate, Semaphore semaphore, int maxParallelism,
                                        Duration lifespan, SubagentAuditLogger auditLogger) {
        this.delegate = delegate;
        this.semaphore = semaphore;
        this.maxParallelism = maxParallelism;
        this.lifespan = lifespan;
        this.auditLogger = auditLogger;
    }

    @Override
    public String getKind() {
        // 委托给被装饰执行器，保持 "claude" 等 kind 不变，确保 TaskTool 正确路由
        return delegate.getKind();
    }

    @Override
    public String execute(TaskCall call, SubagentDefinition definition) {
        String subagentType = call.subagent_type();
        String taskId = UUID.randomUUID().toString();
        String parentId = resolveParentId();
        String description = call.description();
        String prompt = call.prompt();

        // 1. 并行度限流：tryAcquire 带超时，避免无限阻塞主Agent线程
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(lifespan.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[subagent-governance] 获取并行许可被中断 subagentType=" + subagentType;
        }
        if (!acquired) {
            auditLogger.logRejected(subagentType, parentId);
            return "[subagent-governance] 并行度超限，当前最大 " + maxParallelism + " subagentType=" + subagentType;
        }

        // 2. 审计开始 + 执行 + 存活时长限流
        auditLogger.logStart(subagentType, taskId, parentId, description, prompt);
        long start = System.currentTimeMillis();
        try {
            Future<String> future = worker.submit(() -> delegate.execute(call, definition));
            try {
                String result = future.get(lifespan.toMillis(), TimeUnit.MILLISECONDS);
                long duration = System.currentTimeMillis() - start;
                auditLogger.logEnd(subagentType, taskId, parentId, duration, "success");
                return result;
            } catch (TimeoutException e) {
                future.cancel(true);
                long duration = System.currentTimeMillis() - start;
                auditLogger.logTimeout(subagentType, taskId, parentId, duration);
                return "[subagent-governance] 子Agent执行超时 subagentType=" + subagentType
                        + " lifespan=" + lifespan.toSeconds() + "s";
            } catch (ExecutionException e) {
                long duration = System.currentTimeMillis() - start;
                auditLogger.logError(subagentType, taskId, parentId, duration, description, prompt, e.getCause());
                return "[subagent-governance] 子Agent执行异常 subagentType=" + subagentType
                        + " error=" + (e.getCause() != null ? e.getCause().getMessage() : "unknown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                long duration = System.currentTimeMillis() - start;
                auditLogger.logTimeout(subagentType, taskId, parentId, duration);
                return "[subagent-governance] 子Agent执行被中断 subagentType=" + subagentType;
            }
        } finally {
            semaphore.release();
        }
    }

    /**
     * 解析父Agent标识：优先取 MDC 中的 parentId，其次用线程名
     */
    private String resolveParentId() {
        String mdc = MDC.get("parentId");
        return (mdc != null && !mdc.isBlank()) ? mdc : Thread.currentThread().getName();
    }

    /**
     * 工具方法：批量装饰一组 SubagentType，共享同一个 Semaphore 以保证全局最大并行度。
     * 为多模型路由预留：每个 SubagentType 的 executor 被替换为限流装饰器，resolver 不变。
     *
     * @param types           原始 SubagentType 列表
     * @param maxParallelism  最大并行度
     * @param lifespan        单次执行存活时长
     * @param auditLogger     审计日志器
     * @return 装饰后的 SubagentType 列表（顺序与入参一致）
     */
    public static List<SubagentType> wrapAll(List<SubagentType> types, int maxParallelism,
                                             Duration lifespan, SubagentAuditLogger auditLogger) {
        Semaphore shared = new Semaphore(maxParallelism);
        return types.stream()
                .map(t -> new SubagentType(t.resolver(),
                        new RateLimitingSubagentExecutor(t.executor(), shared, maxParallelism, lifespan, auditLogger)))
                .toList();
    }
}
