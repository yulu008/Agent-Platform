package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.rpg.model.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态动机引擎：三层动机变更机制。
 * <p>
 * 所有动机值（greed/fear/curiosity）clamp 到 [0.0, 1.0]。
 * <ol>
 *   <li>层1: 引擎规则（确定性规则表，如 NPC 被打败 → fear +0.5、greed -0.2）</li>
 *   <li>层2: 从 state_delta 提取动机变更（GM 推断输出）</li>
 *   <li>层3: 叙述关键词推断兜底（如"逃跑" → fear +0.3）</li>
 * </ol>
 */
@Component
public class MotivationEngine {

    private static final Logger log = LoggerFactory.getLogger(MotivationEngine.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 引擎规则表：事件类型 → 动机变更映射。
     * <p>
     * 规则定义是确定性的，不依赖 LLM。
     */
    private static final Map<String, MotivationChange> RULE_TABLE = new HashMap<>();

    static {
        RULE_TABLE.put("defeated", new MotivationChange("fear", +0.5, "greed", -0.2));
        RULE_TABLE.put("victory", new MotivationChange("confidence", +0.3, "greed", +0.1));
        RULE_TABLE.put("bribed", new MotivationChange("greed", +0.3, "fear", -0.1));
        RULE_TABLE.put("threatened", new MotivationChange("fear", +0.4, "anger", +0.3));
        RULE_TABLE.put("befriended", new MotivationChange("trust", +0.2, "curiosity", +0.1));
    }

    /**
     * 叙述关键词 → 动机变更映射（层3 兜底）。
     */
    private static final Map<String, MotivationChange> KEYWORD_TABLE = new HashMap<>();

    static {
        KEYWORD_TABLE.put("逃跑", new MotivationChange("fear", +0.3));
        KEYWORD_TABLE.put("投降", new MotivationChange("fear", +0.2, "greed", -0.1));
        KEYWORD_TABLE.put("金币", new MotivationChange("greed", +0.1));
        KEYWORD_TABLE.put("武器", new MotivationChange("fear", +0.1));
        KEYWORD_TABLE.put("威胁", new MotivationChange("fear", +0.2, "anger", +0.2));
    }

    /**
     * 应用三层动机变更到 npc_states JSON。
     *
     * @param npcStatesJson 当前 npc_states JSON 字符串
     * @param npcId         NPC ID
     * @param eventType     事件类型（用于层1规则匹配）
     * @param stateDelta    state_delta JSON（用于层2 GM 推断提取）
     * @param narration     GM 叙述文本（用于层3关键词推断兜底）
     * @return 更新后的 npc_states JSON 字符串
     */
    public String applyMotivationChanges(String npcStatesJson, String npcId,
                                          String eventType, JsonNode stateDelta,
                                          String narration) {
        try {
            JsonNode npcStates = npcStatesJson != null && !npcStatesJson.isBlank()
                    ? mapper.readTree(npcStatesJson)
                    : mapper.createObjectNode();
            ObjectNode npcStatesObj = npcStates.isObject()
                    ? (ObjectNode) npcStates
                    : mapper.createObjectNode();

            // 确保 NPC 存在
            if (!npcStatesObj.has(npcId)) {
                npcStatesObj.putObject(npcId);
            }
            ObjectNode npc = (ObjectNode) npcStatesObj.get(npcId);
            if (!npc.has("motives")) {
                npc.putObject("motives");
            }
            ObjectNode motives = (ObjectNode) npc.get("motives");

            // 层1: 引擎规则
            applyRule(motives, eventType);

            // 层2: state_delta GM 推断
            applyStateDelta(motives, npcId, stateDelta);

            // 层3: 叙述关键词兜底
            applyKeywordInference(motives, narration);

            // clamp 所有值
            clampMotives(motives);

            return mapper.writeValueAsString(npcStatesObj);
        } catch (Exception e) {
            log.warn("动机变更失败，返回原始状态: npcId={}, error={}", npcId, e.getMessage());
            return npcStatesJson;
        }
    }

    // ==================== 三层实现 ====================

    /**
     * 层1: 引擎规则（确定性规则表）。
     */
    private void applyRule(ObjectNode motives, String eventType) {
        if (eventType == null) {
            return;
        }
        MotivationChange change = RULE_TABLE.get(eventType);
        if (change != null) {
            applyChange(motives, change);
            log.debug("动机变更层1（引擎规则）: eventType={}", eventType);
        }
    }

    /**
     * 层2: 从 state_delta 提取动机变更（GM 推断输出）。
     */
    private void applyStateDelta(ObjectNode motives, String npcId, JsonNode stateDelta) {
        if (stateDelta == null || stateDelta.isMissingNode()) {
            return;
        }
        // GM 有时模仿 get_game_state 返回值写驼峰 npcStates，两种 key 均接受
        JsonNode npcDelta = stateDelta.path("npc_states");
        if (npcDelta.isMissingNode()) {
            npcDelta = stateDelta.path("npcStates");
        }
        npcDelta = npcDelta.path(npcId).path("motives");
        if (npcDelta.isMissingNode()) {
            return;
        }
        npcDelta.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            double delta = entry.getValue().asDouble(0);
            double current = motives.path(key).asDouble(0.5);
            motives.put(key, current + delta);
        });
        log.debug("动机变更层2（state_delta）: npcId={}", npcId);
    }

    /**
     * 层3: 叙述关键词推断兜底。
     */
    private void applyKeywordInference(ObjectNode motives, String narration) {
        if (narration == null || narration.isBlank()) {
            return;
        }
        for (Map.Entry<String, MotivationChange> entry : KEYWORD_TABLE.entrySet()) {
            if (narration.contains(entry.getKey())) {
                applyChange(motives, entry.getValue());
                log.debug("动机变更层3（关键词）: keyword={}", entry.getKey());
            }
        }
    }

    // ==================== 辅助方法 ====================

    private void applyChange(ObjectNode motives, MotivationChange change) {
        if (change.field1 != null) {
            double current = motives.path(change.field1).asDouble(0.5);
            motives.put(change.field1, current + change.delta1);
        }
        if (change.field2 != null) {
            double current = motives.path(change.field2).asDouble(0.5);
            motives.put(change.field2, current + change.delta2);
        }
    }

    /**
     * 将所有动机值 clamp 到 [0.0, 1.0]。
     */
    private void clampMotives(ObjectNode motives) {
        motives.fields().forEachRemaining(entry -> {
            double val = entry.getValue().asDouble(0.5);
            double clamped = Math.max(0.0, Math.min(1.0, val));
            entry.setValue(mapper.getNodeFactory().numberNode(clamped));
        });
    }

    /**
     * 动机变更描述（内部值对象）。
     */
    private record MotivationChange(String field1, double delta1,
                                     String field2, double delta2) {
        MotivationChange(String field1, double delta1) {
            this(field1, delta1, null, 0);
        }
    }
}
