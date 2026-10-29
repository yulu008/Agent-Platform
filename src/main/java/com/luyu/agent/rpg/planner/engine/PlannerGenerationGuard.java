package com.luyu.agent.rpg.planner.engine;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.luyu.agent.rpg.planner.model.PipelineStage;

/**
 * run 级生成活跃流内存守卫（story-graph-authoring tasks 2.3，进程内单例共享）。
 * <p>
 * 语义：同一 run 同一时刻最多只有一个「生成流」在跑——通用阶段生成
 * （{@link StageGenerationService}）与 S4.5 幕级编排
 * （{@code StoryGraphGenerationService}）共用本守卫互斥
 * （story-graph-generation spec「并发生成被守卫拦截」：幕生成 RUNNING 中
 * 再次触发本 run 任一阶段生成被拒，反向同理）。
 * <p>
 * 内存层占位必须先于 {@code markStageRunning}（双层守卫：stale RUNNING
 * 复位需区分「崩溃残留」与「并发请求刚置 RUNNING」，见
 * {@code StageGenerationService} 类 javadoc）；manifest 层残留 RUNNING
 * （服务重启后无活跃流）兜底复位见 {@code PipelineRunService.markStageRunning}。
 */
@Component
public class PlannerGenerationGuard {

    /** runId → 正在生成的阶段 */
    private final ConcurrentHashMap<String, PipelineStage> active = new ConcurrentHashMap<>();

    /** 占位（返回 false = 该 run 已有活跃流）。 */
    public boolean tryAcquire(String runId, PipelineStage stage) {
        return active.putIfAbsent(runId, stage) == null;
    }

    /** 释放（带 stage 比对，防误释放他人占位）。 */
    public void release(String runId, PipelineStage stage) {
        active.remove(runId, stage);
    }

    /** 当前活跃流探测（测试与诊断用）：runId 正在生成的阶段。 */
    public Optional<PipelineStage> activeStage(String runId) {
        return Optional.ofNullable(active.get(runId));
    }
}
