package com.luyu.agent.rpg.model;

/**
 * 命定人物实体（对应 rpg_destined_figure 表，世界级静态档案）。
 * <p>
 * 真源为 {@code preset-adventure.md} 的 {@code ## 命定人物} 小节，
 * 物化时按 {@code world_id} 全量重写（id 每次重写都换新，跨物化的稳定关联键是 {@code figureKey}）。
 * 星命三字段（faction/constellation/starRank）为 null 表示<b>自由推演模式</b>：
 * 星命由玩家生辰推演产出，不绑定预置星。
 */
public class DestinedFigure {

    private String id;
    private String worldId;
    /** 跨物化稳定键：kagetora/harunobu/nobunaga/nobukatsu */
    private String figureKey;
    /** 人物名（如长尾景虎） */
    private String displayName;
    /** null = 自由推演（三阵营任选，星命随生辰） */
    private String faction;
    /** null = 自由推演 */
    private String constellation;
    /** null = 自由推演 */
    private String starRank;
    /** 1556 年史实处境一句话（人物开局个性化开场白与 UI 展示用） */
    private String eraNote;
    /** 三件套底稿：身份 */
    private String identity;
    /** 三件套底稿：性格 */
    private String personality;
    /** 三件套底稿：背景 */
    private String background;
    /** 逗号分隔别名 */
    private String aliases;
    /** md 内出现顺序（查询排序用） */
    private Integer sortOrder;

    public DestinedFigure() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }

    public String getFigureKey() { return figureKey; }
    public void setFigureKey(String figureKey) { this.figureKey = figureKey; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getFaction() { return faction; }
    public void setFaction(String faction) { this.faction = faction; }

    public String getConstellation() { return constellation; }
    public void setConstellation(String constellation) { this.constellation = constellation; }

    public String getStarRank() { return starRank; }
    public void setStarRank(String starRank) { this.starRank = starRank; }

    public String getEraNote() { return eraNote; }
    public void setEraNote(String eraNote) { this.eraNote = eraNote; }

    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }

    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }

    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }

    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }

    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
