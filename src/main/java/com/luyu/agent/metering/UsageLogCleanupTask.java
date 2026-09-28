package com.luyu.agent.metering;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * 明细日志过期清理（tasks 2.7 / design D7）。
 * <p>
 * {@code token_usage_log} 保留 {@code agent.metering.detail-retention-days}（默认 90）天，
 * 每日凌晨 3 点删除 {@code created_at} 早于保留期的行，控制明细表膨胀。
 * 日桶 {@code tenant_usage_daily} 体量小，不在此清理。
 * <p>
 * 清理失败仅记 error，不抛出（定时任务异常不应影响后续调度）。
 */
@Component
public class UsageLogCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(UsageLogCleanupTask.class);

    private final MeteringRepository repository;
    private final MeteringProperties properties;

    public UsageLogCleanupTask(MeteringRepository repository, MeteringProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanExpiredLogs() {
        int retentionDays = properties.getDetailRetentionDays();
        if (retentionDays <= 0) {
            log.debug("明细保留天数 <= 0，跳过清理");
            return;
        }
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
            int deleted = repository.deleteLogsBefore(cutoff);
            if (deleted > 0) {
                log.info("计量明细清理：删除 {} 条早于 {} 的记录（保留 {} 天）", deleted, cutoff, retentionDays);
            }
        } catch (Exception e) {
            log.error("计量明细清理失败（不影响后续调度）", e);
        }
    }
}
