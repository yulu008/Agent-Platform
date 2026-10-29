package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WorldSettingGenerator} prompt 装配测试（纯静态装配，无 mock）。
 * <p>
 * 钉住（tasks 3.5）：S2 阶段绑定；成稿模板的物化小节结构（六小节对齐
 * PresetWorldService 物化惯例 + 世界书条目小节）；开场白占位符约定；
 * 风格段落（D9）；上游摘要行 + 直接上游确认稿全文注入（D7）；返工约束（D5）。
 */
class WorldSettingGeneratorTest {

    private final WorldSettingGenerator generator = new WorldSettingGenerator();

    @Test
    void 阶段绑定S2() {
        assertThat(generator.stage()).isEqualTo(PipelineStage.S2_WORLD_SETTING);
    }

    @Test
    void 成稿模板含物化六小节与世界书条目小节() {
        String prompt = generator.buildPrompt(context("faithful", null, null, null));

        // 六小节 = PresetWorldService 物化解析键（S6 安装直取 WorldSetting 列）
        assertThat(prompt).contains("## 名称");
        assertThat(prompt).contains("## 时代");
        assertThat(prompt).contains("## 氛围");
        assertThat(prompt).contains("## 规则");
        assertThat(prompt).contains("## 世界描述");
        assertThat(prompt).contains("## 开场白模板");
        // 通用轨条目草稿（对齐工坊世界书表单字段）
        assertThat(prompt).contains("## 世界书条目");
        assertThat(prompt).contains("### 条目标题");
        assertThat(prompt).contains("关键词：");
    }

    @Test
    void 开场白模板约定双占位符() {
        String prompt = generator.buildPrompt(context("faithful", null, null, null));

        assertThat(prompt).contains("{{player_name}}");
        assertThat(prompt).contains("{{constellation}}");
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
    void 上游S1摘要行与确认稿全文都注入() {
        PipelineManifest manifest = manifest("faithful");
        manifest.stage("S1").setSummary("巨人与城墙的黑暗奇幻世界");
        GenerationContext context = context("faithful", "S1 拆解稿全文……", manifest, null);

        String prompt = generator.buildPrompt(context);

        // 摘要行（更早阶段，D7 非全文）
        assertThat(prompt).contains("【上游已确认摘要】");
        assertThat(prompt).contains("- S1 IP 拆解：巨人与城墙的黑暗奇幻世界");
        // 直接上游确认稿全文（S1 是 S2 的主原料）
        assertThat(prompt).contains("【直接上游确认稿全文】");
        assertThat(prompt).contains("S1 拆解稿全文……");
    }

    @Test
    void 无上游确认稿时不注入全文段() {
        String prompt = generator.buildPrompt(context("faithful", null, null, null));

        assertThat(prompt).doesNotContain("【直接上游确认稿全文】");
        assertThat(prompt).doesNotContain("【上游已确认摘要】");
    }

    @Test
    void 返工轮注入反馈与保持不变约束() {
        PipelineManifest.ReRound round = new PipelineManifest.ReRound();
        round.setRound(1);
        round.setFeedback("规则太平淡，要有代价感");
        GenerationContext context = context("faithful", null, null,
                new GenerationContext(manifest("faithful"), null, null,
                        "上轮全稿内容", List.of(round), null, null));

        String prompt = generator.buildPrompt(context);

        assertThat(prompt).contains("第 1 轮返工");
        assertThat(prompt).contains("规则太平淡，要有代价感");
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

    /** 便捷装配：manifest + 直接上游确认稿 + 完整 context。 */
    private static GenerationContext context(String styleMode, String upstreamConfirmed,
                                             PipelineManifest manifestOverride, GenerationContext reworkFrom) {
        if (reworkFrom != null) {
            return reworkFrom;
        }
        PipelineManifest manifest = manifestOverride != null ? manifestOverride : manifest(styleMode);
        return new GenerationContext(manifest, "原始输入文本", upstreamConfirmed, null, null, null, null);
    }
}
