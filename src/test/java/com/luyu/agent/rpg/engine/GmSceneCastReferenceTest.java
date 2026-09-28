package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「当前在场 NPC」名单块（rpg-scene-cast，每轮反哺 GM）的单元测试。
 * <p>
 * 钉住根基约定：
 * <ol>
 *   <li>只列出 in_scene=true 的条目，卡 ID key 标注卡名（与 NPC key 参考块同风格）；</li>
 *   <li>无条目 / 无戳 / 非法 JSON 时降级为占位「（暂无）」，不抛异常（旧存档语义）；</li>
 *   <li>在场段渲染进增量 prompt 的「现有 NPC 状态 key」与「GM 任务指令」之间——
 *       落在 {@code ## 玩家行动} 段之外，{@link RpgHistoryCleaner} 清洗后玩家原文不受污染；</li>
 *   <li>未传在场块时占位为「（暂无）」，五参与六参行为一致（旧签名委托不变形）。</li>
 * </ol>
 * 块组装走纯函数重载（不触库），故 {@code GmContextAssembler} 仍可传 null 仓库。
 */
class GmSceneCastReferenceTest {

    private static final String PLAYER_ACTION = "我把血书递了过去";

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);
    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    @Test
    void 只列在场条目_卡ID标注卡名_不在场不出现() {
        GameState gs = new GameState();
        gs.setNpcStates("{\"card-1\":{\"status\":\"对峙\",\"in_scene\":true},"
                + "\"苏枕雪\":{\"in_scene\":true},"
                + "\"老铁匠\":{\"status\":\"打铁\",\"in_scene\":false}}");

        String cast = assembler.buildSceneCastReference(gs, List.of(card("card-1", "张猛")));

        assertThat(cast)
                .contains("- card-1（角色卡「张猛」）")
                .contains("- 苏枕雪")
                .doesNotContain("老铁匠");
    }

    @Test
    void 无戳或空集时降级为占位_旧存档语义() {
        // 旧存档：有条目但无 in_scene 戳
        GameState legacy = new GameState();
        legacy.setNpcStates("{\"张猛\":{\"status\":\"对峙\"}}");
        assertThat(assembler.buildSceneCastReference(legacy, List.of())).isEqualTo("（暂无）");

        assertThat(assembler.buildSceneCastReference(null, null)).isEqualTo("（暂无）");

        GameState empty = new GameState();
        empty.setNpcStates("{}");
        assertThat(assembler.buildSceneCastReference(empty, List.of())).isEqualTo("（暂无）");

        GameState blank = new GameState();
        blank.setNpcStates("");
        assertThat(assembler.buildSceneCastReference(blank, List.of())).isEqualTo("（暂无）");

        GameState broken = new GameState();
        broken.setNpcStates("not-json");
        assertThat(assembler.buildSceneCastReference(broken, List.of())).isEqualTo("（暂无）");
    }

    @Test
    void 在场段渲染在key参考块与GM任务指令之间() {
        String sceneCast = "- 张猛\n- 苏枕雪";
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, "- 张猛", sceneCast);

        int castIdx = prompt.indexOf("## 当前在场 NPC");
        assertThat(castIdx).isPositive();
        assertThat(castIdx).isGreaterThan(prompt.indexOf("## 现有 NPC 状态 key"));
        assertThat(castIdx).isLessThan(prompt.indexOf("## GM 任务指令"));
        assertThat(prompt).contains(PLAYER_ACTION).contains(sceneCast);
    }

    @Test
    void 带在场段的增量prompt经清洗后只剩玩家行动() {
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, "- 张猛", "- 张猛\n- 苏枕雪");

        // 玩家原文不含 NPC 名，可断言清洗后在场段不残留
        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain("当前在场 NPC")
                .doesNotContain("苏枕雪");
    }

    @Test
    void 未传在场块时占位为暂无_五参与六参行为一致() {
        String fiveArg = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, "- 张猛");
        String sixArgNull = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, "- 张猛", null);

        assertThat(fiveArg).isEqualTo(sixArgNull).contains("（暂无）");
    }

    private CharacterCard card(String id, String name) {
        CharacterCard card = new CharacterCard();
        card.setId(id);
        card.setName(name);
        return card;
    }
}
