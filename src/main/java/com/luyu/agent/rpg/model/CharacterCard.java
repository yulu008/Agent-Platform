package com.luyu.agent.rpg.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 角色卡实体（对应 rpg_character_card 表）
 * <p>
 * type=player 为玩家角色，type=npc 为 NPC 角色。
 */
public class CharacterCard {

    private String id;
    private String worldId;
    private String name;
    private String type;           // player / npc
    private String identity;
    private String personality;
    private String background;
    private String motivation;
    private String speechStyle;
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    private String knowledge;      // JSON 数组: 知识领域列表
    private java.time.LocalDateTime createdAt;

    public CharacterCard() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public String getMotivation() { return motivation; }
    public void setMotivation(String motivation) { this.motivation = motivation; }
    public String getSpeechStyle() { return speechStyle; }
    public void setSpeechStyle(String speechStyle) { this.speechStyle = speechStyle; }
    public String getKnowledge() { return knowledge; }
    public void setKnowledge(String knowledge) { this.knowledge = knowledge; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.LocalDateTime createdAt) { this.createdAt = createdAt; }
}
