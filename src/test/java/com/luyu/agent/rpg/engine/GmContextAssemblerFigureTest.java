package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GmContextAssembler#formatStarDestiny(String)} 命定身份行测试
 * （preset-destined-figures tasks 5.2/5.5）。
 * <p>
 * 钉住三件事：人物模式存档（figure_key 非空）星命行追加
 * 「命定身份：织田信长——&lt;eraNote&gt;」；eraNote 缺失时不带破折号尾；
 * 无名新人/旧档（无 figure 键）星命行零改动。figure 三键由
 * {@code WorkshopService#buildPlayerStatesJson} 开局写入（该侧另有测试）。
 * formatStarDestiny 为纯函数，故 {@code GmContextAssembler} 传 null 仓库即可。
 */
class GmContextAssemblerFigureTest {

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);

    @Test
    void 人物模式星命行追加命定身份与时代处境() {
        String line = assembler.formatStarDestiny("""
                {"constellation":"天魁星","star_rank":"Specter","faction":"specter",
                 "zodiac_traits":"义薄云天、领袖群伦",
                 "figure_key":"nobunaga","figure_display_name":"织田信长",
                 "figure_era_note":"弘治二年二十二岁，稻生合战未起"}""");

        assertThat(line)
                .contains("星命：天魁星 | Specter | specter | 义薄云天、领袖群伦")
                .contains("命定身份：织田信长——弘治二年二十二岁，稻生合战未起");
    }

    @Test
    void eraNote缺失_仅渲染人物名不带破折号尾() {
        String line = assembler.formatStarDestiny(
                "{\"constellation\":\"处女座\",\"star_rank\":\"Gold\",\"faction\":\"saint\","
                        + "\"zodiac_traits\":\"沉稳坚韧\",\"figure_key\":\"kagetora\","
                        + "\"figure_display_name\":\"长尾景虎\"}");

        assertThat(line).contains("命定身份：长尾景虎");
        assertThat(line).doesNotContain("——");
    }

    @Test
    void 无名新人存档_无figure键_星命行零改动() {
        String line = assembler.formatStarDestiny(
                "{\"constellation\":\"白羊座\",\"star_rank\":\"Bronze\",\"faction\":\"saint\","
                        + "\"zodiac_traits\":\"热情冲动\"}");

        assertThat(line)
                .contains("星命：白羊座 | Bronze | saint | 热情冲动")
                .doesNotContain("命定身份");

        // playerStates 为空串（非星命开局）：行为不变
        assertThat(assembler.formatStarDestiny("")).isEmpty();
    }
}
