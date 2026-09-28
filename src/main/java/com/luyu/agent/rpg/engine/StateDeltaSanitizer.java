package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * state_delta 提取器（方案3混合式）。
 * <p>
 * 三层 fallback：
 * <ol>
 *   <li>Step1: 正则提取 {@code <state_delta>...</state_delta>} 块 + JSON 解析</li>
 *   <li>Step2: fallback — 由调用方（StateDeltaExtractor 服务层）调用 compaction 模型二次提取</li>
 *   <li>Step3: 两层均失败 — 状态不变，记录警告日志</li>
 * </ol>
 * 本类负责 Step1 的正则提取和 JSON 解析容错。
 * Step2 由 {@code StateDeltaExtractor}（任务 4.6）调用 LLM 完成。
 */
@Component
public class StateDeltaSanitizer {

    private static final Logger log = LoggerFactory.getLogger(StateDeltaSanitizer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 匹配完整 <state_delta>...</state_delta> 块（容忍标签内空白变体，如 < state_delta >） */
    private static final Pattern STATE_DELTA_PATTERN =
            Pattern.compile("<\\s*state_delta\\s*>\\s*([\\s\\S]*?)\\s*<\\s*/\\s*state_delta\\s*>",
                    Pattern.CASE_INSENSITIVE);

    /** 仅匹配开标签（用于未闭合块的截断与兜底提取） */
    private static final Pattern OPEN_TAG_PATTERN =
            Pattern.compile("<\\s*state_delta\\s*>", Pattern.CASE_INSENSITIVE);

    /**
     * 从 GM 输出文本中提取纯叙述部分（移除 state_delta 块）。
     * <p>
     * 除完整闭合块外，还需处理 GM 漏写闭合标签（流被截断/格式漂移）的未闭合块：
     * 从开标签起整体截断，否则块内 JSON 会泄漏到前端叙述与历史回放。
     *
     * @param gmOutput GM 原始输出
     * @return 过滤后的纯叙述文本
     */
    public String extractNarration(String gmOutput) {
        if (gmOutput == null || gmOutput.isEmpty()) {
            return "";
        }
        String narration = STATE_DELTA_PATTERN.matcher(gmOutput).replaceAll("");
        Matcher open = OPEN_TAG_PATTERN.matcher(narration);
        if (open.find()) {
            narration = narration.substring(0, open.start());
        }
        return narration.trim();
    }

    /**
     * 尝试从 GM 输出中提取并解析 state_delta JSON。
     * <p>
     * Step1：正则提取 + JSON 解析。成功返回解析后的 JsonNode，
     * 失败返回 null（调用方应走 Step2 LLM fallback）。
     *
     * @param gmOutput GM 原始输出
     * @return 解析成功的 state_delta JSON，或 null（需走 Step2）
     */
    public JsonNode tryExtractStateDelta(String gmOutput) {
        if (gmOutput == null || gmOutput.isEmpty()) {
            return null;
        }
        String rawJson = extractBlockText(gmOutput);
        if (rawJson == null) {
            log.debug("state_delta 块未找到，需走 Step2 LLM fallback");
            return null;
        }
        try {
            JsonNode parsed = mapper.readTree(rawJson);
            log.debug("state_delta Step1 解析成功");
            return parsed;
        } catch (Exception e) {
            log.warn("state_delta Step1 JSON 解析失败，需走 Step2: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提取 state_delta 块内文本：优先完整闭合块，兼容未闭合块（从开标签截取到文本末尾）。
     *
     * @return 块内文本，或 null（无块）
     */
    private String extractBlockText(String gmOutput) {
        Matcher matcher = STATE_DELTA_PATTERN.matcher(gmOutput);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        Matcher open = OPEN_TAG_PATTERN.matcher(gmOutput);
        if (open.find()) {
            log.warn("state_delta 块未闭合（缺少 </state_delta>），按开标签截断提取");
            return gmOutput.substring(open.end()).trim();
        }
        return null;
    }

    /**
     * 提取 state_delta 原始文本块（未解析 JSON）。
     * <p>
     * 用于 Step2 LLM fallback 时将原始文本传给 compaction 模型。
     *
     * @param gmOutput GM 原始输出
     * @return state_delta 块内的原始文本，或 null
     */
    public String extractRawStateDeltaBlock(String gmOutput) {
        if (gmOutput == null || gmOutput.isEmpty()) {
            return null;
        }
        return extractBlockText(gmOutput);
    }

    /**
     * 检查 GM 输出中是否包含 state_delta 块（含未闭合块）。
     */
    public boolean hasStateDelta(String gmOutput) {
        return gmOutput != null && !gmOutput.isEmpty()
                && OPEN_TAG_PATTERN.matcher(gmOutput).find();
    }
}
