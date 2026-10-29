package com.luyu.agent.rpg.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.Location;
import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.rpg.model.SharedWorld;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.WorldPublishRepository;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 共享世界发布服务（works-view-and-world-publish）。
 * <p>
 * 传播链与 add-shared-worldbook-publish 快照哲学同构：发布方全家桶 →
 * publish 快照（{@code snapshot_json} 单表文档 v1）→ 橱窗读 → import 复制 →
 * 接收方本地新世界。每跳单一写手、无合并、无反向写回；覆盖更新/撤销不影响
 * 任何已导入副本（已导入的各副本独立互不影响）。
 * <p>
 * 异常约定（控制层映射，参照 SharedPackController）：{@link IllegalArgumentException}
 * → 400（校验拒绝）；{@link IllegalStateException} → 409（发布者本人导入被拒）；
 * {@link NoSuchElementException} → 404（包不存在/已下架）。
 */
@Service
public class WorldPublishService {

    private static final Logger log = LoggerFactory.getLogger(WorldPublishService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 快照文档当前版本（导入端按版本分支反序列化；演进时递增并保留旧版解析） */
    static final int SNAPSHOT_VERSION = 1;

    private final WorldPublishRepository sharedRepo;
    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgLocationRepository locationRepo;
    private final RpgRelationshipRepository relRepo;
    private final RpgTriggerRepository triggerRepo;
    private final RpgWorldbookEntryRepository worldbookRepo;
    private final RpgStarIncarnationRepository starRepo;
    private final WorldCoverStorageService coverStorage;

    public WorldPublishService(WorldPublishRepository sharedRepo,
                               RpgWorldSettingRepository worldRepo,
                               RpgCharacterCardRepository charRepo,
                               RpgLocationRepository locationRepo,
                               RpgRelationshipRepository relRepo,
                               RpgTriggerRepository triggerRepo,
                               RpgWorldbookEntryRepository worldbookRepo,
                               RpgStarIncarnationRepository starRepo,
                               WorldCoverStorageService coverStorage) {
        this.sharedRepo = sharedRepo;
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.locationRepo = locationRepo;
        this.relRepo = relRepo;
        this.triggerRepo = triggerRepo;
        this.worldbookRepo = worldbookRepo;
        this.starRepo = starRepo;
        this.coverStorage = coverStorage;
    }

    // ==================== 快照文档 v1 ====================

    /**
     * 全家桶快照文档：world 核心列 + 六类子数据。字段全 String/Integer/Boolean
     * （时间戳对快照无意义，刻意不含）；子数据带原 id——导入端据此重绑新 id 并
     * 重映射跨行引用（npcIds/triggerIds/charAId/charBId/npcId/world.locations）。
     */
    public record Snapshot(
            WorldInfo world,
            List<CharInfo> characters,
            List<LocInfo> locations,
            List<RelInfo> relationships,
            List<TrigInfo> triggers,
            List<WbInfo> worldbookEntries,
            List<StarInfo> starIncarnations) {
    }

    public record WorldInfo(String name, String settingDesc, String era, String rules,
                            String locations, String atmosphere, String openingTemplate,
                            String themeTokens, String customBlocks) {
    }

    public record CharInfo(String id, String name, String type, String identity, String personality,
                           String background, String motivation, String speechStyle, String knowledge) {
    }

    public record LocInfo(String id, String name, String description, String npcIds, String triggerIds) {
    }

    public record RelInfo(String id, String charAId, String charBId, Integer attitude, Integer trust, String notes) {
    }

    public record TrigInfo(String id, String type, String npcId, String hardConditions, String softCondition,
                           String action, Integer cooldown, Double probability) {
    }

    public record WbInfo(String id, String title, String keywords, String content, Boolean enabled, Integer sortOrder) {
    }

    public record StarInfo(String id, String starName, String warlordName, String lore, String aliases,
                           Boolean rollable, Integer sortOrder) {
    }

    // ==================== 发布 / 覆盖更新 ====================

    /** 发布结果：包 id、是否覆盖了既有包、子数据总数 */
    public record PublishResult(String packId, boolean overwrite, int entryCount) {
    }

    /**
     * 发布（或覆盖更新）世界全家桶快照：WorldSetting + 角色全量 + 地点 + 关系 +
     * 触发器 + 通用世界书模板行（set_id IS NULL）+ 冥星模板行 + 封面文件。
     * <p>
     * 校验：世界存在且属本租户、非物化预设（预设卡本就上橱窗，发布无意义）。
     * 同发布者同源世界已有包 → 覆盖（整包重写、updated_at 刷新、撤销后重发布复活）。
     * 封面复制到 shared 区（复制而非引用，design D6）；封面失败降级 null 不阻断发布。
     */
    @Transactional
    public PublishResult publish(String worldId) {
        if (worldId == null || worldId.isBlank()) {
            throw new IllegalArgumentException("世界 id 不能为空");
        }
        String tenantId = TenantContext.requireTenantId();
        WorldSetting world = worldRepo.findById(worldId);
        if (world == null) {
            throw new IllegalArgumentException("世界不存在或不属于当前租户: " + worldId);
        }
        if (worldId.startsWith(SharedPackService.PRESET_WORLD_PREFIX)) {
            throw new IllegalArgumentException("预设世界无需发布（橱窗已展示预设卡）");
        }

        // ---- 快照组装（全部为未软删行；repo 查询已过滤 deleted_at）----
        List<CharacterCard> characters = charRepo.findByWorldId(worldId);
        List<Location> locations = locationRepo.findByWorldId(worldId);
        List<Trigger> triggers = triggerRepo.findByWorldId(worldId);
        List<WorldbookEntry> wbTemplates = worldbookRepo.findByWorldId(worldId);   // set_id IS NULL 模板行
        List<StarIncarnation> starTemplates = starRepo.findByWorldId(worldId);    // set_id IS NULL 模板行

        // 关系表无 world_id 列：世界级关系 = 两端角色均在快照角色集内（本租户全量里筛）
        Set<String> charIds = characters.stream().map(CharacterCard::getId).collect(Collectors.toSet());
        List<Relationship> relationships = relRepo.findAll().stream()
                .filter(r -> charIds.contains(r.getCharAId()) && charIds.contains(r.getCharBId()))
                .toList();

        int entryCount = characters.size() + locations.size() + relationships.size()
                + triggers.size() + wbTemplates.size() + starTemplates.size();

        Snapshot snapshot = new Snapshot(
                new WorldInfo(world.getName(), world.getSettingDesc(), world.getEra(),
                        world.getRules(), world.getLocations(), world.getAtmosphere(),
                        world.getOpeningTemplate(), world.getThemeTokens(), world.getCustomBlocks()),
                characters.stream().map(c -> new CharInfo(c.getId(), c.getName(), c.getType(),
                        c.getIdentity(), c.getPersonality(), c.getBackground(), c.getMotivation(),
                        c.getSpeechStyle(), c.getKnowledge())).toList(),
                locations.stream().map(l -> new LocInfo(l.getId(), l.getName(), l.getDescription(),
                        l.getNpcIds(), l.getTriggerIds())).toList(),
                relationships.stream().map(r -> new RelInfo(r.getId(), r.getCharAId(), r.getCharBId(),
                        r.getAttitude(), r.getTrust(), r.getNotes())).toList(),
                triggers.stream().map(t -> new TrigInfo(t.getId(), t.getType(), t.getNpcId(),
                        t.getHardConditions(), t.getSoftCondition(), t.getAction(),
                        t.getCooldown(), t.getProbability())).toList(),
                wbTemplates.stream().map(w -> new WbInfo(w.getId(), w.getTitle(), w.getKeywords(),
                        w.getContent(), w.getEnabled(), w.getSortOrder())).toList(),
                starTemplates.stream().map(s -> new StarInfo(s.getId(), s.getStarName(), s.getWarlordName(),
                        s.getLore(), s.getAliases(), s.getRollable(), s.getSortOrder())).toList());

        String snapshotJson;
        try {
            snapshotJson = MAPPER.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照序列化失败: " + e.getMessage(), e);
        }

        // ---- 覆盖定位与写入（同发布者同源世界一包）----
        SharedWorld existing = sharedRepo.findByPublisherAndWorld(tenantId, worldId);
        boolean overwrite = existing != null;
        String packId = overwrite ? existing.getId() : UUID.randomUUID().toString();

        // 封面复制到 shared 区（观感资产，失败降级 null 不阻断发布；覆盖重发原地刷）
        String sharedCover = coverStorage.copyToShared(tenantId, world.getCover(), packId);

        if (overwrite) {
            // 保留旧 cover 当源世界此刻无封面/封面复制失败（撤销后重发布等场景）
            String cover = sharedCover != null ? sharedCover : existing.getCover();
            sharedRepo.refreshPack(packId, world.getName(), WorldGalleryService.toBlurb(world.getSettingDesc()),
                    cover, snapshotJson, SNAPSHOT_VERSION, entryCount, tenantId);
        } else {
            SharedWorld pack = new SharedWorld();
            pack.setId(packId);
            pack.setName(world.getName());
            pack.setBlurb(WorldGalleryService.toBlurb(world.getSettingDesc()));
            pack.setCover(sharedCover);
            pack.setPublisherTenantId(tenantId);
            pack.setPublisherUserId(TenantContext.getUserId());
            pack.setOriginWorldId(worldId);
            pack.setOriginWorldName(world.getName());
            pack.setSnapshotJson(snapshotJson);
            pack.setSnapshotVersion(SNAPSHOT_VERSION);
            pack.setEntryCount(entryCount);
            sharedRepo.insertPack(pack);
        }
        log.info("共享世界已发布: packId={}, world={}, 子数据 {} 项, 覆盖={}",
                packId, worldId, entryCount, overwrite);
        return new PublishResult(packId, overwrite, entryCount);
    }

    // ==================== 橱窗 / 详情 ====================

    /** 橱窗列表：全平台在架包（读侧无租户过滤，包级列免解析整包快照） */
    public List<SharedWorld> listVisible() {
        return sharedRepo.listVisible();
    }

    /** 包详情；revoked/不存在返回 null（供控制层判 404） */
    public SharedWorld findVisibleById(String packId) {
        return sharedRepo.findVisibleById(packId);
    }

    // ==================== 导入 ====================

    /** 导入结果：接收方新世界 id 与子数据总数 */
    public record ImportResult(String newWorldId, int entryCount) {
    }

    /**
     * 导入共享世界快照为当前租户的<b>本地新世界</b>（design D1：各导入各的，多副本独立）。
     * <p>
     * 前置校验：包在架；发布者本人拒绝（409，前端走本人特判直接开局本地原世界，不进此路径）。
     * 写入：新世界 id + 全子数据新行 id（跨行引用 npcIds/triggerIds/charAId/charBId/npcId/
     * world.locations 同步重映射）、tenant_id 归接收方（既有 repo 强制过滤）、冥星保留
     * star_name、通用世界书 source_ref 置 NULL、封面复制到接收方租户私有区。
     * 整体单一事务：部分失败全回滚。
     */
    @Transactional
    public ImportResult importWorld(String packId) {
        String tenantId = TenantContext.requireTenantId();
        SharedWorld pack = sharedRepo.findVisibleById(packId);
        if (pack == null) {
            throw new NoSuchElementException("共享世界不存在或已下架: " + packId);
        }
        if (tenantId.equals(pack.getPublisherTenantId())) {
            // API 防线：正常流程前端经 publisherSelf 标志直接深链开局，不调导入
            throw new IllegalStateException("这是你自己发布的世界，直接开局本地原世界即可（origin: "
                    + pack.getOriginWorldId() + "）");
        }

        Snapshot snapshot = deserialize(pack.getSnapshotJson());

        // ---- 新 id 分配（先全量分配再逐表插入：跨行引用一次性重映射）----
        String newWorldId = UUID.randomUUID().toString();
        Map<String, String> charMap = newIdMap(snapshot.characters(), CharInfo::id);
        Map<String, String> locMap = newIdMap(snapshot.locations(), LocInfo::id);
        Map<String, String> trigMap = newIdMap(snapshot.triggers(), TrigInfo::id);

        // ---- 世界行（locations JSON 数组重绑地点新 id；封面复制到接收方私有区）----
        String cover = coverStorage.copyFromShared(pack.getCover(), tenantId);
        WorldSetting world = new WorldSetting();
        world.setId(newWorldId);
        world.setName(snapshot.world().name());
        world.setSettingDesc(snapshot.world().settingDesc());
        world.setEra(snapshot.world().era());
        world.setRules(snapshot.world().rules());
        world.setLocations(remapJsonArray(snapshot.world().locations(), locMap));
        world.setAtmosphere(snapshot.world().atmosphere());
        world.setOpeningTemplate(snapshot.world().openingTemplate());
        world.setThemeTokens(snapshot.world().themeTokens());
        world.setCustomBlocks(snapshot.world().customBlocks());
        world.setCover(cover);
        worldRepo.save(world);

        // ---- 角色（新 id；类型原样——导入含 player/npc 全量，开局选角自然接管）----
        for (CharInfo c : snapshot.characters()) {
            CharacterCard imported = new CharacterCard();
            imported.setId(charMap.get(c.id()));
            imported.setWorldId(newWorldId);
            imported.setName(c.name());
            imported.setType(c.type());
            imported.setIdentity(c.identity());
            imported.setPersonality(c.personality());
            imported.setBackground(c.background());
            imported.setMotivation(c.motivation());
            imported.setSpeechStyle(c.speechStyle());
            imported.setKnowledge(c.knowledge());
            charRepo.save(imported);
        }

        // ---- 地点（新 id；npcIds/triggerIds JSON 数组重绑新 id）----
        for (LocInfo l : snapshot.locations()) {
            Location imported = new Location();
            imported.setId(locMap.get(l.id()));
            imported.setWorldId(newWorldId);
            imported.setName(l.name());
            imported.setDescription(l.description());
            imported.setNpcIds(remapJsonArray(l.npcIds(), charMap));
            imported.setTriggerIds(remapJsonArray(l.triggerIds(), trigMap));
            locationRepo.save(imported);
        }

        // ---- 关系（新 id；两端引用重绑角色新 id）----
        for (RelInfo r : snapshot.relationships()) {
            Relationship imported = new Relationship();
            imported.setId(UUID.randomUUID().toString());
            imported.setCharAId(charMap.getOrDefault(r.charAId(), r.charAId()));
            imported.setCharBId(charMap.getOrDefault(r.charBId(), r.charBId()));
            imported.setAttitude(r.attitude());
            imported.setTrust(r.trust());
            imported.setNotes(r.notes());
            relRepo.save(imported);
        }

        // ---- 触发器（新 id；npcId 重绑角色新 id）----
        for (TrigInfo t : snapshot.triggers()) {
            Trigger imported = new Trigger();
            imported.setId(trigMap.get(t.id()));
            imported.setWorldId(newWorldId);
            imported.setType(t.type());
            imported.setNpcId(charMap.getOrDefault(t.npcId(), t.npcId()));
            imported.setHardConditions(t.hardConditions());
            imported.setSoftCondition(t.softCondition());
            imported.setAction(t.action());
            imported.setCooldown(t.cooldown());
            imported.setProbability(t.probability());
            triggerRepo.save(imported);
        }

        // ---- 通用世界书模板行（新 id、模板层 set_id NULL、source_ref NULL）----
        for (WbInfo w : snapshot.worldbookEntries()) {
            WorldbookEntry imported = new WorldbookEntry();
            imported.setId(UUID.randomUUID().toString());
            imported.setWorldId(newWorldId);
            imported.setTitle(w.title());
            imported.setKeywords(w.keywords());
            imported.setContent(w.content());
            imported.setEnabled(w.enabled());
            imported.setSortOrder(w.sortOrder());
            imported.setSetId(null);
            imported.setSourceRef(null);
            worldbookRepo.save(imported);
        }

        // ---- 冥星模板行（新 id；star_name 保留——揭示/抢星 runtime 稳定关联键）----
        for (StarInfo s : snapshot.starIncarnations()) {
            StarIncarnation imported = new StarIncarnation();
            imported.setId(UUID.randomUUID().toString());
            imported.setWorldId(newWorldId);
            imported.setStarName(s.starName());
            imported.setWarlordName(s.warlordName());
            imported.setLore(s.lore());
            imported.setAliases(s.aliases());
            imported.setRollable(s.rollable());
            imported.setSortOrder(s.sortOrder());
            imported.setSetId(null);
            imported.setSourceRef(null);
            starRepo.save(imported);
        }

        int entryCount = snapshot.characters().size() + snapshot.locations().size()
                + snapshot.relationships().size() + snapshot.triggers().size()
                + snapshot.worldbookEntries().size() + snapshot.starIncarnations().size();
        log.info("共享世界已导入: packId={}, 新世界={}, 子数据 {} 项",
                packId, newWorldId, entryCount);
        return new ImportResult(newWorldId, entryCount);
    }

    // ==================== 撤销 ====================

    /**
     * 撤销（软下架）自己的包：置 revoked_at，橱窗不再列出、详情/导入 404；
     * 已导入副本零清理（快照哲学，无回链）。
     *
     * @return true = 撤销成功；false = 包不存在/非发布者/已下架（供控制层判 404）
     */
    public boolean revoke(String packId) {
        boolean revoked = sharedRepo.revoke(packId, TenantContext.requireTenantId(), java.time.Instant.now()) > 0;
        if (revoked) {
            log.info("共享世界已撤销: packId={}", packId);
        }
        return revoked;
    }

    // ==================== 内部工具 ====================

    /** 快照反序列化（按 snapshot_version 分支；当前仅 v1） */
    private static Snapshot deserialize(String snapshotJson) {
        try {
            return MAPPER.readValue(snapshotJson, Snapshot.class);
        } catch (Exception e) {
            // 快照损坏是共享库数据问题，非用户输入错误——直接抛 500
            throw new IllegalStateException("快照解析失败: " + e.getMessage(), e);
        }
    }

    /** 为快照子数据行分配全新 id 映射（旧 id → 新 UUID） */
    private static <T> Map<String, String> newIdMap(List<T> items, java.util.function.Function<T, String> idGetter) {
        Map<String, String> map = new HashMap<>();
        for (T item : items) {
            String oldId = idGetter.apply(item);
            if (oldId != null) {
                map.put(oldId, UUID.randomUUID().toString());
            }
        }
        return map;
    }

    /**
     * JSON 数组字符串逐项重映射（["idA","idB"] → ["newA","newB"]）。
     * 空串/非 JSON 数组格式原样保留（不阻断导入——引擎侧本就容忍缺引用）。
     */
    private static String remapJsonArray(String jsonArray, Map<String, String> idMap) {
        if (jsonArray == null || jsonArray.isBlank() || idMap.isEmpty()) {
            return jsonArray;
        }
        try {
            List<String> ids = MAPPER.readValue(jsonArray, new TypeReference<List<String>>() {});
            List<String> remapped = new ArrayList<>(ids.size());
            for (String id : ids) {
                remapped.add(idMap.getOrDefault(id, id));
            }
            return MAPPER.writeValueAsString(remapped);
        } catch (JsonProcessingException e) {
            return jsonArray;
        }
    }
}
