package com.luyu.agent.rpg.planner.engine;

import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.StyleMode;
import org.springframework.stereotype.Component;

/**
 * S4 剧情结构生成器（add-planner-pipeline tasks 3.5 / design D2/D7）。
 * <p>
 * 产物 {@code 04-plot.md}（L1 编辑门：Markdown 可改 + 整稿通过）：
 * 主线大纲（开局锚点 → 发展节拍 → 中枢转折 → 高潮与结局方向，剧情锚点
 * 按发生顺序）+ 叙事规则草稿（世界专属叙事定制）。叙事规则是
 * <b>这个世界特有</b>的约束（节奏/张力/尺度/口吻/禁写事项）——与全局
 * GM 叙事规范（{@code rpg/gm-narrative-rules.md} 的汉字指称、禁章节
 * 字眼等通用条目）互补不重复；S6 物化为世界叙事规则文件。
 * <p>
 * 结局是「方向」而非定死终点——RPG 的终局形态须可被玩家选择改写，
 * 大纲锚定张力曲线而非剥夺自由度。
 */
@Component
public class PlotGenerator implements StageGenerator {

    /**
     * 剧情结构成稿模板（小节标题是 S6 物化解析键，不可改动措辞）。
     * <p>
     * 「## 分幕结构」是 S4.5 故事图骨架的幕划分真源（story-graph-authoring
     * design D2/D9：幕是生成/收束/返工单位）——S4.5 按本小节逐幕生成骨架，
     * 行格式由 {@code StoryActOutlineParser} 确定性解析（钉住字段名与全角分隔符）。
     */
    static final String INSTRUCTION = """
            你的任务：综合上游已确认的拆解、世界观与人物，产出这个世界的剧情结构稿（Markdown）。
            报告分节输出（小节标题必须与下述完全一致，后续阶段按小节解析）：
            ## 主线大纲
            剧情锚点按发生顺序输出：
            - 开局锚点：玩家进入世界的初始处境与第一钩子（与开场白模板衔接）
            - 发展节拍：2-4 个中段大节拍，每个含冲突推进与代价（不要逐场景展开，保持大纲粒度）
            - 中枢转折：1 个不可绕过的中枢事件（世界的核心张力在此摊牌）
            - 高潮与结局方向：终局对峙的形态——结局是方向而非定死终点，须可被玩家选择改写
            ## 分幕结构
            把主线划分为 3-6 幕（每幕后续展开为不超过 25 个节点的分支图，最后一幕包含终局）。
            每幕一行，格式固定（字段名与分隔符不可改）：
            - 第 1 幕｜主题：一句幕主题｜幕末拍子：该幕收束锚点的剧情拍子｜出口轨道：状态键 op 数值（多个轨道用分号分隔）
            出口轨道是幕结束时世界态应满足的收敛谓词（下一幕入口条件），状态键用简洁稳定的
            领域词（如 trust_信长、已获刀）；最后一幕的出口轨道写终局条件。幕数与主线节拍对应，
            不要为凑数增删幕。
            ## 叙事规则
            逐条列出 3-6 条这个世界特有的叙事约束，每条一行，供 GM 叙事时遵守：
            节奏与张力曲线（爆发前的前置积累）、暴力/情感尺度边界、叙述口吻与视角惯例、
            禁写事项（这个世界中绝不该出现的内容）。只写这个世界特有的定制，
            通用叙事规范（汉字指称、禁章节字眼等）由系统另行注入，不要重复。
            """;

    @Override
    public PipelineStage stage() {
        return PipelineStage.S4_PLOT_STRUCTURE;
    }

    @Override
    public String buildPrompt(GenerationContext context) {
        StyleMode styleMode = StyleMode.fromCode(context.manifest().getStyleMode());
        StringBuilder prompt = new StringBuilder();
        prompt.append(StagePromptAssembler.styleSection(styleMode));
        prompt.append(INSTRUCTION);
        prompt.append('\n');
        // 更早阶段摘要行（S1-S3）+ 直接上游确认稿全文（S3 人物稿）
        prompt.append(StagePromptAssembler.upstreamSummariesSection(context.manifest(), stage()));
        String upstream = StagePromptAssembler.upstreamConfirmedSection(context.upstreamConfirmed());
        if (upstream != null) {
            prompt.append(upstream);
        }
        prompt.append(StagePromptAssembler.reworkSection(context));
        return prompt.toString();
    }
}
