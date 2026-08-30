package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.EventLog;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 游戏主循环编排服务。
 * <p>
 * 完整游戏循环（6 步）：
 * <ol>
 *   <li>玩家输入 → 记录事件日志</li>
 *   <li>硬条件扫描 → {@link TriggerEngine}</li>
 *   <li>软条件评估 → {@link SoftConditionEvaluator}</li>
 *   <li>概率检定 → {@link ProbabilityRoller}</li>
 *   <li>GM 上下文组装 → {@link GmContextAssembler}，GM SSE 生成（由 Controller 执行）</li>
 *   <li>state_delta 后处理 → {@link StateDeltaExtractor} + {@link MotivationEngine}</li>
 * </ol>
 * <p>
 * 本服务负责步骤 1-4（prepareTurn）和步骤 6（finalizeTurn），
 * 步骤 5（GM SSE 生成）由 Controller 调用 ChatClient 执行。
 */
@Component
public class GameLoopService {

    private static final Logger log = LoggerFactory.getLogger(GameLoopService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RpgGameStateRepository stateRepo;
    private final RpgEventLogRepository eventRepo;
    private final RpgTriggerRuntimeRepository runtimeRepo;
    private final TriggerEngine triggerEngine;
    private final SoftConditionEvaluator softConditionEvaluator;
    private final ProbabilityRoller probabilityRoller;
    private final GmContextAssembler contextAssembler;
    private final StateDeltaExtractor stateDeltaExtractor;
    private final StateDeltaSanitizer stateDeltaSanitizer;
    private final MotivationEngine motivationEngine;

    public GameLoopService(RpgGameStateRepository stateRepo,
                           RpgEventLogRepository eventRepo,
                           RpgTriggerRuntimeRepository runtimeRepo,
                           TriggerEngine triggerEngine,
                           SoftConditionEvaluator softConditionEvaluator,
                           ProbabilityRoller probabilityRoller,
                           GmContextAssembler contextAssembler,
                           StateDeltaExtractor stateDeltaExtractor,
                           StateDeltaSanitizer stateDeltaSanitizer,
                           MotivationEngine motivationEngine) {
        this.stateRepo = stateRepo;
        this.eventRepo = eventRepo;
        this.runtimeRepo = runtimeRepo;
        this.triggerEngine = triggerEngine;
        this.softConditionEvaluator = softConditionEvaluator;
        this.probabilityRoller = probabilityRoller;
        this.contextAssembler = contextAssembler;
        this.stateDeltaExtractor = stateDeltaExtractor;
        this.stateDeltaSanitizer = stateDeltaSanitizer;
        this.motivationEngine = motivationEngine;
    }

    /**
     * 准备一轮游戏（步骤 1-4，GM 生成前）。
     *
     * @param gameStateId  游戏状态 ID
     * @param playerAction 玩家行动文本
     * @return 轮次上下文（包含 GM 所需的 prompt 和工具上下文）
     */
    public TurnContext prepareTurn(String gameStateId, String playerAction) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            throw new IllegalArgumentException("游戏状态未找到: " + gameStateId);
        }

        int currentTurn = gs.getTurnCount() != null ? gs.getTurnCount() : 0;
        int newTurn = currentTurn + 1;

        // 步骤1: 记录玩家行动事件日志
        EventLog playerEvent = new EventLog();
        playerEvent.setGameStateId(gameStateId);
        playerEvent.setTurn(newTurn);
        playerEvent.setEventType("player_action");
        playerEvent.setContent(playerAction);
        eventRepo.insert(playerEvent);

        // 步骤2: 硬条件扫描
        List<Trigger> hardPassed = triggerEngine.scanHardConditions(gs);

        // 步骤3+4: 软条件评估 + 概率检定
        List<Trigger> triggered = new ArrayList<>();
        for (Trigger trigger : hardPassed) {
            // 步骤3: 软条件评估
            boolean softPassed = softConditionEvaluator.evaluate(trigger, gameStateId);
            if (!softPassed) {
                log.debug("触发器 [{}] 软条件不满足", trigger.getId());
                continue;
            }

            // 步骤4: 概率检定
            double baseProb = trigger.getProbability() != null ? trigger.getProbability() : 1.0;
            boolean rolled = probabilityRoller.roll(baseProb, gs, trigger.getNpcId());
            if (rolled) {
                triggered.add(trigger);
                log.info("触发器 [{}] 命中: npc={}, action={}",
                        trigger.getId(), trigger.getNpcId(), trigger.getAction());

                // 记录触发器命中事件
                EventLog triggerEvent = new EventLog();
                triggerEvent.setGameStateId(gameStateId);
                triggerEvent.setTurn(newTurn);
                triggerEvent.setEventType("trigger_fired");
                triggerEvent.setContent("触发器[" + trigger.getId() + "]: NPC=" +
                        trigger.getNpcId() + ", action=" + trigger.getAction());
                eventRepo.insert(triggerEvent);
            } else {
                log.debug("触发器 [{}] 概率检定未通过", trigger.getId());
            }
        }

        // 步骤5a: GM 上下文组装
        boolean isFirstRound = (currentTurn == 0);
        String systemPrompt = null;
        String userPrompt;

        if (isFirstRound) {
            systemPrompt = contextAssembler.assembleFirstRound(gameStateId);
            userPrompt = playerAction;
        } else {
            userPrompt = contextAssembler.assembleIncremental(playerAction, triggered);
        }

        // 工具上下文
        Map<String, Object> toolContext = Map.of("gameStateId", gameStateId);

        return new TurnContext(
                systemPrompt,
                userPrompt,
                toolContext,
                triggered,
                isFirstRound,
                newTurn
        );
    }

    /**
     * 完成一轮游戏（步骤 6，GM 生成后）。
     * <p>
     * 从 GM 输出中提取 state_delta，应用状态变更，更新动机，更新触发器冷却。
     *
     * @param gameStateId 游戏状态 ID
     * @param gmOutput    GM 完整输出文本
     * @param turnContext  轮次上下文
     */
    public void finalizeTurn(String gameStateId, String gmOutput, TurnContext turnContext) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            log.warn("finalizeTurn: 游戏状态未找到: {}", gameStateId);
            return;
        }

        // 步骤6a: state_delta 提取（方案3混合式）
        JsonNode stateDelta = stateDeltaExtractor.extract(gmOutput);

        // 步骤6b: 应用状态变更
        String newLocation = gs.getCurrentLocation();
        String newFlags = gs.getFlags();
        String newNpcStates = gs.getNpcStates();

        if (stateDelta != null) {
            // 位置变更
            JsonNode locChange = stateDelta.path("location_change");
            if (!locChange.isMissingNode() && !locChange.isNull() && !locChange.asText().isBlank()) {
                newLocation = locChange.asText();
            }

            // 标记变更
            JsonNode flagsNode = stateDelta.path("flags");
            if (!flagsNode.isMissingNode() && flagsNode.isObject()) {
                newFlags = mergeJson(gs.getFlags(), flagsNode);
            }

            // NPC 状态变更（仅 status/mood 等，动机变更由 MotivationEngine 处理）
            JsonNode npcStatesNode = stateDelta.path("npc_states");
            if (!npcStatesNode.isMissingNode() && npcStatesNode.isObject()) {
                newNpcStates = mergeNpcStates(gs.getNpcStates(), npcStatesNode);
            }
        }

        // 步骤6c: 动态动机变更（三层）
        String narration = stateDeltaSanitizer.extractNarration(gmOutput);
        for (Trigger trigger : turnContext.triggered()) {
            if (trigger.getNpcId() != null) {
                newNpcStates = motivationEngine.applyMotivationChanges(
                        newNpcStates, trigger.getNpcId(),
                        trigger.getAction(), stateDelta, narration);
            }
        }

        // 步骤6d: 更新游戏状态
        stateRepo.updateState(gameStateId, newNpcStates, turnContext.newTurn(), newLocation);
        if (newFlags != null && !newFlags.equals(gs.getFlags())) {
            stateRepo.updateFlags(gameStateId, newFlags);
        }

        // 步骤6e: 更新触发器冷却
        for (Trigger trigger : turnContext.triggered()) {
            List<com.luyu.agent.rpg.model.TriggerRuntime> runtimes =
                    runtimeRepo.findByGameStateId(gameStateId);
            var existing = runtimes.stream()
                    .filter(rt -> rt.getTriggerId().equals(trigger.getId()))
                    .findFirst();

            if (existing.isPresent()) {
                runtimeRepo.updateLastTriggeredTurn(existing.get().getId(), turnContext.newTurn());
            } else {
                com.luyu.agent.rpg.model.TriggerRuntime rt = new com.luyu.agent.rpg.model.TriggerRuntime();
                rt.setId(UUID.randomUUID().toString());
                rt.setGameStateId(gameStateId);
                rt.setTriggerId(trigger.getId());
                rt.setLastTriggeredTurn(turnContext.newTurn());
                rt.setIsActive(true);
                runtimeRepo.save(rt);
            }
        }

        // 步骤6f: 记录 NPC 行为和状态变更事件
        if (stateDelta != null) {
            EventLog stateEvent = new EventLog();
            stateEvent.setGameStateId(gameStateId);
            stateEvent.setTurn(turnContext.newTurn());
            stateEvent.setEventType("state_change");
            stateEvent.setContent("状态已更新: location=" + newLocation);
            try {
                stateEvent.setStateDelta(mapper.writeValueAsString(stateDelta));
            } catch (Exception e) {
                log.warn("state_delta 序列化失败: {}", e.getMessage());
            }
            eventRepo.insert(stateEvent);
        }

        log.info("轮次 [{}] 完成: gameStateId={}, triggered={}",
                turnContext.newTurn(), gameStateId, turnContext.triggered().size());
    }

    // ==================== 辅助方法 ====================

    /**
     * 合并两个 JSON 对象（flags）。
     */
    private String mergeJson(String baseJson, JsonNode overlay) {
        try {
            JsonNode base = (baseJson != null && !baseJson.isBlank())
                    ? mapper.readTree(baseJson)
                    : mapper.createObjectNode();
            if (base.isObject() && overlay.isObject()) {
                var baseObj = (com.fasterxml.jackson.databind.node.ObjectNode) base;
                overlay.fields().forEachRemaining(entry ->
                        baseObj.set(entry.getKey(), entry.getValue()));
                return mapper.writeValueAsString(baseObj);
            }
            return baseJson;
        } catch (Exception e) {
            return baseJson;
        }
    }

    /**
     * 合并 NPC 状态（保留原有状态，更新传入的字段）。
     */
    private String mergeNpcStates(String baseJson, JsonNode overlay) {
        try {
            JsonNode base = (baseJson != null && !baseJson.isBlank())
                    ? mapper.readTree(baseJson)
                    : mapper.createObjectNode();
            if (base.isObject() && overlay.isObject()) {
                var baseObj = (com.fasterxml.jackson.databind.node.ObjectNode) base;
                overlay.fields().forEachRemaining(npcEntry -> {
                    String npcId = npcEntry.getKey();
                    JsonNode npcDelta = npcEntry.getValue();
                    if (baseObj.has(npcId)) {
                        var existingNpc = (com.fasterxml.jackson.databind.node.ObjectNode) baseObj.get(npcId);
                        npcDelta.fields().forEachRemaining(field ->
                                existingNpc.set(field.getKey(), field.getValue()));
                    } else {
                        baseObj.set(npcId, npcDelta);
                    }
                });
                return mapper.writeValueAsString(baseObj);
            }
            return baseJson;
        } catch (Exception e) {
            return baseJson;
        }
    }

    /**
     * 轮次上下文（GM 生成前的准备结果）。
     */
    public record TurnContext(
            String systemPrompt,      // 首轮 system prompt（后续轮为 null）
            String userPrompt,       // 用户输入 prompt
            Map<String, Object> toolContext,  // 工具上下文
            List<Trigger> triggered,  // 触发的触发器列表
            boolean firstRound,       // 是否首轮
            int newTurn              // 新轮次号
    ) {}
}
