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

    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgTriggerRepository triggerRepo;
    private final RpgGameStateRepository stateRepo;

    private final String systemPromptTemplate;
    private final String incrementalPromptTemplate;

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
     * 组装后续轮增量 prompt。
     *
     * @param playerAction 玩家行动文本
     * @param triggerHits  触发器命中结果（已通过的触发器列表）
     * @return 增量注入的 user prompt
     */
    public String assembleIncremental(String playerAction, List<Trigger> triggerHits) {
        String triggerText = triggerHits == null || triggerHits.isEmpty()
                ? "本轮无触发器命中。"
                : triggerHits.stream()
                        .map(t -> String.format("- 触发器[%s]: NPC=%s, 行动=%s",
                                t.getId(), t.getNpcId(), t.getAction()))
                        .collect(Collectors.joining("\n"));

        return incrementalPromptTemplate
                .replace("{{PLAYER_ACTION}}", playerAction)
                .replace("{{TRIGGER_HITS}}", triggerText);
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
