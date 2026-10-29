package com.luyu.agent.rpg.model;

/**
 * 共享包内冥星武将录条目（对应 shared_star_entry 表）。
 * <p>
 * 发布时点的模板行内容快照（design D1）。{@code starName} 是揭示/抢星 runtime 的
 * 跨物化稳定关联键，导入时保留原值（导入前置校验会拒绝与目标世界已有星同名）。
 */
public class SharedStarEntry {

    private String id;
    private String packId;
    /** 冥星名（如天魁星；runtime 稳定关联键，导入保留） */
    private String starName;
    /** 命定武将（如织田信长） */
    private String warlordName;
    /** 一句命定叙事 */
    private String lore;
    /** 逗号分隔别名（关键词触发集的一部分） */
    private String aliases;
    /** false = NPC 专属星（永不入池、永不可被抢） */
    private Boolean rollable;
    /** 排序 */
    private Integer sortOrder;
    /** 条目级自由分类（add-star-lorebook-category）：tag-as-string，≤20 字符；NULL = 未分类（旧包兼容） */
    private String category;

    public SharedStarEntry() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPackId() { return packId; }
    public void setPackId(String packId) { this.packId = packId; }
    public String getStarName() { return starName; }
    public void setStarName(String starName) { this.starName = starName; }
    public String getWarlordName() { return warlordName; }
    public void setWarlordName(String warlordName) { this.warlordName = warlordName; }
    public String getLore() { return lore; }
    public void setLore(String lore) { this.lore = lore; }
    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }
    public Boolean getRollable() { return rollable; }
    public void setRollable(Boolean rollable) { this.rollable = rollable; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}
