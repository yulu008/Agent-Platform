package com.luyu.agent.rpg.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.luyu.agent.rpg.model.SharedLorebookPack;
import com.luyu.agent.rpg.model.SharedStarEntry;
import com.luyu.agent.rpg.model.SharedWorldbookEntry;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.SharedPackRepository;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 共享世界书包服务（add-shared-worldbook-publish）。
 * <p>
 * 传播链与 lorebook-sets D1 快照哲学同构：发布方模板 → publish 快照 → 共享包 →
 * import 重绑 → 接收方模板层，此后开局 fork/set 继承/弹窗编辑全部既有机制接管。
 * 每跳单一写手、无合并、无反向写回；覆盖更新/撤回不影响任何已导入副本。
 * <p>
 * 异常约定（控制层映射）：{@link IllegalArgumentException} → 400（参数/校验拒绝）；
 * {@link IllegalStateException} → 409（同名星冲突）；{@link NoSuchElementException} → 404（包不存在）。
 */
@Service
public class SharedPackService {

    private static final Logger log = LoggerFactory.getLogger(SharedPackService.class);

    /** 物化预设世界 id 前缀（导入拒绝：rewriteAll 会清掉导入的冥星模板行，design D5） */
    static final String PRESET_WORLD_PREFIX = "preset-";

    private final SharedPackRepository sharedRepo;
    private final RpgWorldSettingRepository worldRepo;
    private final RpgWorldbookEntryRepository worldbookRepo;
    private final RpgStarIncarnationRepository starRepo;

    public SharedPackService(SharedPackRepository sharedRepo,
                             RpgWorldSettingRepository worldRepo,
                             RpgWorldbookEntryRepository worldbookRepo,
                             RpgStarIncarnationRepository starRepo) {
        this.sharedRepo = sharedRepo;
        this.worldRepo = worldRepo;
        this.worldbookRepo = worldbookRepo;
        this.starRepo = starRepo;
    }

    // ==================== 发布 / 覆盖更新 ====================

    /** 发布结果：包 id、是否覆盖了既有包、条目总数 */
    public record PublishResult(String packId, boolean overwrite, int entryCount) {
    }

    /**
     * 发布（或覆盖更新）共享包：勾选条目快照写入共享库。
     * <p>
     * 校验：世界存在且属本租户；勾选条目均为本租户该世界的<b>模板行</b>（set_id IS NULL）——
     * 跨租户 id / 存档副本行 / 其他世界行一律拒绝（findById 已带租户过滤，天然不可见）。
     * 同租户同源世界已有包 → 覆盖（清旧条目、写新集、刷新 updated_at/entry_count，撤回后重发布复活）。
     */
    public PublishResult publish(String worldId, String name, String blurb,
                                 List<String> worldbookIds, List<String> starIds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("包名不能为空");
        }
        List<String> wbIds = worldbookIds == null ? List.of() : worldbookIds;
        List<String> stIds = starIds == null ? List.of() : starIds;
        if (wbIds.isEmpty() && stIds.isEmpty()) {
            throw new IllegalArgumentException("至少勾选一条世界书条目");
        }

        String tenantId = TenantContext.requireTenantId();
        var world = worldRepo.findById(worldId);
        if (world == null) {
            throw new IllegalArgumentException("世界不存在或不属于当前租户: " + worldId);
        }

        // 逐条校验并快照：均为本租户该世界模板行（null/set 行/异世界行拒绝）
        List<WorldbookEntry> wbTemplates = new ArrayList<>();
        for (String id : new LinkedHashSet<>(wbIds)) {
            WorldbookEntry entry = worldbookRepo.findById(id);
            if (entry == null || !worldId.equals(entry.getWorldId()) || entry.getSetId() != null) {
                throw new IllegalArgumentException("通用条目不存在或非模板行: " + id);
            }
            wbTemplates.add(entry);
        }
        List<StarIncarnation> stTemplates = new ArrayList<>();
        for (String id : new LinkedHashSet<>(stIds)) {
            StarIncarnation star = starRepo.findById(id);
            if (star == null || !worldId.equals(star.getWorldId()) || star.getSetId() != null) {
                throw new IllegalArgumentException("冥星条目不存在或非模板行: " + id);
            }
            stTemplates.add(star);
        }

        int entryCount = wbTemplates.size() + stTemplates.size();
        SharedLorebookPack existing = sharedRepo.findPackByPublisherAndWorld(tenantId, worldId);
        boolean overwrite = existing != null;
        String packId = overwrite ? existing.getId() : UUID.randomUUID().toString();

        if (overwrite) {
            sharedRepo.refreshPack(packId, name, blurb, entryCount, tenantId);
            sharedRepo.deleteEntriesOfPack(packId);
        } else {
            SharedLorebookPack pack = new SharedLorebookPack();
            pack.setId(packId);
            pack.setName(name);
            pack.setBlurb(blurb);
            pack.setPublisherTenantId(tenantId);
            pack.setPublisherUserId(TenantContext.getUserId());
            pack.setOriginWorldId(worldId);
            pack.setOriginWorldName(world.getName());
            pack.setEntryCount(entryCount);
            sharedRepo.insertPack(pack);
        }
        for (WorldbookEntry template : wbTemplates) {
            SharedWorldbookEntry snapshot = new SharedWorldbookEntry();
            snapshot.setId(UUID.randomUUID().toString());
            snapshot.setPackId(packId);
            snapshot.setTitle(template.getTitle());
            snapshot.setKeywords(template.getKeywords());
            snapshot.setContent(template.getContent());
            snapshot.setEnabled(template.getEnabled());
            snapshot.setSortOrder(template.getSortOrder());
            sharedRepo.insertWorldbookEntry(snapshot);
        }
        for (StarIncarnation template : stTemplates) {
            SharedStarEntry snapshot = new SharedStarEntry();
            snapshot.setId(UUID.randomUUID().toString());
            snapshot.setPackId(packId);
            snapshot.setStarName(template.getStarName());
            snapshot.setWarlordName(template.getWarlordName());
            snapshot.setLore(template.getLore());
            snapshot.setAliases(template.getAliases());
            snapshot.setRollable(template.getRollable());
            snapshot.setSortOrder(template.getSortOrder());
            snapshot.setCategory(template.getCategory());
            sharedRepo.insertStarEntry(snapshot);
        }
        log.info("共享包已发布: packId={}, world={}, 通用 {} 条 + 冥星 {} 条, 覆盖={}",
                packId, worldId, wbTemplates.size(), stTemplates.size(), overwrite);
        return new PublishResult(packId, overwrite, entryCount);
    }

    // ==================== 画廊 / 详情 ====================

    /** 画廊列表：全平台在架包（读侧无租户过滤） */
    public List<SharedLorebookPack> listVisible() {
        return sharedRepo.listVisible();
    }

    /** 包详情（含双轨条目）；revoked/不存在返回 null（供控制层判 404） */
    public PackDetail loadPack(String packId) {
        SharedLorebookPack pack = sharedRepo.findVisibleById(packId);
        if (pack == null) {
            return null;
        }
        return new PackDetail(pack,
                sharedRepo.readWorldbookEntries(packId),
                sharedRepo.readStarEntries(packId));
    }

    /** 包详情 DTO：元数据 + 双轨条目（画廊抽屉展示） */
    public record PackDetail(SharedLorebookPack pack,
                             List<SharedWorldbookEntry> worldbookEntries,
                             List<SharedStarEntry> starEntries) {
    }

    // ==================== 导入 ====================

    /** 导入结果：写入接收方的两轨条目数与目标世界 */
    public record ImportResult(int worldbookCount, int starCount, String targetWorldId) {
    }

    /**
     * 导入共享包到当前租户的目标世界模板层。
     * <p>
     * 前置校验（design D5）：① 目标世界非 preset- 前缀（物化 rewriteAll 会清掉导入行）；
     * ② 目标世界存在且属本租户；③ 包内冥星星名与目标世界已有模板名录无冲突（star_name 是
     * 揭示/抢星 runtime 稳定关联键）。写入：新行 id、tenant_id 归导入方（既有 repo 强制过滤）、
     * world_id 重绑、通用轨 source_ref 置 NULL、冥星轨保留 star_name。set/runtime 行零触碰。
     */
    public ImportResult importPack(String packId, String targetWorldId) {
        SharedLorebookPack pack = sharedRepo.findVisibleById(packId);
        if (pack == null) {
            throw new NoSuchElementException("共享包不存在或已下架: " + packId);
        }
        if (targetWorldId == null || targetWorldId.isBlank()) {
            throw new IllegalArgumentException("目标世界不能为空");
        }
        if (targetWorldId.startsWith(PRESET_WORLD_PREFIX)) {
            throw new IllegalArgumentException(
                    "不支持导入到物化预设世界（重物化会清除导入内容），请选择自建世界");
        }
        if (worldRepo.findById(targetWorldId) == null) {
            throw new IllegalArgumentException("目标世界不存在或不属于当前租户: " + targetWorldId);
        }

        List<SharedWorldbookEntry> wbEntries = sharedRepo.readWorldbookEntries(packId);
        List<SharedStarEntry> stEntries = sharedRepo.readStarEntries(packId);

        // 同名星冲突校验：包内星名 ∩ 目标世界模板名录（含 set 行名录？模板行即可——冲突挂 runtime 的是模板名录）
        Set<String> existingStarNames = new LinkedHashSet<>();
        for (StarIncarnation star : starRepo.findByWorldId(targetWorldId)) {
            existingStarNames.add(star.getStarName());
        }
        List<String> conflicts = new ArrayList<>();
        for (SharedStarEntry entry : stEntries) {
            if (existingStarNames.contains(entry.getStarName())) {
                conflicts.add(entry.getStarName());
            }
        }
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("目标世界已有同名冥星，导入会错挂揭示/抢星状态: "
                    + String.join("、", conflicts));
        }

        // 逐轨快照复制为接收方模板行（新 id、world_id 重绑、source_ref NULL、star_name 保留）
        for (SharedWorldbookEntry snapshot : wbEntries) {
            WorldbookEntry imported = new WorldbookEntry();
            imported.setId(UUID.randomUUID().toString());
            imported.setWorldId(targetWorldId);
            imported.setTitle(snapshot.getTitle());
            imported.setKeywords(snapshot.getKeywords());
            imported.setContent(snapshot.getContent());
            imported.setEnabled(snapshot.getEnabled());
            imported.setSortOrder(snapshot.getSortOrder());
            imported.setSetId(null);
            imported.setSourceRef(null);
            worldbookRepo.save(imported);
        }
        for (SharedStarEntry snapshot : stEntries) {
            StarIncarnation imported = new StarIncarnation();
            imported.setId(UUID.randomUUID().toString());
            imported.setWorldId(targetWorldId);
            imported.setStarName(snapshot.getStarName());
            imported.setWarlordName(snapshot.getWarlordName());
            imported.setLore(snapshot.getLore());
            imported.setAliases(snapshot.getAliases());
            imported.setRollable(snapshot.getRollable());
            imported.setSortOrder(snapshot.getSortOrder());
            imported.setCategory(snapshot.getCategory());
            imported.setSetId(null);
            imported.setSourceRef(null);
            starRepo.save(imported);
        }
        log.info("共享包已导入: packId={}, targetWorld={}, 通用 {} 条 + 冥星 {} 条",
                packId, targetWorldId, wbEntries.size(), stEntries.size());
        return new ImportResult(wbEntries.size(), stEntries.size(), targetWorldId);
    }

    // ==================== 撤回 ====================

    /**
     * 撤回（软下架）自己的包：置 revoked_at，画廊不再列出、详情 404；
     * 已导入副本零清理（快照哲学，无回链）。
     *
     * @return true = 撤回成功；false = 包不存在/非发布者/已在架外（供控制层判 404）
     */
    public boolean revoke(String packId) {
        boolean revoked = sharedRepo.revoke(packId, TenantContext.requireTenantId(), java.time.Instant.now()) > 0;
        if (revoked) {
            log.info("共享包已撤回: packId={}", packId);
        }
        return revoked;
    }
}
