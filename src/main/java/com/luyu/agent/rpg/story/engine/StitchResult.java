package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.StoryEdge;

import java.util.List;

/**
 * 跨幕拼接结果（{@link StoryGraphStitcher} 产物）。
 * <p>
 * {@code crossEdges} 是本次生成的跨幕边清单（已就地挂入源幕 {@code edges}，
 * 此处副本供调用方统计与断言）；{@code warnings} 是越权跨幕边丢弃与
 * 衔接跳过告警（spec「模型越权跨幕边被丢弃」——丢弃须记录告警）。
 */
public class StitchResult {

    private final List<StoryEdge> crossEdges;
    private final List<String> warnings;

    public StitchResult(List<StoryEdge> crossEdges, List<String> warnings) {
        this.crossEdges = crossEdges;
        this.warnings = warnings;
    }

    /** 本次生成的跨幕边（出口锚 → 下幕入口；已挂入源幕 edges） */
    public List<StoryEdge> getCrossEdges() { return crossEdges; }

    /** 告警清单（越权丢弃 / 无锚点跳过 / 结构异常提示），空即无告警 */
    public List<String> getWarnings() { return warnings; }
}
