package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「本世界自定义状态字段」动态契约（rpg-hud-custom-blocks tasks 6.4）单元测试。
 * <p>
 * 提示词不落日志，端到端内容用单测直接钉住（双轨注入，design D5）：
 * <ol>
 *   <li>首轮有块：{@code {{CUSTOM_BLOCK_FIELDS}}} 替换为 3 空格缩进契约段，精确落位在
 *       state_delta 契约说明（「不要用 ``` 代码围栏包裹。」）与「5. **工具使用**」之间；</li>
 *   <li>首轮无块/坏配置/gs 缺失：占位段整体剔除、无残留（与占位符引入前一致，零增量）；</li>
 *   <li>增量轮有块：{@code buildCustomBlockContract} 每轮反哺
 *       {@code ## 本世界自定义状态字段} 块（首轮 system prompt 不落库，缺反哺则第 2 轮起契约消失）；</li>
 *   <li>九参 {@code assembleIncremental} 注入该块（追加在模板末尾、## GM 任务指令 段之后）；</li>
 *   <li>校验规则与前端 {@code applyWorldCustomBlocks}（hud.js）同源：坏 JSON/非数组/超 5 块
 *       整包丢弃，单块非法（field 非安全正则/title 超长）仅丢该块。</li>
 * </ol>
 * formatWorld/formatCard/formatGameState 对 null 均容忍，mock 四仓库做最小 stub。
 */
class GmContextAssemblerCustomBlocksTest {

    private static final String GAME_STATE_ID = "gs-cb-1";
    private static final String WORLD_ID = "w-cb-1";

    /** 与工坊端到端（6.1）保存的配置同构：两块，一块带 accent（accent 是渲染侧关注点，契约段不校验） */
    private static final String CUSTOM_BLOCKS_JSON =
            "[{\"title\":\"本幕目标\",\"field\":\"objectives\"},"
                    + "{\"title\":\"传闻\",\"field\":\"rumors\",\"accent\":\"--npc-accent\"}]";

    private final RpgWorldSettingRepository worldRepo = mock(RpgWorldSettingRepository.class);
    private final RpgCharacterCardRepository charRepo = mock(RpgCharacterCardRepository.class);
    private final RpgTriggerRepository triggerRepo = mock(RpgTriggerRepository.class);
    private final RpgGameStateRepository stateRepo = mock(RpgGameStateRepository.class);
    private final GmContextAssembler assembler =
            new GmContextAssembler(worldRepo, charRepo, triggerRepo, stateRepo);

    private GameState gs() {
        GameState gs = new GameState();
        gs.setId(GAME_STATE_ID);
        gs.setWorldId(WORLD_ID);
        gs.setPlayerCharId("pc-1");
        // turnCount 为 Integer：formatGameState 的 %d 不容忍 null
        gs.setTurnCount(0);
        return gs;
    }

    private WorldSetting world(String customBlocks) {
        WorldSetting w = new WorldSetting();
        w.setId(WORLD_ID);
        w.setName("契约验证世界");
        w.setCustomBlocks(customBlocks);
        return w;
    }

    /** 组装首轮 prompt（world.customBlocks = 入参；charRepo.findById 不 stub → null 容忍） */
    private String firstRound(String customBlocks) {
        when(stateRepo.findById(GAME_STATE_ID)).thenReturn(gs());
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(customBlocks));
        when(charRepo.findByWorldIdAndType(WORLD_ID, "npc")).thenReturn(List.of());
        when(triggerRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        return assembler.assembleFirstRound(GAME_STATE_ID);
    }

    /** 模板为 CRLF 行尾：跨行锚点断言前归一为 LF（钉结构而非行尾字节） */
    private static String lf(String s) {
        return s.replace("\r\n", "\n");
    }

    @Test
    void 有块世界_首轮注入缩进契约段且落位在stateDelta契约与工具使用之间() {
        String prompt = firstRound(CUSTOM_BLOCKS_JSON);

        assertThat(prompt)
                .doesNotContain("{{CUSTOM_BLOCK_FIELDS}}")
                .contains("本世界自定义状态字段（写入 `<state_delta>` 根级")
                .contains("- `objectives`（本幕目标）")
                .contains("- `rumors`（传闻）");
        // 契约段在占位符原位展开：前接「不要用 ``` 代码围栏包裹。」、后接「5. **工具使用**」
        assertThat(lf(prompt))
                .contains("不要用 ``` 代码围栏包裹。\n\n   本世界自定义状态字段（写入 `<state_delta>` 根级")
                .contains("无新变化时省略该键）：\n   - `objectives`（本幕目标）\n   - `rumors`（传闻）\n\n5. **工具使用**");
    }

    @Test
    void 无块世界_首轮占位段整体剔除零增量() {
        String prompt = firstRound(null);

        assertThat(prompt)
                .doesNotContain("{{CUSTOM_BLOCK_FIELDS}}")
                .doesNotContain("本世界自定义状态字段");
        // 占位段剔除后原位还原为引入前形态：前后空行合并，契约说明直接衔接工具使用条目
        assertThat(lf(prompt)).contains("不要用 ``` 代码围栏包裹。\n\n5. **工具使用**");
    }

    @Test
    void 坏JSON或超限_首轮整包丢弃无残留() {
        // 坏 JSON / 非数组 / 超 5 块：契约段整包丢弃，提示词等同无块世界
        for (String bad : new String[]{
                "{not-json",
                "{\"title\":\"非数组\"}",
                "[{},{},{},{},{},{}]"}) {
            String prompt = firstRound(bad);
            assertThat(prompt)
                    .as("customBlocks=%s 应整包丢弃", bad)
                    .doesNotContain("{{CUSTOM_BLOCK_FIELDS}}")
                    .doesNotContain("本世界自定义状态字段");
        }
    }

    @Test
    void gs缺失_首轮降级分支也剔除占位段() {
        when(stateRepo.findById(GAME_STATE_ID)).thenReturn(null);

        String prompt = assembler.assembleFirstRound(GAME_STATE_ID);

        assertThat(prompt)
                .doesNotContain("{{CUSTOM_BLOCK_FIELDS}}")
                .doesNotContain("本世界自定义状态字段");
    }

    @Test
    void 增量轮_有块世界每轮反哺契约块() {
        when(stateRepo.findById(GAME_STATE_ID)).thenReturn(gs());
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(CUSTOM_BLOCKS_JSON));

        String block = assembler.buildCustomBlockContract(GAME_STATE_ID);

        assertThat(block)
                .startsWith("\n\n## 本世界自定义状态字段\n\n")
                .contains("根级继续输出本世界自定义字段")
                .contains("- `objectives`（本幕目标）")
                .contains("- `rumors`（传闻）");
    }

    @Test
    void 增量轮_无块世界或gs缺失返回null不注入() {
        when(stateRepo.findById(GAME_STATE_ID)).thenReturn(gs());
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(null));
        assertThat(assembler.buildCustomBlockContract(GAME_STATE_ID)).isNull();

        when(stateRepo.findById(GAME_STATE_ID)).thenReturn(null);
        assertThat(assembler.buildCustomBlockContract(GAME_STATE_ID)).isNull();
    }

    @Test
    void 九参增量注入_契约块追加在模板末尾() {
        String contract = "\n\n## 本世界自定义状态字段\n\n"
                + "`<state_delta>` 根级继续输出本世界自定义字段（前端渲染为面板内容块；"
                + "值为数组，每项 {\"text\": \"…\", \"done\": true|false}，整体替换，无新变化时省略该键）：\n"
                + "- `objectives`（本幕目标）";

        String prompt = assembler.assembleIncremental("我推门而入", List.of(), false,
                null, null, null, null, null, contract);

        assertThat(prompt)
                .contains("我推门而入")
                .endsWith(contract); // 追加在 ## GM 任务指令 段之后（模板末尾），不落入 ## 玩家行动 区间
    }

    @Test
    void 八参委托路径_无契约注入() {
        String prompt = assembler.assembleIncremental("我推门而入", List.of(), false,
                null, null, null, null, null);

        assertThat(prompt)
                .contains("我推门而入")
                .doesNotContain("本世界自定义状态字段");
    }

    @Test
    void 单块非法_仅丢该块其余保留() {
        GmContextAssembler pure = new GmContextAssembler(null, null, null, null);
        // 空仓库构造的查库版直接返回 null（不触库）
        assertThat(pure.buildCustomBlockContract(GAME_STATE_ID)).isNull();

        WorldSetting w = world("[{\"title\":\"本幕目标\",\"field\":\"objectives\"},"
                + "{\"title\":\"坏路径\",\"field\":\"a.b\"},"
                + "{\"title\":\"" + "超".repeat(21) + "\",\"field\":\"long_title\"}]");
        String section = pure.buildCustomBlockContractSection(w);

        assertThat(section)
                .contains("- `objectives`（本幕目标）")
                .doesNotContain("a.b")
                .doesNotContain("long_title");
    }
}
