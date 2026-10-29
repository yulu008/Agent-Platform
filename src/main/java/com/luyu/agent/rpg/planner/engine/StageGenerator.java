package com.luyu.agent.rpg.planner.engine;

import com.luyu.agent.rpg.planner.model.PipelineStage;

/**
 * 阶段生成器接口（add-planner-pipeline tasks 3.1）。
 * <p>
 * 每个阶段一个实现（S1-S5），只负责「组装 prompt」这一件事——
 * SSE 流式、治理三段式、状态机推进、产物落盘由
 * {@code StageGenerationService} 统一编排（生成器无状态、可测、加阶段=加实现）。
 * <p>
 * L1 Markdown 阶段（S1/S2/S4）：prompt 产出 Markdown 全稿，流式 token 直落
 * {@code <dir>.md}；L2 候选集阶段（S3/S5）在任务 3.6 扩展结构化解析。
 * S1 ip_name 模式的受限搜索循环在任务 3.4 独立实现（不实现本接口——它是
 * agentic 循环而非单流生成）。
 */
public interface StageGenerator {

    /** 本生成器负责的阶段 */
    PipelineStage stage();

    /**
     * 组装该阶段的完整 prompt（风格约束 + 阶段任务 + 上下文 + 返工约束）。
     * 各实现用 {@link StagePromptAssembler} 的拼装件 + 自己的阶段专属指令组合。
     */
    String buildPrompt(GenerationContext context);
}
