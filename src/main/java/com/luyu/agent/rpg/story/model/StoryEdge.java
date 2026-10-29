package com.luyu.agent.rpg.story.model;

import java.util.Map;

/**
 * 故事图边（分支叙事的显式"选择"）。
 * <p>
 * 对应现有 GM choices 的 label/quote/hint 三元组（前端按钮交互语义不变，
 * 点击以 {@code quote} 发起新回合）；新增结构语义：guard（出选项过滤）与
 * effects（引擎确定性结算）。LLM 只画幕内边，跨幕边由引擎拼接（design D2）。
 */
public class StoryEdge {

    /** 边 ID：e&lt;act&gt;_&lt;seq&gt;（校验/修复报告定位键；模型可省略，引擎补齐） */
    private String id;

    /** 起点：本幕节点 ID */
    private String from;

    /** 终点：本幕节点 ID 或下一幕入口锚（后者仅引擎拼接边允许出现） */
    private String to;

    /** 按钮主文本：短动词短语（对齐 GM choices label 契约） */
    private String label;

    /** 玩家选中后说出的台词（点击即以这句话作为玩家发言） */
    private String quote;

    /** 可省略的后果预告小字 */
    private String hint;

    /** 出选项守卫（可空；不满足则该边不出现在选项列表中） */
    private GuardClause guard;

    /** 边效果（可空；引擎结算：数值加减、flags/player 自由键覆盖） */
    private Map<String, Object> effects;

    public StoryEdge() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getQuote() { return quote; }
    public void setQuote(String quote) { this.quote = quote; }
    public String getHint() { return hint; }
    public void setHint(String hint) { this.hint = hint; }
    public GuardClause getGuard() { return guard; }
    public void setGuard(GuardClause guard) { this.guard = guard; }
    public Map<String, Object> getEffects() { return effects; }
    public void setEffects(Map<String, Object> effects) { this.effects = effects; }
}
