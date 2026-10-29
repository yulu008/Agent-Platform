package com.luyu.agent.rpg.story.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 故事图节点（story-graph-model spec：骨架层纯结构，正文运行期物化）。
 * <p>
 * 节点只承载 beat 梗概与结构语义（守卫/效果/变体/分化维度），不含正文——
 * 正文是层 2，玩家到达时由 GM 物化（design D1 骨架/血肉分层）。
 */
public class StoryNode {

    /** 节点类型：scene 自由场景 / anchor 锚点（必经拍子，improvise 强制 false）/ ending 结局 */
    public static final String TYPE_SCENE = "scene";
    public static final String TYPE_ANCHOR = "anchor";
    public static final String TYPE_ENDING = "ending";

    /** 节点 ID：带幕命名空间 n&lt;act&gt;_&lt;seq&gt;（跨幕引用禁止是校验规则而非约定） */
    private String id;

    /** beat 梗概：一句话剧情拍子（非正文，物化时的主指示） */
    private String beat;

    /** 节点类型：scene / anchor / ending */
    private String type;

    /** 节点级即兴开关；null = 继承作品级 improvise_mode；anchor 强制 false */
    private Boolean improvise;

    /** 入口守卫（可空；不满足则本节点不可经任何边进入） */
    private GuardClause entryGuard;

    /** 进入效果（可空；effects 语义同边 effects：数值加减、flags/player 覆盖） */
    private java.util.Map<String, Object> onEnter;

    /** 收束节点的期望状态分化维度（状态键列表，防假选择感：拓扑收束状态分化） */
    private List<String> divergenceDimensions;

    /** 显式变体（幕级授权生效；优先级链：guard 命中用种子 → 纯即兴） */
    private List<StoryVariant> variants;

    public StoryNode() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getBeat() { return beat; }
    public void setBeat(String beat) { this.beat = beat; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Boolean getImprovise() { return improvise; }
    public void setImprovise(Boolean improvise) { this.improvise = improvise; }
    public GuardClause getEntryGuard() { return entryGuard; }
    public void setEntryGuard(GuardClause entryGuard) { this.entryGuard = entryGuard; }
    public java.util.Map<String, Object> getOnEnter() { return onEnter; }
    public void setOnEnter(java.util.Map<String, Object> onEnter) { this.onEnter = onEnter; }
    public List<String> getDivergenceDimensions() { return divergenceDimensions; }
    public void setDivergenceDimensions(List<String> divergenceDimensions) { this.divergenceDimensions = divergenceDimensions; }
    public List<StoryVariant> getVariants() { return variants; }
    public void setVariants(List<StoryVariant> variants) { this.variants = variants; }

    /** 是否锚点（含 ending 视同锚点语义：必经且不可即兴绕过）。
     * 计算型无字段 getter：必须 {@code @JsonIgnore}——否则 writeValueAsString
     * 写出幽灵字段 "anchorLike"，反序列化无 setter 报未知字段（finalize
     * 落库→再读链路全断，SharedStoryGraphEndToEndTest 3.4 钉住）。 */
    @JsonIgnore
    public boolean isAnchorLike() {
        return TYPE_ANCHOR.equals(type) || TYPE_ENDING.equals(type);
    }
}
