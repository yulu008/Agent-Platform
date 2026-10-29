package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「故事图当前位置（结构指示）」注入块（{@code GmContextAssembler.assembleIncremental}
 * 十参重载）单元测试（story-graph-runtime task 4.3）。
 * <p>
 * 覆盖（对齐 add-rpg-worldbook 块测试的钉子集）：
 * <ol>
 *   <li>第 10 参 null/空白 → 与九参重载逐字节一致（未挂图 world 零增量回归）；</li>
 *   <li>注入块落在 {@code ## GM 任务指令} 段之后、{@code ## 玩家行动} 区间之外
 *       （避 cleaner 误回放）；</li>
 *   <li>含故事图块的增量 prompt 经 {@link RpgHistoryCleaner#cleanUserMessage}
 *       后仅剩玩家行动原文。</li>
 * </ol>
 * 块内容由 {@code StoryNodeMaterializerTest} 单测覆盖，本类直接以字符串模拟第 10 参。
 */
class GmStoryNodeBlockTest {

    private static final String PLAYER_ACTION = "我推开齿轮塔的铁门";

    /** 模拟 StoryNodeMaterializer 产出的结构指示块（以 \n\n## 开头自成一段） */
    private static final String STORY_NODE_BLOCK =
            "\n\n## 故事图当前位置（结构指示）\n\n"
                    + "【当前节点】n1_01（自由场景）\n"
                    + "【节点拍子】雨夜叩门\n"
                    + "【叙述要求】结构效果已由系统确定性结算并生效。";

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);
    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    @Test
    void 第10参为null时与九参重载逐字节一致() {
        String withNull = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, null, null, null);
        String nineArg = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, null, null);

        assertThat(withNull).isEqualTo(nineArg);
    }

    @Test
    void 第10参为空白串时同样省略注入() {
        String withBlank = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, null, null, "   ");
        String nineArg = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, null, null);

        assertThat(withBlank).isEqualTo(nineArg);
    }

    @Test
    void 注入块落在GM任务指令段之后而非玩家行动段() {
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, null, null,
                STORY_NODE_BLOCK);

        int blockIdx = prompt.indexOf("## 故事图当前位置");
        assertThat(blockIdx).isPositive();
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## 玩家行动"));
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## GM 任务指令"));
    }

    @Test
    void 与既有注入块同轮共存() {
        String customBlock = "\n\n## 本世界自定义状态字段\n\n- 好感度\n";
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, null,
                customBlock, STORY_NODE_BLOCK);

        assertThat(prompt).contains("## 本世界自定义状态字段").contains("## 故事图当前位置");
        assertThat(prompt).contains("雨夜叩门").contains("好感度");
    }

    @Test
    void 含故事图块的增量prompt经清洗后只剩玩家行动() {
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, null, null,
                STORY_NODE_BLOCK);

        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain("故事图当前位置")
                .doesNotContain("雨夜叩门");
    }
}
