package com.luyu.agent.rpg.planner.engine;

import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.StyleMode;
import org.springframework.stereotype.Component;

/**
 * S2 世界观生成器（add-planner-pipeline tasks 3.5 / design D2/D7/D9）。
 * <p>
 * 产物 {@code 02-world.md}（L1 编辑门：表单结构 Markdown 可改 + 整稿通过）。
 * 小节结构刻意对齐预设世界真源 md 的物化惯例（PresetWorldService 以
 * {@code ## } 二级标题切分：名称/时代/氛围/规则/世界描述/开场白模板）——
 * S6 安装物化（任务 5.1）按同一套小节解析直取 WorldSetting 表单列；
 * 附加「世界书条目」小节为通用轨 WorldbookEntry 草稿（标题/关键词/正文，
 * 字段对齐工坊世界书表单）。
 * <p>
 * 风格差异（D9）经 {@link StagePromptAssembler#styleSection} 全局约束：
 * faithful 严格沿用 S1 拆解稿设定；remix 以拆解稿为灵感允许改造。
 */
@Component
public class WorldSettingGenerator implements StageGenerator {

    /** 世界观成稿模板（小节标题是物化解析键，不可改动措辞） */
    static final String INSTRUCTION = """
            你的任务：基于直接上游的 IP 拆解稿，产出这个世界的世界观设定稿（Markdown）。
            报告分节输出（小节标题必须与下述完全一致，后续阶段按小节解析）：
            ## 名称
            世界名（建议 12 字内，可被后续阶段与卡片墙引用）
            ## 时代
            一段话：时代背景、纪年锚点与地理格局总览
            ## 氛围
            一段话：基调与情绪色彩（供 GM 定叙事口吻）
            ## 规则
            逐条列出 3-6 条世界硬规则（力量体系约束、社会法则、玩家不可越阶的铁律），每条一行
            ## 世界描述
            2-4 段世界正文：地理与势力分布、核心张力、日常质感——供 GM 开局与续写参考
            ## 开场白模板
            开局旁白模板，须同时包含 {{player_name}} 与 {{constellation}} 两个占位符
            （开局时以玩家名与主命星渲染），200 字内，以第二人称进入场景
            ## 世界书条目
            通用轨世界书条目草稿 4-8 条（宁缺毋滥），每条格式：
            ### 条目标题
            关键词：逗号分隔触发词（标题本身也参与触发）
            条目正文：100-300 字设定正文，命中关键词时注入 GM 叙事
            """;

    @Override
    public PipelineStage stage() {
        return PipelineStage.S2_WORLD_SETTING;
    }

    @Override
    public String buildPrompt(GenerationContext context) {
        StyleMode styleMode = StyleMode.fromCode(context.manifest().getStyleMode());
        StringBuilder prompt = new StringBuilder();
        prompt.append(StagePromptAssembler.styleSection(styleMode));
        prompt.append(INSTRUCTION);
        prompt.append('\n');
        // 更早阶段摘要行（S1）+ 直接上游确认稿全文（S1 拆解稿，主原料）
        prompt.append(StagePromptAssembler.upstreamSummariesSection(context.manifest(), stage()));
        String upstream = StagePromptAssembler.upstreamConfirmedSection(context.upstreamConfirmed());
        if (upstream != null) {
            prompt.append(upstream);
        }
        prompt.append(StagePromptAssembler.reworkSection(context));
        return prompt.toString();
    }
}
