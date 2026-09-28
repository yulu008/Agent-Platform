package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TurnGuardService} 的单元测试。
 * <p>
 * 钉住 design D11 守卫语义：每存档互斥、跨存档隔离、释放幂等。
 * 另钉住 rpg-server-side-abort（design D1）取消信号语义：信号与锁同生共死、
 * fireAbort 幂等、release 后不误伤下一回合、晚订阅也能收到中止完成。
 * 与 RollbackService / GameLoopService / RpgGameController 的集成行为
 * 分别由各自的测试文件覆盖。
 */
class TurnGuardServiceTest {

    private static final String GS_A = "gs-a";
    private static final String GS_B = "gs-b";

    private final TurnGuardService guard = new TurnGuardService();

    @Test
    void 首次获取成功且标记进行中() {
        assertThat(guard.tryAcquire(GS_A)).isTrue();
        assertThat(guard.isInTurn(GS_A)).isTrue();
    }

    @Test
    void 持有期间重复获取失败() {
        guard.tryAcquire(GS_A);

        assertThat(guard.tryAcquire(GS_A)).isFalse();
        assertThat(guard.isInTurn(GS_A)).isTrue();
    }

    @Test
    void 释放后可再次获取() {
        guard.tryAcquire(GS_A);
        guard.release(GS_A);

        assertThat(guard.isInTurn(GS_A)).isFalse();
        assertThat(guard.tryAcquire(GS_A)).isTrue();
    }

    @Test
    void 不同存档互不阻塞() {
        guard.tryAcquire(GS_A);

        assertThat(guard.tryAcquire(GS_B)).isTrue();
        assertThat(guard.isInTurn(GS_A)).isTrue();
        assertThat(guard.isInTurn(GS_B)).isTrue();
    }

    @Test
    void 释放幂等_未持有时调用无副作用() {
        guard.release(GS_A); // 未持有直接释放

        assertThat(guard.isInTurn(GS_A)).isFalse();
        assertThat(guard.tryAcquire(GS_A)).isTrue();

        guard.release(GS_A);
        guard.release(GS_A); // 双重释放

        assertThat(guard.isInTurn(GS_A)).isFalse();
    }

    // ==================== 服务端中止取消信号（rpg-server-side-abort，design D1） ====================

    @Test
    void 中止信号触发已订阅方完成() {
        guard.tryAcquire(GS_A);
        AtomicBoolean completed = new AtomicBoolean(false);
        guard.abortSignal(GS_A).subscribe(v -> { }, e -> { }, () -> completed.set(true));

        assertThat(guard.fireAbort(GS_A)).isTrue();

        assertThat(completed.get()).isTrue(); // emit 同步传播完成信号
    }

    @Test
    void 先中止后订阅也能收到完成信号() {
        // 竞态窗口：abort 请求先于回合流 takeUntilOther 订阅到达（Sinks.One 终态被记忆）
        guard.tryAcquire(GS_A);

        assertThat(guard.fireAbort(GS_A)).isTrue();

        // 晚订阅立即完成而非永卡
        assertThat(guard.abortSignal(GS_A).block(Duration.ofSeconds(1))).isNull();
    }

    @Test
    void fireAbort幂等_重复调用返回false() {
        guard.tryAcquire(GS_A);

        assertThat(guard.fireAbort(GS_A)).isTrue();
        assertThat(guard.fireAbort(GS_A)).isFalse(); // Sinks.One 单次语义，二次 emit 落空
    }

    @Test
    void fireAbort无回合时落空() {
        assertThat(guard.fireAbort(GS_A)).isFalse();

        guard.tryAcquire(GS_A);
        guard.release(GS_A);

        assertThat(guard.fireAbort(GS_A)).isFalse(); // 释放后信号已摘，迟到 abort 落空
    }

    @Test
    void release后fireAbort落空且不误伤下一回合信号() {
        guard.tryAcquire(GS_A);
        guard.release(GS_A);

        assertThat(guard.fireAbort(GS_A)).isFalse();

        // 新回合拿到全新信号，可正常被中止（上一回合的中止不残留）
        assertThat(guard.tryAcquire(GS_A)).isTrue();
        Mono<Void> newSignal = guard.abortSignal(GS_A);
        assertThat(guard.fireAbort(GS_A)).isTrue();
        assertThat(newSignal.block(Duration.ofSeconds(1))).isNull();

        guard.release(GS_A);
    }

    @Test
    void 无回合时abortSignal永不完成() throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        guard.abortSignal(GS_B).subscribe(v -> { }, e -> { }, completed::countDown);

        guard.fireAbort(GS_B); // 无锁落空

        assertThat(completed.await(200, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void 中止信号跨存档隔离() throws InterruptedException {
        guard.tryAcquire(GS_A);
        guard.tryAcquire(GS_B);

        assertThat(guard.fireAbort(GS_A)).isTrue();

        // GS_A 中止不影响 GS_B 的信号
        CountDownLatch bCompleted = new CountDownLatch(1);
        guard.abortSignal(GS_B).subscribe(v -> { }, e -> { }, bCompleted::countDown);
        assertThat(bCompleted.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(guard.fireAbort(GS_B)).isTrue();
    }
}
