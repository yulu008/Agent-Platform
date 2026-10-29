package com.luyu.agent.rpg.model;

import java.util.List;
import java.util.Map;

/**
 * 星命计算结果。
 * <p>
 * 由 {@link com.luyu.agent.rpg.service.CharacterOriginService} 确定性计算产生，
 * 包含星座/星辰归属、星阶、阵营、星座特质、属性加成及角色卡富化建议。
 * 不调用 LLM，纯本地运算。
 */
public class OriginResult {

    /** 星座/星辰名（如"白羊座"、"天魁星"、"天枢"） */
    private String constellation;

    /** 星阶（Gold/Silver/Bronze/Hidden/Specter/GodWarrior） */
    private String starRank;

    /** 阵营（saint/specter/godwarrior） */
    private String faction;

    /** 星座特质描述（如"热情冲动、勇往直前"） */
    private String zodiacTraits;

    /** 属性加成（key=属性英文名, value=加成值，如 {"strength": 15}） */
    private Map<String, Integer> starBonus;

    /** 角色卡 identity 建议（如"白羊座黄金圣斗士"） */
    private String identitySuggestion;

    /** 角色卡 personality 建议（星座特质展开） */
    private String personalitySuggestion;

    /** 角色卡 background 建议（命定叙事背景） */
    private String backgroundSuggestion;

    /** 星座/星辰招式列表（含建档初始发放的 learned/proficiency 状态，rpg-technique-tree-data） */
    private List<TechniqueState> techniques;

    /**
     * 小宇宙强度初值（rpg-cosmo-sense）。
     * <p>
     * 由 hash(姓名, 生辰, 时辰, 阵营, 星阶, rollSeed) 确定性映射到星阶组起始区间产出，
     * 创角不变量 ∈ [1, 700]（起始感阶至多第七感）。两遍计算（origin 展示 / start 落档）同值。
     */
    private Integer cosmoIntensity;

    /**
     * 命定人物键（preset-destined-figures）：kagetora/harunobu/nobunaga/nobukatsu。
     * <p>
     * 仅人物模式非空（缺省 = 无名新人，现行流程零改动）；落 playerStates 后
     * GM 上下文、抢星联动与开场白均以它感知“玩家扮演的是谁”。
     */
    private String figureKey;

    /** 命定人物名（如“织田信长”），人物模式非空；星命计算的姓名一律用它覆盖用户输入 */
    private String figureDisplayName;

    /** 命定人物的时代处境一句话（如“弘治二年二十二岁，新取清洲……”），人物模式非空 */
    private String figureEraNote;

    public OriginResult() {
    }

    public String getConstellation() { return constellation; }
    public void setConstellation(String constellation) { this.constellation = constellation; }

    public String getStarRank() { return starRank; }
    public void setStarRank(String starRank) { this.starRank = starRank; }

    public String getFaction() { return faction; }
    public void setFaction(String faction) { this.faction = faction; }

    public String getZodiacTraits() { return zodiacTraits; }
    public void setZodiacTraits(String zodiacTraits) { this.zodiacTraits = zodiacTraits; }

    public Map<String, Integer> getStarBonus() { return starBonus; }
    public void setStarBonus(Map<String, Integer> starBonus) { this.starBonus = starBonus; }

    public String getIdentitySuggestion() { return identitySuggestion; }
    public void setIdentitySuggestion(String identitySuggestion) { this.identitySuggestion = identitySuggestion; }

    public String getPersonalitySuggestion() { return personalitySuggestion; }
    public void setPersonalitySuggestion(String personalitySuggestion) { this.personalitySuggestion = personalitySuggestion; }

    public String getBackgroundSuggestion() { return backgroundSuggestion; }
    public void setBackgroundSuggestion(String backgroundSuggestion) { this.backgroundSuggestion = backgroundSuggestion; }

    public List<TechniqueState> getTechniques() { return techniques; }
    public void setTechniques(List<TechniqueState> techniques) { this.techniques = techniques; }

    public Integer getCosmoIntensity() { return cosmoIntensity; }
    public void setCosmoIntensity(Integer cosmoIntensity) { this.cosmoIntensity = cosmoIntensity; }

    public String getFigureKey() { return figureKey; }
    public void setFigureKey(String figureKey) { this.figureKey = figureKey; }

    public String getFigureDisplayName() { return figureDisplayName; }
    public void setFigureDisplayName(String figureDisplayName) { this.figureDisplayName = figureDisplayName; }

    public String getFigureEraNote() { return figureEraNote; }
    public void setFigureEraNote(String figureEraNote) { this.figureEraNote = figureEraNote; }
}
