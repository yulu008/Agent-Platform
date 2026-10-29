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
    private String cover;              // 封面文件名（data/covers/ 目录，{uuid}.{ext}；NULL = 无封面，world-card-gallery）
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    private String themeTokens;        // JSON 对象: HUD 组件主题 token（CSS 变量白名单 + hudStyle 枚举；NULL = 组件默认主题，rpg-hud-bubble-components D10）
    @JsonDeserialize(using = JsonToStringDeserializer.class)
    private String customBlocks;       // JSON 数组: 作者自定义 HUD 内容块（{title, field, accent?}，≤5 块；NULL = 无自定义块，rpg-hud-custom-blocks D1）
    /** 世界特有叙事定制（add-planner-pipeline tasks 5.1：S4「## 叙事规则」安装物化；NULL = 无定制） */
    private String narrativeRules;
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
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getThemeTokens() { return themeTokens; }
    public void setThemeTokens(String themeTokens) { this.themeTokens = themeTokens; }
    public String getCustomBlocks() { return customBlocks; }
    public void setCustomBlocks(String customBlocks) { this.customBlocks = customBlocks; }
    public String getNarrativeRules() { return narrativeRules; }
    public void setNarrativeRules(String narrativeRules) { this.narrativeRules = narrativeRules; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
