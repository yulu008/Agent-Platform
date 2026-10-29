package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.service.LoreArchiveService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设定集一行式注入块（{@code GmContextAssembler.buildStarLorebookBlock}）单元测试
 * （rpg-lore-archival-loading tasks 3.3）。
 * <p>
 * 钉住纯索引档位（design D5/D6）的退场语义：
 * <ol>
 *   <li>空/null → 返回 null（无新命中轮不注入，既有激活走记忆通道）；</li>
 *   <li>非空 → 块头 {@code ## 设定集·已揭示} + 每条一行
 *       {@code - 星名·武将名已揭示，档案见 lore_<星名>.md}，文件名与物化侧共用
 *       {@link LoreArchiveService#starArchiveFileName} 推导（消毒一致）；</li>
 *   <li><b>档案正文绝不进注入</b>（lore/别名全文退场——上下文瘦身即本变更的目的）；</li>
 *   <li>武将名空白仅渲染星名；</li>
 *   <li>落位在 {@code ## GM 任务指令} 段之后（{@code ## 玩家行动} 区间之外，避 cleaner 误回放）；</li>
 *   <li>含该块的增量 prompt 经 {@link RpgHistoryCleaner#cleanUserMessage} 后仅剩玩家行动原文。</li>
 * </ol>
 * 块组装走纯函数（不触库），故 {@code GmContextAssembler} 传 null 仓库即可。
 */
class GmStarLorebookBlockTest {

    private static final String PLAYER_ACTION = "我向店家打听织田信长的动向";

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);
    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    private StarIncarnation incarnation(String starName, String warlord) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId("inc-" + starName);
        inc.setStarName(starName);
        inc.setWarlordName(warlord);
        inc.setLore(starName + "的命定叙事（档案正文，不得出现在注入块中）");
        inc.setAliases("魔王,第六天魔王");
        inc.setRollable(true);
        return inc;
    }

    @Test
    void 空或null条目返回null() {
        assertThat(assembler.buildStarLorebookBlock(List.of())).isNull();
        assertThat(assembler.buildStarLorebookBlock(null)).isNull();
    }

    @Test
    void 非空渲染一行式条目且文件名与物化侧推导一致() {
        String block = assembler.buildStarLorebookBlock(List.of(
                new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长"), false),
                new StarLorebookScanner.RevealedEntry(incarnation("天兽星", "前田庆次"), false)));

        assertThat(block)
                .startsWith("\n\n## 设定集·已揭示")
                .contains("档案已归档")
                .contains("- 天魁星·织田信长已揭示，档案见 lore_天魁星.md")
                .contains("- 天兽星·前田庆次已揭示，档案见 lore_天兽星.md");
        // 文件名与物化侧共用同一推导（消毒规则一致，GM 凭此名 GmMemoryView 即可读到档案）
        assertThat(LoreArchiveService.starArchiveFileName("天魁星")).isEqualTo("lore_天魁星.md");
    }

    @Test
    void 档案正文绝不进入注入块() {
        StarIncarnation inc = incarnation("天魁星", "织田信长");
        String block = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(inc, false)));

        // 一行式纯索引：lore 全文 / 别名全文退场（上下文瘦身的本体断言）
        assertThat(block).doesNotContain("命定叙事");
        assertThat(block).doesNotContain("魔王");
        assertThat(block).doesNotContain("第六天魔王");
        // 每条仅占一行（无多行展开）
        assertThat(block.lines().filter(l -> l.startsWith("- ")).count()).isEqualTo(1);
    }

    @Test
    void 武将名空白仅渲染星名() {
        String block = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(incarnation("天魁星", null), false)));

        assertThat(block).contains("- 天魁星已揭示，档案见 lore_天魁星.md");
        assertThat(block).doesNotContain("天魁星·");
    }

    // ==================== claimed 行人物区分（preset-destined-figures D3 / tasks 5.5） ====================

    @Test
    void claimed行人物区分_人即玩家与星归玩家双语义() {
        StarIncarnation inc = incarnation("天魁星", "织田信长");

        // 人物局撞星（信长即玩家本人）：标记切换【人即玩家】，勿演成失衣武将
        String figureBlock = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(inc, true, true)));
        assertThat(figureBlock)
                .contains("- 天魁星·织田信长【人即玩家】已揭示，档案见 lore_天魁星.md")
                .doesNotContain("【星归玩家】");

        // 无名新人/信胜撞星：维持旧【星归玩家】语义（冥衣归玩家、武将失衣另谋他途）
        String claimedBlock = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(inc, true, false)));
        assertThat(claimedBlock)
                .contains("- 天魁星·织田信长【星归玩家】已揭示，档案见 lore_天魁星.md")
                .doesNotContain("【人即玩家】");

        // 激活行（claimed=false）：无标记，与既有渲染零改动
        String plainBlock = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(inc, false)));
        assertThat(plainBlock)
                .contains("- 天魁星·织田信长已揭示，档案见 lore_天魁星.md")
                .doesNotContain("【");
    }

    @Test
    void 星名特殊字符消毒为合法文件名() {
        String block = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(incarnation("天/魁*星", "织田信长"), false)));

        assertThat(block).contains("档案见 lore_天_魁_星.md");
    }

    @Test
    void 注入块落在GM任务指令段之后而非玩家行动段() {
        String block = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长"), false)));
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, block);

        int blockIdx = prompt.indexOf("## 设定集·已揭示");
        assertThat(blockIdx).isPositive();
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## 玩家行动"));
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## GM 任务指令"));
    }

    @Test
    void 与关联设定块同轮共存() {
        String starBlock = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长"), false)));
        String worldbookBlock = "\n\n## 世界书·关联设定\n\n- 齿轮塔：机械高塔\n";
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, starBlock, worldbookBlock);

        // 两块头都在，互不覆盖（块头刻意不同：已揭示 vs 关联设定）
        assertThat(prompt)
                .contains("## 设定集·已揭示")
                .contains("## 世界书·关联设定")
                .contains("档案见 lore_天魁星.md")
                .contains("- 齿轮塔：机械高塔");
    }

    @Test
    void 含已揭示块的增量prompt经清洗后只剩玩家行动() {
        String block = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长"), false)));
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, block);

        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain("设定集·已揭示")
                .doesNotContain("档案见");
    }

    // ==================== 分类分组（add-star-lorebook-category D3 / tasks 6.1） ====================

    private StarIncarnation incarnation(String starName, String warlord, String category) {
        StarIncarnation inc = incarnation(starName, warlord);
        inc.setCategory(category);
        return inc;
    }

    @Test
    void 同分类条目归组且分类名只出现一次() {
        String block = assembler.buildStarLorebookBlock(List.of(
                new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长", "织田家"), false),
                new StarLorebookScanner.RevealedEntry(incarnation("地魁星", "木下秀吉", "织田家"), false),
                new StarLorebookScanner.RevealedEntry(incarnation("天兽星", "前田庆次", "丰臣家"), false)));

        // 切换处各输出一行【分类】标题，同分类多条共用一次标题（token 效率）
        assertThat(block)
                .contains("【织田家】\n- 天魁星·织田信长已揭示")
                .contains("- 地魁星·木下秀吉已揭示")
                .contains("【丰臣家】\n- 天兽星·前田庆次已揭示");
        assertThat(block.split("【织田家】", -1).length - 1).isEqualTo(1);
        assertThat(block).doesNotContain("【未分类】");
    }

    @Test
    void 无分类条目维持平铺且条目行格式不变() {
        String block = assembler.buildStarLorebookBlock(
                List.of(new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长"), false)));

        assertThat(block)
                .contains("- 天魁星·织田信长已揭示，档案见 lore_天魁星.md")
                .doesNotContain("【");
    }

    @Test
    void 混合序列分类隔断后再次出现重输出标题() {
        // 织田家 → 无分类 → 织田家：切换检测算法（与列表视图 D4 同感知），
        // 分类再次出现时重输出标题行（不重排条目，保持仓储排序稳定）
        String block = assembler.buildStarLorebookBlock(List.of(
                new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长", "织田家"), false),
                new StarLorebookScanner.RevealedEntry(incarnation("地兽星", "本多忠胜"), false),
                new StarLorebookScanner.RevealedEntry(incarnation("天猛星", "柴田胜家", "织田家"), false)));

        // 无分类条目不插标题也不被标题包裹
        assertThat(block).contains("\n- 地兽星·本多忠胜已揭示");
        assertThat(block.split("【织田家】", -1).length - 1).isEqualTo(2);
    }

    @Test
    void 含分类行的注入块经清洗后整体剔除() {
        String block = assembler.buildStarLorebookBlock(List.of(
                new StarLorebookScanner.RevealedEntry(incarnation("天魁星", "织田信长", "织田家"), false)));
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, null, null, block);

        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain("织田家")
                .doesNotContain("设定集·已揭示");
    }
}
