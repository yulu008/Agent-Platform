package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.TriggerRuntime;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 触发器引擎：硬条件扫描 + 冷却判断。
 * <p>
 * 遍历所有启用的触发器，对硬条件（程序化条件如 {@code player.location == "路上"}）
 * 进行引擎级评估。硬条件不满足的触发器直接跳过，不消耗 token。
 */
@Component
public class TriggerEngine {

    private static final Logger log = LoggerFactory.getLogger(TriggerEngine.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RpgTriggerRepository triggerRepo;
    private final RpgTriggerRuntimeRepository runtimeRepo;

    public TriggerEngine(RpgTriggerRepository triggerRepo,
                         RpgTriggerRuntimeRepository runtimeRepo) {
        this.triggerRepo = triggerRepo;
        this.runtimeRepo = runtimeRepo;
    }

    /**
     * 扫描所有硬条件已满足、冷却已过的触发器。
     *
     * @param gameState 当前游戏状态
     * @return 通过硬条件和冷却检查的触发器列表
     */
    public List<Trigger> scanHardConditions(GameState gameState) {
        List<Trigger> triggers = triggerRepo.findByWorldId(gameState.getWorldId());
        List<TriggerRuntime> runtimes = runtimeRepo.findByGameStateId(gameState.getId());

        // 构建触发器运行时映射
        Map<String, TriggerRuntime> runtimeMap = new java.util.HashMap<>();
        for (TriggerRuntime rt : runtimes) {
            runtimeMap.put(rt.getTriggerId(), rt);
        }

        List<Trigger> passed = new ArrayList<>();
        for (Trigger trigger : triggers) {
            TriggerRuntime rt = runtimeMap.get(trigger.getId());

            // 检查是否启用
            if (rt != null && rt.getIsActive() != null && !rt.getIsActive()) {
                continue;
            }

            // 检查冷却
            if (rt != null && rt.getLastTriggeredTurn() != null && trigger.getCooldown() != null) {
                int turnsSinceLast = gameState.getTurnCount() - rt.getLastTriggeredTurn();
                if (turnsSinceLast < trigger.getCooldown()) {
                    log.debug("触发器 [{}] 冷却中: 已过 {} 轮, 需 {} 轮", trigger.getId(), turnsSinceLast, trigger.getCooldown());
                    continue;
                }
            }

            // 评估硬条件
            if (evaluateHardConditions(trigger.getHardConditions(), gameState)) {
                passed.add(trigger);
                log.debug("触发器 [{}] 硬条件全部通过: action={}", trigger.getId(), trigger.getAction());
            } else {
                log.debug("触发器 [{}] 硬条件不满足，跳过", trigger.getId());
            }
        }
        return passed;
    }

    /**
     * 评估硬条件列表。
     * <p>
     * 硬条件格式为 JSON 数组：
     * <pre>[{"field": "player.location", "op": "==", "value": "路上"}, ...]</pre>
     *
     * @param hardConditionsJson 硬条件 JSON 字符串
     * @param gameState          当前游戏状态
     * @return 所有条件均满足则返回 true
     */
    private boolean evaluateHardConditions(String hardConditionsJson, GameState gameState) {
        if (hardConditionsJson == null || hardConditionsJson.isBlank()) {
            return true; // 无硬条件则默认通过
        }
        try {
            JsonNode conditions = mapper.readTree(hardConditionsJson);
            if (!conditions.isArray()) {
                return true;
            }
            for (JsonNode cond : conditions) {
                String field = cond.path("field").asText();
                String op = cond.path("op").asText("==");
                JsonNode valueNode = cond.path("value");
                if (!evaluateSingleCondition(field, op, valueNode, gameState)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("硬条件解析失败，默认通过: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 评估单个硬条件。
     * <p>
     * 支持的字段前缀：
     * <ul>
     *   <li>{@code player.*} → GameState 中的玩家相关字段</li>
     *   <li>{@code npc.*} → npc_states JSON 中的 NPC 状态</li>
     *   <li>{@code turn_count} / {@code current_location} → GameState 直接字段</li>
     * </ul>
     */
    private boolean evaluateSingleCondition(String field, String op, JsonNode valueNode, GameState gameState) {
        Object actualValue = resolveFieldValue(field, gameState);
        Object expectedValue = jsonNodeToObject(valueNode);
        return compareValues(actualValue, op, expectedValue);
    }

    /**
     * 从 GameState 解析字段值。
     */
    private Object resolveFieldValue(String field, GameState gameState) {
        if (field.startsWith("player.")) {
            String subField = field.substring("player.".length());
            switch (subField) {
                case "location":
                case "current_location":
                    return gameState.getCurrentLocation();
                case "char_id":
                    return gameState.getPlayerCharId();
                default:
                    return null;
            }
        } else if (field.startsWith("npc.")) {
            String subField = field.substring("npc.".length());
            return extractFromNpcStates(gameState.getNpcStates(), subField);
        } else {
            switch (field) {
                case "turn_count":
                    return gameState.getTurnCount();
                case "current_location":
                    return gameState.getCurrentLocation();
                default:
                    return null;
            }
        }
    }

    /**
     * 从 npc_states JSON 中提取 NPC 状态字段。
     */
    private Object extractFromNpcStates(String npcStatesJson, String subField) {
        if (npcStatesJson == null || npcStatesJson.isBlank()) {
            return null;
        }
        try {
            JsonNode npcStates = mapper.readTree(npcStatesJson);
            // 遍历所有 NPC，只要有一个满足即可
            // 但具体逻辑由调用方判断，这里只提取第一个 NPC 的对应字段
            // 实际使用时，硬条件通常指定具体 NPC ID
            return null; // 简化：NPC 条件由 GM 通过工具查询
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 比较两个值是否满足操作符。
     */
    @SuppressWarnings("unchecked")
    private boolean compareValues(Object actual, String op, Object expected) {
        if (actual == null) {
            return false;
        }
        switch (op) {
            case "==":
            case "equals":
                return actual.toString().equals(expected != null ? expected.toString() : null);
            case "!=":
            case "not_equals":
                return !actual.toString().equals(expected != null ? expected.toString() : null);
            case ">":
            case "gt":
                return toDouble(actual) > toDouble(expected);
            case ">=":
            case "gte":
                return toDouble(actual) >= toDouble(expected);
            case "<":
            case "lt":
                return toDouble(actual) < toDouble(expected);
            case "<=":
            case "lte":
                return toDouble(actual) <= toDouble(expected);
            case "contains":
                return actual.toString().contains(expected != null ? expected.toString() : "");
            default:
                log.warn("未知操作符: {}", op);
                return false;
        }
    }

    private double toDouble(Object obj) {
        if (obj == null) return 0;
        try {
            return Double.parseDouble(obj.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isDouble()) {
            return node.asDouble();
        }
        return node.asText();
    }
}
