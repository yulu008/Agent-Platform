package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * npc_states 非法 key 的轻量 LLM 修复服务。
 * <p>
 * GM（小模型）为首次登场的新 NPC 编造英文/拼音 key（如 {@code gray_robed_servant}）时，
 * 把本轮叙述原文 + 违规 state_delta + 合法 key 清单喂给 workshop 角色的
 * deepseek-v4-flash（非流式、无 advisor 的纯净 client），仅重算 npc_states 的汉字 key。
 * 叙述正文已随 SSE 交付前端，本服务不触碰；修复失败返回 null，由调用方走确定性丢弃兜底。
 * <p>
 * 乱码加固（2026-09-17）：上游模型流式输出可能混入无法解码的字节，Java UTF-8 解码器会把它们
 * 替换成 U+FFFD（如「我叫\uFFFD的场\uFFFD的场」）。若直接把污染叙述喂给修复模型，模型会把
 * 叠加名「的场的场」当真实称谓写入 npc_states，并作为存档既有 key 持续扩散。故：
 * <ol>
 *   <li>构建 prompt 前剔除叙述中的 U+FFFD（见 {@link #sanitizeNarration(String)}）</li>
 *   <li>修复输出 key 后处理：剔除 U+FFFD、叠字名自动折半（见 {@link #normalizeRepairedKey(String)}）</li>
 * </ol>
 *
 * @see WorkshopLlmGenerator 同款「无 advisor 纯净 client + JSON 解析」装配模式
 */
@Component
public class NpcKeyRepairService {

    private static final Logger log = LoggerFactory.getLogger(NpcKeyRepairService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Unicode REPLACEMENT CHARACTER：上游坏字节经 UTF-8 解码后的残留 */
    static final char REPLACEMENT_CHAR = '\uFFFD';

    private static final String REPAIR_PROMPT = """
            以下是一次 RPG 游戏回合中 GM 输出的 state_delta，其中 npc_states 的部分 key 是英文/拼音标识符（违规），
            需要依据叙述原文中 NPC 的实际汉字称谓重算这些 key。

            本轮叙述原文（已剔除乱码替换符，人名可能出现无分隔的叠字重复）：
            %s

            违规的 state_delta（npc_states 中含违规 key）：
            %s

            违规 key 清单：%s

            合法 key 清单（存档既有 NPC 的 key，修复时对照，不要改动它们的条目）：%s

            修复要求：
            1. 依据叙述原文中对每个 NPC 的实际汉字称谓，把违规 key 改写为对应汉字名
               （如 gray_robed_servant 是叙述中的「灰袍仆人」，就改写为「灰袍仆人」）
            2. 新 key 必须全部使用汉字，禁止罗马音、拼音与拉丁字母，也不得包含乱码替换符
            3. 若某称谓呈叠字重复（如「的场的场」实为「的场」），取其一半作为 key
            4. 每个条目的状态内容（status/motives 等）保持原样，不得增删或改写
            5. 只输出修正后的完整 npc_states JSON（一个 JSON 对象），不要包含其他文本

            JSON 格式：
            {
              "key1": {"status": "..."},
              "key2": {"status": "..."}
            }
            """;

    private final ChatClientRegistry chatClientRegistry;

    public NpcKeyRepairService(ChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    /**
     * 修复 state_delta 中 npc_states 的非法 key。
     *
     * @param narration  本轮叙述正文（已剥离 state_delta）
     * @param stateDelta 含非法 key 的 state_delta 原文节点
     * @param invalidKeys 违规 key 清单
     * @param validKeys   合法 key 清单（卡 ID ∪ 存档既有 key），供模型对照
     * @return 修正后的完整 npc_states JSON 对象；调用失败/解析失败/输出非对象时返回 null
     */
    public JsonNode repair(String narration, JsonNode stateDelta,
                           Collection<String> invalidKeys, Set<String> validKeys) {
        try {
            String sanitizedNarration = sanitizeNarration(narration);
            String prompt = String.format(REPAIR_PROMPT,
                    sanitizedNarration == null || sanitizedNarration.isBlank() ? "（缺失）" : sanitizedNarration,
                    stateDelta == null ? "（缺失）" : MAPPER.writeValueAsString(stateDelta),
                    invalidKeys == null ? "[]" : String.join(", ", invalidKeys),
                    validKeys == null || validKeys.isEmpty()
                            ? "（无）" : validKeys.stream().sorted().collect(Collectors.joining(", ")));
            ChatClient client = chatClientRegistry.forRole("workshop");
            String response = client.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "npc_key_repair"))
                    .call()
                    .content();
            if (response == null || response.isBlank()) {
                log.warn("npc_states key 修复调用返回空响应");
                return null;
            }
            JsonNode repaired = MAPPER.readTree(extractJson(response));
            if (!repaired.isObject()) {
                log.warn("npc_states key 修复输出不是 JSON 对象: {}", response.length() > 200
                        ? response.substring(0, 200) + "..." : response);
                return null;
            }
            return normalizeRepairedKeys((ObjectNode) repaired);
        } catch (Exception e) {
            log.warn("npc_states key 修复调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 剔除叙述中的 U+FFFD 乱码替换符。
     * <p>
     * 上游模型流式输出混入坏字节时，UTF-8 解码器写入 U+FFFD（前端渲染为方块/菱形符号）。
     * 污染文本喂给修复模型会把叠加名当真实称谓，故构建 prompt 前先剔除。
     * 仅影响喂给修复模型的文本，不改动已交付前端的叙述原文。
     */
    static String sanitizeNarration(String narration) {
        if (narration == null) {
            return null;
        }
        return narration.replace(String.valueOf(REPLACEMENT_CHAR), "");
    }

    /**
     * 修复输出 key 的确定性后处理：剔除 U+FFFD；叠字名（偶数长度且前后半相同，
     * 如「的场的场」→「的场」）自动折半；清洗后为空则丢弃该条目。
     * <p>
     * 这是提示词约束（REPAIR_PROMPT 第 3 条）的兜底：即使模型仍输出叠加名，
     * 也会在此被折半，保证不把损坏名写入 npc_states。
     */
    static String normalizeRepairedKey(String key) {
        if (key == null) {
            return null;
        }
        String cleaned = key.replace(String.valueOf(REPLACEMENT_CHAR), "").trim();
        int len = cleaned.length();
        if (len >= 4 && len % 2 == 0) {
            String half = cleaned.substring(0, len / 2);
            if (half.equals(cleaned.substring(len / 2))) {
                return half;
            }
        }
        return cleaned;
    }

    /** 逐 key 后处理修复输出节点：key 变更/丢弃均记日志；归一后撞名保留首个 */
    private JsonNode normalizeRepairedKeys(ObjectNode repaired) {
        ObjectNode normalized = MAPPER.createObjectNode();
        Set<String> seen = new LinkedHashSet<>();
        repaired.fields().forEachRemaining(entry -> {
            String rawKey = entry.getKey();
            String key = normalizeRepairedKey(rawKey);
            if (key == null || key.isBlank()) {
                log.warn("npc_states key 修复输出条目被丢弃（key 清洗后为空）: 原始 key={}", rawKey);
                return;
            }
            if (!key.equals(rawKey)) {
                log.info("npc_states key 修复输出后处理: {} → {}", rawKey, key);
            }
            if (seen.add(key)) {
                normalized.set(key, entry.getValue());
            } else {
                log.warn("npc_states key 修复输出归一后撞名，保留首个: {}", key);
            }
        });
        return normalized;
    }

    /** 剥离 ```json 围栏（与 WorkshopLlmGenerator.extractJson 同款逻辑） */
    private String extractJson(String response) {
        String trimmed = response.trim();
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
}
