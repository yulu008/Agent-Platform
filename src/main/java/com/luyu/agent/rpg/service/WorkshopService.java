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

    // ==================== 一键中文化改名 ====================

    /** 名字含拉丁字母即视为待中文化（罗马音 / 英文名） */
    private static final Pattern LATIN_LETTER = Pattern.compile("[A-Za-z]");

    /** 新名至少得含一个汉字 */
    private static final Pattern CJK_LETTER = Pattern.compile("[\\u4e00-\\u9fff]");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 单条改名记录（旧名 → 新汉字名，卡 ID 不变） */
    public record RenamedCharacter(String cardId, String oldName, String newName) {}

    /**
     * 改名摘要：改名清单 + 各表/文件的迁移计数。
     * <p>
     * 引用重写后 key 一律为角色卡 ID，故后续再改名只动 {@code character_card.name} 一列。
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
     * 一键中文化改名：把世界内含拉丁字母的角色名改为汉字名，并把所有引用 key 迁移到角色卡 ID。
     * <p>
     * 流程：筛选待改名 → 单次 LLM 映射（事务外）→ 校验矩阵 → 事务内重写四张表
     * → 笔记本 best-effort 跟随。校验任一不满足抛 {@link IllegalStateException}，此时尚未写库。
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
        if (targets.isEmpty()) {
            log.info("一键中文化改名: 世界 {} 无罗马音/英文名字，不调 LLM", worldId);
            return new NameLocalizeSummary(List.of(), 0, 0, 0, 0, 0);
        }

        Map<String, String> mapping = llmGenerator.renameToChinese(targets, world);
        Map<String, String> oldNameToCardId = validateMapping(cards, targets, mapping);
        List<GameState> saves = stateRepo.findByWorldId(worldId);

        Migration migration = Objects.requireNonNull(
                txTemplate.execute(status -> rewriteReferences(worldId, targets, mapping, oldNameToCardId, saves)),
                "改名事务未返回计数");
        // 笔记本跟随刻意放在事务提交之后：DB 已生效，IO 失败不应连带回滚
        int notebookFiles = followNotebooks(saves, oldNameToCardId);

        List<RenamedCharacter> renames = targets.stream()
                .map(c -> new RenamedCharacter(c.getId(), c.getName(), mapping.get(c.getName())))
                .toList();
        NameLocalizeSummary summary = new NameLocalizeSummary(renames,
                migration.gameStates(), migration.npcStateKeys(),
                migration.triggers(), migration.relationships(), notebookFiles);
        log.info("一键中文化改名完成: worldId={}, 改名 {} 项, 存档 {} 个/npcStates key {} 个, 触发器 {} 条, 关系 {} 条, 笔记本文件 {} 个",
                worldId, renames.size(), summary.gameStateCount(), summary.npcStateKeyCount(),
                summary.triggerCount(), summary.relationshipCount(), summary.notebookFileCount());
        return summary;
    }

    /**
     * 校验矩阵：覆盖全部旧名、新名含汉字、新名不含拉丁字母、互不重复、不与现存名冲突。
     * 任一不满足即抛异常（调用方此时尚未写库，故天然原子）。
     *
     * @return 旧名 → 角色卡 ID 映射（引用重写用）
     */
    private Map<String, String> validateMapping(List<CharacterCard> allCards,
                                                List<CharacterCard> targets,
                                                Map<String, String> mapping) {
        Set<String> targetNames = targets.stream()
                .map(CharacterCard::getName)
                .collect(Collectors.toSet());
        Set<String> keptNames = allCards.stream()
                .map(CharacterCard::getName)
                .filter(name -> name != null && !targetNames.contains(name))
                .collect(Collectors.toSet());

        Map<String, String> oldNameToCardId = new LinkedHashMap<>();
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
            String previous = oldNameToCardId.put(oldName, card.getId());
            if (previous != null && !previous.equals(card.getId())) {
                throw new IllegalStateException("存在同名角色卡，无法按名字定位引用: " + oldName);
            }
        }
        return oldNameToCardId;
    }

    /**
     * 事务内重写：角色卡名字列 + 三处引用（npcStates key、trigger.npcId、relationship 两端）。
     * 已是卡 ID 或不在映射内的值一律不动。
     */
    private Migration rewriteReferences(String worldId,
                                        List<CharacterCard> targets,
                                        Map<String, String> mapping,
                                        Map<String, String> oldNameToCardId,
                                        List<GameState> saves) {
        for (CharacterCard card : targets) {
            charRepo.updateName(card.getId(), mapping.get(card.getName()));
        }

        int gameStates = 0;
        int npcStateKeys = 0;
        for (GameState gs : saves) {
            NpcStateMigration migrated = migrateNpcStateKeys(gs.getNpcStates(), oldNameToCardId);
            if (migrated.migratedKeys() > 0) {
                stateRepo.updateNpcStates(gs.getId(), migrated.json());
                gameStates++;
                npcStateKeys += migrated.migratedKeys();
            }
        }

        int triggers = 0;
        for (Trigger trigger : triggerRepo.findByWorldId(worldId)) {
            String cardId = trigger.getNpcId() == null ? null : oldNameToCardId.get(trigger.getNpcId());
            if (cardId != null) {
                triggerRepo.updateNpcId(trigger.getId(), cardId);
                triggers++;
            }
        }

        int relationships = 0;
        for (Relationship rel : relRepo.findAll()) {
            String charAId = remapRef(rel.getCharAId(), oldNameToCardId);
            String charBId = remapRef(rel.getCharBId(), oldNameToCardId);
            if (!Objects.equals(charAId, rel.getCharAId()) || !Objects.equals(charBId, rel.getCharBId())) {
                relRepo.updateCharRefs(rel.getId(), charAId, charBId);
                relationships++;
            }
        }
        return new Migration(gameStates, npcStateKeys, triggers, relationships);
    }

    private static String remapRef(String ref, Map<String, String> oldNameToCardId) {
        return ref == null ? null : oldNameToCardId.getOrDefault(ref, ref);
    }

    /**
     * 把 npcStates JSON 中等于旧名的 key 改写为角色卡 ID（保序、value 原样保留）。
     * <p>
     * 若卡 ID key 已先出现（GM 已按新规范写过），则保留既有值并丢弃旧名条目。
     */
    private NpcStateMigration migrateNpcStateKeys(String npcStatesJson, Map<String, String> oldNameToCardId) {
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
            String cardId = oldNameToCardId.get(entry.getKey());
            if (cardId == null) {
                migrated.set(entry.getKey(), entry.getValue());
                continue;
            }
            count++;
            if (migrated.has(cardId)) {
                log.warn("npcStates 已存在卡 ID key，保留既有值并丢弃旧名条目: {} → {}", entry.getKey(), cardId);
                continue;
            }
            migrated.set(cardId, entry.getValue());
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
     * 笔记本 best-effort 跟随：每个存档目录下 {@code npc_<旧名>.md} → {@code npc_<卡ID>.md}，
     * 并把 {@code MEMORY.md} 索引行里的旧文件名替换为新文件名。IO 异常只记 warn。
     *
     * @return 成功重命名的文件数
     */
    private int followNotebooks(List<GameState> saves, Map<String, String> oldNameToCardId) {
        int renamed = 0;
        for (GameState gs : saves) {
            Path dir = RpgSavePaths.saveDir(gs.getId());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<String[]> fileRenames = new ArrayList<>();
            for (Map.Entry<String, String> entry : oldNameToCardId.entrySet()) {
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
