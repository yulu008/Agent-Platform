package com.luyu.agent.rpg.model;

/**
 * 存档卡片 DTO（GET /rpg/game/saves）。
 * <p>
 * 面向前端存档卡片列表的富化视图：由后端 join 世界名与角色名，
 * 字段缺失时回退占位符 "-"，前端无需二次拼装。
 * <p>
 * lastMessage 为读时富化的最近一行可回放对话（conversation-world-title-preview）：
 * 取数规则与 /game/history 回放一致，服务端限长截断；<b>null 表示无可回放消息</b>
 * （无 sessionId / 空会话 / 查询失败），是“无内容”语义而非占位符——前端跳过预览行。
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
    /** 最近一行可回放对话（读时富化，限长截断）；null = 无可回放消息，前端不渲染预览行 */
    private String lastMessage;

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

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
}
