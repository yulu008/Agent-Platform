package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选集拆分器（add-planner-pipeline tasks 3.6 / design D4）。
 * <p>
 * L2 阶段（S3/S5）的 SSE 流仍输出 Markdown 全稿（token 流可观感），完成时
 * 由本类按<b>行首二级标题</b>切分为候选块——每块独立落盘一个
 * {@code candidate-NN.md}（{@code PlannerWorkspaceStore.writeStageCandidates}），
 * 供 L2 门的逐卡毙掉/编辑/返工（任务 4）。
 * <p>
 * 容错语义：首个标题前的导语丢弃（候选集阶段无全稿导语）；无任何二级标题时
 * 整稿降级为单候选（模型格式漂移不丢内容，按单卡走 L2 门）；空白输入返回
 * 空列表（由 StageGenerationService 守卫置 FAILED）。
 */
public final class CandidateSplitter {

    /** 候选分隔标记：行首二级标题（与 L1 阶段小节标题同级——候选即 L2 阶段的「节」） */
    private static final String CANDIDATE_HEADER = "## ";

    private CandidateSplitter() {
    }

    /**
     * 拆分全稿为候选块列表（保持原序 = 生成序）。
     * 块首标题行保留在块内——它是逐卡渲染与毙排除表的标题来源。
     */
    public static List<String> split(String fullDraft) {
        List<String> candidates = new ArrayList<>();
        if (fullDraft == null || fullDraft.isBlank()) {
            return candidates;
        }
        StringBuilder current = null;
        for (String line : fullDraft.split("\n", -1)) {
            if (line.startsWith(CANDIDATE_HEADER)) {
                flush(current, candidates);
                current = new StringBuilder();
            }
            if (current != null) {
                current.append(line).append('\n');
            }
            // current == null：首个标题前的导语，丢弃
        }
        flush(current, candidates);
        if (candidates.isEmpty()) {
            // 无任何二级标题：整稿降级为单候选（格式漂移不丢内容，按单卡走 L2 门）
            String whole = fullDraft.strip();
            if (!whole.isEmpty()) {
                candidates.add(whole);
            }
        }
        return candidates;
    }

    private static void flush(StringBuilder block, List<String> out) {
        if (block != null) {
            String trimmed = block.toString().strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
    }
}
