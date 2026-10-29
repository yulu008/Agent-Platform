package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.rpg.config.RpgTechniqueProperties;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.*;
import com.luyu.agent.rpg.service.LoreArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** customBlocks 块数上限（rpg-hud-custom-blocks D1：≤5 块，与前端 workshop.js/hud.js 同源） */
    static final int MAX_CUSTOM_BLOCKS = 5;

    /** customBlocks field 的安全正则（rpg-hud-custom-blocks D1/D4，与前端 HUD_BLOCK_FIELD_RE 同源） */
    static final String CUSTOM_BLOCK_FIELD_PATTERN = "[a-zA-Z][a-zA-Z0-9_]{0,63}";

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
    /** 招式门禁配置（rpg-technique-tree-data D8）：渲染「需 X≥阈值」文案取 defaultUnlockThreshold */
    private final RpgTechniqueProperties techniqueProps;

    private final String systemPromptTemplate;
    private final String incrementalPromptTemplate;
    private final String memoryPromptTemplate;
    private final String narrativeRulesTemplate;

    public GmContextAssembler(RpgWorldSettingRepository worldRepo,
                               RpgCharacterCardRepository charRepo,
                               RpgTriggerRepository triggerRepo,
                               RpgGameStateRepository stateRepo) {
        this(worldRepo, charRepo, triggerRepo, stateRepo, new RpgTechniqueProperties());
    }

    @Autowired
    public GmContextAssembler(RpgWorldSettingRepository worldRepo,
                               RpgCharacterCardRepository charRepo,
                               RpgTriggerRepository triggerRepo,
                               RpgGameStateRepository stateRepo,
                               RpgTechniqueProperties techniqueProps) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.triggerRepo = triggerRepo;
        this.stateRepo = stateRepo;
        this.techniqueProps = techniqueProps;
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
            // 降级分支同样剔除 {{CUSTOM_BLOCK_FIELDS}} 占位段，避免占位符原文漏进提示词
            return stripCustomBlockPlaceholder(systemPromptTemplate);
        }

        WorldSetting world = worldRepo.findById(gs.getWorldId());
        CharacterCard player = charRepo.findById(gs.getPlayerCharId());
        List<CharacterCard> npcs = charRepo.findByWorldIdAndType(gs.getWorldId(), "npc");
        List<Trigger> triggers = triggerRepo.findByWorldId(gs.getWorldId());

        String prompt = systemPromptTemplate
                .replace("{{WORLD_SETTING}}", formatWorld(world))
                .replace("{{PLAYER_CARD}}", formatCard(player))
                .replace("{{NPC_CARDS}}", npcs.stream().map(this::formatCard).collect(Collectors.joining("\n\n---\n\n")))
                .replace("{{TRIGGER_DEFINITIONS}}", triggers.stream().map(this::formatTrigger).collect(Collectors.joining("\n\n---\n\n")))
                .replace("{{GAME_STATE}}", formatGameState(gs));
        // 自定义状态字段契约段（rpg-hud-custom-blocks D5）：与 state_delta 契约段同位置注入；
        // 无块世界剔除占位段后与现状逐字节一致（零增量）
        return applyCustomBlockContract(prompt, buildCustomBlockContractSection(world));
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
        return assembleIncremental(playerAction, triggerHits, memoryReminder,
                worldRefreshBlock, npcKeyReference, sceneCast, null);
    }

    /**
     * 组装后续轮增量 prompt（七参重载，star-lorebook；委托八参全量重载，{@code worldbookBlock} 传 null）。
     *
     * @param playerAction     玩家行动文本
     * @param triggerHits      触发器命中结果（已通过的触发器列表）
     * @param memoryReminder   是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @param worldRefreshBlock 「世界背景回顾」重注入块（每 N 轮由 {@code GameLoopService} 经
     *                         {@link #buildWorldRefreshBlock(String)} 现算后传入；null 表示本轮不注入）
     * @param npcKeyReference  「现有 NPC 状态 key」参考块（每轮由 {@code GameLoopService} 经
     *                         {@link #buildNpcKeyReference(String)} 现算后传入；null/空白时占位为「（暂无）」）
     * @param sceneCast        「当前在场 NPC」名单块（每轮由 {@code GameLoopService} 经
     *                         {@link #buildSceneCastReference(String)} 现算后传入；null/空白时占位为「（暂无）」）
     * @param starLorebookBlock 「设定集·已揭示」块（每轮由 {@code GameLoopService} 经
     *                         {@link #buildStarLorebookBlock(List)} 现算后传入；null/空白时省略——
     *                         未激活任何条目时不注入占位，与任务指令段的降噪取向一致）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits,
                                     boolean memoryReminder, String worldRefreshBlock,
                                     String npcKeyReference, String sceneCast,
                                     String starLorebookBlock) {
        return assembleIncremental(playerAction, triggerHits, memoryReminder,
                worldRefreshBlock, npcKeyReference, sceneCast, starLorebookBlock, null);
    }

    /**
     * 组装后续轮增量 prompt（八参重载，add-rpg-worldbook；委托九参全量重载，
     * {@code customBlockContract} 传 null）。
     *
     * @param playerAction     玩家行动文本
     * @param triggerHits      触发器命中结果（已通过的触发器列表）
     * @param memoryReminder   是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @param worldRefreshBlock 「世界背景回顾」重注入块（null 表示本轮不注入）
     * @param npcKeyReference  「现有 NPC 状态 key」参考块（null/空白时占位为「（暂无）」）
     * @param sceneCast        「当前在场 NPC」名单块（null/空白时占位为「（暂无）」）
     * @param starLorebookBlock 「设定集·已揭示」块（设定集 sticky；null/空白时省略）
     * @param worldbookBlock   「世界书·关联设定」块（通用世界书瞬时命中；null/空白时省略——
     *                         与 starLorebookBlock 同侧追加，块头不同故可同轮共存）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits,
                                     boolean memoryReminder, String worldRefreshBlock,
                                     String npcKeyReference, String sceneCast,
                                     String starLorebookBlock, String worldbookBlock) {
        return assembleIncremental(playerAction, triggerHits, memoryReminder, worldRefreshBlock,
                npcKeyReference, sceneCast, starLorebookBlock, worldbookBlock, null);
    }

    /**
     * 组装后续轮增量 prompt（九参全量重载，rpg-hud-custom-blocks；委托十参全量重载，
     * {@code storyNodeBlock} 传 null）。
     *
     * @param playerAction     玩家行动文本
     * @param triggerHits      触发器命中结果（已通过的触发器列表）
     * @param memoryReminder   是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @param worldRefreshBlock 「世界背景回顾」重注入块（null 表示本轮不注入）
     * @param npcKeyReference  「现有 NPC 状态 key」参考块（null/空白时占位为「（暂无）」）
     * @param sceneCast        「当前在场 NPC」名单块（null/空白时占位为「（暂无）」）
     * @param starLorebookBlock 「设定集·已揭示」块（设定集 sticky；null/空白时省略）
     * @param worldbookBlock   「世界书·关联设定」块（通用世界书瞬时命中；null/空白时省略——
     *                         与 starLorebookBlock 同侧追加，块头不同故可同轮共存）
     * @param customBlockContract 「本世界自定义状态字段」契约块（每轮由 {@code GameLoopService} 经
     *                            {@link #buildCustomBlockContract(String)} 现算后传入；null/空白时省略——
     *                            首轮 system prompt 不落库，缺本块则第 2 轮起模型无从知晓字段清单）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits,
                                     boolean memoryReminder, String worldRefreshBlock,
                                     String npcKeyReference, String sceneCast,
                                     String starLorebookBlock, String worldbookBlock,
                                     String customBlockContract) {
        return assembleIncremental(playerAction, triggerHits, memoryReminder, worldRefreshBlock,
                npcKeyReference, sceneCast, starLorebookBlock, worldbookBlock,
                customBlockContract, null);
    }

    /**
     * 组装后续轮增量 prompt（十参全量重载，story-graph-runtime 4.3）。
     *
     * @param playerAction     玩家行动文本
     * @param triggerHits      触发器命中结果（已通过的触发器列表）
     * @param memoryReminder   是否在本轮追加一次记忆整理提醒（每 N 轮由 {@code GameLoopService} 置 true）
     * @param worldRefreshBlock 「世界背景回顾」重注入块（null 表示本轮不注入）
     * @param npcKeyReference  「现有 NPC 状态 key」参考块（null/空白时占位为「（暂无）」）
     * @param sceneCast        「当前在场 NPC」名单块（null/空白时占位为「（暂无）」）
     * @param starLorebookBlock 「设定集·已揭示」块（设定集 sticky；null/空白时省略）
     * @param worldbookBlock   「世界书·关联设定」块（通用世界书瞬时命中；null/空白时省略——
     *                         与 starLorebookBlock 同侧追加，块头不同故可同轮共存）
     * @param customBlockContract 「本世界自定义状态字段」契约块（每轮由 {@code GameLoopService} 经
     *                            {@link #buildCustomBlockContract(String)} 现算后传入；null/空白时省略——
     *                            首轮 system prompt 不落库，缺本块则第 2 轮起模型无从知晓字段清单）
     * @param storyNodeBlock   「故事图当前位置（结构指示）」块（story-graph-runtime 4.3，
     *                        {@code StoryNodeMaterializer.buildStoryNodeBlock} 产出；
     *                        null/空白时省略——未挂图/未开图的 world 零增量，
     *                        prompt 与九参重载逐字节一致）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits,
                                     boolean memoryReminder, String worldRefreshBlock,
                                     String npcKeyReference, String sceneCast,
                                     String starLorebookBlock, String worldbookBlock,
                                     String customBlockContract, String storyNodeBlock) {
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
        if (starLorebookBlock != null && !starLorebookBlock.isBlank()) {
            // 「设定集·已揭示」块与【世界背景回顾】同侧（design D4）：追加在模板末尾、
            // ## GM 任务指令 段之后，以 \n\n## 开头自成一段，绝不在 ## 玩家行动 与下一 ## 之间
            prompt += starLorebookBlock;
        }
        if (worldbookBlock != null && !worldbookBlock.isBlank()) {
            // 「世界书·关联设定」块（通用世界书瞬时命中）与已揭示块同侧、同落位约束（design D5）：
            // 追加在模板末尾、## GM 任务指令 段之后，以 \n\n## 开头自成一段，块头不同故可与已揭示块共存
            prompt += worldbookBlock;
        }
        if (customBlockContract != null && !customBlockContract.isBlank()) {
            // 「本世界自定义状态字段」块与既有注入块同侧、同落位约束（rpg-hud-custom-blocks design D5）：
            // 追加在模板末尾、## GM 任务指令 段之后，以 \n\n## 开头自成一段
            prompt += customBlockContract;
        }
        if (storyNodeBlock != null && !storyNodeBlock.isBlank()) {
            // 「故事图当前位置（结构指示）」块（story-graph-runtime 4.3）与既有注入块同侧、同落位约束：
            // 追加在模板末尾、## GM 任务指令 段之后，以 \n\n## 开头自成一段
            // （RpgHistoryCleaner 按「## 玩家行动 到下一个 \n## 」截取玩家原文，本块天然被清洗剔除）
            prompt += storyNodeBlock;
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

    // ==================== 设定集·已揭示（star-lorebook / rpg-lore-archival-loading） ====================

    /**
     * 组装「设定集·已揭示」块（纯函数，不触库；数据由 {@link StarLorebookScanner#scanAndCollect}
     * 现算后传入）。rpg-lore-archival-loading 纯索引档位（design D5/D6）：只渲染<b>本轮新命中</b>，
     * 每条一行「星名·武将名已揭示，档案见 lore_&lt;星名&gt;.md」——命中轮随 user 消息落库在
     * 滑窗内存活约 15 轮（滑窗残留层）；既有激活依赖 MEMORY.md 索引兜底与 GM 主动拉取。
     * 分类分组（add-star-lorebook-category D3）：按传入顺序遍历，category 非空且与前一条不同
     * 时先输出一行【分类】标题行（分类名只出现一次，token 效率）；无分类条目维持平铺。
     * 落位约束见 {@code assembleIncremental} 七参重载：必须追加在 {@code ## GM 任务指令} 段之后
     * （{@code ## 玩家行动} 区间之外），规避 {@link RpgHistoryCleaner} 把名录文本当玩家原话回放
     * （块内新增【分类】行同在块内，按位置段清洗整体剔除，契约不变）。
     *
     * @param entries 本轮新命中的条目（既有已激活不重复注入——旧全量 sticky 渠道已退场）
     * @return 以 {@code \n\n## 设定集·已揭示} 开头的注入块；entries 为空时返回 null（本轮不注入）
     */
    public String buildStarLorebookBlock(List<StarLorebookScanner.RevealedEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            // 本轮无新命中 → 省略块（不报错、不注入占位；既有激活走记忆通道）
            return null;
        }
        String lastCategory = null;
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 设定集·已揭示\n\n");
        sb.append("以下设定集条目本轮首次被提及，档案已归档，细节用 GmMemoryView 读取对应档案：\n");
        for (StarLorebookScanner.RevealedEntry entry : entries) {
            StarIncarnation incarnation = entry.incarnation();
            String fileName = LoreArchiveService.starArchiveFileName(incarnation.getStarName());
            // 分类分组（add-star-lorebook-category D3）：category 非空且与前一条不同 →
            // 先输出一行【分类】（防御空白视为未分类；条目行格式不变）
            String category = incarnation.getCategory() == null || incarnation.getCategory().isBlank()
                    ? null : incarnation.getCategory().trim();
            if (category != null && !category.equals(lastCategory)) {
                sb.append('【').append(category).append("】\n");
            }
            lastCategory = category;
            sb.append("- ").append(incarnation.getStarName());
            if (incarnation.getWarlordName() != null && !incarnation.getWarlordName().isBlank()) {
                sb.append('·').append(incarnation.getWarlordName());
            }
            // claimed 行人物区分提示语（preset-destined-figures D3）：人物局撞星标
            // 【人即玩家】（玩家=武将本人，勿演成失衣武将）；其余 claim 路径（无名新人/
            // 信胜撞星）标【星归玩家】（冥衣归玩家、武将失衣另谋他途）。
            if (entry.claimed()) {
                sb.append(entry.playerIsFigure() ? "【人即玩家】" : "【星归玩家】");
            }
            sb.append("已揭示，档案见 ").append(fileName).append('\n');
        }
        return sb.toString();
    }

    // ==================== 世界书·关联设定（add-rpg-worldbook） ====================

    /**
     * 组装「世界书·关联设定」块（纯函数，不触库；数据由 {@link WorldbookScanner#scan} 现算后传入）。
     * <p>
     * 渲染本轮命中的玩家自定义世界书条目；与设定集的「设定集·已揭示」块<b>块头不同</b>，
     * 二者同轮共存时可区分（design D5）。落位约束见 {@code assembleIncremental} 八参重载：
     * 必须追加在 {@code ## GM 任务指令} 段之后（{@code ## 玩家行动} 区间之外），
     * 规避 {@link RpgHistoryCleaner} 把条目文本当玩家原话回放。
     *
     * @param entries 本轮命中的启用条目（按仓储排序）
     * @return 以 {@code \n\n## 世界书·关联设定} 开头的注入块；entries 为空时返回 null（本轮不注入）
     */
    public String buildWorldbookBlock(List<WorldbookEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            // 未命中任何条目 → 省略块（不报错、不注入占位）
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 世界书·关联设定\n\n");
        sb.append("以下为玩家自定义世界设定，本轮被提及，供叙事参考（保持设定一致，勿凭空改写）：\n");
        for (WorldbookEntry entry : entries) {
            sb.append("- ").append(entry.getTitle());
            if (entry.getContent() != null && !entry.getContent().isBlank()) {
                sb.append('：').append(entry.getContent());
            }
            sb.append('\n');
        }
        return sb.toString();
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

    // ==================== 自定义状态字段契约（rpg-hud-custom-blocks） ====================

    /**
     * 组装增量轮「本世界自定义状态字段」每轮反哺块（查库版）。
     * <p>
     * 动机：字段清单只渲染进首轮 system prompt（SystemMessage 不落库），第 2 轮起上下文里
     * 没有契约，模型无从输出自定义字段——HUD 块会在首轮后消失。本块随增量 prompt 每轮
     * 注入（体积仅为字段清单，不随状态膨胀），照 {@code worldRefreshBlock} 先例由
     * {@code GameLoopService} 现算传入 {@code assembleIncremental} 九参重载。
     *
     * @param gameStateId 游戏状态 ID
     * @return 以 {@code \n\n## 本世界自定义状态字段} 开头的注入块；无有效块时为 null（本轮不注入）
     */
    public String buildCustomBlockContract(String gameStateId) {
        if (stateRepo == null) {
            return null;
        }
        GameState gs = stateRepo.findById(gameStateId);
        WorldSetting world = gs == null ? null : worldRepo.findById(gs.getWorldId());
        List<String> fields = collectCustomBlockFields(world);
        if (fields.isEmpty()) {
            return null;
        }
        return "\n\n## 本世界自定义状态字段\n\n"
                + "`<state_delta>` 根级继续输出本世界自定义字段（前端渲染为面板内容块；"
                + "值为数组，每项 {\"text\": \"…\", \"done\": true|false}，整体替换，无新变化时省略该键）：\n"
                + String.join("\n", fields);
    }

    /**
     * 首轮 system prompt 的 {@code {{CUSTOM_BLOCK_FIELDS}}} 契约段（纯函数，不触库）：
     * 3 空格缩进列表续行形态，落位在 state_delta 契约说明之后（「4. 状态变更」条目内）。
     *
     * @return 缩进契约段；无有效块时为 null（占位段整体剔除，提示词零增量）
     */
    String buildCustomBlockContractSection(WorldSetting world) {
        List<String> fields = collectCustomBlockFields(world);
        if (fields.isEmpty()) {
            return null;
        }
        return "   本世界自定义状态字段（写入 `<state_delta>` 根级，前端渲染为面板内容块；"
                + "值为数组，每项 {\"text\": \"…\", \"done\": true|false}，整体替换，无新变化时省略该键）：\n"
                + String.join("\n", fields.stream().map(f -> "   " + f).toList());
    }

    /**
     * 契约字段行集（{@code - `field`（title）}）。
     * <p>
     * 校验与前端 {@code applyWorldCustomBlocks}（hud.js）同规则（D4/D7 双层校验的后端侧）：
     * 坏 JSON/非数组/超 {@value #MAX_CUSTOM_BLOCKS} 块整包丢弃，单块结构错/title 超长/field
     * 非安全正则仅丢该块（warn 可观测）——保证提示词引导的字段集与前端渲染的块集一致。
     * accent 是渲染侧关注点（CSS 变量白名单），此处不校验。
     */
    private List<String> collectCustomBlockFields(WorldSetting world) {
        if (world == null || world.getCustomBlocks() == null || world.getCustomBlocks().isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(world.getCustomBlocks());
        } catch (IOException e) {
            log.warn("customBlocks 不是合法 JSON，契约段整包丢弃: {}", e.getMessage());
            return List.of();
        }
        if (!root.isArray() || root.isEmpty()) {
            log.warn("customBlocks 非数组或为空，契约段整包丢弃");
            return List.of();
        }
        if (root.size() > MAX_CUSTOM_BLOCKS) {
            log.warn("customBlocks 超 {} 块上限，契约段整包丢弃: {}", MAX_CUSTOM_BLOCKS, root.size());
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (JsonNode block : root) {
            String title = block.path("title").asText(null);
            String field = block.path("field").asText(null);
            if (!block.isObject()
                    || title == null || title.isBlank() || title.length() > 20
                    || field == null || !field.matches(CUSTOM_BLOCK_FIELD_PATTERN)) {
                log.warn("customBlocks 单块非法（title={}, field={}），契约段丢弃该块", title, field);
                continue;
            }
            fields.add("- `" + field + "`（" + title + "）");
        }
        return fields;
    }

    /**
     * 首轮占位段处理：有块替换 {@code {{CUSTOM_BLOCK_FIELDS}}} 为契约段；
     * 无块把「占位行连同其前后段落空行」整块剔除——提示词与占位符引入前逐字节一致（零增量）。
     */
    private static String applyCustomBlockContract(String prompt, String contractSection) {
        if (contractSection == null) {
            return stripCustomBlockPlaceholder(prompt);
        }
        return prompt.replace("{{CUSTOM_BLOCK_FIELDS}}", contractSection);
    }

    private static String stripCustomBlockPlaceholder(String prompt) {
        // 模板文件为 CRLF 行尾（随编辑器/平台而异），LF/CRLF 两种形态都精确剔除占位段；
        // 末个 replace 兜底抹掉异常行尾形态的裸占位符——绝不让占位符残留漏进提示词
        //（spec：无块世界与占位符引入前逐字节一致）
        return prompt.replace("\n\n{{CUSTOM_BLOCK_FIELDS}}\n\n", "\n\n")
                .replace("\r\n\r\n{{CUSTOM_BLOCK_FIELDS}}\r\n\r\n", "\r\n\r\n")
                .replace("{{CUSTOM_BLOCK_FIELDS}}", "");
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
        String starDestiny = formatStarDestiny(gs.getPlayerStates());
        return String.format("""
                当前轮次：%d
                当前位置：%s
                标记：%s
                NPC状态：%s
                玩家状态：%s
                %s
                """, gs.getTurnCount(), gs.getCurrentLocation(),
                gs.getFlags(), gs.getNpcStates(),
                gs.getPlayerStates() != null ? gs.getPlayerStates() : "{}",
                starDestiny);
    }

    /**
     * 从 playerStates JSON 中提取星命摘要行。
     * <p>
     * 格式：星命：白羊座 | Gold | saint | 热情冲动、勇往直前
     * 若有招式，追加招式名称列表。
     * 无星命数据时返回空字符串。
     * <p>包内可见：纯函数（仅依赖 playerStates 字符串），供单测直接注入验证小宇宙措辞追加与降级。
     */
    String formatStarDestiny(String playerStates) {
        if (playerStates == null || playerStates.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(playerStates);
            String constellation = root.path("constellation").asText("");
            String starRank = root.path("star_rank").asText("");
            String faction = root.path("faction").asText("");
            String traits = root.path("zodiac_traits").asText("");
            if (constellation.isBlank() && starRank.isBlank()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("星命：").append(constellation).append(" | ")
              .append(starRank).append(" | ").append(faction).append(" | ").append(traits);
            // 小宇宙行（rpg-cosmo-sense D5）：仅当存档有数值 cosmo 键时追加，措辞取自 CosmoSense 单一真源
            JsonNode cosmoNode = root.path("cosmo");
            if (cosmoNode.isNumber()) {
                sb.append(" | 小宇宙 ").append(CosmoSense.render(cosmoNode.asInt()));
            }
            // 命定身份行（preset-destined-figures D2）：仅人物模式存档有 figure 键时追加——
            // GM 感知「玩家是谁」（如「命定身份：织田信长——弘治二年二十二岁……」）；
            // 无名新人/旧档不含该键，星命行零改动。
            if (!root.path("figure_key").asText("").isBlank()) {
                sb.append("\n命定身份：").append(root.path("figure_display_name").asText(""));
                String figureEraNote = root.path("figure_era_note").asText("");
                if (!figureEraNote.isBlank()) {
                    sb.append("——").append(figureEraNote);
                }
            }
            // 招式树三段压缩渲染（rpg-technique-tree-data D7）：已会 / 可悟 / 封印，替换旧版招式名平铺
            List<TechniqueState> techStates = TechniqueStateReader.read(root);
            if (!techStates.isEmpty()) {
                sb.append("\n").append(renderTechniqueBlock(constellation, techStates));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 招式树三段压缩渲染（D7）。
     * <p>
     * 「已会」带数值/档位（措辞取 {@link TechniqueProficiency} 单一真源）；「可悟」= 未学且全部前置
     * learned=true，附全前置条件文案；「封印」= 前置仍有未学者，附未满足前置条件。
     * 招式总数 &gt; 6 时「封印」段降级为只列名字不列条件（token 控制）。
     */
    private String renderTechniqueBlock(String constellation, List<TechniqueState> states) {
        int threshold = techniqueProps != null
                ? techniqueProps.getDefaultUnlockThreshold()
                : RpgTechniqueProperties.DEFAULT_UNLOCK_THRESHOLD;
        Map<String, TechniqueState> byName = new HashMap<>();
        states.forEach(t -> byName.put(t.name(), t));
        List<TechniqueState> learned = new ArrayList<>();
        List<TechniqueState> unlockable = new ArrayList<>();
        List<TechniqueState> sealed = new ArrayList<>();
        for (TechniqueState t : states) {
            if (t.learned()) {
                learned.add(t);
            } else if (t.prerequisites().stream().allMatch(p -> {
                TechniqueState ps = byName.get(p);
                return ps != null && ps.learned();
            })) {
                unlockable.add(t);
            } else {
                sealed.add(t);
            }
        }
        StringBuilder sb = new StringBuilder("招式（").append(constellation).append("）：");
        if (!learned.isEmpty()) {
            sb.append("\n  已会：").append(learned.stream()
                    .map(t -> t.name() + " " + TechniqueProficiency.render(t.proficiency()))
                    .collect(Collectors.joining("、")));
        }
        if (!unlockable.isEmpty()) {
            sb.append("\n  可悟：").append(unlockable.stream()
                    .map(t -> t.name() + prereqCondition(t, threshold, null))
                    .collect(Collectors.joining("、")));
        }
        if (!sealed.isEmpty()) {
            sb.append("\n  封印：");
            if (states.size() > 6) {
                sb.append(sealed.stream().map(TechniqueState::name).collect(Collectors.joining("、")));
            } else {
                sb.append(sealed.stream()
                        .map(t -> t.name() + prereqCondition(t, threshold, byName))
                        .collect(Collectors.joining("、")));
            }
        }
        return sb.toString();
    }

    /**
     * 前置条件文案「（需 A≥400 且 B≥400）」；无前置返回空串。
     *
     * @param byName null = 可悟段，列全部前置；非 null = 封印段，只列未满足前置（未学或熟练度未达标）
     */
    private String prereqCondition(TechniqueState tech, int threshold, Map<String, TechniqueState> byName) {
        List<String> prereqs = tech.prerequisites();
        if (byName != null) {
            prereqs = prereqs.stream().filter(p -> {
                TechniqueState ps = byName.get(p);
                return ps == null || !ps.learned() || ps.proficiency() < threshold;
            }).toList();
        }
        if (prereqs.isEmpty()) {
            return "";
        }
        return "（需 " + prereqs.stream().map(p -> p + "≥" + threshold)
                .collect(Collectors.joining(" 且 ")) + "）";
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
