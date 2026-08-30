package com.luyu.agent.rpg.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 世界观设定实体（对应 rpg_world_setting 表）
 */
public class WorldSetting {

    private String id;
    private String name;
    private String settingDesc;
    private String era;
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    private String rules;           // JSON 数组
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    private String locations;      // JSON 数组: 地点 ID 列表
    private String atmosphere;
    private String openingTemplate;  // 开场白模板（含 {{}} 占位符）
    private LocalDateTime createdAt;

    public WorldSetting() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSettingDesc() { return settingDesc; }
    public void setSettingDesc(String settingDesc) { this.settingDesc = settingDesc; }
    public String getEra() { return era; }
    public void setEra(String era) { this.era = era; }
    public String getRules() { return rules; }
    public void setRules(String rules) { this.rules = rules; }
    public String getLocations() { return locations; }
    public void setLocations(String locations) { this.locations = locations; }
    public String getAtmosphere() { return atmosphere; }
    public void setAtmosphere(String atmosphere) { this.atmosphere = atmosphere; }
    public String getOpeningTemplate() { return openingTemplate; }
    public void setOpeningTemplate(String openingTemplate) { this.openingTemplate = openingTemplate; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
