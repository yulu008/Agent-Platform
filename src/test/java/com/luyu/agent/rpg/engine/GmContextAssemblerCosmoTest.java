package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link GmContextAssembler#formatStarDestiny(String)} 小宇宙摘要行测试（rpg-cosmo-sense tasks 4.4）。
 * <p>
 * 钉住两件事：星命存档摘要行追加「小宇宙 N（X感）」且措辞取自 {@code CosmoSense} 单一真源；
 * 无 cosmo 键（非星命 / 旧档）时不追加且不抛异常。formatStarDestiny 为纯函数，
 * 故 {@code GmContextAssembler} 可传 null 仓库直接构造。
 */
class GmContextAssemblerCosmoTest {

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);

    @Test
    void 星命存档摘要行含小宇宙措辞() {
        String line = assembler.formatStarDestiny(
                "{\"constellation\":\"金牛座\",\"star_rank\":\"Gold\",\"faction\":\"saint\","
                        + "\"zodiac_traits\":\"沉稳坚韧\",\"cosmo\":339}");

        assertThat(line)
                .contains("星命：金牛座 | Gold | saint | 沉稳坚韧")
                .contains("小宇宙 339（第七感）");
    }

    @Test
    void 小宇宙措辞随分档变化() {
        assertThat(assembler.formatStarDestiny(
                "{\"constellation\":\"白羊座\",\"star_rank\":\"Bronze\",\"cosmo\":268}"))
                .contains("小宇宙 268（第六感）");
        assertThat(assembler.formatStarDestiny(
                "{\"constellation\":\"白羊座\",\"star_rank\":\"Bronze\",\"cosmo\":820}"))
                .contains("小宇宙 820（第八感）");
    }

    @Test
    void 无cosmo键不追加小宇宙行() {
        String line = assembler.formatStarDestiny(
                "{\"constellation\":\"白羊座\",\"star_rank\":\"Bronze\",\"faction\":\"saint\","
                        + "\"zodiac_traits\":\"热情冲动\"}");

        assertThat(line)
                .contains("星命：白羊座")
                .doesNotContain("小宇宙");
    }

    @Test
    void 无星命数据返回空串且不抛异常() {
        assertThatCode(() -> {
            assertThat(assembler.formatStarDestiny("{\"money\":100}")).isEmpty();
            assertThat(assembler.formatStarDestiny("{}")).isEmpty();
            assertThat(assembler.formatStarDestiny("")).isEmpty();
            assertThat(assembler.formatStarDestiny(null)).isEmpty();
        }).doesNotThrowAnyException();
    }
}
