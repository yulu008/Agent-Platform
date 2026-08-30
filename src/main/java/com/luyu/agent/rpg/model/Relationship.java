package com.luyu.agent.rpg.model;

import java.time.LocalDateTime;

/**
 * 角色间关系实体（对应 rpg_relationship 表）
 * <p>
 * attitude: -100~100（敌对~友好），trust: 0~100
 */
public class Relationship {

    private String id;
    private String charAId;
    private String charBId;
    private Integer attitude;      // -100 ~ 100
    private Integer trust;         // 0 ~ 100
    private String notes;
    private LocalDateTime createdAt;

    public Relationship() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCharAId() { return charAId; }
    public void setCharAId(String charAId) { this.charAId = charAId; }
    public String getCharBId() { return charBId; }
    public void setCharBId(String charBId) { this.charBId = charBId; }
    public Integer getAttitude() { return attitude; }
    public void setAttitude(Integer attitude) { this.attitude = attitude; }
    public Integer getTrust() { return trust; }
    public void setTrust(Integer trust) { this.trust = trust; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
