package com.luyu.agent.rpg.model;

/**
 * 冥星武将录实体（对应 rpg_star_incarnation 表，世界级静态名录）。
 * <p>
 * 真源为 {@code preset-adventure.md} 的 {@code ## 冥星武将录} 小节，
 * 物化时按 {@code world_id} 全量重写（id 每次重写都换新，跨物化的稳定关联键是 {@code starName}）。
 * {@code rollable=false} 表示 NPC 专属星（天兽星/天魔星）：不入可推演池、永不可被玩家抢占。
 */
public class StarIncarnation {

    private String id;
    private String worldId;
    private String starName;
    private String warlordName;
    /** 一句命定叙事 */
    private String lore;
    /** 逗号分隔别名（关键词触发集的一部分） */
    private String aliases;
    /** false = NPC 专属星（永不入池、永不可被抢） */
    private Boolean rollable;
    /** md 内出现顺序（查询排序用） */
    private Integer sortOrder;
    /** 副本层（add-rpg-lorebook-sets）：NULL = 世界模板行；非空 = lorebook set 行 */
    private String setId;
    /** set 行指向模板出处（star_name，跨物化稳定）；原生新增行为 NULL */
    private String sourceRef;
    /** 条目级自由分类（add-star-lorebook-category）：tag-as-string，≤20 字符；NULL = 未分类 */
    private String category;

    public StarIncarnation() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
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
    public String getSetId() { return setId; }
    public void setSetId(String setId) { this.setId = setId; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    /**
     * 规范化分类（add-star-lorebook-category D5）：trim 后空白置 NULL（等价未分类）；
     * trim 后超 20 字符抛 IllegalArgumentException（控制层转 400）。写入路径统一收口。
     */
    public void normalizeCategory() {
        if (category != null) {
            String trimmed = category.trim();
            if (trimmed.isEmpty()) {
                category = null;
            } else if (trimmed.length() > 20) {
                throw new IllegalArgumentException("分类长度超限（≤20 字符）: " + trimmed);
            } else {
                category = trimmed;
            }
        }
    }
}
