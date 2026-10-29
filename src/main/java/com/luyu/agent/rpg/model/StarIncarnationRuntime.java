package com.luyu.agent.rpg.model;

/**
 * 冥星武将录运行时状态实体（对应 rpg_star_incarnation_runtime 表，存档级）。
 * <p>
 * 记录某存档内名录条目的激活与抢星状态：
 * <ul>
 *   <li>{@code activatedTurn}：0 = 开局抢星起点（对玩家恒亮）；&gt;0 = 关键词命中轮次</li>
 *   <li>{@code claimedByPlayer}：抢星失效——此局该武将不再持此冥衣，星归玩家</li>
 * </ul>
 * {@code starName} 为跨物化稳定键（物化全量重写会换 incarnationId，星名不变）。
 */
public class StarIncarnationRuntime {

    private String id;
    private String gameStateId;
    private String incarnationId;
    private String starName;
    private Integer activatedTurn;
    private Boolean claimedByPlayer;

    public StarIncarnationRuntime() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getGameStateId() { return gameStateId; }
    public void setGameStateId(String gameStateId) { this.gameStateId = gameStateId; }
    public String getIncarnationId() { return incarnationId; }
    public void setIncarnationId(String incarnationId) { this.incarnationId = incarnationId; }
    public String getStarName() { return starName; }
    public void setStarName(String starName) { this.starName = starName; }
    public Integer getActivatedTurn() { return activatedTurn; }
    public void setActivatedTurn(Integer activatedTurn) { this.activatedTurn = activatedTurn; }
    public Boolean getClaimedByPlayer() { return claimedByPlayer; }
    public void setClaimedByPlayer(Boolean claimedByPlayer) { this.claimedByPlayer = claimedByPlayer; }
}
