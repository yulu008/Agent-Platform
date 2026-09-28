package com.luyu.agent.rpg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final WorkshopLlmGenerator llmGenerator;
    private final TransactionTemplate txTemplate;

    public WorkshopService(RpgWorldSettingRepository worldRepo,
                           RpgCharacterCardRepository charRepo,
                           RpgLocationRepository locationRepo,
                           RpgRelationshipRepository relRepo,
                           RpgTriggerRepository triggerRepo,
                           RpgGameStateRepository stateRepo,
                           RpgTriggerRuntimeRepository runtimeRepo,
                           WorkshopLlmGenerator llmGenerator,
                           PlatformTransactionManager transactionManager) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.locationRepo = locationRepo;
        this.relRepo = relRepo;
        this.triggerRepo = triggerRepo;
        this.stateRepo = stateRepo;
        this.runtimeRepo = runtimeRepo;
        this.llmGenerator = llmGenerator;
        // 用 TransactionTemplate 而非 @Transactional：localizeNames 先做 LLM 调用与校验（耗时、失败无需回滚），
        // 只把四张表的 DB 重写包进事务；若给同类的私有重写方法标 @Transactional，
        // 自调用会绕过代理而静默失效。
        this.txTemplate = new TransactionTemplate(transactionManager);
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
     * 阻塞式删除世界观：旗下仍有地点/角色/触发器/存档任一类子数据时拒绝。
     *
     * @throws IllegalArgumentException 世界不存在
     * @throws IllegalStateException    存在子数据引用，拒绝删除（消息指明具体类别）
     */
    public void deleteWorld(String id) {
        if (worldRepo.findById(id) == null) {
            throw new IllegalArgumentException("世界不存在: " + id);
        }
        if (charRepo.countByWorldId(id) > 0) {
            throw new IllegalStateException("该世界下仍有角色，请先删除角色");
        }
        if (locationRepo.countByWorldId(id) > 0) {
            throw new IllegalStateException("该世界下仍有地点，请先删除地点");
        }
        if (triggerRepo.countByWorldId(id) > 0) {
            throw new IllegalStateException("该世界下仍有触发器，请先删除触发器");
        }
        if (stateRepo.countByWorldId(id) > 0) {
            throw new IllegalStateException("该世界下仍有存档，请先删除存档");
        }
        worldRepo.deleteById(id);
        log.info("世界观已删除: id={}", id);
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
     * 阻塞式删除角色：被关系（任一端）/触发器/存档（作为玩家）引用时拒绝。
     *
     * @throws IllegalArgumentException 角色不存在
     * @throws IllegalStateException    存在引用，拒绝删除（消息指明具体原因）
     */
    public void deleteCharacter(String id) {
        if (charRepo.findById(id) == null) {
            throw new IllegalArgumentException("角色不存在: " + id);
        }
        if (relRepo.countByCharId(id) > 0) {
            throw new IllegalStateException("该角色存在角色间关系，请先删除相关关系");
        }
        if (triggerRepo.countByNpcId(id) > 0) {
            throw new IllegalStateException("该角色被触发器引用，请先删除相关触发器");
        }
        if (stateRepo.countByPlayerCharId(id) > 0) {
            throw new IllegalStateException("该角色是某存档的玩家角色，请先删除对应存档");
        }
        charRepo.deleteById(id);
        log.info("角色卡已删除: id={}", id);
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
        // 验证玩家角色存在且属于该世界；"谁是玩家"在此运行时确定，被选角色升级为 player
        CharacterCard player = charRepo.findById(playerCharId);
        if (player == null || !worldId.equals(player.getWorldId())) {
            throw new IllegalArgumentException("玩家角色不存在或不属于该世界: " + playerCharId);
        }
        charRepo.updateType(playerCharId, "player");

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
