package com.luyu.agent.rpg.model;

/**
 * 触发器定义实体（对应 rpg_trigger 表）
 * <p>
 * type=event 为事件触发器（如进入客栈→小二接待），
 * type=motivation 为动机触发器（如盗贼见财起意）。
 */
public class Trigger {

    private String id;
    private String worldId;
    private String type;           // event / motivation
    private String npcId;          // 关联 NPC
    private String hardConditions; // JSON: 硬条件列表
    private String softCondition;  // 软条件自然语言描述
    private String action;         // greet / rob / ...
    private Integer cooldown;      // 冷却轮次
    private Double probability;    // 触发概率
    private java.time.LocalDateTime createdAt;

    public Trigger() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getNpcId() { return npcId; }
    public void setNpcId(String npcId) { this.npcId = npcId; }
    public String getHardConditions() { return hardConditions; }
    public void setHardConditions(String hardConditions) { this.hardConditions = hardConditions; }
    public String getSoftCondition() { return softCondition; }
    public void setSoftCondition(String softCondition) { this.softCondition = softCondition; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getCooldown() { return cooldown; }
    public void setCooldown(Integer cooldown) { this.cooldown = cooldown; }
    public Double getProbability() { return probability; }
    public void setProbability(Double probability) { this.probability = probability; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
}
