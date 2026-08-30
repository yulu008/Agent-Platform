package com.luyu.agent.rpg.model;

/**
 * 游戏状态实体（对应 rpg_game_state 表）
 * <p>
 * 独立于 session，支持跨会话加载。
 * npc_states 存储动态动机值（greed/fear/curiosity）、情绪、武器等。
 */
public class GameState {

    private String id;
    private String sessionId;       // 可变，支持跨会话
    private String worldId;
    private String playerCharId;
    private String currentLocation;
    private Integer turnCount;
    private String flags;           // JSON: {"见过盗贼": true, ...}
    private String npcStates;      // JSON: {"盗贼-001": {status, mood, motives, ...}}
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
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
