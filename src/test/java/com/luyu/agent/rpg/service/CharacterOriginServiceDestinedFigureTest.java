package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.OriginResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CharacterOriginService#calculate(String, String, LocalDate, int, String, int, DestinedFigure)}
 * 命定人物模式的单元测试（preset-destined-figures tasks 4.4）。
 * <p>
 * 钉住 design D2 处理矩阵：预绑圣衣/预绑冥衣跳漏斗取档案星命、自由推演走现行漏斗、
 * 姓名一律覆盖为档案人物名、figure 缺省转发现行重载（向后兼容）、同输入同 seed 确定性。
 */
class CharacterOriginServiceDestinedFigureTest {

    /** 任一生辰：人物模式的玩家生辰照填（小宇宙与自由推演漏斗用） */
    private static final LocalDate BIRTH = LocalDate.of(1534, 6, 15);

    private final CharacterOriginService service = new CharacterOriginService();

    private static DestinedFigure figure(String key, String displayName, String faction,
                                          String constellation, String starRank) {
        DestinedFigure f = new DestinedFigure();
        f.setFigureKey(key);
        f.setDisplayName(displayName);
        f.setFaction(faction);
        f.setConstellation(constellation);
        f.setStarRank(starRank);
        f.setEraNote("弘治二年，稻生合战未起");
        f.setIdentity("测试身份");
        f.setPersonality("测试性格");
        f.setBackground("测试背景");
        return f;
    }

    // ==================== 预绑圣衣（kagetora/harunobu） ====================

    @Test
    void 预绑圣衣人物跳漏斗取档案星命且姓名被覆盖() {
        DestinedFigure kagetora = figure("kagetora", "长尾景虎", "saint", "处女座", "Gold");
        // 用户传的 name/faction 一律被档案覆盖（子时/specter 不得影响结果）
        OriginResult r = service.calculate("随便填", "male", BIRTH, 0, "specter", 3, kagetora);

        assertThat(r.getFaction()).isEqualTo("saint");
        assertThat(r.getConstellation()).isEqualTo("处女座");
        assertThat(r.getStarRank()).isEqualTo("Gold");
        // 特质按星座名查 88 星座表（处女座 Gold 行）
        assertThat(r.getZodiacTraits()).isEqualTo("严谨细致、追求完美");
        // 人物身份三字段落位
        assertThat(r.getFigureKey()).isEqualTo("kagetora");
        assertThat(r.getFigureDisplayName()).isEqualTo("长尾景虎");
        assertThat(r.getFigureEraNote()).isEqualTo("弘治二年，稻生合战未起");
        // 三件套底稿 = 人物档案（前端步骤②预填）
        assertThat(r.getIdentitySuggestion()).isEqualTo("测试身份");
        assertThat(r.getPersonalitySuggestion()).isEqualTo("测试性格");
        assertThat(r.getBackgroundSuggestion()).isEqualTo("测试背景");
        // 小宇宙按锁定 Gold 区间掷骰（保留推演仪式感）
        assertThat(r.getCosmoIntensity()).isBetween(301, 700);
    }

    @Test
    void 预绑圣衣双子人物特质取星座表() {
        DestinedFigure harunobu = figure("harunobu", "武田晴信", "saint", "双子座", "Gold");
        OriginResult r = service.calculate("武田", "male", BIRTH, 0, "saint", 0, harunobu);

        assertThat(r.getConstellation()).isEqualTo("双子座");
        assertThat(r.getZodiacTraits()).isEqualTo("机敏多变、善于交际");
        assertThat(r.getStarBonus()).isNotNull();
    }

    // ==================== 预绑冥衣（nobunaga） ====================

    @Test
    void 预绑冥衣人物产出specter天魁星() {
        DestinedFigure nobunaga = figure("nobunaga", "织田信长", "specter", "天魁星", "Specter-TianGang");
        OriginResult r = service.calculate("信长", "male", BIRTH, 0, "saint", 0, nobunaga);

        assertThat(r.getFaction()).isEqualTo("specter");
        assertThat(r.getConstellation()).isEqualTo("天魁星");
        assertThat(r.getStarRank()).isEqualTo("Specter-TianGang");
        // 天罡特质按星名查天罡表（天魁星首行）
        assertThat(r.getZodiacTraits()).isEqualTo("义薄云天、领袖群伦");
        // 天罡小宇宙区间 [250, 300]
        assertThat(r.getCosmoIntensity()).isBetween(250, 300);
    }

    // ==================== 自由推演（nobukatsu） ====================

    @Test
    void 自由推演人物走现行漏斗星命随生辰() {
        DestinedFigure nobukatsu = figure("nobukatsu", "织田信胜", null, null, null);
        OriginResult r = service.calculate("信胜", "male", BIRTH, 6, "saint", 0, nobukatsu);

        // 阵营取用户传的（三阵营任选），漏斗照常：6 月午时 → 双子季 Gold
        assertThat(r.getFaction()).isEqualTo("saint");
        assertThat(r.getStarRank()).isEqualTo("Gold");
        assertThat(r.getConstellation()).isNotBlank();
        // 姓名仍覆盖为档案人物名
        assertThat(r.getFigureDisplayName()).isEqualTo("织田信胜");
        assertThat(r.getFigureKey()).isEqualTo("nobukatsu");
        // 三件套底稿仍取档案（人物身份与星命解耦）
        assertThat(r.getBackgroundSuggestion()).isEqualTo("测试背景");
        // 与无名新人同生辰同姓名同阵营的星座一致——漏斗不被人物模式干扰
        OriginResult anonymous = service.calculate("织田信胜", "male", BIRTH, 6, "saint", 0);
        assertThat(r.getConstellation()).isEqualTo(anonymous.getConstellation());
    }

    // ==================== 缺省兼容与确定性 ====================

    @Test
    void figure为null转发现行重载人物字段全空() {
        OriginResult r = service.calculate("卢煜", "male", BIRTH, 6, "saint", 0, null);
        OriginResult legacy = service.calculate("卢煜", "male", BIRTH, 6, "saint", 0);

        assertThat(r.getConstellation()).isEqualTo(legacy.getConstellation());
        assertThat(r.getCosmoIntensity()).isEqualTo(legacy.getCosmoIntensity());
        assertThat(r.getStarBonus()).isEqualTo(legacy.getStarBonus());
        // 向后兼容：响应无人物字段（序列化 null 语义由 controller 层验证）
        assertThat(r.getFigureKey()).isNull();
        assertThat(r.getFigureDisplayName()).isNull();
        assertThat(r.getFigureEraNote()).isNull();
    }

    @Test
    void 人物模式用户侧差异不进计算同seed同值() {
        DestinedFigure nobunaga = figure("nobunaga", "织田信长", "specter", "天魁星", "Specter-TianGang");
        OriginResult a = service.calculate("x", "male", BIRTH, 4, "specter", 7, nobunaga);
        OriginResult b = service.calculate("y", "female", BIRTH, 4, "saint", 7, nobunaga);

        // 姓名/阵营/性别差异被档案覆盖或与掷骰要素无关（spec：小宇宙仍以玩家提交的生辰
        // 与姓名作种子——生辰含时辰故两次同时辰；姓名取档案人物名，用户侧姓名不进哈希）
        assertThat(a.getConstellation()).isEqualTo(b.getConstellation());
        assertThat(a.getStarRank()).isEqualTo(b.getStarRank());
        assertThat(a.getCosmoIntensity()).isEqualTo(b.getCosmoIntensity());
        assertThat(a.getStarBonus()).isEqualTo(b.getStarBonus());
    }
}
