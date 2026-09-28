package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.rpg.config.RpgMemoryProperties;
import com.luyu.agent.rpg.model.EventLog;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.repository.*;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final RpgMemoryProperties memoryProperties;
    private final RpgGameStateSnapshotRepository snapshotRepo;
    private final MemorySnapshotStore memorySnapshotStore;
    private final NpcKeyRepairService npcKeyRepairService;
    private final TurnGuardService turnGuardService;

    public GameLoopService(RpgGameStateRepository stateRepo,
                           RpgEventLogRepository eventRepo,
                           RpgTriggerRuntimeRepository runtimeRepo,
                           TriggerEngine triggerEngine,
                           SoftConditionEvaluator softConditionEvaluator,
                           ProbabilityRoller probabilityRoller,
                           GmContextAssembler contextAssembler,
                           StateDeltaExtractor stateDeltaExtractor,
                           StateDeltaSanitizer stateDeltaSanitizer,
                           MotivationEngine motivationEngine,
                           RpgMemoryProperties memoryProperties,
                           RpgGameStateSnapshotRepository snapshotRepo,
                           MemorySnapshotStore memorySnapshotStore,
                           NpcKeyRepairService npcKeyRepairService,
                           TurnGuardService turnGuardService) {
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
        this.memoryProperties = memoryProperties;
        this.snapshotRepo = snapshotRepo;
        this.memorySnapshotStore = memorySnapshotStore;
        this.npcKeyRepairService = npcKeyRepairService;
        this.turnGuardService = turnGuardService;
    }

    /**
     * 准备一轮游戏（步骤 1-4，GM 生成前）。
     * <p>
     * 入口处获取回合互斥守卫（design D11）：获取失败说明同存档已有回合/回溯进行中，拒绝开新回合。
     * 成功后守卫由回合 SSE 流终止（Controller doFinally，覆盖 complete/cancel/error）释放；
     * 本方法内部异常时自释放防泄漏，正常返回时持有至流终止。
     *
     * @param gameStateId  游戏状态 ID
     * @param playerAction 玩家行动文本
     * @return 轮次上下文（包含 GM 所需的 prompt 和工具上下文）
     */
    public TurnContext prepareTurn(String gameStateId, String playerAction) {
        if (!turnGuardService.tryAcquire(gameStateId)) {
            throw new IllegalStateException("当前有回合正在进行，请等待其完成或中止后再试");
        }
        try {
            return doPrepareTurn(gameStateId, playerAction);
        } catch (RuntimeException e) {
            // 异常路径守卫自释放：调用方未拿到回合，无人替它释放
            turnGuardService.release(gameStateId);
            throw e;
        }
    }

    private TurnContext doPrepareTurn(String gameStateId, String playerAction) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            throw new IllegalArgumentException("游戏状态未找到: " + gameStateId);
        }

        int currentTurn = gs.getTurnCount() != null ? gs.getTurnCount() : 0;
        int newTurn = currentTurn + 1;

        // 步骤0: 联合快照（回溯支撑）：记录 GM 即将看到的世界（turn = N-1 = currentTurn）。
        // 必须先于本轮任何事件落库（步骤1 的 player_action 日志属于被回溯轮次，不入快照）。
        // 快照失败仅降级告警：本回合照常进行，只是该轮回溯时会走「快照缺失」拒绝路径。
        try {
            snapshotRepo.upsert(gs, currentTurn);
            memorySnapshotStore.snapshot(gameStateId, currentTurn);
            snapshotRepo.prune(gameStateId, RpgGameStateSnapshotRepository.KEEP_COUNT);
            memorySnapshotStore.prune(gameStateId, MemorySnapshotStore.KEEP_COUNT);
        } catch (Exception e) {
            log.warn("回合快照失败（该轮回溯将不可用）: gameStateId={}, turn={}", gameStateId, currentTurn, e);
        }

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

        // 「现有 NPC 状态 key」参考块 + 合法 key 集合（单次查库，双重用途）：
        // 参考块注入增量 prompt 让 GM 沿用既有 key；
        // key 集合随 TurnContext 传给 finalizeTurn，供 state_delta 的 npc_states key 硬校验。
        GmContextAssembler.NpcKeyContext npcKeyCtx = contextAssembler.buildNpcKeyContext(gameStateId);
        Set<String> validNpcKeys = npcKeyCtx == null || npcKeyCtx.validKeys() == null
                ? Set.of() : npcKeyCtx.validKeys();

        if (isFirstRound) {
            systemPrompt = contextAssembler.assembleFirstRound(gameStateId);
            userPrompt = playerAction;
        } else {
            // 每 N 轮追加一次记忆整理提醒 + 世界背景回顾（N 见 rpg.memory.remind-every-turns /
            // rpg.memory.world-refresh-every-turns）。
            // 刻意只在非首轮生效：首轮的 userPrompt 是玩家行动原文（未经增量模板包裹），
            // 在其后拼接提醒会让 RpgHistoryCleaner 把提醒当成玩家说的话回放给前端。
            boolean memoryReminder = memoryProperties.shouldRemind(newTurn);
            boolean worldRefresh = memoryProperties.shouldRefreshWorld(newTurn);
            String worldRefreshBlock = worldRefresh
                    ? contextAssembler.buildWorldRefreshBlock(gameStateId)
                    : null;
            // 现有 npc_states key 参考：每轮注入，让 GM 沿用既有 key 而非另造拼音 key
            // （世界观/npc_states 只在首轮 system prompt 且不落库，后续轮 GM 看不到既有 key）
            String npcKeyReference = npcKeyCtx == null ? null : npcKeyCtx.referenceBlock();
            // 当前在场 NPC 名单（rpg-scene-cast D6）：每轮反哺 GM，保持场景班底在场感一致；
            // 旧存档无戳时为「（暂无）」占位，推进一轮盖戳后自然重建
            String sceneCastReference = contextAssembler.buildSceneCastReference(gameStateId);
            userPrompt = contextAssembler.assembleIncremental(
                    playerAction, triggered, memoryReminder, worldRefreshBlock,
                    npcKeyReference, sceneCastReference);
            if (memoryReminder) {
                log.debug("第 {} 轮触发记忆整理提醒（间隔 {} 轮）", newTurn, memoryProperties.getRemindEveryTurns());
            }
            if (worldRefresh) {
                log.debug("第 {} 轮重注入世界背景回顾（间隔 {} 轮）",
                        newTurn, memoryProperties.getWorldRefreshEveryTurns());
            }
        }

        // 工具上下文：gameStateId（存档级记忆隔离）+ tenantId（租户身份）。
        // prepareTurn 在请求线程执行，TenantContext 必可用；tenantId 随 TurnContext
        // 作为显式载体传播到异步执行链路（GM 流式 / 降级重跑 / finalizeTurn）。
        Map<String, Object> toolContext = Map.of(
                "gameStateId", gameStateId,
                TenantContext.TOOL_CONTEXT_KEY, TenantContext.requireTenantId());

        return new TurnContext(
                systemPrompt,
                userPrompt,
                toolContext,
                triggered,
                isFirstRound,
                newTurn,
                validNpcKeys
        );
    }

    /**
     * 完成一轮游戏（步骤 6，GM 生成后）。
     * <p>
     * 从 GM 输出中提取 state_delta，应用状态变更，更新动机，更新触发器冷却。
     * <p>
     * 调用方多在 SSE 回调线程（doOnComplete/doOnCancel），ThreadLocal 已失效：
     * 此处从 TurnContext 的 ToolContext 显式载体恢复租户上下文（同 gameStateId 透传模式），
     * 恢复者负责 finally 清理（防线程池串号）；同步调用（上下文已存在）不覆盖不清理。
     *
     * @param gameStateId 游戏状态 ID
     * @param gmOutput    GM 完整输出文本
     * @param turnContext  轮次上下文
     */
    public void finalizeTurn(String gameStateId, String gmOutput, TurnContext turnContext) {
        boolean restored = false;
        if (!TenantContext.isPresent()) {
            Object carrier = turnContext.toolContext().get(TenantContext.TOOL_CONTEXT_KEY);
            if (carrier instanceof String tenantId && !tenantId.isBlank()) {
                TenantContext.set("gm-async", tenantId);
                restored = true;
            }
        }
        try {
            doFinalizeTurn(gameStateId, gmOutput, turnContext);
        } finally {
            if (restored) {
                TenantContext.clear();
            }
        }
    }

    private void doFinalizeTurn(String gameStateId, String gmOutput, TurnContext turnContext) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            log.warn("finalizeTurn: 游戏状态未找到: {}", gameStateId);
            return;
        }

        // 步骤6a: state_delta 提取（方案3混合式）
        JsonNode stateDelta = stateDeltaExtractor.extract(gmOutput);

        // 叙述原文（npc key 修复与动机变更共用）
        String narration = stateDeltaSanitizer.extractNarration(gmOutput);

        // 步骤6b: npc_states key 硬校验与静默修复（先于合并，保证非法 key 永不入库）
        NpcKeyValidationOutcome outcome =
                validateAndRepairNpcStateKeys(gameStateId, stateDelta, narration, turnContext);
        stateDelta = outcome.stateDelta();

        // 步骤6c: 应用状态变更
        String newLocation = gs.getCurrentLocation();
        String newFlags = gs.getFlags();
        String newNpcStates = gs.getNpcStates();
        String newPlayerStates = gs.getPlayerStates();
        String playerChangeSummary = null;

        if (stateDelta != null) {
            // 位置变更（GM 有时模仿 get_game_state 返回值写 currentLocation，两种 key 均接受）
            JsonNode locChange = firstPresent(stateDelta, "location_change", "currentLocation");
            if (locChange != null && !locChange.isNull() && !locChange.asText().isBlank()) {
                newLocation = locChange.asText();
            }

            // 标记变更
            JsonNode flagsNode = stateDelta.path("flags");
            if (!flagsNode.isMissingNode() && flagsNode.isObject()) {
                newFlags = mergeJson(gs.getFlags(), flagsNode);
            }

            // NPC 状态变更（仅 status/mood 等，动机变更由 MotivationEngine 处理；
            // GM 有时写驼峰 npcStates，两种 key 均接受）
            JsonNode npcStatesNode = firstPresent(stateDelta, "npc_states", "npcStates");
            if (npcStatesNode != null && npcStatesNode.isObject()) {
                newNpcStates = mergeNpcStates(gs.getNpcStates(), npcStatesNode);
            }

            // PC 结构化状态变更（GM 有时写驼峰 playerStates，两种 key 均接受）
            JsonNode playerNode = firstPresent(stateDelta, "player", "playerStates");
            if (playerNode != null && playerNode.isObject()) {
                newPlayerStates = mergePlayerStates(gs.getPlayerStates(), playerNode);
                List<String> changedFields = new ArrayList<>();
                playerNode.fieldNames().forEachRemaining(changedFields::add);
                playerChangeSummary = String.join(",", changedFields);
            }
        }

        // 步骤6d: 动态动机变更（三层）
        for (Trigger trigger : turnContext.triggered()) {
            if (trigger.getNpcId() != null) {
                newNpcStates = motivationEngine.applyMotivationChanges(
                        newNpcStates, trigger.getNpcId(),
                        trigger.getAction(), stateDelta, narration);
            }
        }

        // 步骤6d'（rpg-scene-cast D7）：场景在场盖戳——merge/动机之后、落库之前。
        // 按本轮 scene_npcs（校验后）∪ touched key 给每个条目写 in_scene，其余全量置 false；
        // 任何异常降级为不盖戳直接落库 merge 产物，不中断回合。
        newNpcStates = stampScenePresence(
                newNpcStates, stateDelta, gs.getCurrentLocation(), newLocation, turnContext);

        // 步骤6e: 更新游戏状态
        stateRepo.updateState(gameStateId, newNpcStates, turnContext.newTurn(), newLocation);
        if (newFlags != null && !newFlags.equals(gs.getFlags())) {
            stateRepo.updateFlags(gameStateId, newFlags);
        }
        if (newPlayerStates != null && !newPlayerStates.equals(gs.getPlayerStates())) {
            stateRepo.updatePlayerStates(gameStateId, newPlayerStates);
        }

        // 步骤6f: 更新触发器冷却
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

        // 步骤6g: 记录 NPC 行为和状态变更事件（state_delta 存修复后的版本，所见即所存）
        if (stateDelta != null) {
            EventLog stateEvent = new EventLog();
            stateEvent.setGameStateId(gameStateId);
            stateEvent.setTurn(turnContext.newTurn());
            stateEvent.setEventType("state_change");
            stateEvent.setContent("状态已更新: location=" + newLocation
                    + (playerChangeSummary == null ? "" : "; player变更: " + playerChangeSummary)
                    + (outcome.repairNote() == null ? "" : "；" + outcome.repairNote()));
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

    // ==================== npc_states key 校验/修复 ====================

    /** npc_states key 校验结果：可能被修复/清洗过的 state_delta + 事件日志附注（null 表示无修复发生） */
    private record NpcKeyValidationOutcome(JsonNode stateDelta, String repairNote) {}

    /**
     * 步骤6b：校验 state_delta 中 npc_states 的 key（兼容 GM 的下划线/驼峰两种命名），
     * 非法 key 触发轻量 LLM 修复，仍非法的条目确定性丢弃——非法 key 永不入库。
     * 任何异常仅降级为丢弃非法条目，不中断回合主流程。
     */
    private NpcKeyValidationOutcome validateAndRepairNpcStateKeys(
            String gameStateId, JsonNode stateDelta, String narration, TurnContext turnContext) {
        if (stateDelta == null || !stateDelta.isObject()) {
            return new NpcKeyValidationOutcome(stateDelta, null);
        }
        String npcFieldName = firstPresentFieldName(stateDelta, "npc_states", "npcStates");
        if (npcFieldName == null) {
            return new NpcKeyValidationOutcome(stateDelta, null);
        }
        JsonNode npcStatesNode = stateDelta.get(npcFieldName);
        if (npcStatesNode == null || !npcStatesNode.isObject()) {
            return new NpcKeyValidationOutcome(stateDelta, null);
        }

        Set<String> validKeys = turnContext.validNpcKeys();
        StateDeltaKeyValidator.Result verdict =
                StateDeltaKeyValidator.validate(npcStatesNode, validKeys);
        if (!verdict.hasInvalid()) {
            return new NpcKeyValidationOutcome(stateDelta, null);
        }

        log.warn("非法 npc_states key: {}（gameStateId={}），触发修复", verdict.invalidKeys(), gameStateId);

        // 终局节点 = 原合法条目 + 修复后通过再校验的新条目；其余非法条目丢弃
        ObjectNode finalNpcStates = verdict.validEntries();
        List<String> addedKeys = new ArrayList<>();
        try {
            JsonNode repaired = npcKeyRepairService.repair(
                    narration, stateDelta, verdict.invalidKeys(), validKeys);
            if (repaired != null) {
                StateDeltaKeyValidator.Result recheck =
                        StateDeltaKeyValidator.validate(repaired, validKeys);
                recheck.validEntries().fields().forEachRemaining(entry -> {
                    if (!finalNpcStates.has(entry.getKey())) {
                        finalNpcStates.set(entry.getKey(), entry.getValue());
                        addedKeys.add(entry.getKey());
                    }
                });
            }
        } catch (Exception e) {
            log.warn("npc_states key 修复链路异常，降级为丢弃非法条目: {}", e.getMessage(), e);
        }

        String note;
        if (!addedKeys.isEmpty()) {
            int dropped = Math.max(0, verdict.invalidKeys().size() - addedKeys.size());
            note = "npc_states key 修复: 非法" + verdict.invalidKeys()
                    + " → 新增" + addedKeys
                    + (dropped > 0 ? "，丢弃 " + dropped + " 条" : "");
            log.info("npc_states key 修复完成: 非法 {} → 新增 {}{}（gameStateId={}）",
                    verdict.invalidKeys(), addedKeys,
                    dropped > 0 ? "，丢弃 " + dropped + " 条" : "", gameStateId);
        } else {
            note = "npc_states key 校验: 丢弃非法 key " + verdict.invalidKeys();
            log.info("npc_states key 修复未生效，丢弃非法条目: {}（gameStateId={}）",
                    verdict.invalidKeys(), gameStateId);
        }

        // 仅替换 npc_states 节点，location_change/flags 等其他字段原样保留
        ObjectNode deltaCopy = (ObjectNode) stateDelta.deepCopy();
        deltaCopy.set(npcFieldName, finalNpcStates);
        return new NpcKeyValidationOutcome(deltaCopy, note);
    }

    /** 返回 node 中首个存在的字段名（配合 firstPresent 的节点版本，兼容下划线/驼峰命名） */
    private String firstPresentFieldName(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key)) {
                return key;
            }
        }
        return null;
    }

    // ==================== 辅助方法 ====================

    /**
     * 合并 PC 结构化状态（player_states，rpg-player-memory 设计 D2）。
     * <ul>
     *   <li>{@code money}：数值<b>增量</b>——现值累加变化量，无现值时以 0 为基数
     *       （GM 报变化量而非余额，防模型算术漂移；整数值保持无小数点输出）</li>
     *   <li>{@code titles}：数组整体替换</li>
     *   <li>{@code abilities} 与其余自由键：对象值<b>深合并</b>（同键新值替换旧值，未提及键保留）；
     *       标量/数组值直接替换</li>
     * </ul>
     * 任一异常降级返回原值，不中断回合。
     */
    private String mergePlayerStates(String baseJson, JsonNode overlay) {
        try {
            JsonNode base = (baseJson != null && !baseJson.isBlank())
                    ? mapper.readTree(baseJson)
                    : mapper.createObjectNode();
            if (!base.isObject() || !overlay.isObject()) {
                return baseJson;
            }
            ObjectNode baseObj = (ObjectNode) base;
            overlay.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode delta = entry.getValue();
                if ("money".equals(key)) {
                    double current = baseObj.path(key).isNumber() ? baseObj.path(key).asDouble() : 0.0;
                    double after = current + (delta.isNumber() ? delta.asDouble() : 0.0);
                    if (after == Math.rint(after) && !Double.isInfinite(after)) {
                        baseObj.put(key, (long) after);
                    } else {
                        baseObj.put(key, after);
                    }
                } else if (delta.isObject() && baseObj.path(key).isObject()) {
                    // abilities 与自由键的对象值深合并：同键新值替换，未提及键保留
                    baseObj.set(key, deepMergeObjects((ObjectNode) baseObj.get(key), (ObjectNode) delta));
                } else {
                    // titles 数组整体替换；标量自由键覆盖替换
                    baseObj.set(key, delta);
                }
            });
            return mapper.writeValueAsString(baseObj);
        } catch (Exception e) {
            log.warn("player_states 合并失败，保持原值: {}", e.getMessage());
            return baseJson;
        }
    }

    /** 对象深合并：overlay 的键覆盖 base，未提及键保留（递归嵌套对象）。 */
    private ObjectNode deepMergeObjects(ObjectNode base, ObjectNode overlay) {
        overlay.fields().forEachRemaining(entry -> {
            JsonNode delta = entry.getValue();
            JsonNode current = base.get(entry.getKey());
            if (delta.isObject() && current != null && current.isObject()) {
                base.set(entry.getKey(), deepMergeObjects((ObjectNode) current, (ObjectNode) delta));
            } else {
                base.set(entry.getKey(), delta);
            }
        });
        return base;
    }

    /**
     * 依次尝试多个 key，返回首个存在且非 missing 的节点（兼容 GM 输出的下划线/驼峰两种命名）。
     *
     * @return 命中的节点；全部缺失时返回 null
     */
    private JsonNode firstPresent(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode candidate = node.path(key);
            if (!candidate.isMissingNode()) {
                return candidate;
            }
        }
        return null;
    }

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

    // ==================== 场景在场盖戳（rpg-scene-cast） ====================

    /**
     * 在合并后的 npc_states 上盖「当前场景在场」戳（rpg-scene-cast design D3/D4/D5）。
     * <ul>
     *   <li>正常路径：在场 = 校验后的 {@code scene_npcs}（仅保留有状态条目的合法 key）
     *       ∪ 本轮 {@code npc_states} 触及 key；其余既有条目一律置 {@code in_scene:false}
     *       （全量替换语义，离场 NPC 下一轮即掉出面板）</li>
     *   <li>{@code scene_npcs} 缺失/非数组时的降级链：地点变更 → 在场 = 仅 touched；
     *       地点未变 → 沿用旧戳（merge 保留旧条目字段）+ touched 置 true</li>
     *   <li>声明里的非法 key 确定性丢弃（复用 {@code TurnContext.validNpcKeys}，不走 LLM 修复）</li>
     *   <li>任何异常降级为返回原 json（不盖戳直接落库），不中断回合</li>
     * </ul>
     * 盖戳发生在 6b key 修复与 6d 动机合并之后，后端每轮覆盖：GM 回声的 in_scene 值惰性。
     *
     * @param npcStatesJson 合并后的 npc_states JSON 文本
     * @param stateDelta    6b 校验/修复后的 state_delta（可能为 null）
     * @param prevLocation  上一轮地点（落库前存档值）
     * @param newLocation   本轮地点（location_change 后，可能未变）
     * @param turnContext   轮次上下文（提供 validNpcKeys）
     * @return 盖戳后的 JSON 文本；异常时原样返回
     */
    private String stampScenePresence(String npcStatesJson, JsonNode stateDelta,
                                      String prevLocation, String newLocation,
                                      TurnContext turnContext) {
        if (npcStatesJson == null || npcStatesJson.isBlank()) {
            return npcStatesJson;
        }
        try {
            JsonNode root = mapper.readTree(npcStatesJson);
            if (!root.isObject() || root.isEmpty()) {
                return npcStatesJson;
            }
            ObjectNode rootObj = (ObjectNode) root;
            Set<String> validKeys = turnContext == null ? null : turnContext.validNpcKeys();

            Set<String> present = new LinkedHashSet<>();

            // touched：本轮 npc_states 触及的 key（仅盖有状态条目的）
            JsonNode npcDelta = stateDelta == null
                    ? null : firstPresent(stateDelta, "npc_states", "npcStates");
            if (npcDelta != null && npcDelta.isObject()) {
                npcDelta.fieldNames().forEachRemaining(key -> {
                    if (rootObj.has(key)) {
                        present.add(key);
                    }
                });
            }

            // declared：scene_npcs（兼容驼峰），非法 key 确定性丢弃（D5）
            JsonNode sceneNode = stateDelta == null
                    ? null : firstPresent(stateDelta, "scene_npcs", "sceneNpcs");
            boolean declared = sceneNode != null && sceneNode.isArray();
            if (declared) {
                for (JsonNode item : sceneNode) {
                    String key = item.asText();
                    if (rootObj.has(key)) {
                        present.add(key);
                    } else if (validKeys != null && validKeys.contains(key)) {
                        log.warn("scene_npcs 声明了合法但无状态条目的 key，忽略: {}", key);
                    } else {
                        log.warn("scene_npcs 含非法 key，确定性丢弃: {}", key);
                    }
                }
            } else {
                // 降级链（D4）：无声明时以地点是否变更为换幕信号
                boolean locationChanged = newLocation != null && !newLocation.equals(prevLocation);
                if (locationChanged) {
                    log.warn("scene_npcs 缺失且地点变更（{} → {}），在场集合降级为本轮触及 key",
                            prevLocation, newLocation);
                } else {
                    log.warn("scene_npcs 缺失且地点未变，沿用旧在场戳 + 本轮触及 key");
                    rootObj.fields().forEachRemaining(entry -> {
                        if (entry.getValue().isObject()
                                && entry.getValue().path("in_scene").asBoolean(false)) {
                            present.add(entry.getKey());
                        }
                    });
                }
            }

            // 盖戳：每个条目都写 in_scene（含 false），保证全量替换语义
            rootObj.fields().forEachRemaining(entry -> {
                if (entry.getValue().isObject()) {
                    ((ObjectNode) entry.getValue()).put("in_scene", present.contains(entry.getKey()));
                }
            });
            return mapper.writeValueAsString(rootObj);
        } catch (Exception e) {
            log.warn("场景在场盖戳失败，保持未盖戳的 npc_states: {}", e.getMessage());
            return npcStatesJson;
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
            int newTurn,              // 新轮次号
            Set<String> validNpcKeys  // 合法 npc_states key 集合（卡 ID ∪ 存档既有 key，供 finalizeTurn 校验）
    ) {}
}
