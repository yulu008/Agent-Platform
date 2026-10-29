package com.luyu.agent.rpg.planner.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StoryActOutlineParser} 分幕结构解析测试（story-graph-authoring tasks 2.2）。
 * <p>
 * 钉住：格式行解析（全角/半角竖线宽容）、非幕行跳过、小节边界（前/后小节
 * 不扫）、幕号连续性（断档/重复拒绝）、小节缺失/空稿返回空列表。
 */
@DisplayName("S4 分幕结构确定性解析")
class StoryActOutlineParserTest {

    private static final String PLOT = """
            ## 主线大纲
            剧情锚点按发生顺序输出：
            - 开局锚点：夜袭织田邸
            ## 分幕结构
            把主线划分为 3 幕。

            - 第 1 幕｜主题：潜入与结识｜幕末拍子：拿到密信｜出口轨道：trust_信长 >= 30
            说明行（不是幕行，应被跳过）
            - 第 2 幕｜主题：分裂与抉择｜幕末拍子：清洲会议摊牌｜出口轨道：已获刀 == true; 阵营 in [织田, 明智]
            - 第 3 幕｜主题：终局对决｜幕末拍子：比叡山火起｜出口轨道：终局 == true
            ## 叙事规则
            - 每章聚焦一名武将
            """;

    @Test
    void 解析三幕大纲行() {
        List<StoryActOutlineParser.ActOutline> acts = StoryActOutlineParser.parse(PLOT);

        assertThat(acts).hasSize(3);
        assertThat(acts.get(0).actNumber()).isEqualTo(1);
        assertThat(acts.get(0).theme()).isEqualTo("潜入与结识");
        assertThat(acts.get(0).endingBeat()).isEqualTo("拿到密信");
        assertThat(acts.get(0).orbitDraft()).isEqualTo("trust_信长 >= 30");
        assertThat(acts.get(2).theme()).isEqualTo("终局对决");
        // "已获刀/阵营"轨道在第 2 幕行；第 3 幕是终局轨道
        assertThat(acts.get(1).orbitDraft()).contains("已获刀", "阵营");
        assertThat(acts.get(2).orbitDraft()).isEqualTo("终局 == true");
    }

    @Test
    void 半角竖线宽容归一() {
        String plot = """
                ## 分幕结构
                - 第 1 幕|主题：潜入|幕末拍子：密信|出口轨道：a >= 1
                - 第 2 幕|主题：抉择|幕末拍子：摊牌|出口轨道：b == true
                """;

        List<StoryActOutlineParser.ActOutline> acts = StoryActOutlineParser.parse(plot);

        assertThat(acts).hasSize(2);
        assertThat(acts.get(0).theme()).isEqualTo("潜入");
        assertThat(acts.get(1).endingBeat()).isEqualTo("摊牌");
    }

    @Test
    void 小节外的幕行不被扫描() {
        // 主线大纲小节里出现相似行（无小节头前缀场景不存在，但防误扫其他小节）
        String plot = """
                ## 分幕结构
                - 第 1 幕｜主题：潜入｜幕末拍子：密信｜出口轨道：a >= 1
                ## 叙事规则
                - 第 2 幕｜主题：这行在叙事规则小节内｜幕末拍子：不应解析｜出口轨道：x
                """;

        List<StoryActOutlineParser.ActOutline> acts = StoryActOutlineParser.parse(plot);

        assertThat(acts).hasSize(1);
    }

    @Test
    void 幕号断档拒绝() {
        String plot = """
                ## 分幕结构
                - 第 1 幕｜主题：潜入｜幕末拍子：密信｜出口轨道：a >= 1
                - 第 3 幕｜主题：跳过了第 2 幕｜幕末拍子：断档｜出口轨道：x
                """;

        assertThatThrownBy(() -> StoryActOutlineParser.parse(plot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幕号不连续")
                .hasMessageContaining("期望 2");
    }

    @Test
    void 幕号重复拒绝() {
        String plot = """
                ## 分幕结构
                - 第 1 幕｜主题：潜入｜幕末拍子：密信｜出口轨道：a >= 1
                - 第 1 幕｜主题：重复幕号｜幕末拍子：重复｜出口轨道：x
                """;

        assertThatThrownBy(() -> StoryActOutlineParser.parse(plot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("幕号不连续");
    }

    @Test
    void 小节缺失返回空列表() {
        String plot = """
                ## 主线大纲
                - 开局锚点：夜袭
                ## 叙事规则
                - 每章聚焦一名武将
                """;

        assertThat(StoryActOutlineParser.parse(plot)).isEmpty();
    }

    @Test
    void 空稿与null返回空列表() {
        assertThat(StoryActOutlineParser.parse(null)).isEmpty();
        assertThat(StoryActOutlineParser.parse("   \n\n")).isEmpty();
        assertThat(StoryActOutlineParser.parse("")).isEmpty();
    }

    @Test
    void 同输入重复解析结果一致() {
        List<StoryActOutlineParser.ActOutline> first = StoryActOutlineParser.parse(PLOT);
        List<StoryActOutlineParser.ActOutline> second = StoryActOutlineParser.parse(PLOT);

        assertThat(first).isEqualTo(second);
    }
}
