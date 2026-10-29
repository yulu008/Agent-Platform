package com.luyu.agent.rpg.model;

import java.time.LocalDateTime;

/**
 * 世界书副本元数据实体（对应 rpg_lorebook_set 表，add-rpg-lorebook-sets D2）。
 * <p>
 * 一个 set = 一套完整双轨名录（冥星武将录 + 通用世界书条目，行挂 {@code set_id} 指向本表）。
 * 生命周期独立于存档：开局时复制模板/源 set 创建；存档删除时<b>孤儿化保留</b>
 * （{@code gameStateId=NULL}），仍可作为新局继承来源。
 * {@code name}/{@code origin} 由出处自动生成（世界默认 / 继承自「X」），无改名 UI。
 */
public class LorebookSet {

    private String id;
    private String worldId;
    /** 展示名（世界默认 / 继承自「X」） */
    private String name;
    /** 出处描述（弹窗抬头出处行） */
    private String origin;
    /** 引用存档；NULL = 孤儿（存档已删但世界书留下） */
    private String gameStateId;
    private LocalDateTime createdAt;

    public LorebookSet() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getGameStateId() { return gameStateId; }
    public void setGameStateId(String gameStateId) { this.gameStateId = gameStateId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
