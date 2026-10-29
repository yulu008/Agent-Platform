package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.WorldbookEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通用世界书注入块（{@code GmContextAssembler.buildWorldbookBlock}）单元测试
 * （add-rpg-worldbook task 6.3 + 6.4）。
 * <p>
 * 覆盖：
 * <ol>
 *   <li>空/null 条目 → 返回 null（本轮不注入，与 star-lorebook 降噪取向一致）；</li>
 *   <li>非空 → 块头 {@code ## 世界书·关联设定}（刻意区别于设定集轨的 {@code ## 设定集·已揭示}）
 *       + 首行注明「玩家自定义世界设定」+ 每条 {@code - 标题：正文}；</li>
 *   <li>落位在 {@code ## GM 任务指令} 段之后、{@code ## 玩家行动} 区间之外（避 cleaner 误回放）；</li>
 *   <li>与「设定集·已揭示」块同轮共存（两块头都在，互不覆盖）；</li>
 *   <li>含世界书块的增量 prompt 经 {@link RpgHistoryCleaner#cleanUserMessage} 后仅剩玩家行动原文。</li>
 * </ol>
 * 块组装走纯函数（不触库），故 {@code GmContextAssembler} 传 null 仓库即可。
 */
class GmWorldbookBlockTest {

    private static final String PLAYER_ACTION = "我推开齿轮塔的铁门";
    /** 设定集块头（用于共存断言，直接以字符串模拟第 7 参，无需构造 StarIncarnation） */
    private static final String STAR_BLOCK = "\n\n## 设定集·已揭示\n\n- 天罡星→关羽\n";

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);
    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    private WorldbookEntry entry(String title, String content) {
        WorldbookEntry e = new WorldbookEntry();
        e.setTitle(title);
        e.setKeywords(title);
        e.setContent(content);
        e.setEnabled(true);
        return e;
    }

    @Test
    void 空条目返回null() {
        assertThat(assembler.buildWorldbookBlock(List.of())).isNull();
        assertThat(assembler.buildWorldbookBlock(null)).isNull();
    }

    @Test
    void 非空渲染关联设定块头与条目正文() {
        String block = assembler.buildWorldbookBlock(List.of(
                entry("齿轮塔", "城中最高的机械塔，顶端常年笼罩蒸汽。"),
                entry("雾都", "钟表城的别称，因终年雾气得名。")));

        assertThat(block)
                .startsWith("\n\n## 世界书·关联设定")
                .contains("以下为玩家自定义世界设定")
                .contains("- 齿轮塔：城中最高的机械塔，顶端常年笼罩蒸汽。")
                .contains("- 雾都：钟表城的别称，因终年雾气得名。");
        // 块头必须区别于设定集块，避免同轮两块混淆
        assertThat(block).doesNotContain("设定集·已揭示");
    }

    @Test
    void 无正文条目仅渲染标题() {
        WorldbookEntry e = entry("齿轮塔", null);
        String block = assembler.buildWorldbookBlock(List.of(e));

        assertThat(block).contains("- 齿轮塔").doesNotContain("齿轮塔：");
    }

    @Test
    void 注入块落在GM任务指令段之后而非玩家行动段() {
        String block = assembler.buildWorldbookBlock(List.of(entry("齿轮塔", "机械高塔")));
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, block);

        int blockIdx = prompt.indexOf("## 世界书·关联设定");
        assertThat(blockIdx).isPositive();
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## 玩家行动"));
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## GM 任务指令"));
    }

    @Test
    void 与已揭示块同轮共存() {
        String worldbookBlock = assembler.buildWorldbookBlock(List.of(entry("齿轮塔", "机械高塔")));
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, STAR_BLOCK, worldbookBlock);

        // 两块头都在，互不覆盖
        assertThat(prompt).contains("## 设定集·已揭示").contains("## 世界书·关联设定");
        assertThat(prompt).contains("天罡星→关羽").contains("- 齿轮塔：机械高塔");
    }

    @Test
    void 第8参为null时与七参重载行为一致() {
        String withNull = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, STAR_BLOCK, null);
        String sevenArg = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, STAR_BLOCK);

        assertThat(withNull).isEqualTo(sevenArg);
    }

    @Test
    void 含世界书块的增量prompt经清洗后只剩玩家行动() {
        String block = assembler.buildWorldbookBlock(List.of(entry("齿轮塔", "机械高塔")));
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, null, block);

        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain("世界书·关联设定")
                .doesNotContain("机械高塔");
    }
}
