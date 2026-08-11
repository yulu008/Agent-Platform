package com.luyu.agent.governance;

import org.springaicommunity.agent.tools.task.repository.BackgroundTask;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;

import com.luyu.agent.logger.SubagentAuditLogger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 受治理的任务仓库：包装 DefaultTaskRepository，为后台任务叠加 TTL 清扫。
 *
 * 存活时长的前台兜底由 RateLimitingSubagentExecutor.execute() 的 Future.get(lifespan) 保证；
 * 本类作为后台任务的二次防线：当供应商线程尚未启动 execute() 或卡住时，
 * 周期性扫描跟踪的任务，对超过 lifespan 的 BackgroundTask 调 cancel(true) 并审计记录。
 */
public class GovernedTaskRepository implements TaskRepository, AutoCloseable {

    private final DefaultTaskRepository delegate;
    private final Duration lifespan;
    private final SubagentAuditLogger auditLogger;
    private final ScheduledExecutorService sweeper;
    /** 跟踪后台任务 taskId → 开始时间戳（TaskRepository 接口无列表方法，需自行跟踪） */
    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();

    public GovernedTaskRepository(Duration lifespan, SubagentAuditLogger auditLogger) {
        this.delegate = new DefaultTaskRepository();
        this.lifespan = lifespan;
        this.auditLogger = auditLogger;
        // 清扫周期：取 10s 与 lifespan/2 的较小值，下限 1s
        long sweepPeriod = Math.max(1000L, Math.min(10_000L, lifespan.toMillis() / 2));
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "subagent-ttl-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleAtFixedRate(this::sweepExpired, sweepPeriod, sweepPeriod, TimeUnit.MILLISECONDS);
    }

    @Override
    public BackgroundTask putTask(String taskId, Supplier<String> supplier) {
        startTimes.put(taskId, System.currentTimeMillis());
        return delegate.putTask(taskId, supplier);
    }

    @Override
    public BackgroundTask getTasks(String taskId) {
        return delegate.getTasks(taskId);
    }

    @Override
    public void removeTask(String taskId) {
        startTimes.remove(taskId);
        delegate.removeTask(taskId);
    }

    @Override
    public void clear() {
        startTimes.clear();
        delegate.clear();
    }

    /**
     * 周期清扫：取消超过存活时长的后台任务，清理已完成任务的跟踪记录
     */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        long maxMs = lifespan.toMillis();
        startTimes.forEach((id, start) -> {
            BackgroundTask bt = delegate.getTasks(id);
            if (bt == null) {
                startTimes.remove(id);
                return;
            }
            if (bt.isCompleted() || bt.isCancelled()) {
                startTimes.remove(id);
                return;
            }
            if (now - start > maxMs) {
                bt.cancel(true);
                auditLogger.logTimeout("background", id, "ttl-sweeper", now - start);
                startTimes.remove(id);
            }
        });
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
        delegate.shutdown();
    }
}
