package com.luyu.agent.rpg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.*;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private final RpgStarIncarnationRepository starIncarnationRepo;
    private final RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private final WorkshopLlmGenerator llmGenerator;
    private final CharacterOriginService originService;
    private final WorldCoverStorageService coverStorage;
    private final RpgWorldbookEntryRepository worldbookRepo;
    private final TransactionTemplate txTemplate;
    private final LorebookSetService lorebookSetService;
    private final LoreArchiveService loreArchiveService;
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;

    public WorkshopService(RpgWorldSettingRepository worldRepo,
                           RpgCharacterCardRepository charRepo,
                           RpgLocationRepository locationRepo,
                           RpgRelationshipRepository relRepo,
                           RpgTriggerRepository triggerRepo,
                           RpgGameStateRepository stateRepo,
                           RpgTriggerRuntimeRepository runtimeRepo,
                           RpgStarIncarnationRepository starIncarnationRepo,
                           RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                           WorkshopLlmGenerator llmGenerator,
                           CharacterOriginService originService,
                           WorldCoverStorageService coverStorage,
                           PlatformTransactionManager transactionManager,
                           RpgWorldbookEntryRepository worldbookRepo,
                           LorebookSetService lorebookSetService,
                           LoreArchiveService loreArchiveService,
                           SessionService sessionService,
                           SessionRepository sessionRepository) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.locationRepo = locationRepo;
        this.relRepo = relRepo;
        this.triggerRepo = triggerRepo;
        this.stateRepo = stateRepo;
        this.runtimeRepo = runtimeRepo;
        this.starIncarnationRepo = starIncarnationRepo;
        this.starRuntimeRepo = starRuntimeRepo;
        this.llmGenerator = llmGenerator;
        this.originService = originService;
        this.coverStorage = coverStorage;
        this.worldbookRepo = worldbookRepo;
        // 用 TransactionTemplate 而非 @Transactional：localizeNames 先做 LLM 调用与校验（耗时、失败无需回滚），
        // 只把四张表的 DB 重写包进事务；若给同类的私有重写方法标 @Transactional，
        // 自调用会绕过代理而静默失效。
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.lorebookSetService = lorebookSetService;
        this.loreArchiveService = loreArchiveService;
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
    }

    // ==================== 世界观 CRUD ====================

    public WorldSetting saveWorld(WorldSetting world) {
        boolean isNew = world.getId() == null || world.getId().isBlank();
        if (isNew) {
            world.setId(UUID.randomUUID().toString());
        }
        if (!isNew && worldRepo.findById(world.getId()) != null) {
            worldRepo.update(world);
        } else {
            worldRepo.save(world);
        }
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

    /**
     * 条件级联软删除世界观（workshop-soft-delete-cascade D3）：
     * 世界下有存档 → 409 阻塞（提示先删存档）；无存档 → 单事务内级联软删
     * 世界、全部角色、地点、关系、触发器与冥星模板行。
     * 通用世界书模板行与封面文件不参与级联、原样保留（软删是数据安全网）。
     *
     * @throws IllegalArgumentException 世界不存在
     * @throws IllegalStateException    世界下仍有存档，拒绝删除
     */
    public void deleteWorld(String id) {
        WorldSetting world = worldRepo.findById(id);
        if (world == null) {
            throw new IllegalArgumentException("世界不存在: " + id);
        }
        if (stateRepo.countByWorldId(id) > 0) {
            throw new IllegalStateException("该世界下仍有存档，请先删除存档");
        }
        txTemplate.executeWithoutResult(status -> {
            // 关系表无 world_id 列：经该世界每个角色双向软删（另一端在他世界的行随端点失效一并软删）
            for (CharacterCard card : charRepo.findByWorldId(id)) {
                relRepo.softDeleteByCharId(card.getId());
            }
            charRepo.softDeleteByWorldId(id);
            locationRepo.softDeleteByWorldId(id);
            triggerRepo.softDeleteByWorldId(id);
            starIncarnationRepo.softDeleteTemplatesByWorldId(id);
            worldRepo.softDeleteById(id);
        });
        // 封面文件刻意保留（design D3）：物理行与资产都不动，对齐软删安全网定位
        log.info("世界观已级联软删除: id={}, name={}", id, world.getName());
    }

    // ==================== 角色卡 CRUD ====================

    public CharacterCard saveCharacter(CharacterCard card) {
        boolean isNew = card.getId() == null || card.getId().isBlank();
        if (isNew) {
            card.setId(UUID.randomUUID().toString());
        }
        CharacterCard existing = isNew ? null : charRepo.findById(card.getId());
        // 类型不在创建表单中声明：新建兜底 npc；编辑时表单不传 type，保留库内现值
        // （避免把已被开始冒险升级为 player 的角色降级）。
        if (card.getType() == null || card.getType().isBlank()) {
            card.setType(existing != null && existing.getType() != null ? existing.getType() : "npc");
        }
        // knowledge 不在表单中编辑：传入为空时保留库内现值，避免编辑时误清空
        if (existing != null && (card.getKnowledge() == null || card.getKnowledge().isBlank()
                || "[]".equals(card.getKnowledge().trim()))) {
            card.setKnowledge(existing.getKnowledge());
        }
        if (existing != null) {
            charRepo.update(card);
        } else {
            charRepo.save(card);
        }
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

    /**
     * 条件级联软删除角色（workshop-soft-delete-cascade D4）：
     * 被存档引用为玩家 → 409；否则级联软删其两端关系与 npc_id 指向它的全部触发器。
     * 被关系/触发器引用不构成拒绝理由（推翻 workshop-form-delete 阻塞式）；
     * NPC 侧存档 npc_states 悬空 key 走兜底渲染，无需迁移。
     *
     * @throws IllegalArgumentException 角色不存在
     * @throws IllegalStateException    该角色是某存档的玩家角色，拒绝删除
     */
    public void deleteCharacter(String id) {
        if (charRepo.findById(id) == null) {
            throw new IllegalArgumentException("角色不存在: " + id);
        }
        if (stateRepo.countByPlayerCharId(id) > 0) {
            throw new IllegalStateException("该角色是某存档的玩家角色，请先删除对应存档");
        }
        txTemplate.executeWithoutResult(status -> {
            relRepo.softDeleteByCharId(id);
            triggerRepo.softDeleteByNpcId(id);
            charRepo.softDeleteById(id);
        });
        log.info("角色卡已级联软删除: id={}", id);
    }

    // ==================== 地点 CRUD ====================

    public Location saveLocation(Location location) {
        boolean isNew = location.getId() == null || location.getId().isBlank();
        if (isNew) {
            location.setId(UUID.randomUUID().toString());
        }
        if (!isNew && locationRepo.findById(location.getId()) != null) {
            locationRepo.update(location.getId(), location.getName(), location.getDescription(),
                    location.getNpcIds(), location.getTriggerIds());
        } else {
            locationRepo.save(location);
        }
        log.info("地点已保存: id={}, name={}", location.getId(), location.getName());
        return location;
    }

    public Location getLocation(String id) {
        return locationRepo.findById(id);
    }

    public List<Location> listLocations(String worldId) {
        return locationRepo.findByWorldId(worldId);
    }

    /**
     * 无条件软删除地点（workshop-soft-delete-cascade D5：三件套无对话挂靠，不设阻塞）。
     *
     * @return true = 删除成功；false = 不存在或已删（供控制层判 404）
     */
    public boolean deleteLocation(String id) {
        boolean deleted = locationRepo.softDeleteById(id) > 0;
        if (deleted) {
            log.info("地点已软删除: id={}", id);
        }
        return deleted;
    }

    // ==================== 关系 CRUD ====================

    /**
     * 保存关系（workshop-soft-delete-cascade D6 复活语义）：
     * 含 id 且未软删 → 常规编辑；同对角色（任一方向）存在软删行 → 复活原行
     * （清 deleted_at、覆写字段、复用其 id），绕开 uk_relationship 唯一约束，
     * 对齐共享包"撤回后重发布复活"惯例；否则新建。
     */
    public Relationship saveRelationship(Relationship rel) {
        boolean isNew = rel.getId() == null || rel.getId().isBlank();
        if (isNew) {
            rel.setId(UUID.randomUUID().toString());
        }
        if (!isNew && relRepo.findById(rel.getId()) != null) {
            relRepo.update(rel);
        } else {
            Relationship softDeleted = relRepo.findSoftDeletedByPair(rel.getCharAId(), rel.getCharBId());
            if (softDeleted != null) {
                rel.setId(softDeleted.getId());
                relRepo.revive(rel);
            } else {
                relRepo.save(rel);
            }
        }
        log.info("关系已保存: id={}, {} <-> {}", rel.getId(), rel.getCharAId(), rel.getCharBId());
        return rel;
    }

    public List<Relationship> getRelationships(String charId) {
        return relRepo.findByCharId(charId);
    }

    /**
     * 某世界的全部关系（关系表无 world_id 列，经该世界角色双向收集后按 id 去重；
     * 三件套列表用，前端富化两端角色名）。
     */
    public List<Relationship> listRelationshipsByWorld(String worldId) {
        Map<String, Relationship> byId = new LinkedHashMap<>();
        for (CharacterCard card : charRepo.findByWorldId(worldId)) {
            for (Relationship rel : relRepo.findByCharId(card.getId())) {
                byId.putIfAbsent(rel.getId(), rel);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 无条件软删除关系（D5：三件套无对话挂靠，不设阻塞）。
     *
     * @return true = 删除成功；false = 不存在或已删（供控制层判 404）
     */
    public boolean deleteRelationship(String id) {
        boolean deleted = relRepo.softDeleteById(id) > 0;
        if (deleted) {
            log.info("关系已软删除: id={}", id);
        }
        return deleted;
    }

    // ==================== 触发器 CRUD ====================

    public Trigger saveTrigger(Trigger trigger) {
        // 前端留空的 npcId 以空串提交，空串不是合法角色 id（FK 允许 NULL），归一化后落库
        if (trigger.getNpcId() != null && trigger.getNpcId().isBlank()) {
            trigger.setNpcId(null);
        }
        boolean isNew = trigger.getId() == null || trigger.getId().isBlank();
        if (isNew) {
            trigger.setId(UUID.randomUUID().toString());
        }
        if (!isNew && triggerRepo.findById(trigger.getId()) != null) {
            triggerRepo.update(trigger);
        } else {
            triggerRepo.save(trigger);
        }
        log.info("触发器已保存: id={}, type={}, action={}", trigger.getId(), trigger.getType(), trigger.getAction());
        return trigger;
    }

    public List<Trigger> listTriggers(String worldId) {
        return triggerRepo.findByWorldId(worldId);
    }

    /**
     * 无条件软删除触发器（D5）。软删后触发引擎扫描（findByWorldId 已过滤）
     * 即时排除，进行中存档本局不再触发；runtime 行不动（随存档生命周期）。
     *
     * @return true = 删除成功；false = 不存在或已删（供控制层判 404）
     */
    public boolean deleteTrigger(String id) {
        boolean deleted = triggerRepo.softDeleteById(id) > 0;
        if (deleted) {
            log.info("触发器已软删除: id={}", id);
        }
        return deleted;
    }

    // ==================== 世界书条目 CRUD ====================

    /**
     * 保存世界书条目（含 id 且库内存在则 update，否则新建 UUID 后 insert）。
     * <p>
     * enabled/sortOrder 缺省补齐（新建默认启用、排序 0）；worldId 由前端随选中世界带入。
     */
    public WorldbookEntry saveWorldbookEntry(WorldbookEntry entry) {
        boolean isNew = entry.getId() == null || entry.getId().isBlank();
        if (isNew) {
            entry.setId(UUID.randomUUID().toString());
        }
        if (entry.getEnabled() == null) {
            entry.setEnabled(Boolean.TRUE);
        }
        if (entry.getSortOrder() == null) {
            entry.setSortOrder(0);
        }
        if (!isNew && worldbookRepo.findById(entry.getId()) != null) {
            worldbookRepo.update(entry);
        } else {
            worldbookRepo.save(entry);
        }
        log.info("世界书条目已保存: id={}, worldId={}, title={}",
                entry.getId(), entry.getWorldId(), entry.getTitle());
        return entry;
    }

    /** 列某世界的全部世界书条目（含停用，供工坊编辑）；无条目时为空列表 */
    public List<WorldbookEntry> listWorldbookEntries(String worldId) {
        return worldbookRepo.findByWorldId(worldId);
    }

    /**
     * 软删除世界书模板行（workshop-soft-delete-cascade D1：工坊删除统一软删基线；
     * 限模板层 set_id IS NULL，set 层游戏内删除维持硬删路径不受影响）。
     *
     * @return true = 删除成功；false = 条目不存在/set 行/已删（供控制层判 404）
     */
    public boolean deleteWorldbookEntry(String id) {
        boolean deleted = worldbookRepo.softDeleteTemplateById(id) > 0;
        if (deleted) {
            log.info("世界书条目已软删除: id={}", id);
        }
        return deleted;
    }

    // ==================== 冥星模板（工坊冥星轨） ====================

    /**
     * 保存冥星模板行（workshop-soft-delete-cascade D8：非预设世界的冥星轨 CRUD）。
     * <p>
     * 预设世界（preset- 前缀）的设定集由 md 物化维护，工坊写操作拒绝（400）。
     * 条目定位一律限模板层（set_id IS NULL）：set 行是存档快照，绝不触碰。
     *
     * @throws IllegalArgumentException 世界不存在或目标世界为预设世界
     */
    public StarIncarnation saveStarIncarnation(StarIncarnation incarnation) {
        WorldSetting world = worldRepo.findById(incarnation.getWorldId());
        if (world == null) {
            throw new IllegalArgumentException("世界不存在: " + incarnation.getWorldId());
        }
        if (world.getId().startsWith(PresetWorldService.WORLD_ID_PREFIX)) {
            throw new IllegalArgumentException("预设世界的设定集由 md 物化维护，不可在工坊编辑");
        }
        boolean isNew = incarnation.getId() == null || incarnation.getId().isBlank();
        if (isNew) {
            incarnation.setId(UUID.randomUUID().toString());
        }
        // 模板层锚定：工坊保存绝不写 set 行；缺省补齐（新建默认可推演、排序 0）
        incarnation.setSetId(null);
        if (incarnation.getRollable() == null) {
            incarnation.setRollable(Boolean.TRUE);
        }
        if (incarnation.getSortOrder() == null) {
            incarnation.setSortOrder(0);
        }
        // 分类规范化（add-star-lorebook-category D5）：trim/空白置 NULL/超长抛出→控制层 400
        incarnation.normalizeCategory();
        if (!isNew && starIncarnationRepo.findTemplateById(incarnation.getId()) != null) {
            starIncarnationRepo.updateTemplate(incarnation);
        } else {
            starIncarnationRepo.save(incarnation);
        }
        log.info("冥星模板已保存: id={}, worldId={}, star={}, warlord={}",
                incarnation.getId(), incarnation.getWorldId(), incarnation.getStarName(), incarnation.getWarlordName());
        return incarnation;
    }

    /** 工坊冥星轨列表：某世界的模板行（预设世界同样只读展示，读端点不门控） */
    public List<StarIncarnation> listStarIncarnations(String worldId) {
        return starIncarnationRepo.findByWorldId(worldId);
    }

    /**
     * 软删除冥星模板行（预设世界 400；存档 set 副本快照不受影响）。
     *
     * @return true = 删除成功；false = 模板行不存在（供控制层判 404）
     * @throws IllegalArgumentException 目标行为预设世界的模板行
     */
    public boolean deleteStarIncarnation(String id) {
        StarIncarnation row = starIncarnationRepo.findTemplateById(id);
        if (row == null) {
            return false;
        }
        if (row.getWorldId().startsWith(PresetWorldService.WORLD_ID_PREFIX)) {
            throw new IllegalArgumentException("预设世界的设定集由 md 物化维护，不可在工坊删除");
        }
        boolean deleted = starIncarnationRepo.softDeleteTemplateById(id) > 0;
        if (deleted) {
            log.info("冥星模板已软删除: id={}, worldId={}", id, row.getWorldId());
        }
        return deleted;
    }

    // ==================== 游戏状态初始化 ====================

    /**
     * 创建游戏状态，开始冒险。
     * <p>
     * 根据世界观 ID 和玩家角色 ID 创建 GameState，
     * 并为所有触发器初始化运行时状态。
     * 若提供 originResult，则注入星命数据到 playerStates。
     *
     * @param worldId      世界观 ID
     * @param playerCharId 玩家角色卡 ID
     * @param sessionId    会话 ID
     * @return 创建的 GameState
     */
    public GameState startGame(String worldId, String playerCharId, String sessionId) {
        return startGame(worldId, playerCharId, sessionId, null);
    }

    /**
     * 创建游戏状态，开始冒险（带星命注入）。
     * <p>
     * 若 originResult 非空，将星座/星阶/阵营/特质/属性加成注入 playerStates JSON。
     *
     * @param worldId       世界观 ID
     * @param playerCharId  玩家角色卡 ID
     * @param sessionId     会话 ID
     * @param originResult  星命计算结果（可为 null）
     * @return 创建的 GameState
     */
    public GameState startGame(String worldId, String playerCharId, String sessionId,
                               OriginResult originResult) {
        return startGame(worldId, playerCharId, sessionId, originResult, null);
    }

    /**
     * 开始冒险（add-rpg-lorebook-sets D4）：inheritSetId 缺省 = 世界默认模板；
     * 非空 = 继承指定 set。世界书副本在开局事务内同步复制，
     * 抢星判定随即对新局 set 名录执行。
     */
    public GameState startGame(String worldId, String playerCharId, String sessionId,
                               OriginResult originResult, String inheritSetId) {
        // 验证玩家角色存在且属于该世界；"谁是玩家"在此运行时确定，被选角色升级为 player
        CharacterCard player = charRepo.findById(playerCharId);
        if (player == null || !worldId.equals(player.getWorldId())) {
            throw new IllegalArgumentException("玩家角色不存在或不属于该世界: " + playerCharId);
        }
        charRepo.updateType(playerCharId, "player");

        // RPG 会话打标（separate-rpg-chat-sessions D2）：开局即建壳/补标，壳先于首回合
        // 存在，rpgSessionMemoryAdvisor 后续 appendEvent 直接向已存在会话追加
        tagRpgSession(sessionId);

        GameState gs = new GameState();
        gs.setId(UUID.randomUUID().toString());
        gs.setSessionId(sessionId);
        gs.setWorldId(worldId);
        gs.setPlayerCharId(playerCharId);
        gs.setTurnCount(0);
        gs.setFlags("{}");
        gs.setNpcStates("{}");

        // 星命注入：若有 OriginResult，注入 playerStates JSON
        if (originResult != null) {
            gs.setPlayerStates(buildPlayerStatesJson(originResult));
        } else {
            gs.setPlayerStates("{}");
        }

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

        // 世界书副本（add-rpg-lorebook-sets D4）：开局同步复制模板/源 set 为新局 set，
        // 首轮扫描与抢星判定立刻读到新 set 名录；揭示/抢星 runtime 不继承。
        // 档案物化（rpg-lore-archival-loading D1）：与 set 落库同点快照预设人物为记忆档案；
        // 冥斗士开局传主命星名（物化侧按抢星命中条件写变体段）。
        // figureKey 透传（preset-destined-figures D3）：人物扮演局撞星的变体段/索引标记
        // 切换为「人即玩家」双语义；无名新人/信胜为 null（旧「星归玩家」语义零改动）。
        LorebookSet lorebookSet = lorebookSetService.copyForNewGame(worldId, inheritSetId, gs.getId(),
                claimCandidateStar(originResult), claimFigureKey(originResult));

        // 抢星判定（star-lorebook）：命中的条目写 runtime 行 claimed_by_player=true、
        // activated_turn=0，该行同时是 sticky 起点（抢星条目本局恒亮）；命中同时向
        // MEMORY.md 追加带【星归玩家】标记的索引行（rpg-lore-archival-loading D4：
        // GM 必须知道玩家持有什么星，否则抢星叙事崩坏）。
        claimStarIncarnationIfHit(gs, lorebookSet.getId(), originResult);

        log.info("游戏状态已创建: gameStateId={}, worldId={}, triggers={}, hasOrigin={}"
                + (originResult != null && "specter".equals(originResult.getFaction())
                ? ", starClaimed=" + originResult.getConstellation() : ""),
                gs.getId(), worldId, triggers.size(), originResult != null);
        return gs;
    }

    /**
     * RPG 会话打标（separate-rpg-chat-sessions D2）：开局即为 sessionId 幂等建壳并写
     * {@code metadata.kind="rpg"}——壳先于首回合存在，消灭「rpgSessionMemoryAdvisor
     * 首轮自动建壳（无标记）」的时序窗口，主会话列表/读路径按 kind 隔离才有可靠真源。
     * <ul>
     *   <li>壳不存在 → 以请求租户为 userId 显式建壳（sessionId 为前端生成，须指定 id）</li>
     *   <li>壳已存在（重开局/异常重复）→ merge 补写 kind，保留既有键（幂等）</li>
     *   <li>打标失败 WARN 降级不阻断开局：后续回合 advisor 仍会自动建壳，
     *       失标会话由 AiSessionKindBackfill 启动回填按 GameState 引用自愈</li>
     * </ul>
     */
    private void tagRpgSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            // 租户上下文缺失（非请求线程等）与仓储异常同降级：打标失败不阻断开局，回填自愈
            String tenantId = TenantContext.requireTenantId();
            Session existing = sessionRepository.findById(sessionId);
            if (existing == null) {
                sessionService.create(CreateSessionRequest.builder()
                        .id(sessionId)
                        .userId(tenantId)
                        .metadata("kind", "rpg")
                        .build());
                log.info("RPG 会话壳已创建并打标: sessionId={}", sessionId);
            } else {
                Map<String, Object> meta = existing.metadata();
                if (meta != null && "rpg".equals(meta.get("kind"))) {
                    return;
                }
                Map<String, Object> newMeta = new HashMap<>(meta != null ? meta : Map.of());
                newMeta.put("kind", "rpg");
                sessionRepository.save(Session.builder()
                        .id(existing.id())
                        .userId(existing.userId())
                        .createdAt(existing.createdAt())
                        .expiresAt(existing.expiresAt())
                        .metadata(newMeta)
                        .build());
                log.info("RPG 会话壳已补打标: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("RPG 会话打标失败（降级不阻断开局，回填可自愈）: sessionId={}", sessionId, e);
        }
    }

    /**
     * 抢星判定：冥斗士开局且主命星（天罡，即 constellation）命中名录可推演条目时，
     * 写 runtime 行 claimed_by_player=true、activated_turn=0（对玩家恒亮）。
     * <p>
     * 刻意不判定的三类（spec「副星不触发抢星」）：非 specter 阵营；副星（地煞，
     * 仅存在于 zodiacTraits 文本，不反解析）；rollable=false 的 NPC 专属星
     * （天兽/天魔不入可推演池，constellation 也推不到，此处双重保险）。
     * 名录未物化/未命中时零写入，不阻断开局。
     */
    private void claimStarIncarnationIfHit(GameState gs, String setId, OriginResult origin) {
        if (origin == null || !"specter".equals(origin.getFaction())) {
            return;
        }
        String constellation = origin.getConstellation();
        if (constellation == null || constellation.isBlank()) {
            return;
        }
        for (StarIncarnation incarnation : starIncarnationRepo.findBySetId(setId)) {
            if (!Boolean.TRUE.equals(incarnation.getRollable())) {
                continue;
            }
            if (!constellation.equals(incarnation.getStarName())) {
                continue;
            }
            StarIncarnationRuntime rt = new StarIncarnationRuntime();
            rt.setId(UUID.randomUUID().toString());
            rt.setGameStateId(gs.getId());
            rt.setIncarnationId(incarnation.getId());
            rt.setStarName(incarnation.getStarName());
            rt.setActivatedTurn(0);
            rt.setClaimedByPlayer(true);
            starRuntimeRepo.save(rt);
            // 索引行随命中追加（rpg-lore-archival-loading D4）：带【星归玩家】标记；
            // 人物局撞星（figureKey 非空，preset-destined-figures D3）切换为【人即玩家】。
            // IO 失败由服务内部降级告警，不阻断开局。
            loreArchiveService.appendRevealedIndexLine(gs.getId(),
                    incarnation.getStarName(), incarnation.getWarlordName(), true,
                    origin.getFigureKey());
            log.info("抢星判定命中: gameStateId={}, star={}, warlord={}",
                    gs.getId(), incarnation.getStarName(), incarnation.getWarlordName());
            return;
        }
    }

    /**
     * 档案变体段的候选星名（rpg-lore-archival-loading D2）：冥斗士开局取主命星名，
     * 其余为 null——物化侧按「rollable + 星名相等」复刻抢星命中条件，未命中即无变体段。
     */
    private static String claimCandidateStar(OriginResult origin) {
        if (origin == null || !"specter".equals(origin.getFaction())) {
            return null;
        }
        return origin.getConstellation() == null || origin.getConstellation().isBlank()
                ? null : origin.getConstellation();
    }

    /**
     * 变体段的人物信号（preset-destined-figures D3）：人物扮演局（figureKey 非空）时
     * 随开局透传给档案物化链路，命中档案的变体段与索引标记切换为「人即玩家」；
     * 其余为 null（旧语义零改动）。claimStarIncarnationIfHit 判定本身零改动——
     * 预绑冥衣（信长→天魁星）即一次被人物钉死的推演结果，自动命中。
     */
    private static String claimFigureKey(OriginResult origin) {
        return origin == null ? null : origin.getFigureKey();
    }

    /**
     * 构建 playerStates JSON，注入星命数据。
     * <p>
     * 字段：constellation, star_rank, faction, zodiac_traits, star_bonus（嵌套对象）。
     * <p>包内可见：纯函数（仅依赖 OriginResult），供单测直接注入验证 cosmo 键注入与缺省跳过。
     */
    String buildPlayerStatesJson(OriginResult origin) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            root.put("constellation", origin.getConstellation());
            root.put("star_rank", origin.getStarRank());
            root.put("faction", origin.getFaction());
            root.put("zodiac_traits", origin.getZodiacTraits());
            // 小宇宙初值注入（rpg-cosmo-sense）：仅星命路径（origin 非空）有值，
            // 非星命开局不写 cosmo 键，回合结算与展示分支据此跳过（零影响）。
            if (origin.getCosmoIntensity() != null) {
                root.put("cosmo", origin.getCosmoIntensity());
            }
            if (origin.getStarBonus() != null) {
                ObjectNode bonusNode = mapper.createObjectNode();
                origin.getStarBonus().forEach(bonusNode::put);
                root.set("star_bonus", bonusNode);
            }
            // 命定人物三键（preset-destined-figures D2）：仅人物模式写入（无名新人/旧档
            // 不含 figure 键），GM 上下文星命行与索引/变体段链路据此感知「玩家是谁」。
            if (origin.getFigureKey() != null && !origin.getFigureKey().isBlank()) {
                root.put("figure_key", origin.getFigureKey());
                root.put("figure_display_name", origin.getFigureDisplayName());
                root.put("figure_era_note", origin.getFigureEraNote());
            }
            // 招式列表注入（rpg-technique-tree-data D6：七字段全写，供 GM 叙事与门禁判定）
            if (origin.getTechniques() != null && !origin.getTechniques().isEmpty()) {
                com.fasterxml.jackson.databind.node.ArrayNode techArray = mapper.createArrayNode();
                origin.getTechniques().forEach(t -> {
                    ObjectNode techNode = mapper.createObjectNode();
                    techNode.put("name", t.name());
                    techNode.put("description", t.description());
                    techNode.put("source", t.source());
                    techNode.put("tier", t.tier());
                    com.fasterxml.jackson.databind.node.ArrayNode prereqNode = mapper.createArrayNode();
                    t.prerequisites().forEach(prereqNode::add);
                    techNode.set("prerequisites", prereqNode);
                    techNode.put("learned", t.learned());
                    techNode.put("proficiency", t.proficiency());
                    techArray.add(techNode);
                });
                root.set("techniques", techArray);
            }
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("构建 playerStates JSON 失败，降级为空 JSON: {}", e.getMessage());
            return "{}";
        }
    }

    public GameState getGameState(String gameStateId) {
        return stateRepo.findById(gameStateId);
    }

    /**
     * 按会话 ID查游戏状态（回溯前端近似置灰所需的 currentTurn 来源）。
     */
    public GameState getGameStateBySessionId(String sessionId) {
        return stateRepo.findLatestBySessionId(sessionId);
    }

    public void updateGameSession(String gameStateId, String sessionId) {
        stateRepo.updateSessionId(gameStateId, sessionId);
    }

    public List<GameState> listGameStates() {
        return stateRepo.findAll();
    }

    /**
     * 存档卡片列表（GET /rpg/game/saves 数据源）。
     * <p>
     * 在 {@link #listGameStates()} 基础上 join 世界名与玩家角色名：
     * 引用的世界/角色卡已被删除（或防御性缺失）时回退占位符 "-"，列表不中断。
     * {@code stateRepo.findAll()} 已按 updated_at 倒序。
     */
    public List<SaveCard> listSaveCards() {
        List<SaveCard> cards = new ArrayList<>();
        for (GameState gs : stateRepo.findAll()) {
            String worldName = "-";
            WorldSetting world = gs.getWorldId() != null ? worldRepo.findById(gs.getWorldId()) : null;
            if (world != null && world.getName() != null && !world.getName().isBlank()) {
                worldName = world.getName();
            }
            String playerCharName = "-";
            CharacterCard player = gs.getPlayerCharId() != null
                    ? charRepo.findById(gs.getPlayerCharId()) : null;
            if (player != null && player.getName() != null && !player.getName().isBlank()) {
                playerCharName = player.getName();
            }
            String location = gs.getCurrentLocation() != null && !gs.getCurrentLocation().isBlank()
                    ? gs.getCurrentLocation() : "-";
            cards.add(new SaveCard(gs.getId(), gs.getSessionId(), gs.getWorldId(), worldName,
                    playerCharName, location, gs.getTurnCount(), gs.getUpdatedAt()));
        }
        return cards;
    }

    // ==================== 一键中文化改名 ====================

    /** 名字含拉丁字母即视为待中文化（罗马音 / 英文名） */
    private static final Pattern LATIN_LETTER = Pattern.compile("[A-Za-z]");

    /** 新名至少得含一个汉字 */
    private static final Pattern CJK_LETTER = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 单条改名记录（旧名 → 新汉字名）。
     * <p>
     * {@code cardId} 为 {@code null} 表示无角色卡的剧情临时 NPC（只迁移引用 key，不写卡表）。
     */
    public record RenamedCharacter(String cardId, String oldName, String newName) {}

    /**
     * 改名摘要：改名清单 + 各表/文件的迁移计数。
     * <p>
     * 引用重写后：角色卡引用 key 一律为卡 ID（后续再改名只动 {@code character_card.name} 一列），
     * 无卡临时 NPC 的 key 为其新汉字名。
     */
    public record NameLocalizeSummary(List<RenamedCharacter> renames,
                                      int gameStateCount,
                                      int npcStateKeyCount,
                                      int triggerCount,
                                      int relationshipCount,
                                      int notebookFileCount) {}

    /** 事务内四张表重写的计数 */
    private record Migration(int gameStates, int npcStateKeys, int triggers, int relationships) {}

    /** npcStates key 迁移结果：json 为 null 表示无需写库 */
    private record NpcStateMigration(String json, int migratedKeys) {}

    /**
     * 一键中文化改名：把世界内含拉丁字母的角色名改为汉字名，并把所有引用 key 迁移——
     * 角色卡引用迁移到卡 ID；无角色卡的剧情临时 NPC（GM 临时创造、在存档 npc_states 里
     * 以拼音/罗马音名作 key 的 NPC）迁移到其新汉字名。
     * <p>
     * 流程：筛选待改名（卡名 + 存档 npc_states 中的无卡拼音 key）→ 单次 LLM 映射（事务外）
     * → 校验矩阵 → 事务内重写四张表 → 笔记本 best-effort 跟随。
     * 校验任一不满足抛 {@link IllegalStateException}，此时尚未写库。
     *
     * @param worldId 世界观 ID
     * @return 改名摘要（无待改名时清单为空且不调 LLM）
     * @throws IllegalArgumentException 世界不存在
     * @throws IllegalStateException    LLM 映射未通过校验矩阵
     */
    public NameLocalizeSummary localizeNames(String worldId) {
        WorldSetting world = worldRepo.findById(worldId);
        if (world == null) {
            throw new IllegalArgumentException("世界不存在: " + worldId);
        }
        List<CharacterCard> cards = charRepo.findByWorldId(worldId);
        List<CharacterCard> targets = cards.stream()
                .filter(c -> c.getName() != null && LATIN_LETTER.matcher(c.getName()).find())
                .toList();
        List<GameState> saves = stateRepo.findByWorldId(worldId);
        List<WorkshopLlmGenerator.OrphanNpcName> orphans = collectOrphanNpcNames(cards, saves);
        if (targets.isEmpty() && orphans.isEmpty()) {
            log.info("一键中文化改名: 世界 {} 无罗马音/英文名字，不调 LLM", worldId);
            return new NameLocalizeSummary(List.of(), 0, 0, 0, 0, 0);
        }

        Map<String, String> mapping = llmGenerator.renameToChinese(targets, orphans, world);
        Map<String, String> keyRewrites = validateMapping(cards, targets, orphans, mapping);

        Migration migration = Objects.requireNonNull(
                txTemplate.execute(status -> rewriteReferences(worldId, targets, mapping, keyRewrites, saves)),
                "改名事务未返回计数");
        // 笔记本跟随刻意放在事务提交之后：DB 已生效，IO 失败不应连带回滚
        int notebookFiles = followNotebooks(saves, keyRewrites);

        List<RenamedCharacter> renames = new ArrayList<>(targets.stream()
                .map(c -> new RenamedCharacter(c.getId(), c.getName(), mapping.get(c.getName())))
                .toList());
        orphans.forEach(o -> renames.add(
                new RenamedCharacter(null, o.name(), mapping.get(o.name()))));
        NameLocalizeSummary summary = new NameLocalizeSummary(renames,
                migration.gameStates(), migration.npcStateKeys(),
                migration.triggers(), migration.relationships(), notebookFiles);
        log.info("一键中文化改名完成: worldId={}, 改名 {} 项, 存档 {} 个/npcStates key {} 个, 触发器 {} 条, 关系 {} 条, 笔记本文件 {} 个",
                worldId, renames.size(), summary.gameStateCount(), summary.npcStateKeyCount(),
                summary.triggerCount(), summary.relationshipCount(), summary.notebookFileCount());
        return summary;
    }

    /**
     * 收集无卡临时 NPC 的拼音/罗马音 key：遍历存档 npcStates，取既非卡 ID 也非卡名、
     * 且含拉丁字母的 key（GM 对无卡 NPC 常把名字转成拼音当 key 用）。
     * <p>
     * 同名 key 跨存档只保留一份，附上首个非空状态摘要（status/location）供 LLM 推断身份。
     */
    private List<WorkshopLlmGenerator.OrphanNpcName> collectOrphanNpcNames(List<CharacterCard> cards,
                                                                          List<GameState> saves) {
        Set<String> cardIds = cards.stream()
                .map(CharacterCard::getId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Set<String> cardNames = cards.stream()
                .map(CharacterCard::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toSet());

        Map<String, String> orphanContexts = new LinkedHashMap<>();
        for (GameState gs : saves) {
            if (gs.getNpcStates() == null || gs.getNpcStates().isBlank()) {
                continue;
            }
            JsonNode root;
            try {
                root = JSON.readTree(gs.getNpcStates());
            } catch (IOException e) {
                log.warn("npcStates 不是合法 JSON，跳过 orphan key 收集: {}", e.getMessage());
                continue;
            }
            if (!root.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                if (cardIds.contains(key) || cardNames.contains(key)
                        || !LATIN_LETTER.matcher(key).find()) {
                    continue;
                }
                orphanContexts.computeIfAbsent(key, k -> npcContext(entry.getValue()));
            }
        }
        return orphanContexts.entrySet().stream()
                .map(e -> new WorkshopLlmGenerator.OrphanNpcName(e.getKey(), e.getValue()))
                .toList();
    }

    /** 从 npcStates value 提取状态摘要（status/location），供 LLM 改名时推断身份 */
    private static String npcContext(JsonNode state) {
        if (state == null || !state.isObject()) {
            return "未知";
        }
        List<String> parts = new ArrayList<>();
        String status = state.path("status").asText(null);
        if (status != null && !status.isBlank()) {
            parts.add("状态:" + status);
        }
        String location = state.path("location").asText(null);
        if (location != null && !location.isBlank()) {
            parts.add("位置:" + location);
        }
        return parts.isEmpty() ? "未知" : String.join("，", parts);
    }

    /**
     * 校验矩阵：覆盖全部旧名、新名含汉字、新名不含拉丁字母、互不重复、不与现存名冲突。
     * 任一不满足即抛异常（调用方此时尚未写库，故天然原子）。
     *
     * @return 引用重写映射（旧名 → 新 key）：角色卡旧名 → 卡 ID；无卡临时 NPC 拼音名 → 新汉字名
     */
    private Map<String, String> validateMapping(List<CharacterCard> allCards,
                                                List<CharacterCard> targets,
                                                List<WorkshopLlmGenerator.OrphanNpcName> orphans,
                                                Map<String, String> mapping) {
        Set<String> targetNames = targets.stream()
                .map(CharacterCard::getName)
                .collect(Collectors.toSet());
        Set<String> keptNames = allCards.stream()
                .map(CharacterCard::getName)
                .filter(name -> name != null && !targetNames.contains(name))
                .collect(Collectors.toSet());

        Map<String, String> keyRewrites = new LinkedHashMap<>();
        Set<String> newNames = new HashSet<>();
        for (CharacterCard card : targets) {
            String oldName = card.getName();
            String newName = mapping.get(oldName);
            if (newName == null || newName.isBlank()) {
                throw new IllegalStateException("改名映射缺少旧名: " + oldName);
            }
            if (!CJK_LETTER.matcher(newName).find()) {
                throw new IllegalStateException("新名不含汉字: " + oldName + " → " + newName);
            }
            if (LATIN_LETTER.matcher(newName).find()) {
                throw new IllegalStateException("新名仍含拉丁字母: " + oldName + " → " + newName);
            }
            if (!newNames.add(newName)) {
                throw new IllegalStateException("新名重复: " + newName);
            }
            if (keptNames.contains(newName)) {
                throw new IllegalStateException("新名与现存角色名冲突: " + newName);
            }
            String previous = keyRewrites.put(oldName, card.getId());
            if (previous != null && !previous.equals(card.getId())) {
                throw new IllegalStateException("存在同名角色卡，无法按名字定位引用: " + oldName);
            }
        }
        for (WorkshopLlmGenerator.OrphanNpcName orphan : orphans) {
            String oldName = orphan.name();
            String newName = mapping.get(oldName);
            if (newName == null || newName.isBlank()) {
                throw new IllegalStateException("改名映射缺少旧名: " + oldName);
            }
            if (!CJK_LETTER.matcher(newName).find()) {
                throw new IllegalStateException("新名不含汉字: " + oldName + " → " + newName);
            }
            if (LATIN_LETTER.matcher(newName).find()) {
                throw new IllegalStateException("新名仍含拉丁字母: " + oldName + " → " + newName);
            }
            if (!newNames.add(newName)) {
                throw new IllegalStateException("新名重复: " + newName);
            }
            if (keptNames.contains(newName)) {
                throw new IllegalStateException("新名与现存角色名冲突: " + newName);
            }
            String previous = keyRewrites.put(oldName, newName);
            if (previous != null) {
                throw new IllegalStateException("旧名与角色卡名冲突，无法按名字定位引用: " + oldName);
            }
        }
        return keyRewrites;
    }

    /**
     * 事务内重写：角色卡名字列 + 三处引用（npcStates key、trigger.npcId、relationship 两端）。
     * 引用一律改写为新 key（卡旧名 → 卡 ID；无卡拼音名 → 新汉字名）；
     * 已是新 key 或不在映射内的值一律不动。
     */
    private Migration rewriteReferences(String worldId,
                                        List<CharacterCard> targets,
                                        Map<String, String> mapping,
                                        Map<String, String> keyRewrites,
                                        List<GameState> saves) {
        for (CharacterCard card : targets) {
            charRepo.updateName(card.getId(), mapping.get(card.getName()));
        }

        int gameStates = 0;
        int npcStateKeys = 0;
        for (GameState gs : saves) {
            NpcStateMigration migrated = migrateNpcStateKeys(gs.getNpcStates(), keyRewrites);
            if (migrated.migratedKeys() > 0) {
                stateRepo.updateNpcStates(gs.getId(), migrated.json());
                gameStates++;
                npcStateKeys += migrated.migratedKeys();
            }
        }

        int triggers = 0;
        for (Trigger trigger : triggerRepo.findByWorldId(worldId)) {
            String newRef = trigger.getNpcId() == null ? null : keyRewrites.get(trigger.getNpcId());
            if (newRef != null) {
                triggerRepo.updateNpcId(trigger.getId(), newRef);
                triggers++;
            }
        }

        int relationships = 0;
        for (Relationship rel : relRepo.findAll()) {
            String charAId = remapRef(rel.getCharAId(), keyRewrites);
            String charBId = remapRef(rel.getCharBId(), keyRewrites);
            if (!Objects.equals(charAId, rel.getCharAId()) || !Objects.equals(charBId, rel.getCharBId())) {
                relRepo.updateCharRefs(rel.getId(), charAId, charBId);
                relationships++;
            }
        }
        return new Migration(gameStates, npcStateKeys, triggers, relationships);
    }

    private static String remapRef(String ref, Map<String, String> keyRewrites) {
        return ref == null ? null : keyRewrites.getOrDefault(ref, ref);
    }

    /**
     * 把 npcStates JSON 中命中映射的 key 改写为新 key（卡 ID 或新汉字名；保序、value 原样保留）。
     * <p>
     * 若新 key 已先出现（GM 已按新规范写过），则保留既有值并丢弃旧名条目。
     */
    private NpcStateMigration migrateNpcStateKeys(String npcStatesJson, Map<String, String> keyRewrites) {
        if (npcStatesJson == null || npcStatesJson.isBlank()) {
            return new NpcStateMigration(null, 0);
        }
        JsonNode root;
        try {
            root = JSON.readTree(npcStatesJson);
        } catch (IOException e) {
            log.warn("npcStates 不是合法 JSON，跳过 key 迁移: {}", e.getMessage());
            return new NpcStateMigration(null, 0);
        }
        if (!root.isObject()) {
            return new NpcStateMigration(null, 0);
        }

        ObjectNode migrated = JSON.createObjectNode();
        int count = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String newKey = keyRewrites.get(entry.getKey());
            if (newKey == null) {
                migrated.set(entry.getKey(), entry.getValue());
                continue;
            }
            count++;
            if (migrated.has(newKey)) {
                log.warn("npcStates 已存在新 key，保留既有值并丢弃旧名条目: {} → {}", entry.getKey(), newKey);
                continue;
            }
            migrated.set(newKey, entry.getValue());
        }
        if (count == 0) {
            return new NpcStateMigration(null, 0);
        }
        try {
            return new NpcStateMigration(JSON.writeValueAsString(migrated), count);
        } catch (IOException e) {
            log.warn("npcStates 序列化失败，跳过 key 迁移: {}", e.getMessage());
            return new NpcStateMigration(null, 0);
        }
    }

    /**
     * 笔记本 best-effort 跟随：每个存档目录下 {@code npc_<旧名>.md} → {@code npc_<新key>.md}
     * （卡 ID 或新汉字名），并把 {@code MEMORY.md} 索引行里的旧文件名替换为新文件名。IO 异常只记 warn。
     *
     * @return 成功重命名的文件数
     */
    private int followNotebooks(List<GameState> saves, Map<String, String> keyRewrites) {
        int renamed = 0;
        for (GameState gs : saves) {
            Path dir = RpgSavePaths.saveDir(gs.getId());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<String[]> fileRenames = new ArrayList<>();
            for (Map.Entry<String, String> entry : keyRewrites.entrySet()) {
                String oldName = entry.getKey();
                if (!isSafeFileSegment(oldName)) {
                    // 名字含路径分隔符等非法字符时不猜文件名，跳过
                    continue;
                }
                String oldFileName = "npc_" + oldName + ".md";
                String newFileName = "npc_" + entry.getValue() + ".md";
                Path from = dir.resolve(oldFileName);
                Path to = dir.resolve(newFileName);
                if (!Files.isRegularFile(from)) {
                    continue;
                }
                if (Files.exists(to)) {
                    log.warn("笔记本目标文件已存在，跳过重命名: {}", to);
                    continue;
                }
                try {
                    Files.move(from, to);
                    renamed++;
                    fileRenames.add(new String[]{oldFileName, newFileName});
                } catch (IOException | RuntimeException e) {
                    log.warn("笔记本文件重命名失败: {} → {}", from, to, e);
                }
            }
            rewriteIndexFilenames(dir.resolve(RpgSavePaths.INDEX_FILE), fileRenames);
        }
        return renamed;
    }

    /** 把索引文件里的旧文件名字符串替换为新文件名（best-effort） */
    private void rewriteIndexFilenames(Path indexFile, List<String[]> fileRenames) {
        if (fileRenames.isEmpty() || !Files.isRegularFile(indexFile)) {
            return;
        }
        try {
            String content = Files.readString(indexFile);
            String updated = content;
            for (String[] pair : fileRenames) {
                updated = updated.replace(pair[0], pair[1]);
            }
            if (!updated.equals(content)) {
                Files.writeString(indexFile, updated);
                log.info("笔记本索引已跟随改名: {}", indexFile);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("笔记本索引改写失败: {}", indexFile, e);
        }
    }

    /** 旧名能否安全拼成文件名（含分隔符或 Windows 非法字符时返回 false） */
    private static boolean isSafeFileSegment(String name) {
        return !name.isBlank()
                && !name.equals(".") && !name.equals("..")
                && name.chars().noneMatch(c -> "/\\:*?\"<>|".indexOf(c) >= 0);
    }
}
