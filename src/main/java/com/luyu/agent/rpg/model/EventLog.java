package com.luyu.agent.rpg.model;

import java.time.LocalDateTime;

/**
 * 事件日志实体（对应 rpg_event_log 表）
 * <p>
 * append-only，记录每轮的玩家行动、NPC 行为、状态变更和触发器触发。
 */
public class EventLog {

    private Long id;
    private String gameStateId;
    private Integer turn;
    private String eventType;      // player_action / npc_action / state_change / trigger_fired
    private String content;
    private String stateDelta;    // JSON: 该轮的状态变更快照
    private LocalDateTime createdAt;

    public EventLog() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGameStateId() { return gameStateId; }
    public void setGameStateId(String gameStateId) { this.gameStateId = gameStateId; }
    public Integer getTurn() { return turn; }
    public void setTurn(Integer turn) { this.turn = turn; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStateDelta() { return stateDelta; }
    public void setStateDelta(String stateDelta) { this.stateDelta = stateDelta; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
