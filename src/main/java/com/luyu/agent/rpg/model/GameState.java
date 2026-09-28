package com.luyu.agent.rpg.model;

/**
 * 游戏状态实体（对应 rpg_game_state 表）
 * <p>
 * 每回合自动落库；历史剧情按 sessionId 存于 AI_SESSION_EVENT，可跨会话重放。
 * npc_states 存储动态动机值（greed/fear/curiosity）、情绪、武器等。
 */
public class GameState {

    private String id;
    // 关联的 AI 会话 ID，运行时与存档 1:1 绑定；loadGame 虽可换绑，但旧 session 的历史事件不随换绑迁移
    private String sessionId;
    private String worldId;
    private String playerCharId;
    private String currentLocation;
    private Integer turnCount;
    private String flags;           // JSON: {"见过盗贼": true, ...}
    private String npcStates;      // JSON: {"盗贼-001": {status, mood, motives, ...}}
    private String playerStates;   // JSON: {"money": 70, "abilities": {...}, "titles": [...], ...}（PC 结构化状态，money 增量累积）
    private java.time.LocalDateTime createdAt;
    private java.time.LocalDateTime updatedAt;

    public GameState() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
    public String getPlayerCharId() { return playerCharId; }
    public void setPlayerCharId(String playerCharId) { this.playerCharId = playerCharId; }
    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }
    public Integer getTurnCount() { return turnCount; }
    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }
    public String getFlags() { return flags; }
    public void setFlags(String flags) { this.flags = flags; }
    public String getNpcStates() { return npcStates; }
    public void setNpcStates(String npcStates) { this.npcStates = npcStates; }
    public String getPlayerStates() { return playerStates; }
    public void setPlayerStates(String playerStates) { this.playerStates = playerStates; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
