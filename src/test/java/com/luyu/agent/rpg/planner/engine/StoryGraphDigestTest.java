package com.luyu.agent.rpg.planner.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.story.engine.StoryGraphParser;

/**
 * {@link StoryGraphDigest} 前幕摘要提取测试（story-graph-authoring tasks 2.2 /
 * spec「生成注入纪律」）。
 * <p>
 * 钉住：每幕一行摘要形态（入口/锚点链/轨道表文本化）、第 1 幕 state_schema
 * 提取、坏幕防御降级、空输入。注入纪律（prompt 不含前幕全量 JSON）由
 * StoryGraphPromptBuilderTest 联合钉住。
 */
@DisplayName("前幕锚点链与轨道表摘要提取")
class StoryGraphDigestTest {

    private final StoryGraphDigest digest = new StoryGraphDigest(new StoryGraphParser());

    private static final String ACT1 = """
            <story_graph>
            {
              "act": 1,
              "theme": "潜入与结识",
              "converge_to": [
                {"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 30}},
                {"orbit": "怀疑线", "guard": {"key": "trust_信长", "op": "<", "value": 30}}
              ],
              "state_schema": {"trust_信长": {"type": "number", "min": 0, "max": 100}},
              "nodes": [
                {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                {"id": "n1_02", "type": "scene", "beat": "廊下遇伏"},
                {"id": "n1_07", "type": "anchor", "beat": "拿到密信"}
              ],
              "edges": [
                {"from": "n1_01", "to": "n1_02", "label": "潜入"},
                {"from": "n1_02", "to": "n1_07", "label": "脱身"}
              ]
            }
            </story_graph>
            """;

    private static final String ACT2 = """
            <story_graph>
            {
              "act": 2,
              "theme": "分裂与抉择",
              "entry_guard": {"key": "trust_信长", "op": ">=", "value": 30},
              "converge_to": [{"orbit": "持刀线", "guard": {"key": "已获刀", "op": "==", "value": true}}],
              "nodes": [
                {"id": "n2_01", "type": "scene", "beat": "清洲城密议"},
                {"id": "n2_05", "type": "anchor", "beat": "清洲会议摊牌"}
              ],
              "edges": [
                {"from": "n2_01", "to": "n2_05", "label": "摊牌"}
              ]
            }
            </story_graph>
            """;

    @Test
    void 每幕一行摘要含入口锚点链与轨道表() {
        StoryGraphDigest.Digest result = digest.build(List.of(ACT1, ACT2));

        String text = result.summaryText();
        assertThat(text).contains("第 1 幕「潜入与结识」");
        // 入口 = 序号最小节点
        assertThat(text).contains("入口 n1_01");
        // 锚点链（只列 anchor/ending，不含普通 scene）
        assertThat(text).contains("n1_07（拿到密信）");
        assertThat(text).doesNotContain("n1_02（廊下遇伏）");
        // 轨道表文本化
        assertThat(text).contains("信任线 = trust_信长 >= 30");
        assertThat(text).contains("怀疑线 = trust_信长 < 30");
        assertThat(text).contains("第 2 幕「分裂与抉择」");
        assertThat(text).contains("入口 n2_01");
        assertThat(text).contains("n2_05（清洲会议摊牌）");
        assertThat(text).contains("持刀线 = 已获刀 == true");
    }

    @Test
    void 第1幕词汇表随摘要提取() {
        StoryGraphDigest.Digest result = digest.build(List.of(ACT1, ACT2));

        assertThat(result.stateSchema()).contains("trust_信长").contains("number");
    }

    @Test
    void 摘要不携带前幕全量节点边JSON() {
        // spec 注入纪律：摘要只含锚点与轨道，n1_02（非锚点 scene）与边数组不出现
        StoryGraphDigest.Digest result = digest.build(List.of(ACT1));

        String text = result.summaryText();
        assertThat(text).doesNotContain("n1_02");
        assertThat(text).doesNotContain("\"edges\"");
        assertThat(text).doesNotContain("\"nodes\"");
    }

    @Test
    void 坏幕防御降级标注不中断() {
        StoryGraphDigest.Digest result = digest.build(List.of(ACT1, "不是合法 JSON {"));

        assertThat(result.summaryText()).contains("第 1 幕「潜入与结识」");
        assertThat(result.summaryText()).contains("第 2 幕：（骨架损坏，摘要不可用）");
    }

    @Test
    void 空输入返回null摘要与null词汇表() {
        StoryGraphDigest.Digest result = digest.build(List.of());

        assertThat(result.summaryText()).isNull();
        assertThat(result.stateSchema()).isNull();
        assertThat(digest.build(null).summaryText()).isNull();
    }

    @Test
    void guard文本化数组值() {
        String guard = StoryGraphDigest.guardText(
                new com.luyu.agent.rpg.story.model.GuardClause("阵营", "in", List.of("织田", "明智")));

        assertThat(guard).isEqualTo("阵营 in [织田, 明智]");
    }

    @Test
    void 同输入重复构建摘要一致() {
        String first = digest.build(List.of(ACT1, ACT2)).summaryText();
        String second = digest.build(List.of(ACT1, ACT2)).summaryText();

        assertThat(first).isEqualTo(second);
    }
}
