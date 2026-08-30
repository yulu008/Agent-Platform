package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 工坊服务。
 * <p>
 * 实现世界观/角色卡/地点/关系/触发器的 CRUD 操作（调用 Repository 层）。
 * 提供"开始冒险"入口，创建 GameState 并初始化触发器运行时记录。
 */
@Service
public class WorkshopService {

    private static final Logger log = LoggerFactory.getLogger(WorkshopService.class);

    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgLocationRepository locationRepo;
    private final RpgRelationshipRepository relRepo;
    private final RpgTriggerRepository triggerRepo;
    private final RpgGameStateRepository stateRepo;
    private final RpgTriggerRuntimeRepository runtimeRepo;

    public WorkshopService(RpgWorldSettingRepository worldRepo,
                           RpgCharacterCardRepository charRepo,
                           RpgLocationRepository locationRepo,
                           RpgRelationshipRepository relRepo,
                           RpgTriggerRepository triggerRepo,
                           RpgGameStateRepository stateRepo,
                           RpgTriggerRuntimeRepository runtimeRepo) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.locationRepo = locationRepo;
        this.relRepo = relRepo;
        this.triggerRepo = triggerRepo;
        this.stateRepo = stateRepo;
        this.runtimeRepo = runtimeRepo;
    }

    // ==================== 世界观 CRUD ====================

    public WorldSetting saveWorld(WorldSetting world) {
        if (world.getId() == null || world.getId().isBlank()) {
            world.setId(UUID.randomUUID().toString());
        }
        worldRepo.save(world);
        log.info("世界观已保存: id={}, name={}", world.getId(), world.getName());
        return world;
    }

    public WorldSetting getWorld(String id) {
        return worldRepo.findById(id);
    }

    public List<WorldSetting> listWorlds() {
        return worldRepo.findAll();
    }

    public void updateOpeningTemplate(String worldId, String template) {
        worldRepo.updateOpeningTemplate(worldId, template);
    }

    // ==================== 角色卡 CRUD ====================

    public CharacterCard saveCharacter(CharacterCard card) {
        if (card.getId() == null || card.getId().isBlank()) {
            card.setId(UUID.randomUUID().toString());
        }
        charRepo.save(card);
        log.info("角色卡已保存: id={}, name={}, type={}", card.getId(), card.getName(), card.getType());
        return card;
    }

    public CharacterCard getCharacter(String id) {
        return charRepo.findById(id);
    }

    public List<CharacterCard> listNpcs(String worldId) {
        return charRepo.findByWorldIdAndType(worldId, "npc");
    }

    public List<CharacterCard> listAllCharacters(String worldId) {
        return charRepo.findByWorldId(worldId);
    }

    // ==================== 地点 CRUD ====================

    public Location saveLocation(Location location) {
        if (location.getId() == null || location.getId().isBlank()) {
            location.setId(UUID.randomUUID().toString());
        }
        locationRepo.save(location);
        log.info("地点已保存: id={}, name={}", location.getId(), location.getName());
        return location;
    }

    public Location getLocation(String id) {
        return locationRepo.findById(id);
    }

    public List<Location> listLocations(String worldId) {
        return locationRepo.findByWorldId(worldId);
    }

    // ==================== 关系 CRUD ====================

    public Relationship saveRelationship(Relationship rel) {
        if (rel.getId() == null || rel.getId().isBlank()) {
            rel.setId(UUID.randomUUID().toString());
        }
        relRepo.save(rel);
        log.info("关系已保存: id={}, {} <-> {}", rel.getId(), rel.getCharAId(), rel.getCharBId());
        return rel;
    }

    public List<Relationship> getRelationships(String charId) {
        return relRepo.findByCharId(charId);
    }

    // ==================== 触发器 CRUD ====================

    public Trigger saveTrigger(Trigger trigger) {
        if (trigger.getId() == null || trigger.getId().isBlank()) {
            trigger.setId(UUID.randomUUID().toString());
        }
        triggerRepo.save(trigger);
        log.info("触发器已保存: id={}, type={}, action={}", trigger.getId(), trigger.getType(), trigger.getAction());
        return trigger;
    }

    public List<Trigger> listTriggers(String worldId) {
        return triggerRepo.findByWorldId(worldId);
    }

    // ==================== 游戏状态初始化 ====================

    /**
     * 创建游戏状态，开始冒险。
     * <p>
     * 根据世界观 ID 和玩家角色 ID 创建 GameState，
     * 并为所有触发器初始化运行时状态。
     *
     * @param worldId      世界观 ID
     * @param playerCharId 玩家角色卡 ID
     * @param sessionId    会话 ID
     * @return 创建的 GameState
     */
    public GameState startGame(String worldId, String playerCharId, String sessionId) {
        GameState gs = new GameState();
        gs.setId(UUID.randomUUID().toString());
        gs.setSessionId(sessionId);
        gs.setWorldId(worldId);
        gs.setPlayerCharId(playerCharId);
        gs.setTurnCount(0);
        gs.setFlags("{}");
        gs.setNpcStates("{}");

        stateRepo.save(gs);

        // 初始化触发器运行时
        List<Trigger> triggers = triggerRepo.findByWorldId(worldId);
        for (Trigger trigger : triggers) {
            TriggerRuntime rt = new TriggerRuntime();
            rt.setId(UUID.randomUUID().toString());
            rt.setGameStateId(gs.getId());
            rt.setTriggerId(trigger.getId());
            rt.setLastTriggeredTurn(0);
            rt.setIsActive(true);
            runtimeRepo.save(rt);
        }

        log.info("游戏状态已创建: gameStateId={}, worldId={}, triggers={}",
                gs.getId(), worldId, triggers.size());
        return gs;
    }

    public GameState getGameState(String gameStateId) {
        return stateRepo.findById(gameStateId);
    }

    public void updateGameSession(String gameStateId, String sessionId) {
        stateRepo.updateSessionId(gameStateId, sessionId);
    }

    public List<GameState> listGameStates() {
        return stateRepo.findAll();
    }
}
