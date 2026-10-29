package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RpgHistoryCleaner} 单元测试。
 * <p>
 * 覆盖 read-time 清洗的确定性规则：user 侧按固定标记 {@code ## 玩家行动} 抽取，
 * assistant 侧移除 {@code <state_delta>} 块。清洗器为纯逻辑，无需 Spring 上下文。
 */
class RpgHistoryCleanerTest {

    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    @Test
    void cleanUserMessage_包裹prompt_抽取玩家行动段() {
        String raw = """
                # GM 增量注入 Prompt（后续轮）

                ## 玩家行动

                我走进客栈，要间上房

                ## 触发器命中

                本轮无触发器命中。

                ## GM 任务指令

                请根据上述玩家行动和触发器命中结果，继续推进故事：
                1. 描述玩家行动的直接结果
                """;
        assertThat(cleaner.cleanUserMessage(raw)).isEqualTo("我走进客栈，要间上房");
    }

    @Test
    void cleanUserMessage_首轮未包裹_原样返回并trim() {
        assertThat(cleaner.cleanUserMessage("  我推开木门  ")).isEqualTo("我推开木门");
    }

    @Test
    void cleanUserMessage_空输入_返回空串() {
        assertThat(cleaner.cleanUserMessage(null)).isEmpty();
        assertThat(cleaner.cleanUserMessage("")).isEmpty();
    }

    @Test
    void cleanUserMessage_玩家行动为末段无后续标记_仍能抽取() {
        String raw = "## 玩家行动\n\n我静静等待\n";
        assertThat(cleaner.cleanUserMessage(raw)).isEqualTo("我静静等待");
    }

    @Test
    void cleanUserMessage_带每N轮记忆整理提醒_提醒不会当成玩家说的话() {
        // 提醒文本由 GmContextAssembler 追加在模板末尾（## GM 任务指令 段内），
        // 落在 ## 玩家行动 与下一个 \n## 之间之外，故不会被回放给前端
        String raw = """
                # GM 增量注入 Prompt（后续轮）

                ## 玩家行动

                我把玉佩当给了林掌柜

                ## 触发器命中

                本轮无触发器命中。

                ## GM 任务指令

                请根据上述玩家行动和触发器命中结果，继续推进故事：
                1. 描述玩家行动的直接结果

                （整理笔记：本轮是否有值得 NPC 长期记住的事、新埋的伏笔或即兴生成的世界细节？若有，用 GmMemory* 工具写进笔记本。）
                """;
        assertThat(cleaner.cleanUserMessage(raw))
                .isEqualTo("我把玉佩当给了林掌柜")
                .doesNotContain("整理笔记")
                .doesNotContain("GmMemory");
    }

    @Test
    void cleanUserMessage_带世界书已揭示块_名录文本不回放给前端() {
        // 世界书块（star-lorebook design D4）追加在模板末尾、## GM 任务指令 段之后，
        // 绝不落在 ## 玩家行动 与下一个 \n## 之间——否则名录条目会被当成玩家原话回放给前端
        String raw = """
                # GM 增量注入 Prompt（后续轮）

                ## 玩家行动

                我向店家打听织田家的动向

                ## 触发器命中

                本轮无触发器命中。

                ## GM 任务指令

                请根据上述玩家行动和触发器命中结果，继续推进故事：
                1. 描述玩家行动的直接结果

                ## 世界书·已揭示

                以下冥星武将录条目已在剧情中被提及，设定供叙事参考（每条含命定归属）：
                - 天魁星·织田信长：第六天魔王，命定以冥衣搅动天下（此局织田信长不再持有天魁冥衣——星归玩家，他的命运轨迹随之改变）
                - 天兽星·前田庆次：倾奇者，不入世俗常理的传奇枪豪
                """;
        assertThat(cleaner.cleanUserMessage(raw))
                .isEqualTo("我向店家打听织田家的动向")
                .doesNotContain("世界书")
                .doesNotContain("冥星武将录")
                .doesNotContain("星归玩家")
                .doesNotContain("前田庆次");
    }

    @Test
    void cleanUserMessage_带设定集已揭示块_清洗行为与旧块头一致() {
        // rename-star-lorebook-compendium D4：块头正名为 ## 设定集·已揭示。清洗按位置段
        // （## 玩家行动 到下一个 \n## 之间）识别而非块头字面量——新块头行为须与旧块头一致
        String raw = """
                # GM 增量注入 Prompt（后续轮）

                ## 玩家行动

                我向店家打听织田家的动向

                ## 触发器命中

                本轮无触发器命中。

                ## GM 任务指令

                请根据上述玩家行动和触发器命中结果，继续推进故事：
                1. 描述玩家行动的直接结果

                ## 设定集·已揭示

                以下设定集条目本轮首次被提及，档案已归档，细节用 GmMemoryView 读取对应档案：
                - 天魁星·织田信长已揭示，档案见 lore_天魁星.md
                - 天兽星·前田庆次已揭示，档案见 lore_天兽星.md
                """;
        assertThat(cleaner.cleanUserMessage(raw))
                .isEqualTo("我向店家打听织田家的动向")
                .doesNotContain("设定集")
                .doesNotContain("档案见")
                .doesNotContain("前田庆次");
    }

    @Test
    void cleanUserMessage_带分类标题行的设定集块_整块剔除不回放() {
        // add-star-lorebook-category D3：块内新增【分类】标题行。清洗按位置段识别
        // （## 玩家行动 到下一个 \n## 之间）而非块内行格式——分类行随块整体剔除，
        // 绝不作为玩家原话回放给前端
        String raw = """
                # GM 增量注入 Prompt（后续轮）

                ## 玩家行动

                我向店家打听织田家的动向

                ## 触发器命中

                本轮无触发器命中。

                ## GM 任务指令

                请根据上述玩家行动和触发器命中结果，继续推进故事：
                1. 描述玩家行动的直接结果

                ## 设定集·已揭示

                以下设定集条目本轮首次被提及，档案已归档，细节用 GmMemoryView 读取对应档案：
                【织田家】
                - 天魁星·织田信长已揭示，档案见 lore_天魁星.md
                【丰臣家】
                - 天兽星·前田庆次已揭示，档案见 lore_天兽星.md
                """;
        assertThat(cleaner.cleanUserMessage(raw))
                .isEqualTo("我向店家打听织田家的动向")
                .doesNotContain("设定集")
                .doesNotContain("【织田家】")
                .doesNotContain("档案见")
                .doesNotContain("前田庆次");
    }

    @Test
    void cleanAssistantMessage_移除stateDelta块仅留叙述() {
        String raw = """
                小二搭着毛巾迎上来，"客官里边请——"
                <state_delta>
                {"location_change":"客栈"}
                </state_delta>
                """;
        String cleaned = cleaner.cleanAssistantMessage(raw);
        assertThat(cleaned).contains("小二搭着毛巾迎上来");
        assertThat(cleaned).doesNotContain("state_delta");
        assertThat(cleaned).doesNotContain("location_change");
    }

    @Test
    void cleanAssistantMessage_无stateDelta_原样保留() {
        String raw = "夜色渐深，客栈里只剩炉火噼啪。";
        assertThat(cleaner.cleanAssistantMessage(raw)).isEqualTo(raw);
    }
}
