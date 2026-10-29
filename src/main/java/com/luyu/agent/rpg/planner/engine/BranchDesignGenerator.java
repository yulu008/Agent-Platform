package com.luyu.agent.rpg.planner.engine;

import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.StyleMode;
import org.springframework.stereotype.Component;

/**
 * S5 分支设计生成器（add-planner-pipeline tasks 3.6 / design D4/D7）。
 * <p>
 * 产物 {@code 05-branches/candidate-NN.md}（L2 返工门：逐卡毙掉/编辑/返工）：
 * 每个行首 {@code ## } 二级标题一个分支候选块，字段四件套对齐物化语义——
 * 触发词（keywords）/ 轨位（world 通用轨 / star 冥星轨）/ 优先级（注入
 * 排序与冲突让位）/ 注入内容（命中时注入 GM 的分支引导文本）。S6 物化
 * （任务 5.1）按字段直取 WorldbookEntry 形态。
 * <p>
 * 候选数量上限（{@code rpg.planner.max-candidates}）为 prompt 硬约束
 * （design 风险表：防 S5 分支过多导致上下文膨胀）。
 */
@Component
public class BranchDesignGenerator implements StageGenerator {

    /** 分支候选成稿模板（## 块结构 = 候选拆分键；字段头是任务 4/5 解析键） */
    static final String INSTRUCTION = """
            你的任务：基于上游全部已确认产物，为这个世界设计剧情分支触发条目候选集（Markdown）。
            分支候选上限 %d 个（硬上限，宁缺毋滥：只收能改变故事走向的分支，不收氛围性条目）。
            每个分支一个独立候选块（行首 ## 二级标题开启，块外不要有任何其他正文——
            系统按 ## 标题把全稿拆成逐卡候选）。候选块格式：

            ## 分支 1：<分支标题>
            触发词：<逗号分隔（玩家叙述或 NPC 对话命中即触发注入；含关键人名/地名/事件名）>
            轨位：<world 或 star——world=通用世界轨；star=与冥星武将录关联的注入>
            优先级：<1-9，数字越大越优先；同轮多分支命中时低优先级让位>
            注入内容：
            <多行正文：命中触发词时注入 GM 的分支引导——场景钩子、可参与的人物、
            张力方向与代价提示。这是给 GM 的方向盘，不是逐句剧本>

            触发词命中依赖玩家自然叙述，选词要具体（人名、地名、事件名），
            不要选「战斗」「对话」这类高频泛词。
            """;

    private final RpgPlannerProperties properties;

    public BranchDesignGenerator(RpgPlannerProperties properties) {
        this.properties = properties;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.S5_BRANCH_DESIGN;
    }

    @Override
    public String buildPrompt(GenerationContext context) {
        StyleMode styleMode = StyleMode.fromCode(context.manifest().getStyleMode());
        StringBuilder prompt = new StringBuilder();
        prompt.append(StagePromptAssembler.styleSection(styleMode));
        prompt.append(INSTRUCTION.formatted(properties.getMaxCandidates()));
        prompt.append('\n');
        // 更早阶段摘要行（S1-S4）+ 直接上游确认稿全文（S4 剧情结构稿）
        prompt.append(StagePromptAssembler.upstreamSummariesSection(context.manifest(), stage()));
        String upstream = StagePromptAssembler.upstreamConfirmedSection(context.upstreamConfirmed());
        if (upstream != null) {
            prompt.append(upstream);
        }
        prompt.append(StagePromptAssembler.reworkSection(context));
        return prompt.toString();
    }
}
