package com.luyu.agent.rpg.story.model;

import java.time.LocalDateTime;

/**
 * 故事图运行副本（对应 rpg_story_run 表，存档级）。
 * <p>
 * 开局 = 模板图物化运行副本（design D8）：玩家游走只写本表（当前节点/
 * 已走边/物化正文缓存/即兴深度），不污染模板层。回溯 = 图上回退分叉点 +
 * 确定性重放（初始态 + 保留已走边 effects 依次结算），不存全量状态快照。
 * <ul>
 *   <li>{@code graphId}：模板图引用（运行期结构共享，模板不变则无副本必要）</li>
 *   <li>{@code currentNode}：当前所在节点 ID（null = 尚未进入图/开局前）</li>
 *   <li>{@code walkedEdges}：已走边 JSON 数组（边 ID 有序列表——重放按序结算）</li>
 *   <li>{@code materializedBodies}：物化缓存 JSON：{nodeId: {pathHash, narration}}；
 *       缓存命中仅当到达路径 hash 一致（不同来路/状态强制重物化）</li>
 *   <li>{@code improviseDepth}：连续纯即兴轮计数（走图边即重置；超上限注入收敛指令）</li>
 *   <li>{@code rematerialize}：多周目配置——true = 强制重新物化（不复用缓存）</li>
 *   <li>{@code pendingGate}：轨道闸收敛指令暂存（跨幕发散时写入，物化注入轮消费即清）</li>
 *   <li>{@code initialPlayerStates}：开局基线（挂图结算前原始 playerStates，4.8 回溯确定性重放起点；仅开局写入不可变）</li>
 * </ul>
 */
public class StoryRun {

    private String id;
    private String tenantId;

    /** 关联游戏存档（rpg_game_state.id），一存档一运行副本 */
    private String gameStateId;

    /** 模板图引用（rpg_story_graph.id；模板层不可变语义由校验链保证） */
    private String graphId;

    /** 当前节点 ID；null = 开局前（首节点由入口物化确定） */
    private String currentNode;

    /** 已走边 JSON：["e1_01","e1_03",...]（按游走顺序，确定性重放的结算序列） */
    private String walkedEdges;

    /** 物化正文缓存 JSON：{nodeId: {pathHash, narration}} */
    private String materializedBodies;

    /** 连续纯即兴轮计数（bounded 模式锚点引力/深度上限依据） */
    private Integer improviseDepth;

    /** 强制重新物化（多周目配置：true 不复用既有缓存） */
    private Boolean rematerialize;

    /** 轨道闸收敛指令暂存（4.7：跨幕发散时引擎写入，物化注入轮消费即清——一次性） */
    private String pendingGate;

    /**
     * 开局基线（4.8 图上回溯）：挂图结算入口 on_enter 前的原始 playerStates。
     * 确定性重放链 = 本基线 + 入口 on_enter + 保留已走边 effects 依次结算；
     * 仅开局保存一次，回退不改（UPDATE 不携带本列）。旧数据缺省 null → 重放容错空态。
     */
    private String initialPlayerStates;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public StoryRun() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getGameStateId() { return gameStateId; }
    public void setGameStateId(String gameStateId) { this.gameStateId = gameStateId; }
    public String getGraphId() { return graphId; }
    public void setGraphId(String graphId) { this.graphId = graphId; }
    public String getCurrentNode() { return currentNode; }
    public void setCurrentNode(String currentNode) { this.currentNode = currentNode; }
    public String getWalkedEdges() { return walkedEdges; }
    public void setWalkedEdges(String walkedEdges) { this.walkedEdges = walkedEdges; }
    public String getMaterializedBodies() { return materializedBodies; }
    public void setMaterializedBodies(String materializedBodies) { this.materializedBodies = materializedBodies; }
    public Integer getImproviseDepth() { return improviseDepth; }
    public void setImproviseDepth(Integer improviseDepth) { this.improviseDepth = improviseDepth; }
    public Boolean getRematerialize() { return rematerialize; }
    public void setRematerialize(Boolean rematerialize) { this.rematerialize = rematerialize; }
    public String getPendingGate() { return pendingGate; }
    public void setPendingGate(String pendingGate) { this.pendingGate = pendingGate; }
    public String getInitialPlayerStates() { return initialPlayerStates; }
    public void setInitialPlayerStates(String initialPlayerStates) { this.initialPlayerStates = initialPlayerStates; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
