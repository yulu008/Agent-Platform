package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * GM 上下文招式树三段渲染测试（rpg-technique-tree-data tasks 6.3）。
 * <p>
 * 钉住三件事：新建档（1 招已学）渲染出已会/可悟/封印三段且条件文案正确；
 * 满配档（Gold 全根、招式总数 &gt; 6）封印段降级为只列名字；
 * 无 techniques 的 playerStates 不渲染招式段且不抛异常。
 */
class GmContextAssemblerTechniqueTest {

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);

    /** 天马座 Bronze 新建档：仅天马流星拳已学 200 */
    private static final String BRONZE_SAVE = "{\"constellation\":\"天马座\",\"star_rank\":\"Bronze\","
            + "\"faction\":\"saint\",\"zodiac_traits\":\"热情冲动\",\"techniques\":["
            + "{\"name\":\"天马流星拳\",\"description\":\"d\",\"source\":\"经典系列\",\"tier\":1,"
            + "\"prerequisites\":[],\"learned\":true,\"proficiency\":200},"
            + "{\"name\":\"天马彗星拳\",\"description\":\"d\",\"source\":\"经典系列\",\"tier\":2,"
            + "\"prerequisites\":[\"天马流星拳\"],\"learned\":false,\"proficiency\":0},"
            + "{\"name\":\"天马回旋碎击拳\",\"description\":\"d\",\"source\":\"经典系列\",\"tier\":2,"
            + "\"prerequisites\":[\"天马流星拳\"],\"learned\":false,\"proficiency\":0},"
            + "{\"name\":\"天马闪光拳\",\"description\":\"d\",\"source\":\"经典系列\",\"tier\":3,"
            + "\"prerequisites\":[\"天马彗星拳\",\"天马回旋碎击拳\"],\"learned\":false,\"proficiency\":0}]}";

    @Test
    void 新建档渲染已会可悟封印三段() {
        String line = assembler.formatStarDestiny(BRONZE_SAVE);

        assertThat(line).contains("招式（天马座）：");
        assertThat(line).contains("已会：天马流星拳 200/入门");
        assertThat(line).contains("可悟：天马彗星拳（需 天马流星拳≥400）、天马回旋碎击拳（需 天马流星拳≥400）");
        assertThat(line).contains("封印：天马闪光拳（需 天马彗星拳≥400 且 天马回旋碎击拳≥400）");
        // 旧版平铺格式已移除
        assertThat(line).doesNotContain("招式：天马流星拳、");
    }

    @Test
    void 前置未全部学会归入封印而非可悟() {
        // 全未学档：无前置的流星拳按定义归「可悟」，有前置且前置未学者归「封印」
        String save = BRONZE_SAVE.replace("\"learned\":true", "\"learned\":false");
        String line = assembler.formatStarDestiny(save);

        assertThat(line).doesNotContain("已会：");
        assertThat(line).contains("可悟：天马流星拳");
        assertThat(line).contains("封印：天马彗星拳（需 天马流星拳≥400）、天马回旋碎击拳（需 天马流星拳≥400）"
                + "、天马闪光拳（需 天马彗星拳≥400 且 天马回旋碎击拳≥400）");
    }

    @Test
    void 满配档封印段降级只列名字() {
        // 双子座 Gold 全根：10 招 > 6，封印段只列名字不列条件
        String save = "{\"constellation\":\"双子座\",\"star_rank\":\"Gold\",\"faction\":\"saint\","
                + "\"zodiac_traits\":\"机敏多变\",\"techniques\":["
                + tech("银河星爆", "[\"异次元空间\",\"幻胧魔皇拳\"]", false)
                + "," + tech("异次元空间", "[]", true)
                + "," + tech("幻胧魔皇拳", "[]", true)
                + "," + tech("起死回星", "[\"银河星爆\"]", false)
                + "," + tech("幸运之壁", "[]", true)
                + "," + tech("歧路幻景", "[\"幸运之壁\"]", false)
                + "," + tech("绯红雅努斯", "[\"歧路幻景\"]", false)
                + "," + tech("世界即终结", "[\"绯红雅努斯\"]", false)
                + "," + tech("最后终点", "[\"世界即终结\"]", false)
                + "," + tech("银河终结", "[\"银河星爆\"]", false)
                + "]}";

        String line = assembler.formatStarDestiny(save);

        assertThat(line).contains("已会：异次元空间 400/小成、幻胧魔皇拳 400/小成、幸运之壁 400/小成");
        assertThat(line).contains("可悟：银河星爆（需 异次元空间≥400 且 幻胧魔皇拳≥400）、歧路幻景（需 幸运之壁≥400）");
        // 封印段降级：只列名字
        assertThat(line).contains("封印：起死回星、绯红雅努斯、世界即终结、最后终点、银河终结");
        assertThat(line).doesNotContain("起死回星（需");
    }

    @Test
    void 无techniques键不渲染招式段且不抛异常() {
        assertThatCode(() -> {
            assertThat(assembler.formatStarDestiny(
                    "{\"constellation\":\"天马座\",\"star_rank\":\"Bronze\",\"faction\":\"saint\","
                            + "\"zodiac_traits\":\"热情冲动\"}")).doesNotContain("招式");
            assertThat(assembler.formatStarDestiny("{\"money\":100}")).isEmpty();
            assertThat(assembler.formatStarDestiny("{}")).isEmpty();
            assertThat(assembler.formatStarDestiny(null)).isEmpty();
        }).doesNotThrowAnyException();
    }

    private static String tech(String name, String prereqsJson, boolean learned) {
        return "{\"name\":\"" + name + "\",\"description\":\"d\",\"source\":\"经典系列\","
                + "\"tier\":1,\"prerequisites\":" + prereqsJson + ",\"learned\":" + learned
                + ",\"proficiency\":" + (learned ? 400 : 0) + "}";
    }
}
