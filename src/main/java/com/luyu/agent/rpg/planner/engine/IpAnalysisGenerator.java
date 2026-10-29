package com.luyu.agent.rpg.planner.engine;

import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.SourceMode;
import com.luyu.agent.rpg.planner.model.StyleMode;
import org.springframework.stereotype.Component;

/**
 * S1 IP 拆解生成器（add-planner-pipeline tasks 3.3 / design D8/D9）。
 * <p>
 * text 模式：单流深度提炼（原文 → 结构化拆解稿，token 流式可观感）。
 * tasks 描述的「多轮提炼归纳」v1 以单轮结构化深度拆解实现——一次 SSE 流内
 * 完成要素提取与归纳成稿（流式体验与断点语义最简）；多轮链式提炼留调优期
 * 升级（接口不变，生成器内部串联即可）。ip_name 模式的受限搜索循环是
 * 独立的 agentic 实现（tasks 3.4），不经本类。
 * <p>
 * 产物 {@code 01-ip-analysis.md}（L1 编辑门：Markdown 可改 + 整稿通过）：
 * 风格差异（D9）——faithful 每条设定带出处标注（gate 处逐条核对、可反馈
 * 「这条重查」）；remix 只附灵感源清单不带出处。
 */
@Component
public class IpAnalysisGenerator implements StageGenerator {

    /**
     * 忠实拆解成稿模板（包级可见：搜索模式 ip_name 复用同一成稿结构，tasks 3.4）。
     * 人物节为全量名册语义（planner-html-source 实测反馈：提炼式「重要人物」裁量
     * 会把资料页大量具名人物裁掉，faithful 期望逐人收录——单流与链式汇总轮共用
     * 本模板，一处改动双路径生效）
     */
    static final String FAITHFUL_INSTRUCTION = """
            你的任务：对下面的原文做一次完整的 IP 拆解，产出《IP 拆解报告》（Markdown）。
            报告分节输出：
            ## 一、核心概念
            这个 IP 的一句话灵魂（题材、基调、最独特的设定）
            ## 二、世界观要素
            力量体系/规则、时代与地理、社会结构、关键组织——逐条列出
            ## 三、人物全量名册
            原文出现的所有具名人物一律收录，不得省略、合并或降格为群体一笔带过：
            每人一个条目（姓名：身份、动机、人物关系、标志性特征）；
            戏份极少的次要人物可精简为姓名 + 一句话身份，但姓名必须出现
            ## 四、主线与关键剧情节点
            主线脉络 + 不可绕过的剧情锚点（按发生顺序）
            ## 五、风格与基调
            叙事口吻、氛围、暴力/情感尺度
            ## 六、出处对照表
            上面每条关键设定一行：设定摘要 | 原文出处（章节/段落/引文片段）
            """;

    /** 自由二创成稿模板（包级可见：搜索模式 ip_name 复用同一成稿结构，tasks 3.4） */
    static final String REMIX_INSTRUCTION = """
            你的任务：以下面原文为灵感源做一次自由二创拆解，产出《灵感拆解报告》（Markdown）。
            报告分节输出：
            ## 一、灵感内核
            这个 IP 最值得借鉴的 3 个灵魂要素（不是复述，是提炼可迁移的创作资产）
            ## 二、可改造的世界观骨架
            力量体系/规则的骨架 + 你建议的改造方向
            ## 三、值得保留的人物气质原型
            人物原型（气质、动机结构）而非具体人物
            ## 四、剧情节奏资产
            原作叙事节奏中值得复用的结构（伏笔手法、张力曲线）
            ## 五、二创方向建议
            3 条可行的混搭/改造方向（一句话即可）
            ## 六、灵感源清单
            从原文中提取的意象/概念清单（一行一个，不带出处）
            """;

    @Override
    public PipelineStage stage() {
        return PipelineStage.S1_IP_ANALYSIS;
    }

    @Override
    public String buildPrompt(GenerationContext context) {
        StyleMode styleMode = StyleMode.fromCode(context.manifest().getStyleMode());
        // ip_name 模式措辞适配（planner-html-source tasks 4.2）：原料是上传资料页
        // 合并虚拟原文（页间「===== 资料页 NN =====」分隔行，readS1Material 已带
        // IP 名语境头），忠实拆解的出处对照表锚到资料页页号、原料标签用资料页
        // 语义；text 模式措辞零改动
        boolean ipNameMode = SourceMode.IP_NAME.code().equals(context.manifest().getSourceMode());
        StringBuilder prompt = new StringBuilder();
        prompt.append(StagePromptAssembler.styleSection(styleMode));
        String instruction = styleMode == StyleMode.REMIX ? REMIX_INSTRUCTION : FAITHFUL_INSTRUCTION;
        if (ipNameMode && styleMode == StyleMode.FAITHFUL) {
            instruction = FAITHFUL_INSTRUCTION.replace(
                    "原文出处（章节/段落/引文片段）", "资料页出处（===== 资料页 NN ===== 分隔行页号）");
        }
        prompt.append(instruction);
        prompt.append('\n');
        // S1 无上游确认稿；返工轮注入反馈约束（D5）
        prompt.append(StagePromptAssembler.reworkSection(context));
        prompt.append(ipNameMode ? "【资料页合并文本】\n" : "【原文】\n")
                .append(context.sourceText() == null ? "" : context.sourceText());
        return prompt.toString();
    }
}
