package com.luyu.agent.rpg.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * GM 只读工具集（调和 C：全部只读无副作用）。
 * <p>
 * 11 个 {@code get_} 方法供 GM 在叙事中查询世界状态，不执行任何 INSERT/UPDATE/DELETE。
 * 通过 {@link ToolContext} 传入 {@code gameStateId}，标识当前游戏会话。
 */
@Component
public class RpgGmTools {

    private static final Logger log = LoggerFactory.getLogger(RpgGmTools.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgLocationRepository locationRepo;
    private final RpgRelationshipRepository relRepo;
    private final RpgTriggerRepository triggerRepo;
    private final RpgGameStateRepository stateRepo;
    private final RpgTriggerRuntimeRepository runtimeRepo;
    private final RpgEventLogRepository eventRepo;

    public RpgGmTools(RpgWorldSettingRepository worldRepo,
                      RpgCharacterCardRepository charRepo,
                      RpgLocationRepository locationRepo,
                      RpgRelationshipRepository relRepo,
                      RpgTriggerRepository triggerRepo,
                      RpgGameStateRepository stateRepo,
                      RpgTriggerRuntimeRepository runtimeRepo,
                      RpgEventLogRepository eventRepo) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.locationRepo = locationRepo;
        this.relRepo = relRepo;
        this.triggerRepo = triggerRepo;
        this.stateRepo = stateRepo;
        this.runtimeRepo = runtimeRepo;
        this.eventRepo = eventRepo;
    }

    // ==================== 世界级工具 ====================

    @Tool(description = "查询当前游戏的世界观设定，返回名称、描述、时代、规则、地点和氛围。")
    public String get_world_setting(ToolContext toolContext) {
        GameState gs = resolveGameState(toolContext);
        if (gs == null) return error("游戏状态未找到");
        WorldSetting world = worldRepo.findById(gs.getWorldId());
        if (world == null) return error("世界观未找到");
        return toJson(world);
    }

    @Tool(description = "查询当前游戏状态快照，返回当前轮次、位置、标记(flags)和 NPC 状态。")
    public String get_game_state(ToolContext toolContext) {
        GameState gs = resolveGameState(toolContext);
        if (gs == null) return error("游戏状态未找到");
        return toJson(gs);
    }

    // ==================== 角色级工具 ====================

    @Tool(description = "查询玩家角色状态，返回玩家角色卡和当前游戏状态中的玩家信息。")
    public String get_player_state(ToolContext toolContext) {
        GameState gs = resolveGameState(toolContext);
        if (gs == null) return error("游戏状态未找到");
        CharacterCard player = charRepo.findById(gs.getPlayerCharId());
        if (player == null) return error("玩家角色未找到");
        return toJson(player);
    }

    @Tool(description = "查询指定 NPC 的状态，返回 NPC 角色卡信息和游戏状态中的动态状态。")
    public String get_npc_state(
            @ToolParam(description = "NPC 角色 ID") String npcId,
            ToolContext toolContext) {
        CharacterCard npc = charRepo.findById(npcId);
        if (npc == null) return error("NPC 未找到: " + npcId);
        return toJson(npc);
    }

    @Tool(description = "查询指定地点的所有 NPC 列表及其当前状态。")
    public String get_npc_list(
            @ToolParam(description = "地点 ID 或名称") String locationId,
            ToolContext toolContext) {
        Location loc = locationRepo.findById(locationId);
        if (loc == null) {
            // 尝试按名称查询
            GameState gs = resolveGameState(toolContext);
            if (gs != null) {
                List<Location> locs = locationRepo.findByWorldId(gs.getWorldId());
                loc = locs.stream()
                        .filter(l -> l.getName().equals(locationId))
                        .findFirst().orElse(null);
            }
        }
        if (loc == null) return error("地点未找到: " + locationId);
        return toJson(loc);
    }

    @Tool(description = "查询角色卡完整信息，包含身份、性格、背景、动机、说话风格和知识。")
    public String get_character_card(
            @ToolParam(description = "角色 ID") String charId) {
        CharacterCard card = charRepo.findById(charId);
        if (card == null) return error("角色未找到: " + charId);
        return toJson(card);
    }

    @Tool(description = "查询指定角色的所有关系，返回态度(attitude)、信任(trust)和备注。")
    public String get_relationships(
            @ToolParam(description = "角色 ID") String charId) {
        List<Relationship> rels = relRepo.findByCharId(charId);
        return toJson(rels);
    }

    // ==================== 触发器级工具 ====================

    @Tool(description = "查询当前活跃的触发器列表（硬条件已满足、冷却已过的触发器）。")
    public String get_active_triggers(ToolContext toolContext) {
        GameState gs = resolveGameState(toolContext);
        if (gs == null) return error("游戏状态未找到");
        List<TriggerRuntime> runtimes = runtimeRepo.findActiveByGameStateId(gs.getId());
        return toJson(runtimes);
    }

    @Tool(description = "查询指定 NPC 的触发器历史，返回该 NPC 关联的触发器定义。")
    public String get_trigger_history(
            @ToolParam(description = "NPC 角色 ID") String npcId) {
        List<Trigger> triggers = triggerRepo.findByNpcId(npcId);
        return toJson(triggers);
    }

    // ==================== 事件级工具 ====================

    @Tool(description = "查询最近 N 轮的事件日志，包含玩家行动、NPC 行为和状态变更。")
    public String get_recent_events(
            @ToolParam(description = "查询的事件数量，默认 5") Integer count,
            ToolContext toolContext) {
        GameState gs = resolveGameState(toolContext);
        if (gs == null) return error("游戏状态未找到");
        int n = count != null && count > 0 ? count : 5;
        List<EventLog> events = eventRepo.findRecentByGameStateId(gs.getId(), n);
        return toJson(events);
    }

    @Tool(description = "查询指定地点的详细信息，包含描述、NPC 列表和可用交互。")
    public String get_location_info(
            @ToolParam(description = "地点 ID 或名称") String locationId,
            ToolContext toolContext) {
        Location loc = locationRepo.findById(locationId);
        if (loc == null) {
            GameState gs = resolveGameState(toolContext);
            if (gs != null) {
                List<Location> locs = locationRepo.findByWorldId(gs.getWorldId());
                loc = locs.stream()
                        .filter(l -> l.getName().equals(locationId))
                        .findFirst().orElse(null);
            }
        }
        if (loc == null) return error("地点未找到: " + locationId);
        return toJson(loc);
    }

    // ==================== 辅助方法 ====================

    private GameState resolveGameState(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object id = toolContext.getContext().get("gameStateId");
        if (id == null) {
            return null;
        }
        return stateRepo.findById(id.toString());
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("GM 工具序列化失败: {}", e.getMessage());
            return "{\"error\":\"序列化失败\"}";
        }
    }

    private String error(String msg) {
        return "{\"error\":\"" + msg + "\"}";
    }
}
