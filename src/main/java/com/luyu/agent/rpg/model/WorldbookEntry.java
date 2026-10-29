package com.luyu.agent.rpg.model;

/**
 * 通用世界书条目实体（对应 rpg_worldbook_entry 表，世界级可编辑）。
 * <p>
 * 与冥星武将录（{@link StarIncarnation}，只读、sticky、md 真源）不同：本条目由工坊 CRUD 维护、
 * 面向任意世界、采用<b>瞬时注入</b>（仅命中轮注入 GM 上下文，无跨轮状态，故无 runtime 表）。
 * {@code title} 既是展示名也是关键词之一；{@code keywords} 为逗号分隔的额外触发词集。
 */
public class WorldbookEntry {

    private String id;
    private String worldId;
    /** 条目标题/主名词（展示名，同时作为关键词参与匹配） */
    private String title;
    /** 逗号分隔的关键词触发集（子串匹配） */
    private String keywords;
    /** 命中时注入 GM 的设定正文 */
    private String content;
    /** 软开关：false 时扫描跳过（保留数据不删）；null 视为启用 */
    private Boolean enabled;
    /** 展示/注入排序 */
    private Integer sortOrder;
    /** 副本层（add-rpg-lorebook-sets）：NULL = 世界模板行；非空 = lorebook set 行 */
    private String setId;
    /** set 行指向模板出处（模板行 id）；原生新增行为 NULL */
    private String sourceRef;

    public WorldbookEntry() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
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
    public String getSetId() { return setId; }
    public void setSetId(String setId) { this.setId = setId; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
}
