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
