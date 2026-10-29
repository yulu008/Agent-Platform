package com.luyu.agent.rpg.eval.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.model.EvalRecord;
import com.luyu.agent.rpg.eval.model.JudgeJudgment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * judge 输出宽反序列化解析器（content-eval tasks 5.4，design D4）。
 * <p>
 * GLM 系列输出数组/字符串形态不稳定是项目已知坑，按宽反序列化器惯例
 * （JsonToStringDeserializer）防御：
 * <ul>
 *   <li>围栏剥离（```json 围栏 + 围栏外夹杂文字时按首 {/[ 末 }/] 兜底截取）；</li>
 *   <li>judgments 容器：顶层数组 / 顶层对象 judgments 字段 / 字符串再 parse
 *       互转；单对象当 1 条；</li>
 *   <li>evidence 容器：数组 / 单对象 / 字符串再 parse 互转；span 数组拼接；</li>
 *   <li>turn：数字 / 数字字符串互转，无法解析的 evidence 条目丢弃（留给
 *       证据核验按「无证据」作废，不在解析层臆造轮次）；</li>
 *   <li>verdict：trim + 小写归一后限三档——非法值整体按畸形处理（spec
 *       「其余值走防御降级路径，不落非法 verdict」→ 触发重试/降级）。</li>
 * </ul>
 * 解析成功返回的 judgments（invalidReason 全为 null——证据核验是下一层
 * {@code EvidenceVerifier} 职责）；畸形抛 {@link JudgeParseException}。
 */
@Component
public class JudgeResponseParser {

    private static final Logger log = LoggerFactory.getLogger(JudgeResponseParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 子维度归一键：去 -/_/空白小写 → 规范四值 */
    private static final Map<String, String> SUB_BY_NORMALIZED = Map.of(
            "hardfacts", EvalRecord.SUB_HARD_FACTS,
            "motivation", EvalRecord.SUB_MOTIVATION,
            "relations", EvalRecord.SUB_RELATIONS,
            "speechstyle", EvalRecord.SUB_SPEECH_STYLE);

    /** verdict 归一后三档（其余值 = 畸形） */
    private static final List<String> VERDICTS = List.of(
            EvalRecord.VERDICT_PASS, EvalRecord.VERDICT_BORDERLINE, EvalRecord.VERDICT_FAIL);

    /**
     * 解析 judge 原始输出。
     *
     * @throws JudgeParseException 非 JSON / 无可识别判分 / 任一条 verdict 非三档
     */
    public List<JudgeJudgment> parse(String raw) {
        JsonNode root = readTreeDefensively(raw);
        List<JsonNode> entries = judgmentEntries(root);
        List<JudgeJudgment> parsed = new ArrayList<>();
        for (JsonNode entry : entries) {
            JudgeJudgment judgment = parseJudgment(entry);
            if (judgment != null) {
                parsed.add(judgment);
            }
        }
        if (parsed.isEmpty()) {
            throw new JudgeParseException("judgments 无任何可识别条目: " + clip(raw));
        }
        return parsed;
    }

    // ==================== 容器宽容提取 ====================

    /** judgments 容器：顶层对象取 judgments 字段；顶层数组直接当清单；字符串再 parse */
    private List<JsonNode> judgmentEntries(JsonNode root) {
        JsonNode container = root;
        if (root.isObject()) {
            container = fieldOfAnyName(root, "judgments", "judgement", "results", "scores");
            if (container == null) {
                // 顶层对象但无 judgments 字段：整个对象当单条（模型偷懒形态）
                return List.of(root);
            }
        }
        if (container == null) {
            throw new JudgeParseException("judgments 容器缺失");
        }
        return arrayOrSingle(container, "judgments");
    }

    /** 字段名宽容查找（大小写/下划线差异） */
    private static JsonNode fieldOfAnyName(JsonNode object, String... names) {
        for (String name : names) {
            JsonNode v = object.get(name);
            if (v == null) {
                v = object.get(Character.toUpperCase(name.charAt(0)) + name.substring(1));
            }
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    /** 数组/单对象/字符串互转 → 节点清单（字符串按 JSON 再 parse，失败视为畸形） */
    private List<JsonNode> arrayOrSingle(JsonNode container, String what) {
        if (container.isArray()) {
            List<JsonNode> nodes = new ArrayList<>();
            container.forEach(nodes::add);
            return nodes;
        }
        if (container.isObject()) {
            return List.of(container); // 单对象当 1 条
        }
        if (container.isTextual()) {
            try {
                JsonNode inner = MAPPER.readTree(container.asText());
                return arrayOrSingle(inner, what);
            } catch (Exception e) {
                throw new JudgeParseException(what + " 字段字符串形态无法再解析: " + clip(container.asText()));
            }
        }
        throw new JudgeParseException(what + " 容器形态非法: " + container.getNodeType());
    }

    // ==================== 单条判分解析 ====================

    private JudgeJudgment parseJudgment(JsonNode entry) {
        if (!entry.isObject()) {
            log.warn("judgment 条目非对象（跳过）: {}", entry.getNodeType());
            return null;
        }
        String sub = subDimension(entry);
        if (sub == null) {
            log.warn("judgment 条目 sub_dimension 无法识别（跳过）: {}", clip(entry.toString()));
            return null;
        }
        String verdict = normalizeVerdict(fieldOfAnyName(entry, "verdict", "result"));
        if (verdict == null) {
            // 三档约束：非法 verdict = 整体畸形（spec：走防御降级路径，不落非法值）
            throw new JudgeParseException("verdict 非三档: " + clip(entry.toString()));
        }
        String rationale = asText(fieldOfAnyName(entry, "rationale", "reason"));
        List<JudgeJudgment.Evidence> evidence = parseEvidence(fieldOfAnyName(entry, "evidence", "spans"));
        return new JudgeJudgment(sub, verdict, rationale, evidence, null);
    }

    /** sub_dimension 归一匹配四值（去 -/_/空白小写）；未知返回 null（条目跳过） */
    private static String subDimension(JsonNode entry) {
        String raw = asText(entry.get("sub_dimension"));
        if (raw == null) {
            raw = asText(entry.get("subDimension"));
        }
        if (raw == null) {
            raw = asText(entry.get("dimension"));
        }
        if (raw == null) {
            return null;
        }
        return SUB_BY_NORMALIZED.get(raw.replaceAll("[-_\\s]", "").toLowerCase());
    }

    /** verdict trim+小写归一；null/非三档返回 null */
    private static String normalizeVerdict(JsonNode node) {
        String raw = asText(node);
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return VERDICTS.contains(normalized) ? normalized : null;
    }

    private List<JudgeJudgment.Evidence> parseEvidence(JsonNode container) {
        if (container == null || container.isNull()) {
            return List.of(); // 无证据：留给核验层作废（无证据判分作废）
        }
        if (container.isTextual()) {
            // 字符串形态：JSON 数组文本则展开；纯文本无轮次可定位 → 无法构造
            // 证据（丢弃，核验层按无证据作废——解析层不臆造轮次）
            try {
                JsonNode inner = MAPPER.readTree(container.asText());
                return parseEvidence(inner);
            } catch (Exception e) {
                return List.of();
            }
        }
        List<JsonNode> entries = arrayOrSingle(container, "evidence");
        List<JudgeJudgment.Evidence> evidence = new ArrayList<>();
        for (JsonNode e : entries) {
            JudgeJudgment.Evidence item = parseEvidenceItem(e);
            if (item != null) {
                evidence.add(item);
            }
        }
        return evidence;
    }

    private JudgeJudgment.Evidence parseEvidenceItem(JsonNode node) {
        Integer turn = parseTurn(fieldOfAnyName(node, "turn", "round", "轮次"));
        String span = asText(fieldOfAnyName(node, "span", "quote", "text", "citation"));
        if (turn == null || span == null || span.isBlank()) {
            // turn 无法解析 / span 缺失：丢弃该条（核验层按无/不足证据作废，
            // 解析层不臆造定位）
            log.warn("evidence 条目 turn/span 缺失（跳过）: {}", clip(node.toString()));
            return null;
        }
        return new JudgeJudgment.Evidence(turn, span.trim());
    }

    /** turn：数字 / 数字字符串互转；无法解析返回 null */
    private static Integer parseTurn(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 文本宽容：字符串直取；数组拼接；null 安全 */
    private static String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            node.forEach(v -> {
                if (v.isTextual()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(v.asText());
                }
            });
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }

    // ==================== 剥围栏与兜底截取 ====================

    private JsonNode readTreeDefensively(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new JudgeParseException("judge 输出为空");
        }
        String candidate = stripFence(raw);
        try {
            return MAPPER.readTree(candidate);
        } catch (Exception first) {
            // 围栏外夹杂解释文字（"以下是评估结果：{...}"）：首 {/[ 到末 }/] 兜底
            String clipped = clipToJson(candidate);
            if (clipped == null || clipped.equals(candidate)) {
                throw new JudgeParseException("judge 输出非 JSON: " + clip(raw), first);
            }
            try {
                return MAPPER.readTree(clipped);
            } catch (Exception second) {
                throw new JudgeParseException("judge 输出非 JSON（兜底截取后仍畸形）: " + clip(raw), second);
            }
        }
    }

    /** 首尾围栏剥离（对齐 WorkshopLlmGenerator.extractJson 惯例） */
    private static String stripFence(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    /** 围栏外文字兜底：首个 { 或 [ 到末个 } 或 ] */
    private static String clipToJson(String text) {
        int start = indexOfAny(text, '{', '[');
        int end = lastIndexOfAny(text, '}', ']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static int indexOfAny(String text, char a, char b) {
        int ia = text.indexOf(a);
        int ib = text.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }

    private static int lastIndexOfAny(String text, char a, char b) {
        int ia = text.lastIndexOf(a);
        int ib = text.lastIndexOf(b);
        return Math.max(ia, ib);
    }

    private static String clip(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
