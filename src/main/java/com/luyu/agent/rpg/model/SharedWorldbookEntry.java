package com.luyu.agent.rpg.model;

/**
 * 共享包内通用世界书条目（对应 shared_worldbook_entry 表）。
 * <p>
 * 发布时点的模板行内容快照（design D1）：此后源模板增删改不影响包内行，直至发布者覆盖更新。
 * 导入时逐行复制为接收方租户的 rpg_worldbook_entry 模板行（新 id、world_id 重绑、source_ref 置 NULL）。
 */
public class SharedWorldbookEntry {

    private String id;
    private String packId;
    /** 条目标题/主名词（快照自发布方模板行） */
    private String title;
    /** 逗号分隔关键词触发集 */
    private String keywords;
    /** 命中时注入 GM 的设定正文 */
    private String content;
    /** 软开关：false 时扫描跳过；null 视为启用 */
    private Boolean enabled;
    /** 展示/注入排序 */
    private Integer sortOrder;

    public SharedWorldbookEntry() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPackId() { return packId; }
    public void setPackId(String packId) { this.packId = packId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
