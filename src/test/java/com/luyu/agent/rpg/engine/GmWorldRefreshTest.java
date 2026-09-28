package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.config.RpgMemoryProperties;
import com.luyu.agent.rpg.model.WorldSetting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 世界背景回顾重注入（每 N 轮）的单元测试。
 * <p>
 * 覆盖三处易错点：
 * <ol>
 *   <li><b>追加位置</b>——重注入块必须落在 {@code ## GM 任务指令} 段之后，绝不能落进
 *       {@code ## 玩家行动} 与下一个 {@code \n## } 之间，否则 {@link RpgHistoryCleaner}
 *       会把世界观文本当成玩家说的话回放给前端；</li>
 *   <li><b>降级语义</b>——world 为 null / MEMORY.md 缺失时块不抛异常，降级为占位文案；</li>
 *   <li><b>关闭语义</b>——{@code world-refresh-every-turns <= 0} 时必须完全关闭且不能触发除零。</li>
 * </ol>
 * 块组装走纯函数重载（不触库、不碰文件系统），故 {@code GmContextAssembler} 仍可传 null 仓库。
 */
class GmWorldRefreshTest {

    private static final String PLAYER_ACTION = "我把玉佩当给了林掌柜";
    private static final String INDEX_LINE = "- [npc_lin](npc_lin.md) — 当铺老板，记着那枚玉佩";

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);
    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    @Test
    void 注入块追加在GM任务指令段之后而非玩家行动段() {
        String block = assembler.buildWorldRefreshBlock(world(), INDEX_LINE);
        String prompt = assembler.assembleIncremental(PLAYER_ACTION, List.of(), true, block);

        int blockIdx = prompt.indexOf("## 世界背景回顾");
        assertThat(blockIdx).isPositive();
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## 玩家行动"));
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## 触发器命中"));
        assertThat(blockIdx).isGreaterThan(prompt.indexOf("## GM 任务指令"));
        assertThat(prompt).contains(PLAYER_ACTION).contains(INDEX_LINE);
    }

    @Test
    void 带重注入块的增量prompt经清洗后只剩玩家行动() {
        String block = assembler.buildWorldRefreshBlock(world(), INDEX_LINE);
        String prompt = assembler.assembleIncremental(PLAYER_ACTION, List.of(), false, block);

        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain("世界背景回顾")
                .doesNotContain("GmMemoryView");
    }

    @Test
    void 本轮不注入时_与三参重载行为一致() {
        String withNullBlock = assembler.assembleIncremental(PLAYER_ACTION, List.of(), false, null);
        String threeArg = assembler.assembleIncremental(PLAYER_ACTION, List.of(), false);

        assertThat(withNullBlock).isEqualTo(threeArg);
    }

    @Test
    void 纯组装_world与索引缺失时降级为占位文案() {
        String block = assembler.buildWorldRefreshBlock(null, null);

        assertThat(block).contains("（未设定）").contains("（笔记本尚为空）");
    }

    @Test
    void 纯组装_包含世界观五字段与索引原文() {
        String block = assembler.buildWorldRefreshBlock(world(), INDEX_LINE);

        assertThat(block)
                .contains("名称：东瀛试炼")
                .contains("时代：战国")
                .contains("描述：妖魔横行的孤岛")
                .contains("规则：[\"灵力制衡\"]")
                .contains("氛围：肃杀")
                .contains(INDEX_LINE);
        // 精简版刻意不含地点列表（地点明细有 get_ 工具可查，控制注入体积）
        assertThat(block).doesNotContain("地点：");
    }

    @Test
    void 默认间隔10轮_仅整倍数轮触发() {
        RpgMemoryProperties properties = new RpgMemoryProperties();

        assertThat(properties.getWorldRefreshEveryTurns()).isEqualTo(10);
        assertThat(properties.shouldRefreshWorld(1)).isFalse();
        assertThat(properties.shouldRefreshWorld(9)).isFalse();
        assertThat(properties.shouldRefreshWorld(10)).isTrue();
        assertThat(properties.shouldRefreshWorld(30)).isTrue();
        // 轮次从 1 开始，0 不是合法轮次
        assertThat(properties.shouldRefreshWorld(0)).isFalse();
    }

    @Test
    void 间隔设为0或负数_完全关闭且不抛除零异常() {
        RpgMemoryProperties properties = new RpgMemoryProperties();

        properties.setWorldRefreshEveryTurns(0);
        assertThatCode(() -> {
            for (int turn = 0; turn <= 50; turn++) {
                assertThat(properties.shouldRefreshWorld(turn)).isFalse();
            }
        }).doesNotThrowAnyException();

        properties.setWorldRefreshEveryTurns(-5);
        assertThatCode(() -> {
            for (int turn = 0; turn <= 50; turn++) {
                assertThat(properties.shouldRefreshWorld(turn)).isFalse();
            }
        }).doesNotThrowAnyException();
    }

    private WorldSetting world() {
        WorldSetting world = new WorldSetting();
        world.setName("东瀛试炼");
        world.setEra("战国");
        world.setSettingDesc("妖魔横行的孤岛");
        world.setRules("[\"灵力制衡\"]");
        world.setAtmosphere("肃杀");
        return world;
    }
}
