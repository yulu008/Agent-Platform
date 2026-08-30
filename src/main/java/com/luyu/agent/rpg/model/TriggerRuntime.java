package com.luyu.agent.rpg.model;

/**
 * 触发器运行时状态实体（对应 rpg_trigger_runtime 表）
 * <p>
 * 每轮触发器扫描时使用，记录上次触发轮次和启用状态。
 */
public class TriggerRuntime {

    private String id;
    private String gameStateId;
    private String triggerId;
    private Integer lastTriggeredTurn;
    private Boolean isActive;

    public TriggerRuntime() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getGameStateId() { return gameStateId; }
    public void setGameStateId(String gameStateId) { this.gameStateId = gameStateId; }
    public String getTriggerId() { return triggerId; }
    public void setTriggerId(String triggerId) { this.triggerId = triggerId; }
    public Integer getLastTriggeredTurn() { return lastTriggeredTurn; }
    public void setLastTriggeredTurn(Integer lastTriggeredTurn) { this.lastTriggeredTurn = lastTriggeredTurn; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
}
