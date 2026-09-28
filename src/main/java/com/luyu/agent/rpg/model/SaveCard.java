package com.luyu.agent.rpg.model;

/**
 * 存档卡片 DTO（GET /rpg/game/saves）。
 * <p>
 * 面向前端存档卡片列表的富化视图：由后端 join 世界名与角色名，
 * 字段缺失时回退占位符 "-"，前端无需二次拼装。
 */
public class SaveCard {

    private String gameStateId;
    private String sessionId;
    private String worldId;
    private String worldName;
    private String playerCharName;
    private String currentLocation;
    private Integer turnCount;
    private java.time.LocalDateTime lastPlayedAt;

    public SaveCard() {
    }

    public SaveCard(String gameStateId, String sessionId, String worldId, String worldName,
                    String playerCharName, String currentLocation, Integer turnCount,
                    java.time.LocalDateTime lastPlayedAt) {
        this.gameStateId = gameStateId;
        this.sessionId = sessionId;
        this.worldId = worldId;
        this.worldName = worldName;
        this.playerCharName = playerCharName;
        this.currentLocation = currentLocation;
        this.turnCount = turnCount;
        this.lastPlayedAt = lastPlayedAt;
    }

    public String getGameStateId() { return gameStateId; }
    public void setGameStateId(String gameStateId) { this.gameStateId = gameStateId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }

    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }

    public String getPlayerCharName() { return playerCharName; }
    public void setPlayerCharName(String playerCharName) { this.playerCharName = playerCharName; }

    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }

    public Integer getTurnCount() { return turnCount; }
    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }

    public java.time.LocalDateTime getLastPlayedAt() { return lastPlayedAt; }
    public void setLastPlayedAt(java.time.LocalDateTime lastPlayedAt) { this.lastPlayedAt = lastPlayedAt; }
}
