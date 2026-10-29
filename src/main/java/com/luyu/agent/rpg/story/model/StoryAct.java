package com.luyu.agent.rpg.story.model;

import java.util.List;

/**
 * 故事图幕（story-graph-authoring design D2：分幕生成/收束节拍/返工单位）。
 * <p>
 * 幕是 LLM 生成的单位（≤25 节点小契约 JSON）、双收束的挂点（幕末锚点 +
 * 出口轨道表）与 L2 返工门的单位（单幕返工不波及他幕）。幕间靠出口状态
 * 轨道表对接：幕 k+1 的 {@code entryGuard} 引用幕 k 的轨道。
 */
public class StoryAct {

    /** 幕 ID：act1 / act2 ...（节点 ID 幕命名空间的来源） */
    private String id;

    /** 幕主题（一句话，S4 幕大纲锚定） */
    private String theme;

    /** 幕末拍子：锚点节点的 beat（S4 大纲锚定主干） */
    private String endingBeat;

    /** 入口守卫（可空；引用上一幕出口轨道或首幕为 null=初始态可入） */
    private GuardClause entryGuard;

    /** 出口收敛轨道表（轨道闸求值依据；非空为必需字段） */
    private List<ConvergeOrbit> convergeTo;

    /** 幕级变体授权：false 时本幕所有节点 variants 被忽略（物化走即兴） */
    private Boolean allowExplicitVariants;

    /** 幕内节点（≤25，超限拒绝） */
    private List<StoryNode> nodes;

    /** 幕内边（LLM 只画幕内边；跨幕边由引擎拼接，不在此列） */
    private List<StoryEdge> edges;

    public StoryAct() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public String getEndingBeat() { return endingBeat; }
    public void setEndingBeat(String endingBeat) { this.endingBeat = endingBeat; }
    public GuardClause getEntryGuard() { return entryGuard; }
    public void setEntryGuard(GuardClause entryGuard) { this.entryGuard = entryGuard; }
    public List<ConvergeOrbit> getConvergeTo() { return convergeTo; }
    public void setConvergeTo(List<ConvergeOrbit> convergeTo) { this.convergeTo = convergeTo; }
    public Boolean getAllowExplicitVariants() { return allowExplicitVariants; }
    public void setAllowExplicitVariants(Boolean allowExplicitVariants) { this.allowExplicitVariants = allowExplicitVariants; }
    public List<StoryNode> getNodes() { return nodes; }
    public void setNodes(List<StoryNode> nodes) { this.nodes = nodes; }
    public List<StoryEdge> getEdges() { return edges; }
    public void setEdges(List<StoryEdge> edges) { this.edges = edges; }
}
