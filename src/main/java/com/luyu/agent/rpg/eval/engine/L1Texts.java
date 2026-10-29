package com.luyu.agent.rpg.eval.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.model.EvalCase;

/**
 * L1 检查器共享文本工具（content-eval tasks 4.x）。
 * <p>
 * 统一「被检文本源」语义：动态层 = 叙述窗（早/晚窗合并去重——出场不足窗宽时
 * 两窗重叠，避免同一行产出重复预警）；静态层 = 设定包文本段（档案自洽核对）。
 * 包私有，仅供 eval.engine 检查器与其单测复用。
 */
final class L1Texts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private L1Texts() {
    }

    /**
     * GM 叙述行（动态层窗内 assistant 行，早/晚合并去重保序）。
     * user 行不是被评产物（玩家输入），不进入 GM 叙述核对。
     */
    static List<EvalCase.TurnText> narrationRows(EvalCase c) {
        Set<EvalCase.TurnText> merged = new LinkedHashSet<>();
        if (c.earlyWindow() != null) {
            merged.addAll(c.earlyWindow());
        }
        if (c.lateWindow() != null) {
            merged.addAll(c.lateWindow());
        }
        List<EvalCase.TurnText> rows = new ArrayList<>();
        for (EvalCase.TurnText row : merged) {
            if (EvalCase.TurnText.ROLE_ASSISTANT.equals(row.role())
                    && row.text() != null && !row.text().isBlank()) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 该轮的全部行（断崖检测的「该轮叙述」= user 行动 + GM 叙述；无则空） */
    static List<EvalCase.TurnText> rowsOfTurn(EvalCase c, int turn) {
        Set<EvalCase.TurnText> merged = new LinkedHashSet<>();
        if (c.earlyWindow() != null) {
            merged.addAll(c.earlyWindow());
        }
        if (c.lateWindow() != null) {
            merged.addAll(c.lateWindow());
        }
        return merged.stream().filter(r -> r.turn() == turn).toList();
    }

    /** 静态层设定包文本段（identity/personality/background/lore 非空即入） */
    static List<String> settingTexts(EvalCase c) {
        List<String> texts = new ArrayList<>();
        try {
            JsonNode pack = MAPPER.readTree(c.settingPackJson());
            addIfPresent(texts, pack, "identity");
            addIfPresent(texts, pack, "personality");
            addIfPresent(texts, pack, "background");
            addIfPresent(texts, pack, "lore");
        } catch (Exception ignored) {
            // settingPackJson 非法 JSON：文本源为空，硬事实核对自然跳过（防御）
        }
        return texts;
    }

    private static void addIfPresent(List<String> texts, JsonNode pack, String field) {
        JsonNode v = pack.get(field);
        if (v != null && v.isTextual() && !v.asText().isBlank()) {
            texts.add(v.asText());
        }
    }

    /** 分句（。！？!?；;——按句读切，供「同句含指称」约束） */
    static List<String> sentences(String text) {
        List<String> parts = new ArrayList<>();
        for (String s : text.split("[。！？!?；;\n]")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }
}
