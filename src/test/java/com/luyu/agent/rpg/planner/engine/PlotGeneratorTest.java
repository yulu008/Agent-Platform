package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlotGenerator} prompt 装配测试（纯静态装配，无 mock）。
 * <p>
 * 钉住（tasks 3.5）：S4 阶段绑定；成稿模板双小节（主线大纲：锚点按发生
 * 顺序 + 结局可改写；叙事规则：世界特有定制不重复通用 GM 规范）；
 * 风格段落（D9）；上游摘要行（S1-S3）+ 直接上游确认稿全文（S3 人物稿）；
 * 返工约束（D5）。
 */
class PlotGeneratorTest {

    private final PlotGenerator generator = new PlotGenerator();

    @Test
    void 阶段绑定S4() {
        assertThat(generator.stage()).isEqualTo(PipelineStage.S4_PLOT_STRUCTURE);
    }

    @Test
    void 成稿模板含主线大纲与叙事规则双小节() {
        String prompt = generator.buildPrompt(context("faithful", null, null, null));

        assertThat(prompt).contains("## 主线大纲");
        assertThat(prompt).contains("## 叙事规则");
        // 大纲语义锚点：开局 / 中枢转折 / 结局方向（可被玩家改写）
        assertThat(prompt).contains("开局锚点");
        assertThat(prompt).contains("中枢转折");
        assertThat(prompt).contains("结局方向");
        assertThat(prompt).contains("玩家选择改写");
        // 叙事规则定位：世界特有定制，不重复通用 GM 规范
        assertThat(prompt).contains("这个世界特有的叙事约束");
        assertThat(prompt).contains("不要重复");
    }

    @Test
    void 成稿模板含分幕结构小节供S4点5解析() {
        // story-graph-authoring 2.2：分幕结构是 S4.5 幕划分真源（行格式被
        // StoryActOutlineParser 钉住：字段名与全角分隔符）
        String prompt = generator.buildPrompt(context("faithful", null, null, null));

        assertThat(prompt).contains("## 分幕结构");
        assertThat(prompt).contains("每幕后续展开为不超过 25 个节点");
        assertThat(prompt).contains("- 第 1 幕｜主题：");
        assertThat(prompt).contains("｜幕末拍子：");
        assertThat(prompt).contains("｜出口轨道：");
    }

    @Test
    void 忠实模式注入贴设定风格段() {
        String prompt = generator.buildPrompt(context("faithful", null, null, null));

        assertThat(prompt).contains("【风格：忠实拆解】");
    }

    @Test
    void 二创模式注入自由改造风格段() {
        String prompt = generator.buildPrompt(context("remix", null, null, null));

        assertThat(prompt).contains("【风格：自由二创】");
    }

    @Test
    void 上游S1至S3摘要行注入且只含已确认阶段() {
        PipelineManifest manifest = manifest("faithful");
        manifest.stage("S1").setSummary("巨人世界的黑暗奇幻");
        manifest.stage("S2").setSummary("三层城墙下的最后人类国度");
        manifest.stage("S3").setSummary("六位核心人物：兵长、主角与其战友");

        String prompt = generator.buildPrompt(context("faithful", null, manifest, null));

        assertThat(prompt).contains("- S1 IP 拆解：巨人世界的黑暗奇幻");
        assertThat(prompt).contains("- S2 世界观设定：三层城墙下的最后人类国度");
        assertThat(prompt).contains("- S3 人物设定：六位核心人物：兵长、主角与其战友");
        // S4 自身与之后的阶段不进摘要
        assertThat(prompt).doesNotContain("- S4");
        assertThat(prompt).doesNotContain("- S5");
    }

    @Test
    void 直接上游S3确认稿全文注入() {
        String prompt = generator.buildPrompt(context("faithful", "S3 人物稿全文……", null, null));

        assertThat(prompt).contains("【直接上游确认稿全文】");
        assertThat(prompt).contains("S3 人物稿全文……");
    }

    @Test
    void 无上游时不注入全文与摘要段() {
        String prompt = generator.buildPrompt(context("faithful", null, null, null));

        assertThat(prompt).doesNotContain("【直接上游确认稿全文】");
        assertThat(prompt).doesNotContain("【上游已确认摘要】");
    }

    @Test
    void 返工轮注入反馈与上轮全稿() {
        // 两轮返工历史（reworkRound = 反馈条数；ReRound.round = 该反馈的轮次编号）
        PipelineManifest.ReRound round1 = new PipelineManifest.ReRound();
        round1.setRound(1);
        round1.setFeedback("节奏太快");
        PipelineManifest.ReRound round2 = new PipelineManifest.ReRound();
        round2.setRound(2);
        round2.setFeedback("主线太平铺直叙");
        GenerationContext context = new GenerationContext(manifest("faithful"), null, null,
                "上轮全稿内容", List.of(round1, round2), null, null);

        String prompt = generator.buildPrompt(context);

        assertThat(prompt).contains("第 2 轮返工");
        assertThat(prompt).contains("- 第 1 轮反馈：节奏太快");
        assertThat(prompt).contains("- 第 2 轮反馈：主线太平铺直叙");
        assertThat(prompt).contains("反馈未提及的部分保持与上轮一致");
        assertThat(prompt).contains("【上轮全稿】");
        assertThat(prompt).contains("上轮全稿内容");
    }

    // ===== 辅助 =====

    private static PipelineManifest manifest(String styleMode) {
        PipelineManifest manifest = new PipelineManifest();
        manifest.setStyleMode(styleMode);
        return manifest;
    }

    private static GenerationContext context(String styleMode, String upstreamConfirmed,
                                             PipelineManifest manifestOverride, GenerationContext reworkFrom) {
        if (reworkFrom != null) {
            return reworkFrom;
        }
        PipelineManifest manifest = manifestOverride != null ? manifestOverride : manifest(styleMode);
        return new GenerationContext(manifest, "原始输入文本", upstreamConfirmed, null, null, null, null);
    }
}
