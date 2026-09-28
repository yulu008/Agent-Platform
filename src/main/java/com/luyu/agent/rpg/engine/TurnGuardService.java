package com.luyu.agent.rpg.engine;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回合进行中互斥守卫（design D11，进程内单例）。
 * <p>
 * 语义：同一存档（gameStateId）同一时刻最多只有一个「回合」在进行——
 * 回合含 prepareTurn 开始至回合 SSE 流终止（complete / cancel / error）的全过程，
 * 以及回溯（backtrack）执行期间。
 * <ul>
 *   <li>加锁点 1：{@link GameLoopService#prepareTurn} 入口（先于快照与 player_action 落库）</li>
 *   <li>加锁点 2：{@link RollbackService#backtrack} 入口</li>
 *   <li>释放点：回合 SSE 流终止（Controller doFinally，覆盖全部终止态）与回溯 finally</li>
 * </ul>
 * 进程内实现依据：单实例部署 + 本地 H2，进程重启状态天然归零，无需持久化与超时兜底。
 * 前端「流式禁用按钮」仅是单页 UX 防护（刷新 / 多标签 / SSE 断流均绕过），本守卫是后端唯一真源。
 * <p>
 * 服务端中止（rpg-server-side-abort，design D1）：取消信号与锁<b>同生共死</b>——
 * {@link #tryAcquire} 成功时同步创建 {@link Sinks.One} 中止信号，{@link #release} 时同步移除；
 * 回合流管道以 takeUntilOther 订阅 {@link #abortSignal} 接收中止。
 * 绑在同一组件内是唯一能让「锁生命周期 = 信号生命周期」原子成立的位置：
 * 信号先于锁创建会被上一回合误发，晚于锁创建则存在 abort 打空窗口。
 */
@Component
public class TurnGuardService {

    private final Set<String> inTurn = ConcurrentHashMap.newKeySet();

    /** 中止信号注册表：与 inTurn 同生共死（tryAcquire 创建 / release 移除）。 */
    private final Map<String, Sinks.One<Void>> abortSignals = new ConcurrentHashMap<>();

    /**
     * 尝试占用该存档的回合锁。
     *
     * @return true = 占用成功（调用方负责在回合/回溯结束后 {@link #release}，中止信号已同步创建）；
     *         false = 已有回合或回溯进行中
     */
    public boolean tryAcquire(String gameStateId) {
        if (!inTurn.add(gameStateId)) {
            return false;
        }
        abortSignals.put(gameStateId, Sinks.one());
        return true;
    }

    /**
     * 释放该存档的回合锁（幂等：未持有时调用无副作用），同步移除中止信号——
     * 杜绝迟到的 abort 误伤下一回合。
     */
    public void release(String gameStateId) {
        inTurn.remove(gameStateId);
        abortSignals.remove(gameStateId);
    }

    /** 该存档是否有回合或回溯进行中。 */
    public boolean isInTurn(String gameStateId) {
        return inTurn.contains(gameStateId);
    }

    /**
     * 触发该存档进行中回合的服务端中止信号（rpg-server-side-abort，design D1）。
     * <p>
     * 仅当信号存在且本次 emit 成功时返回 true；无进行中回合、信号已随 release 移除、
     * 或重复调用（{@link Sinks.One} 单次语义，已终止）均返回 false——abort 端点幂等。
     *
     * @return true = 命中并已发出中止信号；false = 落空（无副作用）
     */
    public boolean fireAbort(String gameStateId) {
        Sinks.One<Void> sink = abortSignals.get(gameStateId);
        if (sink == null) {
            return false;
        }
        // tryEmitEmpty 非成功值（FAIL_TERMINATED=重复中止 / FAIL_CANCELLED 等）静默落空
        return sink.tryEmitEmpty().isSuccess();
    }

    /**
     * 该存档回合流的中止信号（rpg-server-side-abort，design D2）：
     * 回合管道以 takeUntilOther 订阅，信号触发时流被取消并走既有 doOnCancel/doFinally 收尾。
     * <p>
     * 无进行中回合时返回 {@link Mono#never()}——语义即「永不中止」，订阅方照常运行。
     *
     * @return 信号 Mono（完成即代表中止）；无回合时永不完成
     */
    public Mono<Void> abortSignal(String gameStateId) {
        Sinks.One<Void> sink = abortSignals.get(gameStateId);
        return sink != null ? sink.asMono() : Mono.never();
    }
}
