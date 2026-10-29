package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CharactersGenerator} 与 {@link BranchDesignGenerator} prompt 装配测试
 * （tasks 3.6，纯装配无 mock）。
 * <p>
 * 钉住：阶段绑定；候选块模板（## 块结构 + 块外无正文约束 + 字段头——
 * S3 人物卡字段对齐 CharacterCard，命定档案子节已整体移除；S5 四件套字段）；
 * 候选数量上限注入（rpg.planner.max-candidates）；上游注入与返工约束。
 */
class CandidateGeneratorsTest {

    private CharactersGenerator characters;
    private BranchDesignGenerator branches;

    @BeforeEach
    void setUp() {
        RpgPlannerProperties properties = new RpgPlannerProperties();
        properties.setMaxCandidates(5);
        characters = new CharactersGenerator(properties);
        branches = new BranchDesignGenerator(properties);
    }

    // ===== S3 人物候选集 =====

    @Test
    void S3阶段绑定() {
        assertThat(characters.stage()).isEqualTo(PipelineStage.S3_CHARACTERS);
    }

    @Test
    void S3模板含候选块结构与人物卡字段() {
        String prompt = characters.buildPrompt(context("faithful", null));

        assertThat(prompt).contains("## 人物 1：<人物名>");
        assertThat(prompt).contains("按 ## 标题把全稿拆成逐卡候选");
        // 人物卡字段（CharacterCard 物化对齐）
        assertThat(prompt).contains("- 身份：");
        assertThat(prompt).contains("- 性格：");
        assertThat(prompt).contains("- 背景：");
        assertThat(prompt).contains("- 动机：");
        assertThat(prompt).contains("- 说话风格：");
        assertThat(prompt).contains("- 知识：");
        // 命定档案子节已整体移除（非星命世界曾批量产出 faction 占位脏数据）
        assertThat(prompt).doesNotContain("### 命定档案");
        assertThat(prompt).doesNotContain("- faction:");
        assertThat(prompt).doesNotContain("- constellation:");
        assertThat(prompt).doesNotContain("- starRank:");
        assertThat(prompt).doesNotContain("- eraNote:");
    }

    @Test
    void S3注入候选数量上限() {
        String prompt = characters.buildPrompt(context("faithful", null));

        assertThat(prompt).contains("人物候选上限 5 个");
    }

    @Test
    void S3名册注入与覆盖语义() {
        GenerationContext ctx = new GenerationContext(manifest("faithful"), "原始输入文本",
                null, null, null, null,
                "## 三、人物全量名册\n- **严嵩**\n  - 身份：明代奸臣\n- **欧阳轩**：济州大侠");

        String prompt = characters.buildPrompt(ctx);

        assertThat(prompt).contains("【人物全量名册（S1 确认稿原文）】");
        assertThat(prompt).contains("- **严嵩**");
        assertThat(prompt).contains("欧阳轩");
        assertThat(prompt).contains("不要只聚焦各门派首领级人物");
        assertThat(prompt).contains("不得虚构名册外人物");
        // 名册段在指令之后、上游注入之前（对照要求紧跟名册本体）
        assertThat(prompt.indexOf("名册对照要求"))
                .isGreaterThan(prompt.indexOf("人物候选上限"));
    }

    @Test
    void S3无名册零注入() {
        // remix / 旧稿 roster 为 null：名册段与覆盖语义均不出现（原行为不变；
        // 注意 INSTRUCTION 上限句本身提及「见名册对照要求」，反断言锄名册段专属锄点）
        String prompt = characters.buildPrompt(context("remix", "S2 世界观稿"));

        assertThat(prompt).doesNotContain("【人物全量名册");
        assertThat(prompt).doesNotContain("不要只聚焦各门派首领级人物");
    }

    // ===== S5 分支候选集 =====

    @Test
    void S5阶段绑定() {
        assertThat(branches.stage()).isEqualTo(PipelineStage.S5_BRANCH_DESIGN);
    }

    @Test
    void S5模板含四件套字段与块结构() {
        String prompt = branches.buildPrompt(context("faithful", null));

        assertThat(prompt).contains("## 分支 1：<分支标题>");
        assertThat(prompt).contains("按 ## 标题把全稿拆成逐卡候选");
        // 四件套字段（物化解析键）
        assertThat(prompt).contains("触发词：");
        assertThat(prompt).contains("轨位：");
        assertThat(prompt).contains("优先级：");
        assertThat(prompt).contains("注入内容：");
        assertThat(prompt).contains("world 或 star");
    }

    @Test
    void S5注入候选数量上限() {
        String prompt = branches.buildPrompt(context("faithful", null));

        assertThat(prompt).contains("分支候选上限 5 个");
    }

    // ===== 共用：风格/上游/返工 =====

    @Test
    void 两个生成器都注入风格段与二创差异() {
        assertThat(characters.buildPrompt(context("faithful", null))).contains("【风格：忠实拆解】");
        assertThat(branches.buildPrompt(context("remix", null))).contains("【风格：自由二创】");
    }

    @Test
    void S3上游S2确认稿全文注入() {
        String prompt = characters.buildPrompt(context("faithful", "S2 世界观稿全文……"));

        assertThat(prompt).contains("【直接上游确认稿全文】");
        assertThat(prompt).contains("S2 世界观稿全文……");
    }

    @Test
    void S5上游S4确认稿全文注入() {
        String prompt = branches.buildPrompt(context("faithful", "S4 剧情稿全文……"));

        assertThat(prompt).contains("【直接上游确认稿全文】");
        assertThat(prompt).contains("S4 剧情稿全文……");
    }

    @Test
    void 返工轮注入已毙排除表() {
        GenerationContext ctx = new GenerationContext(manifest("faithful"), "原始输入文本", null,
                "上轮全稿", List.of(round(1, "第一卡人物太扁")),
                List.of("织田信长"), null);

        String charactersPrompt = characters.buildPrompt(ctx);
        String branchesPrompt = branches.buildPrompt(ctx);

        assertThat(charactersPrompt).contains("【上轮全稿】");
        assertThat(charactersPrompt).contains("织田信长");   // 已毙排除表（D5 防「复活」）
        assertThat(branchesPrompt).contains("已毙候选排除表");
    }

    // ===== 辅助 =====

    private static PipelineManifest manifest(String styleMode) {
        PipelineManifest manifest = new PipelineManifest();
        manifest.setStyleMode(styleMode);
        return manifest;
    }

    private static PipelineManifest.ReRound round(int no, String feedback) {
        PipelineManifest.ReRound round = new PipelineManifest.ReRound();
        round.setRound(no);
        round.setFeedback(feedback);
        return round;
    }

    private static GenerationContext context(String styleMode, String upstreamConfirmed) {
        return new GenerationContext(manifest(styleMode), "原始输入文本",
                upstreamConfirmed, null, null, null, null);
    }
}
