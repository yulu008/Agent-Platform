package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * GM 上下文组装器。
 * <p>
 * 首轮全量注入：世界观 + 玩家角色卡 + 所有 NPC 角色卡 + 触发器定义 + 游戏状态
 * + 工具列表 + 行为指令 + state_delta 格式说明。
 * <p>
 * 后续轮增量注入：玩家行动 + 触发器命中结果。
 */
@Component
public class GmContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(GmContextAssembler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT_PATH = "rpg/gm-system-prompt.md";
    private static final String INCREMENTAL_PROMPT_PATH = "rpg/gm-incremental-prompt.md";

    /**
     * GM 叙事规范提示词（NPC 汉字指称、禁章节字眼、npc_states key 用卡 ID）。
     * 与 {@link #MEMORY_PROMPT_PATH} 一同由 {@link RpgMemoryPromptAdvisor} 每轮注入 system 消息，
     * 原因同记忆规范：首轮 system prompt 不落库，第 2 轮起上下文中不存在。
     */
    private static final String NARRATIVE_RULES_PATH = "rpg/gm-narrative-rules.md";

    /**
     * GM 记忆规范提示词。由 {@link RpgMemoryPromptAdvisor} <b>每轮</b>注入 system 消息，
     * 而不是拼进首轮 system prompt（{@code RpgGameController} 只在第 1 轮调 {@code .system(...)}，
     * 且 {@code SessionMemoryAdvisor} 从不持久化 SystemMessage，第 2 轮起上下文里压根没有它），
     * 也不是塞进增量模板（那是 user 消息，会被每轮持久化，maxEvents=30 的窗口内最多攒 30 份副本）。
     */
    private static final String MEMORY_PROMPT_PATH = "rpg/gm-memory-prompt.md";

    /** 索引重注入时 MEMORY.md 的行数上限，与 gm-memory-prompt.md 宣告的截断规则一致 */
    static final int MEMORY_INDEX_MAX_LINES = 200;

    /** npc_states 无既有条目（或不可解析）时，NPC key 参考块的占位文案 */
    static final String NPC_KEYS_EMPTY = "（暂无）";

    /**
     * 每 N 轮插入一次的记忆整理提醒。
     * <p>
     * 追加在增量模板末尾，即 {@code ## GM 任务指令} 段内 —— 刻意避开 {@code ## 玩家行动} 段：
     * {@link RpgHistoryCleaner} 按「{@code ## 玩家行动} 到下一个 {@code \n## }」截取玩家原文，
     * 提醒文本落在该区间内会被当成玩家说的话回放给前端。
     * <p>
     * 提醒覆盖五类记忆的叙事场景：NPC 印象、伏笔、世界细节、玩家偏好、
     * 以及 PC 的秘密/旅途节点/背景展开等 player_memory 内容
     * （PC 数值事实走 state_delta player 节点，不属笔记提醒范围）。
     */
    private static final String MEMORY_REMINDER =
            "\n\n（整理笔记：本轮是否有值得 NPC 长期记住的事、新埋的伏笔或即兴生成的世界细节？"
                    + "PC 是否藏了秘密、经历关键旅途节点或展开新背景值得记入 player.md？"
                    + "若有，用 GmMemory* 工具写进笔记本。）";

    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgTriggerRepository triggerRepo;
    private final RpgGameStateRepository stateRepo;

    private final String systemPromptTemplate;
    private final String incrementalPromptTemplate;
    private final String memoryPromptTemplate;
    private final String narrativeRulesTemplate;

    public GmContextAssembler(RpgWorldSettingRepository worldRepo,
                               RpgCharacterCardRepository charRepo,
                               RpgTriggerRepository triggerRepo,
                               RpgGameStateRepository stateRepo) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.triggerRepo = triggerRepo;
        this.stateRepo = stateRepo;
        this.systemPromptTemplate = loadTemplate(SYSTEM_PROMPT_PATH);
        this.incrementalPromptTemplate = loadTemplate(INCREMENTAL_PROMPT_PATH);
        this.memoryPromptTemplate = loadTemplate(MEMORY_PROMPT_PATH);
        this.narrativeRulesTemplate = loadTemplate(NARRATIVE_RULES_PATH);
    }

    /**
     * GM 记忆规范提示词原文（四类型定义、文件粒度、frontmatter 约定、负面清单）。
     * <p>
     * 刻意<b>不</b>做任何占位符渲染：文中的 JSON 花括号示例（如 {@code {"flags": {...}}}）
     * 必须原样送达模型。若改用 {@code PromptTemplate} 渲染，花括号会被当成占位符语法而抛异常。
     *
     * @return 提示词原文；加载失败时为空串
     */
    public String getMemoryPrompt() {
        return memoryPromptTemplate;
    }

    /**
     * GM 叙事规范提示词原文（汉字指称、禁章节字眼、npc_states key 用卡 ID）。
     *
     * @return 提示词原文；加载失败时为空串
     */
    public String getNarrativeRules() {
        return narrativeRulesTemplate;
    }

    /**
     * 组装首轮 system prompt（全量注入）。
     *
     * @param gameStateId 游戏状态 ID
     * @return 完整的 system prompt
     */
    public String assembleFirstRound(String gameStateId) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            return systemPromptTemplate;
        }

        WorldSetting world = worldRepo.findById(gs.getWorldId());
        CharacterCard player = charRepo.findById(gs.getPlayerCharId());
        List<CharacterCard> npcs = charRepo.findByWorldIdAndType(gs.getWorldId(), "npc");
        List<Trigger> triggers = triggerRepo.findByWorldId(gs.getWorldId());

        return systemPromptTemplate
                .replace("{{WORLD_SETTING}}", formatWorld(world))
                .replace("{{PLAYER_CARD}}", formatCard(player))
                .replace("{{NPC_CARDS}}", npcs.stream().map(this::formatCard).collect(Collectors.joining("\n\n---\n\n")))
                .replace("{{TRIGGER_DEFINITIONS}}", triggers.stream().map(this::formatTrigger).collect(Collectors.joining("\n\n---\n\n")))
                .replace("{{GAME_STATE}}", formatGameState(gs));
    }

    /**
     * 组装后续轮增量 prompt（不追加记忆整理提醒）。
     *
     * @param playerAction 玩家行动文本
     * @param triggerHits  触发器命中结果（已通过的触发器列表）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits) {
        return assembleIncremental(playerAction, triggerHits, false);
    }

    /**
     * 组装后续轮增量 prompt。
     *
     * @param playerAction   玩家行动文本
     * @param triggerHits    触发器命中结果（已通过的触发器列表）
     * @param memoryReminder 是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits, boolean memoryReminder) {
        return assembleIncremental(playerAction, triggerHits, memoryReminder, null);
    }

    /**
     * 组装后续轮增量 prompt（四参重载：不注入 NPC key 参考，占位为「（暂无）」）。
     *
     * @param playerAction     玩家行动文本
     * @param triggerHits      触发器命中结果（已通过的触发器列表）
     * @param memoryReminder   是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @param worldRefreshBlock 「世界背景回顾」重注入块（每 N 轮由 {@code GameLoopService} 经
     *                         {@link #buildWorldRefreshBlock(String)} 现算后传入；null 表示本轮不注入）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits,
                                     boolean memoryReminder, String worldRefreshBlock) {
        return assembleIncremental(playerAction, triggerHits, memoryReminder, worldRefreshBlock, null);
    }

    /**
     * 组装后续轮增量 prompt（五参重载：不注入在场名单，占位为「（暂无）」）。
     *
     * @param playerAction     玩家行动文本
     * @param triggerHits      触发器命中结果（已通过的触发器列表）
     * @param memoryReminder   是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @param worldRefreshBlock 「世界背景回顾」重注入块（每 N 轮由 {@code GameLoopService} 经
     *                         {@link #buildWorldRefreshBlock(String)} 现算后传入；null 表示本轮不注入）
     * @param npcKeyReference  「现有 NPC 状态 key」参考块（每轮由 {@code GameLoopService} 经
     *                         {@link #buildNpcKeyReference(String)} 现算后传入；null/空白时占位为「（暂无）」）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits,
                                     boolean memoryReminder, String worldRefreshBlock,
                                     String npcKeyReference) {
        return assembleIncremental(playerAction, triggerHits, memoryReminder,
                worldRefreshBlock, npcKeyReference, null);
    }

    /**
     * 组装后续轮增量 prompt（六参全量重载，rpg-scene-cast）。
     *
     * @param playerAction     玩家行动文本
     * @param triggerHits      触发器命中结果（已通过的触发器列表）
     * @param memoryReminder   是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @param worldRefreshBlock 「世界背景回顾」重注入块（每 N 轮由 {@code GameLoopService} 经
     *                         {@link #buildWorldRefreshBlock(String)} 现算后传入；null 表示本轮不注入）
     * @param npcKeyReference  「现有 NPC 状态 key」参考块（每轮由 {@code GameLoopService} 经
     *                         {@link #buildNpcKeyReference(String)} 现算后传入；null/空白时占位为「（暂无）」）
     * @param sceneCast        「当前在场 NPC」名单块（每轮由 {@code GameLoopService} 经
     *                         {@link #buildSceneCastReference(String)} 现算后传入；null/空白时占位为「（暂无）」。
     *                         模板位置在 NPC key 参考块之后、「## GM 任务指令」之前——
     *                         落在 {@code ## 玩家行动} 段之外，不污染 {@link RpgHistoryCleaner} 的玩家原文截取）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits,
                                     boolean memoryReminder, String worldRefreshBlock,
                                     String npcKeyReference, String sceneCast) {
        String triggerText = triggerHits == null || triggerHits.isEmpty()
                ? "本轮无触发器命中。"
                : triggerHits.stream()
                        .map(t -> String.format("- 触发器[%s]: NPC=%s, 行动=%s",
                                t.getId(), t.getNpcId(), t.getAction()))
                        .collect(Collectors.joining("\n"));

        String prompt = incrementalPromptTemplate
                .replace("{{PLAYER_ACTION}}", playerAction)
                .replace("{{TRIGGER_HITS}}", triggerText)
                .replace("{{NPC_KEY_REFERENCE}}",
                        npcKeyReference == null || npcKeyReference.isBlank()
                                ? NPC_KEYS_EMPTY : npcKeyReference)
                .replace("{{SCENE_CAST}}",
                        sceneCast == null || sceneCast.isBlank()
                                ? NPC_KEYS_EMPTY : sceneCast);

        if (memoryReminder) {
            prompt += MEMORY_REMINDER;
        }
        if (worldRefreshBlock != null && !worldRefreshBlock.isBlank()) {
            // 追加在模板末尾（## GM 任务指令 段之后），避开 ## 玩家行动 段：
            // RpgHistoryCleaner 按「## 玩家行动 到下一个 \n## 」截取玩家原文，落进该区间会被当成玩家的话回放
            prompt += worldRefreshBlock;
        }
        return prompt;
    }

    /**
     * 组装「世界背景回顾」重注入块：精简世界观（不含地点列表，那有 get_ 工具可查）
     * + GM 笔记本索引（MEMORY.md）。
     * <p>
     * 动机：世界观只渲染进首轮 system prompt，而 {@code SessionMemoryAdvisor} 从不持久化
     * SystemMessage，第 2 轮起上下文里没有世界观原文。本块随增量 prompt（user 消息，落库）
     * 周期性重注入，旧副本随 30 事件滑窗自然滑出，不会累积。
     *
     * @param gameStateId 游戏状态 ID
     * @return 以 {@code \n\n## 世界背景回顾} 开头的重注入块
     */
    public String buildWorldRefreshBlock(String gameStateId) {
        GameState gs = stateRepo.findById(gameStateId);
        WorldSetting world = gs == null ? null : worldRepo.findById(gs.getWorldId());
        return buildWorldRefreshBlock(world, readMemoryIndex(gameStateId));
    }

    /**
     * 纯组装逻辑（单测直接注入 world 与索引文本，不触库、不碰文件系统）。
     */
    String buildWorldRefreshBlock(WorldSetting world, String memoryIndex) {
        String indexText = memoryIndex == null || memoryIndex.isBlank()
                ? "（笔记本尚为空）"
                : memoryIndex;
        return "\n\n## 世界背景回顾\n\n"
                + "系统定期重注入以下设定，防止长对话后世界细节漂移。涉及笔记本条目时，"
                + "用 GmMemoryView 读取对应文件。\n\n"
                + "【世界观】\n" + formatCompactWorld(world) + "\n"
                + "【笔记本索引 MEMORY.md】\n" + indexText;
    }

    /**
     * npc_states key 参考块与合法 key 集合的一次性计算结果。
     *
     * @param referenceBlock 「现有 NPC 状态 key」参考块文本（空时为 {@link #NPC_KEYS_EMPTY}）
     * @param validKeys      合法 key 集合（该世界角色卡 ID ∪ 存档 npc_states 既有 key）
     */
    public record NpcKeyContext(String referenceBlock, Set<String> validKeys) {}

    /**
     * 一次性计算「现有 NPC 状态 key」参考块 + 合法 key 集合（单次查库，双重用途：
     * 参考块注入增量 prompt，key 集合随 {@code TurnContext} 传递给 finalizeTurn
     * 供 {@link StateDeltaKeyValidator} 校验）。
     */
    public NpcKeyContext buildNpcKeyContext(String gameStateId) {
        if (stateRepo == null) {
            return new NpcKeyContext(NPC_KEYS_EMPTY, Set.of());
        }
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            return new NpcKeyContext(NPC_KEYS_EMPTY, Set.of());
        }
        List<CharacterCard> cards = charRepo == null
                ? List.of()
                : charRepo.findByWorldId(gs.getWorldId());
        return new NpcKeyContext(
                buildNpcKeyReference(gs, cards), collectValidNpcKeys(gs, cards));
    }

    /**
     * 纯收集逻辑：合法 key 集合 = 角色卡 ID ∪ 存档 npc_states 既有 key
     * （npcStates 非法 JSON 时仅含卡 ID，与 key 参考块的降级语义一致）。
     */
    Set<String> collectValidNpcKeys(GameState gs, List<CharacterCard> cards) {
        Set<String> keys = new LinkedHashSet<>();
        if (cards != null) {
            for (CharacterCard card : cards) {
                if (card != null && card.getId() != null) {
                    keys.add(card.getId());
                }
            }
        }
        if (gs == null || gs.getNpcStates() == null || gs.getNpcStates().isBlank()) {
            return keys;
        }
        try {
            JsonNode root = mapper.readTree(gs.getNpcStates());
            if (root.isObject()) {
                root.fieldNames().forEachRemaining(keys::add);
            }
        } catch (IOException e) {
            log.warn("npcStates 不是合法 JSON，合法 key 集合仅含角色卡 ID: {}", e.getMessage());
        }
        return keys;
    }

    /**
     * 组装「现有 NPC 状态 key」参考块：列出存档 npc_states 的全部既有 key，
     * 有角色卡的 key（即卡 ID）顺带标注卡名，供 GM 在 state_delta 里原样沿用。
     * <p>
     * 动机：世界观与 npc_states 只渲染进首轮 system prompt（SystemMessage 不落库），
     * 后续轮 GM 看不到既有 key，会把同一无卡临时 NPC 再造一个拼音/罗马音 key
     * （如「苏枕雪」→ su_zhenxue），导致状态面板出现重复的英文条目。
     * 本块随增量 prompt 每轮注入（体积仅为 key 列表，不随状态膨胀）。
     *
     * @param gameStateId 游戏状态 ID
     * @return key 参考列表；无条目或不可解析时为「（暂无）」
     */
    public String buildNpcKeyReference(String gameStateId) {
        return buildNpcKeyContext(gameStateId).referenceBlock();
    }

    /**
     * 纯组装逻辑（单测直接注入 GameState 与角色卡列表，不触库）。
     */
    String buildNpcKeyReference(GameState gs, List<CharacterCard> cards) {
        if (gs == null || gs.getNpcStates() == null || gs.getNpcStates().isBlank()) {
            return NPC_KEYS_EMPTY;
        }
        JsonNode root;
        try {
            root = mapper.readTree(gs.getNpcStates());
        } catch (IOException e) {
            log.warn("npcStates 不是合法 JSON，NPC key 参考块降级为占位: {}", e.getMessage());
            return NPC_KEYS_EMPTY;
        }
        if (!root.isObject() || root.isEmpty()) {
            return NPC_KEYS_EMPTY;
        }
        Map<String, String> cardNames = new HashMap<>();
        if (cards != null) {
            for (CharacterCard card : cards) {
                if (card != null && card.getId() != null && card.getName() != null) {
                    cardNames.put(card.getId(), card.getName());
                }
            }
        }
        List<String> lines = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            String key = fields.next().getKey();
            String cardName = cardNames.get(key);
            lines.add(cardName != null
                    ? "- " + key + "（角色卡「" + cardName + "」，必须用此 ID 作 key）"
                    : "- " + key);
        }
        return String.join("\n", lines);
    }

    // ==================== 场景在场名单（rpg-scene-cast） ====================

    /**
     * 组装「当前在场 NPC」名单块（查库版）：读存档 npc_states 条目的 in_scene 戳。
     *
     * @param gameStateId 游戏状态 ID
     * @return 在场名单列表；无条目/无戳/不可解析时为「（暂无）」
     */
    public String buildSceneCastReference(String gameStateId) {
        if (stateRepo == null) {
            return NPC_KEYS_EMPTY;
        }
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            return NPC_KEYS_EMPTY;
        }
        List<CharacterCard> cards = charRepo == null
                ? List.of()
                : charRepo.findByWorldId(gs.getWorldId());
        return buildSceneCastReference(gs, cards);
    }

    /**
     * 纯组装逻辑（单测直接注入 GameState 与角色卡列表，不触库）：
     * 列出 in_scene=true 的条目 key，卡 ID key 顺带标注卡名，反哺 GM 保持在场感一致。
     * <p>
     * 动机（rpg-scene-cast design D6）：场景班底由 finalizeTurn 每轮盖戳，
     * 本块随增量 prompt 每轮注入，让 GM 知道当前场景站着谁（含沉默旁观者）。
     * 旧存档无戳 → 空集 → 占位「（暂无）」，与面板降级语义一致。
     */
    String buildSceneCastReference(GameState gs, List<CharacterCard> cards) {
        if (gs == null || gs.getNpcStates() == null || gs.getNpcStates().isBlank()) {
            return NPC_KEYS_EMPTY;
        }
        JsonNode root;
        try {
            root = mapper.readTree(gs.getNpcStates());
        } catch (IOException e) {
            log.warn("npcStates 不是合法 JSON，在场名单降级为占位: {}", e.getMessage());
            return NPC_KEYS_EMPTY;
        }
        if (!root.isObject() || root.isEmpty()) {
            return NPC_KEYS_EMPTY;
        }
        Map<String, String> cardNames = new HashMap<>();
        if (cards != null) {
            for (CharacterCard card : cards) {
                if (card != null && card.getId() != null && card.getName() != null) {
                    cardNames.put(card.getId(), card.getName());
                }
            }
        }
        List<String> lines = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isObject()
                    || !entry.getValue().path("in_scene").asBoolean(false)) {
                continue;
            }
            String key = entry.getKey();
            String cardName = cardNames.get(key);
            lines.add(cardName != null
                    ? "- " + key + "（角色卡「" + cardName + "」）"
                    : "- " + key);
        }
        return lines.isEmpty() ? NPC_KEYS_EMPTY : String.join("\n", lines);
    }

    // ==================== 格式化辅助方法 ====================

    /**
     * 读取存档笔记本索引 MEMORY.md（超 {@value #MEMORY_INDEX_MAX_LINES} 行截断，与宣告的规则一致）；
     * 文件缺失或读取失败返回 null（重注入块降级为「笔记本尚为空」）。
     */
    private String readMemoryIndex(String gameStateId) {
        Path index = RpgSavePaths.saveDir(gameStateId).resolve(RpgSavePaths.INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
            if (lines.size() > MEMORY_INDEX_MAX_LINES) {
                log.info("MEMORY.md 超 {} 行，重注入时已截断: gameStateId={}", MEMORY_INDEX_MAX_LINES, gameStateId);
                lines = lines.subList(0, MEMORY_INDEX_MAX_LINES);
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            log.warn("读取 MEMORY.md 失败，本次重注入省略索引: gameStateId={}", gameStateId, e);
            return null;
        }
    }

    /**
     * 精简世界观（重注入块专用）：比 {@link #formatWorld} 少地点列表——
     * 地点明细有 get_ 工具可查，刻意控制注入体积。
     */
    private String formatCompactWorld(WorldSetting world) {
        if (world == null) return "（未设定）";
        return String.format("""
                名称：%s
                时代：%s
                描述：%s
                规则：%s
                氛围：%s
                """, world.getName(), world.getEra(), world.getSettingDesc(),
                world.getRules(), world.getAtmosphere());
    }

    private String formatWorld(WorldSetting world) {
        if (world == null) return "（未设定）";
        return String.format("""
                名称：%s
                时代：%s
                描述：%s
                规则：%s
                地点：%s
                氛围：%s
                """, world.getName(), world.getEra(), world.getSettingDesc(),
                world.getRules(), world.getLocations(), world.getAtmosphere());
    }

    private String formatCard(CharacterCard card) {
        if (card == null) return "（未设定）";
        return String.format("""
                ID：%s
                名称：%s（%s）
                身份：%s
                性格：%s
                背景：%s
                动机：%s
                说话风格：%s
                知识：%s
                """, card.getId(), card.getName(), card.getType(), card.getIdentity(),
                card.getPersonality(), card.getBackground(),
                card.getMotivation(), card.getSpeechStyle(), card.getKnowledge());
    }

    private String formatTrigger(Trigger trigger) {
        if (trigger == null) return "（未设定）";
        return String.format("""
                ID：%s
                类型：%s
                关联NPC：%s
                行动：%s
                硬条件：%s
                软条件：%s
                概率：%s
                冷却：%s轮
                """, trigger.getId(), trigger.getType(), trigger.getNpcId(),
                trigger.getAction(), trigger.getHardConditions(),
                trigger.getSoftCondition(),
                trigger.getProbability(), trigger.getCooldown());
    }

    private String formatGameState(GameState gs) {
        if (gs == null) return "（未初始化）";
        return String.format("""
                当前轮次：%d
                当前位置：%s
                标记：%s
                NPC状态：%s
                """, gs.getTurnCount(), gs.getCurrentLocation(),
                gs.getFlags(), gs.getNpcStates());
    }

    private String loadTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("加载 prompt 模板失败: {}", path, e);
            return "";
        }
    }
}
