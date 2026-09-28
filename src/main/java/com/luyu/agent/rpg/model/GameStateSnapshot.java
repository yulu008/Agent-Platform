package com.luyu.agent.rpg.model;

/**
 * 游戏状态快照实体（对应 rpg_game_state_snapshot 表）。
 * <p>
 * 每轮 {@code prepareTurn} 开始时存一份 turn=N-1 的状态副本（首个回合存 turn=0 初始态），
 * 回溯时用它覆盖 {@code rpg_game_state} 实现状态回滚。字段与 GameState 的可变部分同构。
 */
public class GameStateSnapshot {

    private String gameStateId;
    private Integer turn;
    private String currentLocation;
    private String flags;           // JSON: {"见过盗贼": true, ...}
    private String npcStates;       // JSON: {"盗贼-001": {status, mood, ...}}
    private String playerStates;    // JSON: 与 GameState.playerStates 同构（变更前旧快照为 NULL）
    private java.time.LocalDateTime createdAt;

    public String getGameStateId() { return gameStateId; }
    public void setGameStateId(String gameStateId) { this.gameStateId = gameStateId; }
    public Integer getTurn() { return turn; }
    public void setTurn(Integer turn) { this.turn = turn; }
    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }
    public String getFlags() { return flags; }
    public void setFlags(String flags) { this.flags = flags; }
    public String getNpcStates() { return npcStates; }
    public void setNpcStates(String npcStates) { this.npcStates = npcStates; }
    public String getPlayerStates() { return playerStates; }
    public void setPlayerStates(String playerStates) { this.playerStates = playerStates; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
}
