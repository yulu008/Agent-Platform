package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.springframework.stereotype.Component;

import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.StyleMode;

/**
 * S4.5 单幕生成 prompt 组装器（story-graph-authoring tasks 2.2 /
 * story-graph-generation spec「单幕生成小契约」「生成注入纪律」）。
 * <p>
 * 组成（对齐 spec 注入清单，全图 JSON 禁入）：
 * <ol>
 *   <li>风格段（run 级风格贯穿，同其他生成器）</li>
 *   <li>幕骨架 JSON 契约指令 + 硬约束（只画幕内边/ID 幕命名空间/≤25 节点/
 *       锚点与轨道表/词汇表引用——违反由 1.5 校验确定性丢弃或拒绝）</li>
 *   <li>上游已确认摘要行（S1-S4，含世界观摘要——D7 摘要行注入）</li>
 *   <li>S4 剧情结构确认稿全文（直接内容上游：主线大纲 + 分幕结构）</li>
 *   <li>前幕锚点链与出口轨道表摘要（{@link StoryGraphDigest} 产物，非前幕 JSON）</li>
 *   <li>状态词汇表（第 1 幕产出指令 / 后续幕沿用注入）</li>
 *   <li>幕级返工段（反馈历史 + 上轮幕稿 + 保持不变约束，2.6 接线存储）</li>
 * </ol>
 * 纯组装（无状态、无 IO），措辞钉死可单测——2.7 断言「prompt 不含前幕全量
 * 节点 JSON」的测试对象即本类产物。
 */
@Component
public class StoryGraphPromptBuilder {

    /** 单幕幕契约指令（JSON 字段对齐 1.2 解析器支持的蛇形 key 与 guard 形态） */
    static final String ACT_CONTRACT = """
            你的任务：为这个世界的第 %d 幕（共 %d 幕）生成分支叙事「骨架」——纯结构 JSON，不含任何正文散文。
            把完整 JSON 放在 <story_graph> 与 </story_graph> 标签之间输出，标签外的文字会被忽略。

            JSON 顶层字段（蛇形命名，缺失必需字段会被拒绝）：
            {
              "act": %d,
              "theme": "本幕主题（对齐下方分幕结构中本幕行的主题）",
              "ending_beat": "幕末收束拍子（对齐本幕行的幕末拍子）",
              "entry_guard": {"key": "状态键", "op": ">=|<=|>|<|==|in", "value": 数值或字符串或布尔},
              "converge_to": [
                {"orbit": "轨道名", "guard": {"key": "状态键", "op": "…", "value": …}}
              ],
              "allow_explicit_variants": false,
              "nodes": [
                {"id": "n%d_01", "type": "scene", "beat": "一句话剧情拍子"},
                {"id": "n%d_02", "type": "anchor", "beat": "幕末必经收束拍子"}
              ],
              "edges": [
                {"from": "n%d_01", "to": "n%d_02", "label": "选项标签", "quote": "玩家点击选项时说的话", "guard": {"key": "…", "op": "…", "value": …}, "effects": {"状态键": 数值增量}}
              ]
            }
            （nodes 与 edges 数组展开为实际内容；guard/effects 字段可按需省略；边 effects 的数值是增量语义，flags/player 键是覆盖语义）

            硬约束（违反会被系统确定性丢弃或拒绝）：
            1. 本幕节点数 ≤ 25；节点 ID 格式为 n<幕号>_<两位序号>（本幕即 n%d_01 到 n%d_25），序号从 01 连续递增，幕号与本幕一致
            2. 只画幕内边：edges 的 from 与 to 都必须是本幕 nodes 内的节点 ID。跨幕衔接（本幕出口锚 → 下一幕入口）由系统自动拼接，禁止引用其他幕的节点 ID（如 n%d_xx）
            3. 至少一个 "type": "anchor" 的出口锚点节点：本幕多条支路在锚点汇聚收束，锚点不写出口边（系统接向下一幕）。%s
            4. converge_to 非空：出口轨道表是本幕结束时世界态应满足的收敛谓词（对齐下方分幕结构中本幕行的出口轨道）；entry_guard 引用上一幕出口轨道（对齐下方前幕摘要的轨道）
            5. guard 与 effects 引用的状态键必须来自下方状态词汇表%s
            6. 只写 beat 梗概（每节点一句话拍子），不写正文散文——节点正文在玩家到达时另行生成
            """;

    /** 第 1 幕额外指令：产出状态词汇表（后续幕沿用） */
    static final String FIRST_ACT_SCHEMA = """

            【状态词汇表（本幕产出）】
            本幕是第 1 幕，额外在 JSON 顶层产出状态词汇表 state_schema——声明这个故事关心的状态谓词键：
            "state_schema": {
              "trust_信长": {"type": "number", "min": 0, "max": 100},
              "已获刀": {"type": "flag"},
              "阵营": {"type": "string", "values": ["织田", "武田"]}
            }
            词汇表小而稳（≤ 10 键）：guard / effects / converge_to / entry_guard 全部引用这些键；
            键名用简洁稳定的领域词（如 trust_信长、已获刀），后续幕沿用此表不可新增。
            """;

    /** 后续幕词汇表注入（沿用约束） */
    static final String SCHEMA_REUSE = """

            【状态词汇表（沿用既有，不得新增键）】
            %s
            本幕所有 guard / effects / converge_to / entry_guard 引用的状态键必须来自上表。
            """;

    /**
     * 幕生成 prompt 素材（纯值对象，由 2.3 编排层从 run 工作区组装）。
     *
     * @param styleMode 风格模式（run 级）
     * @param actNumber 幕号（1 起）
     * @param totalActs 幕总数（S4 分幕结构行数）
     * @param actOutline 本幕大纲行（S4 分幕结构切出）
     * @param plotDraftFull S4 确认稿全文（主线大纲 + 分幕结构）
     * @param manifest run manifest（S1-S4 摘要行来源）
     * @param previousDigest 前幕锚点链/轨道表摘要文本（第 1 幕为 null）
     * @param stateSchemaJson 既有词汇表 JSON（第 1 幕为 null → 产出指令）
     * @param actRounds 本幕返工反馈历史（空 = 首轮；2.6 接线存储）
     * @param lastActDraft 上轮本幕骨架稿（返工轮注入）
     */
    public record ActPromptSpec(StyleMode styleMode, int actNumber, int totalActs,
                                StoryActOutlineParser.ActOutline actOutline, String plotDraftFull,
                                PipelineManifest manifest, String previousDigest,
                                String stateSchemaJson, List<PipelineManifest.ReRound> actRounds,
                                String lastActDraft) {
    }

    public String buildActPrompt(ActPromptSpec spec) {
        int n = spec.actNumber();
        boolean lastAct = n == spec.totalActs();
        // ending 提示以双引号开头（"type"…）：text block 起始四连引号在 ECJ 与
        // javac 间存在解析分歧，改普通字符串转义更稳（构建两套编译器都要过）
        String endingHint = lastAct
                ? "\"type\": \"ending\" 仅最后一幕允许——本幕是最后一幕，终局节点用 ending（收束本图，无后续幕）"
                : "\"type\": \"ending\" 仅最后一幕允许（本幕不是最后一幕，不得出现 ending 节点）";

        StringBuilder prompt = new StringBuilder();
        prompt.append(StagePromptAssembler.styleSection(spec.styleMode()));
        prompt.append('\n');
        // ACT_CONTRACT 含 10 个 %d（幕号×9 + 总幕数×1）+ 2 个 %s（ending/词汇表提示），
        // 参数序与模板出现序一致
        prompt.append(ACT_CONTRACT.formatted(n, spec.totalActs(), n, n, n, n, n, n, n, n, endingHint,
                spec.stateSchemaJson() != null ? "（见下方）" : "（第 1 幕见下方词汇表产出要求）"));
        prompt.append('\n');
        if (spec.stateSchemaJson() == null) {
            prompt.append(FIRST_ACT_SCHEMA);
        } else {
            prompt.append(SCHEMA_REUSE.formatted(spec.stateSchemaJson()));
        }
        prompt.append('\n');
        // 上游摘要行（S1-S4：世界观摘要在此——D7 摘要行注入而非全文）
        prompt.append(StagePromptAssembler.upstreamSummariesSection(
                spec.manifest(), PipelineStage.S4P5_STORY_GRAPH));
        // S4 确认稿全文（直接内容上游：主线大纲 + 分幕结构；截断保护同惯例）
        String plotSection = StagePromptAssembler.upstreamConfirmedSection(spec.plotDraftFull());
        if (plotSection != null) {
            prompt.append(plotSection.replace("【直接上游确认稿全文】", "【S4 剧情结构确认稿（主线大纲 + 分幕结构）】"));
        }
        // 本幕定位强调（分幕结构中本幕行）
        StoryActOutlineParser.ActOutline outline = spec.actOutline();
        if (outline != null) {
            prompt.append("【本幕任务定位】\n");
            prompt.append("第 ").append(n).append(" 幕｜主题：").append(outline.theme())
                    .append("｜幕末拍子：").append(outline.endingBeat())
                    .append("｜出口轨道草案：").append(outline.orbitDraft())
                    .append("\n（本幕 theme / ending_beat / converge_to 须与上行对齐）\n\n");
        }
        // 前幕摘要（锚点链 + 出口轨道表；非前幕 JSON）
        if (spec.previousDigest() != null && !spec.previousDigest().isBlank()) {
            prompt.append("【前幕锚点链与出口轨道表（摘要，非全图）】\n")
                    .append(spec.previousDigest()).append('\n');
        }
        // 幕级返工段
        prompt.append(actReworkSection(spec));
        return prompt.toString();
    }

    /** 幕级返工段（复用阶段级四件套语义，粒度是单幕——单幕返工不波及他幕）。 */
    private String actReworkSection(ActPromptSpec spec) {
        List<PipelineManifest.ReRound> rounds = spec.actRounds();
        if (rounds == null || rounds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【返工要求】\n");
        sb.append("这是本幕（第 ").append(spec.actNumber()).append(" 幕）的第 ")
                .append(rounds.size()).append(" 轮返工。此前的反馈历史（从早到晚）：\n");
        rounds.forEach(round ->
                sb.append("- 第 ").append(round.getRound()).append(" 轮反馈：").append(round.getFeedback()).append('\n'));
        sb.append("""
                请依据全部反馈重新生成本幕完整骨架 JSON。硬约束：
                1. 反馈未提及的节点/边保持与上轮一致，不要自行改动
                2. 逐条回应反馈中点名的问题
                3. 输出仍是完整本幕骨架 JSON（非补丁片段），仍只画幕内边
                """);
        if (spec.lastActDraft() != null && !spec.lastActDraft().isBlank()) {
            sb.append("\n【上轮本幕骨架稿】\n").append(spec.lastActDraft()).append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 解析失败修复 prompt（tasks 2.4） ====================

    private static final String ACT_REPAIR_PROMPT = """
            以下是「第 %d 幕」分支叙事骨架的首次生成输出，但确定性解析校验未通过（错误清单见下）。
            请修复这些错误，输出修正后的完整单幕骨架。

            【原始输出（需修复）】
            %s

            【确定性解析校验错误清单】
            %s

            修复硬约束：
            1. 只修正错误清单指出的问题，其余节点/边/字段保持原样
            2. 若节点数超限（上限 25），压缩或拆分节点：保留主线拍子与出口锚点，合并可有可无的过渡节点
            3. 修复后的输出仍须遵守单幕小契约：story_graph 标签包裹的纯结构 JSON、
               节点 ID 幂号 n<本幕号>_、只画幕内边
            4. 本幕是第 %d 幕：JSON 顶层必须携带 "act": %d；节点 ID 前缀必须是 n%d_。
               若错误清单报「幕命名空间违规」而节点 ID 前缀已与幕号一致，说明顶层 act/id 字段缺失
               （补字段即可），前缀正确的节点不要改编号
            5. 每个节点必须有非空 "beat" 字段（一句话剧情拍子梗概，非正文散文），缺失的按节点定位补写
            6. 节点 "type" 只允许 "scene"（普通场景）/ "anchor"（幕末必经收束锚点）/ "ending"
               （全图终局，仅最后一幕）：entry/plot/choice/branch/merge/exit 等自创类型改写为
               语义最近的合法值——入口/剧情/选项/分支节点 → scene，多支路汇聚的收束节点 → anchor，
               终局节点 → ending
            7. 只输出修正后的完整单幕骨架（含 story_graph 标签），
               不要解释、不要 diff、不要省略未出错的节点
            """;

    /**
     * 解析失败修复 prompt（story-graph-authoring tasks 2.4 /
     * story-graph-generation spec「单幕生成小契约」：解析失败走 LLM fallback
     * 修复一次，再失败则本幕 FAILED 可重试）。调用方为幕级编排的非流式修复
     * 调用（对齐 NpcKeyRepairService 模式）。
     *
     * @param actNumber       幕号（错误指明归属）
     * @param originalOutput  首次生成的 LLM 原文（含标签与前后噪声，原样回喂）
     * @param errors          确定性解析校验错误清单（逐条呈现给模型）
     */
    public String buildActRepairPrompt(int actNumber, String originalOutput, List<String> errors) {
        String errorLines = errors == null || errors.isEmpty()
                ? "（无具体错误，输出格式不符合契约）"
                : String.join("\n", errors.stream().map(e -> "- " + e).toList());
        return ACT_REPAIR_PROMPT.formatted(actNumber, originalOutput, errorLines,
                actNumber, actNumber, actNumber);
    }
}
