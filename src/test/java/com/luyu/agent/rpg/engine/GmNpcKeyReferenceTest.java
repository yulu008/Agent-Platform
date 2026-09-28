package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「现有 NPC 状态 key」参考块（每轮注入增量 prompt）的单元测试。
 * <p>
 * 钉住防重复 key 的根基约定：
 * <ol>
 *   <li>列出全部既有 key，卡 ID key 标注卡名（GM 后续轮看不到首轮 system prompt，需此列表才知道该沿用哪些 key）；</li>
 *   <li>npc_states 空 / 非法 JSON / 非对象时降级为占位文案，不抛异常；</li>
 *   <li>参考块渲染进增量 prompt 的「触发器命中」与「GM 任务指令」之间——
 *       落在 {@code ## 玩家行动} 段之外，{@link RpgHistoryCleaner} 清洗后玩家原文不受污染。</li>
 * </ol>
 * 块组装走纯函数重载（不触库），故 {@code GmContextAssembler} 仍可传 null 仓库。
 */
class GmNpcKeyReferenceTest {

    private static final String PLAYER_ACTION = "我把血书递了过去";

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);
    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    @Test
    void 列出全部既有key_卡ID标注卡名_无卡key原样() {
        GameState gs = new GameState();
        gs.setNpcStates("{\"苏枕雪\":{\"status\":\"idle\"},"
                + "\"card-1\":{\"status\":\"idle\"},"
                + "\"su_zhenxue\":{\"status\":\"idle\"}}");

        String reference = assembler.buildNpcKeyReference(gs, List.of(card("card-1", "船老大")));

        assertThat(reference)
                .contains("- 苏枕雪")
                .contains("- card-1（角色卡「船老大」，必须用此 ID 作 key）")
                .contains("- su_zhenxue");
    }

    @Test
    void 参考块渲染在触发器命中与GM任务指令之间() {
        String reference = "- 苏枕雪\n- 船老大";
        String prompt = assembler.assembleIncremental(PLAYER_ACTION, List.of(), false, null, reference);

        int refIdx = prompt.indexOf("## 现有 NPC 状态 key");
        assertThat(refIdx).isPositive();
        assertThat(refIdx).isGreaterThan(prompt.indexOf("## 触发器命中"));
        assertThat(refIdx).isLessThan(prompt.indexOf("## GM 任务指令"));
        assertThat(prompt).contains(PLAYER_ACTION).contains(reference);
    }

    @Test
    void 带参考块的增量prompt经清洗后只剩玩家行动() {
        String prompt = assembler.assembleIncremental(
                PLAYER_ACTION, List.of(), false, null, "- 苏枕雪");

        // 玩家原文不含 NPC 名，可断言清洗后参考块不残留
        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain("现有 NPC 状态 key")
                .doesNotContain("苏枕雪");
    }

    @Test
    void 未传参考块时占位为暂无_四参与五参行为一致() {
        String fourArg = assembler.assembleIncremental(PLAYER_ACTION, List.of(), false, null);
        String fiveArgNull = assembler.assembleIncremental(PLAYER_ACTION, List.of(), false, null, null);

        assertThat(fourArg).isEqualTo(fiveArgNull).contains("（暂无）");
    }

    @Test
    void npcStates空或非法时降级为占位文案() {
        assertThat(assembler.buildNpcKeyReference(null, null)).isEqualTo("（暂无）");

        GameState empty = new GameState();
        empty.setNpcStates("{}");
        assertThat(assembler.buildNpcKeyReference(empty, List.of())).isEqualTo("（暂无）");

        GameState blank = new GameState();
        blank.setNpcStates("");
        assertThat(assembler.buildNpcKeyReference(blank, List.of())).isEqualTo("（暂无）");

        GameState broken = new GameState();
        broken.setNpcStates("not-json");
        assertThat(assembler.buildNpcKeyReference(broken, List.of())).isEqualTo("（暂无）");
    }

    private CharacterCard card(String id, String name) {
        CharacterCard card = new CharacterCard();
        card.setId(id);
        card.setName(name);
        return card;
    }
}
