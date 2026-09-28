package com.luyu.agent.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * {@link UsageLogCleanupTask} 单元测试（tasks 10.9 / design D7）。
 * <p>
 * 明细 {@code token_usage_log} 保留 {@code detail-retention-days}（默认 90）天，清理只删过期行
 * （cutoff = now - retentionDays，repository 以 {@code created_at < cutoff} 过滤）。覆盖：
 * 正确计算 cutoff 并委托删除、保留天数 &le; 0 时跳过、删除异常被吞掉不影响调度。
 */
class UsageLogCleanupTaskTest {

    private MeteringRepository repository;
    private MeteringProperties properties;
    private UsageLogCleanupTask task;

    @BeforeEach
    void setUp() {
        repository = mock(MeteringRepository.class);
        properties = new MeteringProperties();
        properties.setDetailRetentionDays(90);
        task = new UsageLogCleanupTask(repository, properties);
    }

    @Test
    void 默认保留90天_cutoff为now减90天并委托删除() {
        LocalDateTime before = LocalDateTime.now().minusDays(90);
        task.cleanExpiredLogs();
        LocalDateTime after = LocalDateTime.now().minusDays(90);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteLogsBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        // cutoff 落在调用前后计算的 now-90d 区间内（容忍执行耗时）
        assertThat(cutoff).isBetween(before.minusSeconds(5), after.plusSeconds(5));
    }

    @Test
    void 自定义保留天数_cutoff随配置变化() {
        properties.setDetailRetentionDays(7);
        task.cleanExpiredLogs();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deleteLogsBefore(captor.capture());
        assertThat(captor.getValue()).isBetween(
                LocalDateTime.now().minusDays(7).minusSeconds(5),
                LocalDateTime.now().minusDays(7).plusSeconds(5));
    }

    @Test
    void 保留天数为0_跳过清理不删任何行() {
        properties.setDetailRetentionDays(0);
        task.cleanExpiredLogs();
        verify(repository, never()).deleteLogsBefore(any());
    }

    @Test
    void 保留天数为负_跳过清理不删任何行() {
        properties.setDetailRetentionDays(-1);
        task.cleanExpiredLogs();
        verify(repository, never()).deleteLogsBefore(any());
    }

    @Test
    void 删除抛异常_被吞掉不影响后续调度() {
        when(repository.deleteLogsBefore(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> task.cleanExpiredLogs()).doesNotThrowAnyException();
    }
}
