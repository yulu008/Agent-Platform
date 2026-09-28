package com.luyu.agent.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.repository.MeteringRepository;

/**
 * {@link QuotaGuard} 单元测试（tasks 10.3 / design D8）。
 * <p>
 * 覆盖：开关关闭放行、租户缺失放行（无归属无法限额）、已用 &lt; 预算放行、已用 &ge; 预算硬拒、
 * 租户预算覆盖优先于平台默认、滚动窗口起始日 = today-(windowDays-1)（旧用量随窗口滑出即恢复的机制）。
 */
class QuotaGuardTest {

    private static final long MICRO_PER_YUAN = 1_000_000L;

    private MeteringRepository repository;
    private MeteringProperties properties;
    private QuotaGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(MeteringRepository.class);
        properties = new MeteringProperties();
        properties.getQuota().setEnabled(true);
        properties.getQuota().setDefaultBudgetYuan(20.0);
        properties.getQuota().setWindowDays(30);
        guard = new QuotaGuard(repository, properties);
    }

    // ==================== 开关 ====================

    @Test
    void 配额开关关闭_直接放行不查库() {
        properties.getQuota().setEnabled(false);
        assertThatCode(() -> guard.check("t1")).doesNotThrowAnyException();
        verify(repository, never()).sumAmountMicroSince(any(), any());
    }

    // ==================== 租户缺失放行 ====================

    @Test
    void 租户缺失_告警放行不硬拒() {
        assertThatCode(() -> guard.check(null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.check("")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(TenantUsageService.MISSING_TENANT)).doesNotThrowAnyException();
        verify(repository, never()).sumAmountMicroSince(any(), any());
    }

    // ==================== 放行 / 硬拒 ====================

    @Test
    void 已用低于预算_放行() {
        when(repository.findBudgetOverrideMicro("t1")).thenReturn(null);
        when(repository.sumAmountMicroSince(eq("t1"), any())).thenReturn(10L * MICRO_PER_YUAN);
        assertThatCode(() -> guard.check("t1")).doesNotThrowAnyException();
    }

    @Test
    void 已用等于预算_硬拒() {
        when(repository.findBudgetOverrideMicro("t1")).thenReturn(null);
        when(repository.sumAmountMicroSince(eq("t1"), any())).thenReturn(20L * MICRO_PER_YUAN);
        assertThatThrownBy(() -> guard.check("t1"))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> {
                    QuotaExceededException q = (QuotaExceededException) e;
                    assertThat(q.getUsedMicro()).isEqualTo(20L * MICRO_PER_YUAN);
                    assertThat(q.getBudgetMicro()).isEqualTo(20L * MICRO_PER_YUAN);
                });
    }

    @Test
    void 已用超过预算_硬拒() {
        when(repository.findBudgetOverrideMicro("t1")).thenReturn(null);
        when(repository.sumAmountMicroSince(eq("t1"), any())).thenReturn(20L * MICRO_PER_YUAN + 1);
        assertThatThrownBy(() -> guard.check("t1")).isInstanceOf(QuotaExceededException.class);
    }

    // ==================== 租户预算覆盖 ====================

    @Test
    void 租户预算覆盖优先于平台默认() {
        // 覆盖为 5 元，已用 5 元 → 硬拒（若不覆盖，默认 20 元不会拒）
        when(repository.findBudgetOverrideMicro("t1")).thenReturn(5L * MICRO_PER_YUAN);
        when(repository.sumAmountMicroSince(eq("t1"), any())).thenReturn(5L * MICRO_PER_YUAN);
        assertThatThrownBy(() -> guard.check("t1"))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> assertThat(((QuotaExceededException) e).getBudgetMicro())
                        .isEqualTo(5L * MICRO_PER_YUAN));
    }

    @Test
    void 租户覆盖提高预算_平台默认下会拒但覆盖后放行() {
        when(repository.findBudgetOverrideMicro("t1")).thenReturn(50L * MICRO_PER_YUAN);
        when(repository.sumAmountMicroSince(eq("t1"), any())).thenReturn(30L * MICRO_PER_YUAN);
        assertThatCode(() -> guard.check("t1")).doesNotThrowAnyException();
    }

    // ==================== 滚动窗口边界 ====================

    @Test
    void 滚动窗口起始日_等于today减windowDays减1() {
        when(repository.findBudgetOverrideMicro("t1")).thenReturn(null);
        when(repository.sumAmountMicroSince(eq("t1"), any())).thenReturn(0L);
        guard.check("t1");

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).sumAmountMicroSince(eq("t1"), captor.capture());
        // 30 天窗口含当天 → 起始 today-29；旧用量随窗口滑出即恢复
        assertThat(captor.getValue()).isEqualTo(LocalDate.now().minusDays(29));
    }

    @Test
    void 窗口天数为1_起始日即当天() {
        properties.getQuota().setWindowDays(1);
        when(repository.findBudgetOverrideMicro("t1")).thenReturn(null);
        when(repository.sumAmountMicroSince(eq("t1"), any())).thenReturn(0L);
        guard.check("t1");

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).sumAmountMicroSince(eq("t1"), captor.capture());
        assertThat(captor.getValue()).isEqualTo(LocalDate.now());
    }
}
