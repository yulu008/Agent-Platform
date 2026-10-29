package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import com.luyu.agent.rpg.planner.model.PipelineManifest;

/**
 * 单次阶段生成的上下文包（stage run 的全部输入，add-planner-pipeline design D7/D9）。
 * <p>
 * 由 {@code StageGenerationService} 在触发生成时从 manifest 与工作区现算装配，
 * 交 {@link StageGenerator} 组装 prompt。字段语义：
 * <ul>
 *   <li>{@code manifest}：run 全量状态（风格模式、各阶段状态与摘要）</li>
 *   <li>{@code sourceText}：原始输入全文（text 原文 / ip_name IP 名，S1 的主原料）</li>
 *   <li>{@code upstreamConfirmed}：直接上游阶段的确认稿全文（S2 的原料是 S1 拆解稿；
 *       v1 简化：直接上游注入确认稿全文 + 截断保护，更早阶段只走摘要行——
 *       D7 完整版的「细节经工具按需拉取」留给后续，planner 角色是纯净 client 无工具）</li>
 *   <li>{@code roster}：S1 确认稿的「人物全量名册」节（S3 对照池：摘要行只有一句话
 *       概括，名册不进 S3 视野会让人物候选只能从 S2 世界书显式人物里挑；
 *       faithful S1 成稿固定带名册节，remix / 无该节的旧稿为 null 零注入）</li>
 *   <li>{@code batchRoster}：S3 分批生成的本批名册块（planner-s3-batch-generate
 *       D1：批内 prompt 只带本批名册块而非全量名册——批内不膨胀；由
 *       S3BatchGenerationService 装配，单流路径为 null 且 roster 语义不变）</li>
 *   <li>{@code producedNames}：S3 分批生成的已产出候选人名索引（批间防重产出同名
 *       人物、供写跨批人物关系；单流路径为空）</li>
 *   <li>{@code currentDraft}：本阶段上一轮全稿（返工时注入，D5「上轮全稿」来源；首轮为 null）</li>
 *   <li>{@code rounds}：本阶段返工反馈历史（L2 门多轮，任务 4 写入）</li>
 *   <li>{@code rejectedTitles}：已毙候选的显式排除表（L2 门，任务 4 写入；
 *       D5——已毙候选不进上下文防"复活"）</li>
 * </ul>
 */
public record GenerationContext(
        PipelineManifest manifest,
        String sourceText,
        String upstreamConfirmed,
        String currentDraft,
        List<PipelineManifest.ReRound> rounds,
        List<String> rejectedTitles,
        String roster,
        String batchRoster,
        List<String> producedNames) {

    /** 单流路径兼容构造器（分批字段缺省：batchRoster null / producedNames 空）。 */
    public GenerationContext(PipelineManifest manifest, String sourceText, String upstreamConfirmed,
                             String currentDraft, List<PipelineManifest.ReRound> rounds,
                             List<String> rejectedTitles, String roster) {
        this(manifest, sourceText, upstreamConfirmed, currentDraft, rounds, rejectedTitles,
                roster, null, List.of());
    }

    /** 返工轮数（反馈历史长度；首轮为 0） */
    public int reworkRound() {
        return rounds == null ? 0 : rounds.size();
    }

    /** 是否返工轮（带反馈的新一轮，D5） */
    public boolean isRework() {
        return reworkRound() > 0;
    }

    /** 是否批内执行（S3 分批生成的单批形态：批内名册块 + 人名索引注入） */
    public boolean isBatch() {
        return batchRoster != null && !batchRoster.isBlank();
    }
}
