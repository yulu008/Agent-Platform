package com.luyu.agent.rpg.eval.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.springframework.stereotype.Component;

/**
 * judge 提示词组装器（content-eval tasks 5.2，design D5/D7）。
 * <p>
 * 输入四件套：rubric 判据全文（{@link RubricLoader}）+ 设定包 + 动态层
 * 叙述窗/状态轨迹 + L1 预警清单 → 单条 user prompt（对齐 WorkshopLlmGenerator
 * 调用惯例：判据与材料一体全文）。
 * <p>
 * 设计契约：
 * <ul>
 *   <li>不隐藏被评产物生成模型信息（design 不做单盲）：动态层
 *       targetFingerprint（prompt_hash / approx:model）原样注入；</li>
 *   <li>L1 预警只筛不判：注入时明示「你的裁决覆盖预警」；</li>
 *   <li>早/晚窗分开渲染（D9 滑窗对照，漂移检测须两窗可见），出场不足
 *       窗宽时两窗重叠行去重保序；</li>
 *   <li>静态层无叙述窗（设定包自身即被评产物），judgment 证据为档案字段
 *       引用（turn=-1，rubric 契约已约定）。</li>
 * </ul>
 */
@Component
public class JudgePromptAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RubricLoader rubricLoader;

    public JudgePromptAssembler(RubricLoader rubricLoader) {
        this.rubricLoader = rubricLoader;
    }

    /**
     * 组装单 case 的 judge 评估 prompt。
     *
     * @param c          评估单元（静态层/动态层）
     * @param l1Warnings L1 确定性检查器预警（可能为 null/空 = 无预警）
     */
    public String assemble(EvalCase c, List<L1Warning> l1Warnings) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 RPG 内容质量评估裁判，对「人物一致性」维度执行 LLM-as-judge 评估。\n\n");

        appendRubric(sb);
        appendSubject(sb, c);
        appendSettingPack(sb, c);
        if (EvalRun.LAYER_DYNAMIC.equals(c.layer())) {
            appendWindows(sb, c);
            appendTrajectory(sb, c);
            appendSnapshots(sb, c);
            appendTargetFingerprint(sb, c);
        }
        appendL1Warnings(sb, l1Warnings);
        appendOutputReminder(sb);
        return sb.toString();
    }

    private void appendRubric(StringBuilder sb) {
        sb.append("=== 评估判据（rubric）===\n");
        String rubric = rubricLoader.content();
        if (rubric != null) {
            sb.append(rubric.trim()).append("\n\n");
        } else {
            // 防御：rubric 资源缺失（RubricLoader 已 warn）——降级为最小判据，
            // 判分不中断（对齐 PromptHashCalculator 缺资源跳过附着哲学）
            sb.append("（判据资源缺失，按通用一致性常识裁决：档案事实 vs 叙述表现，")
            .append("三档 pass/borderline/fail，每条附证据 {turn, span}。）\n\n");
        }
    }

    private void appendSubject(StringBuilder sb, EvalCase c) {
        sb.append("=== 被评人物 ===\n");
        sb.append("人物：").append(c.subjectLabel()).append("（subject: ").append(c.subject())
                .append("；来源: ").append(c.sourceType()).append("；层级: ").append(c.layer())
                .append("）\n\n");
    }

    private void appendSettingPack(StringBuilder sb, EvalCase c) {
        sb.append("=== 人物设定包 ===\n");
        EvalCase.SettingPack pack = parsePack(c);
        if (pack != null) {
            appendField(sb, "结构化字段", pack.structuredJson());
            appendField(sb, "identity", pack.identity());
            appendField(sb, "personality", pack.personality());
            appendField(sb, "background", pack.background());
            appendField(sb, "lore", pack.lore());
            if (pack.aliases() != null && !pack.aliases().isEmpty()) {
                appendField(sb, "别称", String.join("、", pack.aliases()));
            }
        } else {
            sb.append(c.settingPackJson()).append("\n");
        }
        sb.append("\n");
    }

    private void appendWindows(StringBuilder sb, EvalCase c) {
        sb.append("=== 叙述窗 · 早窗（出场最早 ").append(sizeOf(c.earlyWindow())).append(" 行）===\n");
        appendRows(sb, c.earlyWindow());
        sb.append("=== 叙述窗 · 晚窗（最近 ").append(sizeOf(c.lateWindow())).append(" 行）===\n");
        appendRows(sb, c.lateWindow());
        sb.append("\n");
    }

    private void appendTrajectory(StringBuilder sb, EvalCase c) {
        if (c.stateTrajectory() == null || c.stateTrajectory().isEmpty()) {
            return;
        }
        sb.append("=== 逐轮状态轨迹（state_delta 增量，断崖检测输入）===\n");
        for (EvalCase.TurnState state : c.stateTrajectory()) {
            if (state.deltas() == null || state.deltas().isEmpty()) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Double> e : state.deltas().entrySet()) {
                parts.add(e.getKey() + " " + String.format("%+.1f", e.getValue()));
            }
            sb.append("第").append(state.turn()).append("轮: ")
                    .append(String.join("；", parts)).append("\n");
        }
        sb.append("\n");
    }

    private void appendSnapshots(StringBuilder sb, EvalCase c) {
        if (c.npcStatesJson() != null && !c.npcStatesJson().isBlank()) {
            sb.append("=== npc_states 快照 ===\n").append(c.npcStatesJson()).append("\n\n");
        }
        if (c.playerStatesJson() != null && !c.playerStatesJson().isBlank()) {
            sb.append("=== playerStates 快照 ===\n").append(c.playerStatesJson()).append("\n\n");
        }
    }

    private void appendTargetFingerprint(StringBuilder sb, EvalCase c) {
        // 不做单盲：被评产物的版本指纹原样可见（prompt_hash / approx:model）
        if (c.targetFingerprint() != null && !c.targetFingerprint().isBlank()) {
            sb.append("=== 被评产物版本指纹 ===\n").append(c.targetFingerprint()).append("\n\n");
        }
    }

    private void appendL1Warnings(StringBuilder sb, List<L1Warning> l1Warnings) {
        sb.append("=== L1 规则引擎预警（疑似矛盾，请核对原文后裁决——你的裁决覆盖预警）===\n");
        if (l1Warnings == null || l1Warnings.isEmpty()) {
            sb.append("无\n\n");
            return;
        }
        int index = 1;
        for (L1Warning w : l1Warnings) {
            sb.append(index++).append(". [").append(w.checker()).append("] ");
            if (w.turn() != L1Warning.TURN_NONE) {
                sb.append("第").append(w.turn()).append("轮: ");
            }
            sb.append(w.message()).append("\n");
            if (w.span() != null && !w.span().isBlank()) {
                sb.append("   原文: ").append(w.span()).append("\n");
            }
        }
        sb.append("\n");
    }

    private void appendOutputReminder(StringBuilder sb) {
        sb.append("=== 输出要求 ===\n");
        sb.append("仅输出一个 JSON 对象（无围栏、无解释文字），judgments 四条齐备")
        .append("（hard-facts / motivation / relations / speech-style 各一条）。")
        .append("verdict 仅 pass/borderline/fail。")
        .append("evidence 必须引用上面输入文本的原文连续子串（动态层 turn 为真实轮次；")
        .append("静态层 turn=-1 引档案字段原文）。\n");
    }

    // ==================== 私有辅助 ====================

    private void appendRows(StringBuilder sb, List<EvalCase.TurnText> rows) {
        if (rows == null || rows.isEmpty()) {
            sb.append("（空）\n");
            return;
        }
        Set<EvalCase.TurnText> seen = new LinkedHashSet<>(rows); // 两窗重叠行去重保序
        for (EvalCase.TurnText row : seen) {
            if (row.text() == null || row.text().isBlank()) {
                continue;
            }
            sb.append("第").append(row.turn()).append("轮 [").append(row.role()).append("]: ")
                    .append(row.text().trim()).append("\n");
        }
    }

    private static int sizeOf(List<EvalCase.TurnText> rows) {
        return rows == null ? 0 : rows.size();
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(name).append(": ").append(value.trim()).append("\n");
        }
    }

    private static EvalCase.SettingPack parsePack(EvalCase c) {
        try {
            return MAPPER.readValue(c.settingPackJson(), EvalCase.SettingPack.class);
        } catch (Exception e) {
            return null; // 非法 JSON：原文贴入（防御不中断）
        }
    }
}
