package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * state_delta 提取器（方案3混合式）。
 * <p>
 * 三层 fallback：
 * <ol>
 *   <li>Step1: 正则提取 {@code <state_delta>} 块 + JSON 解析（使用 {@link StateDeltaSanitizer}）</li>
 *   <li>Step2: fallback — compaction 模型二次 LLM 提取</li>
 *   <li>Step3: 两层均失败 — 状态不变，记录警告日志</li>
 * </ol>
 */
@Component
public class StateDeltaExtractor {

    private static final Logger log = LoggerFactory.getLogger(StateDeltaExtractor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String LLM_EXTRACTION_PROMPT = """
            以下是一段 RPG 游戏的叙述文本。请从中提取状态变更信息，输出为 JSON 格式。
            如果没有明确的状态变更，输出空 JSON 对象 {}。
            JSON 格式要求：
            {
              "location_change": "新地点名称或null",
              "flags": {"新标记": true},
              "npc_states": {
                "NPC-ID": {"status": "状态", "motives": {"greed": 0.1, "fear": -0.2}}
              },
              "player": {
                "money": 数值变化量（支出为负，不是余额；无变更则省略）,
                "abilities": {"能力名": "等级或状态"},
                "titles": ["称号"]
              },
              "events": ["简要事件描述"]
            }

            叙述文本：
            %s

            请仅输出 JSON，不要包含其他文本。
            """;

    private final StateDeltaSanitizer sanitizer;
    private final ChatClientRegistry chatClientRegistry;

    public StateDeltaExtractor(StateDeltaSanitizer sanitizer,
                                ChatClientRegistry chatClientRegistry) {
        this.sanitizer = sanitizer;
        this.chatClientRegistry = chatClientRegistry;
    }

    /**
     * 从 GM 输出中提取 state_delta。
     * <p>
     * 三层 fallback：
     * 1. 正则提取 + JSON 解析
     * 2. LLM 二次提取
     * 3. 返回 null（状态不变）
     *
     * @param gmOutput GM 完整输出（包含叙述 + state_delta 块）
     * @return 解析成功的 state_delta JSON，或 null（状态不变）
     */
    public JsonNode extract(String gmOutput) {
        if (gmOutput == null || gmOutput.isBlank()) {
            return null;
        }

        // Step1: 正则提取 + JSON 解析
        JsonNode step1Result = sanitizer.tryExtractStateDelta(gmOutput);
        if (step1Result != null) {
            log.debug("state_delta 提取成功（Step1 正则）");
            return step1Result;
        }

        // Step2: LLM 二次提取
        JsonNode step2Result = tryLlmExtraction(gmOutput);
        if (step2Result != null) {
            log.debug("state_delta 提取成功（Step2 LLM）");
            return step2Result;
        }

        // Step3: 两者均失败
        log.warn("state_delta 提取失败（Step1 正则 + Step2 LLM 均失败），状态不变");
        return null;
    }

    /**
     * Step2: 调用 compaction 模型从叙述文本中提取状态变更。
     */
    private JsonNode tryLlmExtraction(String gmOutput) {
        // 使用纯叙述文本（已过滤 state_delta 块）
        String narration = sanitizer.extractNarration(gmOutput);
        if (narration == null || narration.isBlank()) {
            return null;
        }

        // 截断避免 token 过长
        String truncated = narration.length() > 2000
                ? narration.substring(0, 2000) + "..."
                : narration;

        String prompt = String.format(LLM_EXTRACTION_PROMPT, truncated);

        try {
            ChatClient compactionClient = chatClientRegistry.forRole("compaction");
            String response = compactionClient.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "state_delta"))
                    .call()
                    .content();
            if (response == null || response.isBlank()) {
                return null;
            }
            // 尝试解析 LLM 返回的 JSON
            String jsonStr = extractJsonFromResponse(response);
            if (jsonStr == null) {
                return null;
            }
            return mapper.readTree(jsonStr);
        } catch (Exception e) {
            log.warn("state_delta Step2 LLM 提取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 LLM 响应中提取 JSON（可能被包裹在 ```json ... ``` 中）。
     */
    private String extractJsonFromResponse(String response) {
        String trimmed = response.trim();
        // 移除 markdown 代码块标记
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        return null;
    }
}
