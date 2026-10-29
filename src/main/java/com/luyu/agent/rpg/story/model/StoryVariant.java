package com.luyu.agent.rpg.story.model;

/**
 * 节点显式变体（story-graph-authoring design D5：变体优先级链）。
 * <p>
 * 收束/锚点节点 MAY 声明：物化时 guard 命中 → 用 {@code beatVariant}/
 * {@code bodySeed} 作为物化种子（GM 展开，基调锁定）；不命中/无变体 → 纯即兴。
 * 仅当所在幕 {@code allowExplicitVariants} 为 true 时生效（幕级授权）。
 */
public class StoryVariant {

    /** 变体守卫（命中判定走 GuardEvaluator 确定性求值） */
    private GuardClause guard;

    /** 变体 beat 梗概（基调锁定的主指示） */
    private String beatVariant;

    /** 可选正文种子：更细的展开指引（一句话情绪/场面指示，非完整正文） */
    private String bodySeed;

    public StoryVariant() {
    }

    public GuardClause getGuard() { return guard; }
    public void setGuard(GuardClause guard) { this.guard = guard; }
    public String getBeatVariant() { return beatVariant; }
    public void setBeatVariant(String beatVariant) { this.beatVariant = beatVariant; }
    public String getBodySeed() { return bodySeed; }
    public void setBodySeed(String bodySeed) { this.bodySeed = bodySeed; }
}
