package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
     * GM 记忆规范提示词。由 {@link RpgMemoryPromptAdvisor} <b>每轮</b>注入 system 消息，
     * 而不是拼进首轮 system prompt（{@code RpgGameController} 只在第 1 轮调 {@code .system(...)}，
     * 且 {@code SessionMemoryAdvisor} 从不持久化 SystemMessage，第 2 轮起上下文里压根没有它），
     * 也不是塞进增量模板（那是 user 消息，会被每轮持久化，maxEvents=30 的窗口内最多攒 30 份副本）。
     */
    private static final String MEMORY_PROMPT_PATH = "rpg/gm-memory-prompt.md";

    /**
     * 每 N 轮插入一次的记忆整理提醒。
     * <p>
     * 追加在增量模板末尾，即 {@code ## GM 任务指令} 段内 —— 刻意避开 {@code ## 玩家行动} 段：
     * {@link RpgHistoryCleaner} 按「{@code ## 玩家行动} 到下一个 {@code \n## }」截取玩家原文，
     * 提醒文本落在该区间内会被当成玩家说的话回放给前端。
     */
    private static final String MEMORY_REMINDER =
            "\n\n（整理笔记：本轮是否有值得 NPC 长期记住的事、新埋的伏笔或即兴生成的世界细节？"
                    + "若有，用 GmMemory* 工具写进笔记本。）";

    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgTriggerRepository triggerRepo;
    private final RpgGameStateRepository stateRepo;

    private final String systemPromptTemplate;
    private final String incrementalPromptTemplate;
    private final String memoryPromptTemplate;

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
        String triggerText = triggerHits == null || triggerHits.isEmpty()
                ? "本轮无触发器命中。"
                : triggerHits.stream()
                        .map(t -> String.format("- 触发器[%s]: NPC=%s, 行动=%s",
                                t.getId(), t.getNpcId(), t.getAction()))
                        .collect(Collectors.joining("\n"));

        String prompt = incrementalPromptTemplate
                .replace("{{PLAYER_ACTION}}", playerAction)
                .replace("{{TRIGGER_HITS}}", triggerText);

        return memoryReminder ? prompt + MEMORY_REMINDER : prompt;
    }

    // ==================== 格式化辅助方法 ====================

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
                名称：%s（%s）
                身份：%s
                性格：%s
                背景：%s
                动机：%s
                说话风格：%s
                知识：%s
                """, card.getName(), card.getType(), card.getIdentity(),
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
