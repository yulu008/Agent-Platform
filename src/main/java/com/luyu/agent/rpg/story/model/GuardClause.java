package com.luyu.agent.rpg.story.model;

/**
 * 守卫谓词（story-graph-authoring design D4/Risks：限简单谓词保确定性）。
 * <p>
 * 形态恒为 {@code key op value} 单谓词：op ∈ {@code >= <= > < == in}，
 * 由 {@code GuardEvaluator} 纯函数求值（同输入同输出，LLM 不参与判定）。
 * value 类型由 {@code state_schema} 声明：number（数值比较）/ string（等值）/
 * flag（布尔）；{@code in} 的 value 为数组（枚举集合成员判定）。
 */
public class GuardClause {

    /** 状态键（必须存在于 state_schema 词汇表） */
    private String key;

    /** 比较算子：>= <= > < == in */
    private String op;

    /** 比较值：数字 / 字符串 / 布尔 / 数组（in） */
    private Object value;

    public GuardClause() {
    }

    public GuardClause(String key, String op, Object value) {
        this.key = key;
        this.op = op;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
}
