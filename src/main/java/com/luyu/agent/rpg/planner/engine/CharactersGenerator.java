package com.luyu.agent.rpg.planner.engine;

import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.StyleMode;
import org.springframework.stereotype.Component;

/**
 * S3 人物候选集生成器（add-planner-pipeline tasks 3.6 / design D4/D7）。
 * <p>
 * 产物 {@code 03-characters/candidate-NN.md}（L2 返工门：逐卡毙掉/编辑/返工）：
 * SSE 流输出候选集全稿（每个行首 {@code ## } 二级标题一个候选块），完成时经
 * {@link CandidateSplitter} 拆分逐候选落盘。人物卡字段对齐
 * {@code CharacterCard}（身份/性格/背景/动机/说话风格/知识——S6 物化直取）。
 * 曾有的「### 命定档案」子节已整体移除（2026-10：无条件注入导致非星命
 * 世界批量产出占位脏数据——faction 照抄模板示例值 saint、未绑定字段填
 * 「无」；星命体系仅预设世界真源 md 物化维护，planner 产物不再承载）。
 * InstallParser 对旧卡残留子节仍兼容解析（历史 run 安装容错）。
 * <p>
 * 候选数量上限（{@code rpg.planner.max-candidates}）为 prompt 硬约束
 * （design 风险表：防 S3 人物过多导致上下文膨胀）。
 */
@Component
public class CharactersGenerator implements StageGenerator {

    /**
     * 人物候选成稿模板（## 块结构 = 候选拆分键，不可改动）。
     * 上限句「有名册对照时按覆盖优先」——名册段注入时由名册对照要求给出
     * 覆盖语义（不再只收首领级核心人物）。
     */
    static final String INSTRUCTION = """
            你的任务：基于上游已确认的拆解与世界观，产出这个世界的人物候选集（Markdown）。
            人物候选上限 %d 个（硬上限；有名册对照时按覆盖优先，见名册对照要求）。
            每个人物一个独立候选块（行首 ## 二级标题开启，块外不要有任何其他正文——
            系统按 ## 标题把全稿拆成逐卡候选）。候选块格式：

            ## 人物 1：<人物名>
            - 身份：<一句话身份钩子（GM 一眼识别用）>
            - 性格：<性格与行为模式>
            - 背景：<出身与关键经历>
            - 动机：<想要什么、为此愿意付出什么>
            - 说话风格：<口吻与语言习惯>
            - 知识：<逗号分隔的领域清单>

            人物间关系不单设块——写进各自的背景与动机里。
            """;

    private final RpgPlannerProperties properties;

    public CharactersGenerator(RpgPlannerProperties properties) {
        this.properties = properties;
    }

    @Override
    public PipelineStage stage() {
        return PipelineStage.S3_CHARACTERS;
    }

    @Override
    public String buildPrompt(GenerationContext context) {
        StyleMode styleMode = StyleMode.fromCode(context.manifest().getStyleMode());
        StringBuilder prompt = new StringBuilder();
        prompt.append(StagePromptAssembler.styleSection(styleMode));
        prompt.append(INSTRUCTION.formatted(properties.getMaxCandidates()));
        prompt.append('\n');
        // 更早阶段摘要行（S1/S2）+ 直接上游确认稿全文（S2 世界观稿）
        prompt.append(StagePromptAssembler.upstreamSummariesSection(context.manifest(), stage()));
        if (context.isBatch()) {
            appendBatchSections(context, prompt);
        } else {
            appendRosterSection(context, prompt);
        }
        String upstream = StagePromptAssembler.upstreamConfirmedSection(context.upstreamConfirmed());
        if (upstream != null) {
            prompt.append(upstream);
        }
        prompt.append(StagePromptAssembler.reworkSection(context));
        return prompt.toString();
    }

    /**
     * 批内形态注入（planner-s3-batch-generate tasks 3.3，spec「批内 prompt 不膨胀」）：
     * 本批名册块（非全量名册）+ 已产出人名索引（防重产出同名人物、供写跨批
     * 人物关系）。返工段不适用于批内（补批/全量与返工互斥，编排层保证 rounds 空）。
     */
    private static void appendBatchSections(GenerationContext context, StringBuilder prompt) {
        prompt.append("【本批人物名册（S1 确认稿节选）】\n")
                .append(context.batchRoster()).append("\n\n");
        prompt.append("""
                本批任务：只为上方名册中的人物产出候选（不得虚构名册外人物，也不得遗漏）。
                每张卡的 ## 标题必须以该人物在名册中的原名开头（如 `## 人物001：身份描述`：
                名册原名原样保留在标题里，不得自行重新编号、不得用绰号身份名替代原名——
                系统按标题与名册名对账，标题对不上的名册人物会判定为未产出而触发补批重跑）。
                """);
        prompt.append('\n');
        if (context.producedNames() != null && !context.producedNames().isEmpty()) {
            prompt.append("【已产出人物（其他批已生成候选，禁止重复产出）】\n");
            context.producedNames().forEach(name -> prompt.append("- ").append(name).append('\n'));
            prompt.append("""

                    以上人物已有候选卡，本批不得重复产出（同名人物不重开卡）；
                    写人物间关系时可引用他们（关系写进本批人物的背景与动机里）。
                    """);
            prompt.append('\n');
        }
    }

    /**
     * 单流路径名册对照注入（名册遗漏反馈）：S1 全量名册直通——候选从名册里选产出，
     * 而不是只从 S2 世界书显式人物里挑首领级几个；无名册（remix / 旧稿）零注入。
     * 行为与名册机制引入前一致（批内形态走 appendBatchSections，不经此路径）。
     */
    private static void appendRosterSection(GenerationContext context, StringBuilder prompt) {
        String roster = context.roster();
        if (roster == null) {
            return;
        }
        prompt.append("【人物全量名册（S1 确认稿原文）】\n").append(roster).append("\n\n");
        prompt.append("""
                名册对照要求：名册是拆解阶段从原文收录的全部具名人物（不得虚构名册外人物）。
                凡名册中对主线有推动力、有独立剧情节点或与玩家阵营博弈直接相关的人物
                都应产出候选，不要只聚焦各门派首领级人物；超出上限时按剧情参与度
                从高到低排满上限，其余人物由创作者返工反馈点名补充。
                """);
        prompt.append('\n');
    }
}
