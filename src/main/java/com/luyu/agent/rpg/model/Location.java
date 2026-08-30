package com.luyu.agent.rpg.model;

import java.time.LocalDateTime;

/**
 * 地点实体（对应 rpg_location 表）
 */
public class Location {

    private String id;
    private String worldId;
    private String name;
    private String description;
    private String npcIds;         // JSON 数组: NPC ID 列表
    private String triggerIds;     // JSON 数组: 触发器 ID 列表
    private LocalDateTime createdAt;

    public Location() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getNpcIds() { return npcIds; }
    public void setNpcIds(String npcIds) { this.npcIds = npcIds; }
    public String getTriggerIds() { return triggerIds; }
    public void setTriggerIds(String triggerIds) { this.triggerIds = triggerIds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
