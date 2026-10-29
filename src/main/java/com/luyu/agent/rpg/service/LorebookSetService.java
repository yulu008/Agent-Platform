package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLorebookSetRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 世界书副本层服务（add-rpg-lorebook-sets D2–D8）。
 * <p>
 * 三层模型的中间层：世界模板（set_id NULL 行，md/工坊单一写手）→ lorebook set
 * （开局快照/继承 fork，游戏内弹窗单一写手）→ 存档 runtime（揭示/抢星，引擎写手）。
 * <p>
 * 读取统一入口 {@link #resolve}：存档有 set 读 set 行，无 set（老存档）回退模板行；
 * 首次写入经 {@link #ensureSet} 事务内物化。fork 在 {@code /start} 同步完成
 * （{@link #copyForNewGame}），首轮扫描与抢星判定立刻读到新 set 名录。
 * <p>
 * 预设人物档案物化（rpg-lore-archival-loading D1）：开局 fork 与懒迁移两入口在
 * set 落库的同一时点同步物化记忆档案（武将录 + NPC 角色卡，见
 * {@link LoreArchiveService#materializePresetArchives}）；武将星名改名在
 * {@link #saveStar} 的事务内同步重命名档案与索引行（D9）。
 */
@Service
public class LorebookSetService {

    private static final Logger log = LoggerFactory.getLogger(LorebookSetService.class);

    /** 世界默认来源的展示名/出处 */
    public static final String ORIGIN_WORLD_DEFAULT = "世界默认";

    private final RpgGameStateRepository stateRepo;
    private final RpgLorebookSetRepository setRepo;
    private final RpgStarIncarnationRepository starRepo;
    private final RpgWorldbookEntryRepository entryRepo;
    private final RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private final LoreArchiveService loreArchiveService;
    private final TransactionTemplate txTemplate;

    public LorebookSetService(RpgGameStateRepository stateRepo,
                              RpgLorebookSetRepository setRepo,
                              RpgStarIncarnationRepository starRepo,
                              RpgWorldbookEntryRepository entryRepo,
                              RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                              LoreArchiveService loreArchiveService,
                              TransactionTemplate txTemplate) {
        this.stateRepo = stateRepo;
        this.setRepo = setRepo;
        this.starRepo = starRepo;
        this.entryRepo = entryRepo;
        this.starRuntimeRepo = starRuntimeRepo;
        this.loreArchiveService = loreArchiveService;
        this.txTemplate = txTemplate;
    }

    /**
     * 存档的世界书视图：set 元数据 + 双轨条目。
     * <p>存档无 set（老存档）时 set=null、条目为模板行（只读回退语义）；存档不存在返回 null。
     */
    public record ResolvedView(LorebookSet set, List<StarIncarnation> stars, List<WorldbookEntry> entries) {}

    /** 导入候选：模板中 ref 不在当前 set 的条目（D6） */
    public record ImportCandidates(List<StarIncarnation> stars, List<WorldbookEntry> entries) {}

    public ResolvedView resolve(String gameStateId) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            return null;
        }
        LorebookSet set = setRepo.findByGameStateId(gameStateId);
        if (set == null) {
            return new ResolvedView(null, starRepo.findByWorldId(gs.getWorldId()), entryRepo.findByWorldId(gs.getWorldId()));
        }
        return new ResolvedView(set, starRepo.findBySetId(set.getId()), entryRepo.findBySetId(set.getId()));
    }

    public LorebookSet findSet(String gameStateId) {
        return setRepo.findByGameStateId(gameStateId);
    }

    /**
     * 开局来源列表（D9 sources）：本世界全部 set（含孤儿），按创建时间序。
     * 供向导「世界书来源」步渲染（前端另附一条 id=null 的「世界默认」合成项）。
     */
    public List<LorebookSet> listSources(String worldId) {
        return setRepo.findByWorldId(worldId);
    }

    /**
     * 取存档 set，无则事务内物化（D5 懒迁移）：复制当前模板行建 set 并绑定存档。
     * 存档不存在返回 null。
     */
    public LorebookSet ensureSet(String gameStateId) {
        LorebookSet existing = setRepo.findByGameStateId(gameStateId);
        if (existing != null) {
            return existing;
        }
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            return null;
        }
        LorebookSet set = txTemplate.execute(status -> {
            LorebookSet s = newSet(gs.getWorldId(), ORIGIN_WORLD_DEFAULT,
                    ORIGIN_WORLD_DEFAULT + "（首次编辑物化）", gameStateId);
            copyTemplateRowsInto(gs.getWorldId(), s.getId());
            log.info("老存档世界书懒物化: gameStateId={}, setId={}", gameStateId, s.getId());
            return s;
        });
        // 预设人物档案物化（rpg-lore-archival-loading D1/D6）：懒迁移首物化的同点补档
        // （老存档兼容——无物化档案的存档由此获得预设档案，不阻断后续编辑；
        // 懒路径无抢星候选，claimedStarName/figureKey 均传 null——人物局开局必走 fork 路径）
        materializeArchives(gameStateId, gs.getWorldId(), set, null, null);
        return set;
    }

    /**
     * 开局 fork（D4）：来源 = 世界模板（inheritSetId 缺省）或源 set；逐行复制为新 set。
     * 源 set 不存在或不属于该世界时抛 IllegalArgumentException。
     */
    public LorebookSet copyForNewGame(String worldId, String inheritSetId, String gameStateId) {
        return copyForNewGame(worldId, inheritSetId, gameStateId, null);
    }

    /**
     * 开局 fork（rpg-lore-archival-loading D1/D2）：claimedStarName 非空时，命中的武将
     * 档案正文写入「星归玩家」变体段（命中条件与抢星判定一致，见
     * {@link LoreArchiveService#materializePresetArchives}）。set 落库事务后同点物化档案。
     */
    public LorebookSet copyForNewGame(String worldId, String inheritSetId,
                                      String gameStateId, String claimedStarName) {
        return copyForNewGame(worldId, inheritSetId, gameStateId, claimedStarName, null);
    }

    /**
     * 开局 fork 全量重载（preset-destined-figures D3）：figureKey 非空 = 人物扮演局撞星，
     * 命中档案变体段与索引标记切换为「人即玩家」双语义；缺省 null 维持旧语义。
     * 其余行为与四参重载一致（对齐 rpg-lore-archival-loading D2 的传参惯例）。
     */
    public LorebookSet copyForNewGame(String worldId, String inheritSetId,
                                      String gameStateId, String claimedStarName, String figureKey) {
        LorebookSet source = null;
        if (inheritSetId != null && !inheritSetId.isBlank()) {
            source = setRepo.findById(inheritSetId);
            if (source == null || !worldId.equals(source.getWorldId())) {
                throw new IllegalArgumentException("继承来源不存在或不属于该世界: " + inheritSetId);
            }
        }
        LorebookSet src = source;
        LorebookSet set = txTemplate.execute(status -> {
            String label = src == null ? ORIGIN_WORLD_DEFAULT : "继承自「" + src.getName() + "」";
            LorebookSet s = newSet(worldId, label, label, gameStateId);
            if (src == null) {
                copyTemplateRowsInto(worldId, s.getId());
            } else {
                copySetRowsInto(src.getId(), s.getId());
            }
            log.info("世界书副本已创建: gameStateId={}, setId={}, origin={}", gameStateId, s.getId(), label);
            return s;
        });
        // 预设人物档案物化（D1）：开局快照，与 set 落库同时点；不写索引（剧透边界 D3）
        materializeArchives(gameStateId, worldId, set, claimedStarName, figureKey);
        return set;
    }

    // ==================== set 内条目 CRUD（弹窗编辑入口） ====================

    /**
     * 保存武将录条目（id 空 = 新增）。starName 改名时同事务同步本存档 runtime 星名（D8）。
     * <p>
     * 懒迁移（D5）：无 set 存档首次编辑时 ensureSet 先物化，此时 payload 仍携<b>模板行 id</b>，
     * 而物化出的 set 行是新 id（source_ref=starName）——故 id 命中模板行时按 source_ref 定位
     * 对应 set 行再更新，保证「看到的模板行可直接编辑」。
     *
     * @return 保存后的行；目标行不属于本存档 set（且非可映射的模板行）时返回 null（控制层判 404）
     */
    public StarIncarnation saveStar(String gameStateId, StarIncarnation payload) {
        // 分类规范化（add-star-lorebook-category D5）：先校验后物化，超长直接 400，不白白物化 set
        payload.normalizeCategory();
        LorebookSet set = ensureSet(gameStateId);
        if (set == null) {
            return null;
        }
        return txTemplate.execute(status -> {
            if (payload.getId() != null && !payload.getId().isBlank()) {
                StarIncarnation row = starRepo.findById(payload.getId());
                if (row == null) {
                    return null;
                }
                StarIncarnation target;
                if (set.getId().equals(row.getSetId())) {
                    target = row;
                } else if (row.getSetId() == null && set.getWorldId().equals(row.getWorldId())) {
                    // 模板行 id → 刚物化的 set 行（source_ref=starName）
                    target = findSetStarBySourceRef(set.getId(), row.getStarName());
                    if (target == null) {
                        return null;
                    }
                    payload.setId(target.getId());
                } else {
                    return null;
                }
                if (target.getStarName() != null && !target.getStarName().equals(payload.getStarName())) {
                    starRuntimeRepo.renameStar(gameStateId, target.getStarName(), payload.getStarName());
                    // 档案同步（rpg-lore-archival-loading D9）：lore_<旧>.md → lore_<新>.md
                    // + 索引行迁移（服务内部降级告警，不阻断本事务提交）
                    loreArchiveService.renameStarArchive(gameStateId, target.getStarName(),
                            payload.getStarName(), payload.getWarlordName());
                }
                payload.setSetId(set.getId());
                payload.setSourceRef(target.getSourceRef());
                payload.setWorldId(set.getWorldId());
                if (payload.getSortOrder() == null) {
                    payload.setSortOrder(target.getSortOrder());
                }
                starRepo.update(payload);
                return payload;
            }
            payload.setId(UUID.randomUUID().toString());
            payload.setWorldId(set.getWorldId());
            payload.setSetId(set.getId());
            payload.setSourceRef(null);
            if (payload.getSortOrder() == null) {
                payload.setSortOrder(nextSortOrder(starRepo.findBySetId(set.getId())));
            }
            starRepo.save(payload);
            return payload;
        });
    }

    /**
     * 保存通用世界书条目（id 空 = 新增；enabled toggling 同路径）。懒迁移映射同 saveStar
     * （模板行 id → source_ref=模板行 id 的 set 行）。
     *
     * @return 保存后的行；目标行不属于本存档 set（且非可映射的模板行）时返回 null
     */
    public WorldbookEntry saveEntry(String gameStateId, WorldbookEntry payload) {
        LorebookSet set = ensureSet(gameStateId);
        if (set == null) {
            return null;
        }
        return txTemplate.execute(status -> {
            if (payload.getId() != null && !payload.getId().isBlank()) {
                WorldbookEntry row = entryRepo.findById(payload.getId());
                if (row == null) {
                    return null;
                }
                WorldbookEntry target;
                if (set.getId().equals(row.getSetId())) {
                    target = row;
                } else if (row.getSetId() == null && set.getWorldId().equals(row.getWorldId())) {
                    target = findSetEntryBySourceRef(set.getId(), row.getId());
                    if (target == null) {
                        return null;
                    }
                    payload.setId(target.getId());
                } else {
                    return null;
                }
                payload.setSetId(set.getId());
                payload.setSourceRef(target.getSourceRef());
                payload.setWorldId(set.getWorldId());
                if (payload.getSortOrder() == null) {
                    payload.setSortOrder(target.getSortOrder());
                }
                entryRepo.update(payload);
                return payload;
            }
            payload.setId(UUID.randomUUID().toString());
            payload.setWorldId(set.getWorldId());
            payload.setSetId(set.getId());
            payload.setSourceRef(null);
            if (payload.getSortOrder() == null) {
                payload.setSortOrder(nextSortOrder(entryRepo.findBySetId(set.getId())));
            }
            entryRepo.save(payload);
            return payload;
        });
    }

    /**
     * 删除 set 内武将条目（揭示 runtime 行保留，自然失活）。懒迁移：无 set 存档首次删除
     * 亦先物化；id 可为 set 行 id 或模板行 id（后者按 source_ref 映射）。
     *
     * @return 受影响行数；非本 set（且不可映射）返回 0
     */
    public int deleteStar(String gameStateId, String id) {
        LorebookSet set = ensureSet(gameStateId);
        if (set == null) {
            return 0;
        }
        return txTemplate.execute(status -> {
            int n = starRepo.deleteByIdAndSet(id, set.getId());
            if (n > 0) {
                return n;
            }
            StarIncarnation row = starRepo.findById(id);
            if (row != null && row.getSetId() == null && set.getWorldId().equals(row.getWorldId())) {
                StarIncarnation target = findSetStarBySourceRef(set.getId(), row.getStarName());
                if (target != null) {
                    return starRepo.deleteByIdAndSet(target.getId(), set.getId());
                }
            }
            return 0;
        });
    }

    /** 删除 set 内通用条目；懒迁移映射同 deleteStar。非本 set（且不可映射）返回 0 */
    public int deleteEntry(String gameStateId, String id) {
        LorebookSet set = ensureSet(gameStateId);
        if (set == null) {
            return 0;
        }
        return txTemplate.execute(status -> {
            int n = entryRepo.deleteByIdAndSet(id, set.getId());
            if (n > 0) {
                return n;
            }
            WorldbookEntry row = entryRepo.findById(id);
            if (row != null && row.getSetId() == null && set.getWorldId().equals(row.getWorldId())) {
                WorldbookEntry target = findSetEntryBySourceRef(set.getId(), row.getId());
                if (target != null) {
                    return entryRepo.deleteByIdAndSet(target.getId(), set.getId());
                }
            }
            return 0;
        });
    }

    // ==================== 模板单向导入（D6） ====================

    /** 导入候选：无 set（未物化）时为空（模板内容已在回退视图全量可见） */
    public ImportCandidates importCandidates(String gameStateId) {
        LorebookSet set = setRepo.findByGameStateId(gameStateId);
        if (set == null) {
            return new ImportCandidates(List.of(), List.of());
        }
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            return new ImportCandidates(List.of(), List.of());
        }
        List<StarIncarnation> setStars = starRepo.findBySetId(set.getId());
        List<WorldbookEntry> setEntries = entryRepo.findBySetId(set.getId());
        Set<String> starNames = new HashSet<>();
        for (StarIncarnation s : setStars) {
            starNames.add(s.getStarName());
        }
        Set<String> refs = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (WorldbookEntry e : setEntries) {
            if (e.getSourceRef() != null) {
                refs.add(e.getSourceRef());
            }
            titles.add(e.getTitle());
        }
        List<StarIncarnation> starCandidates = new ArrayList<>();
        for (StarIncarnation t : starRepo.findByWorldId(gs.getWorldId())) {
            if (!starNames.contains(t.getStarName())) {
                starCandidates.add(t);
            }
        }
        List<WorldbookEntry> entryCandidates = new ArrayList<>();
        for (WorldbookEntry t : entryRepo.findByWorldId(gs.getWorldId())) {
            if (!refs.contains(t.getId()) && !titles.contains(t.getTitle())) {
                entryCandidates.add(t);
            }
        }
        return new ImportCandidates(starCandidates, entryCandidates);
    }

    /**
     * 导入选中模板条目为 set 行（保留 source_ref）；已缺席判定防重复。
     *
     * @return 导入的行数
     */
    public int importEntries(String gameStateId, List<String> starRefs, List<String> entryRefs) {
        LorebookSet set = ensureSet(gameStateId);
        if (set == null) {
            return 0;
        }
        ImportCandidates candidates = importCandidates(gameStateId);
        int[] count = {0};
        txTemplate.executeWithoutResult(status -> {
            for (StarIncarnation t : candidates.stars()) {
                if (starRefs != null && starRefs.contains(t.getStarName())) {
                    starRepo.save(copyStar(t, set.getId()));
                    count[0]++;
                }
            }
            for (WorldbookEntry t : candidates.entries()) {
                if (entryRefs != null && entryRefs.contains(t.getId())) {
                    entryRepo.save(copyEntry(t, set.getId()));
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    // ==================== 生命周期（D7） ====================

    /** 存档删除孤儿化：set 与条目行保留，仅引用置空 */
    public int orphanize(String gameStateId) {
        return setRepo.orphanizeByGameStateId(gameStateId);
    }

    /** 删除孤儿 set 及其条目行；set 不存在返回 -1，仍被存档引用返回 -2 */
    public int deleteOrphanSet(String setId) {
        LorebookSet set = setRepo.findById(setId);
        if (set == null) {
            return -1;
        }
        if (set.getGameStateId() != null) {
            return -2;
        }
        return txTemplate.execute(status -> {
            starRepo.deleteBySetId(setId);
            entryRepo.deleteBySetId(setId);
            return setRepo.deleteOrphan(setId);
        });
    }

    // ==================== 内部工具 ====================

    /**
     * 档案物化挂载（rpg-lore-archival-loading D1；figureKey 透传 preset-destined-figures D3）：
     * set 落库后同点快照落盘。set 为 null（存档不存在等异常路径）时跳过；
     * 物化失败由服务内部降级，不阻断调用方。
     */
    private void materializeArchives(String gameStateId, String worldId,
                                     LorebookSet set, String claimedStarName, String figureKey) {
        if (set == null) {
            return;
        }
        loreArchiveService.materializePresetArchives(
                gameStateId, worldId, starRepo.findBySetId(set.getId()), claimedStarName, figureKey);
    }

    private LorebookSet newSet(String worldId, String name, String origin, String gameStateId) {
        LorebookSet set = new LorebookSet();
        set.setId(UUID.randomUUID().toString());
        set.setWorldId(worldId);
        set.setName(name);
        set.setOrigin(origin);
        set.setGameStateId(gameStateId);
        setRepo.save(set);
        return set;
    }

    private void copyTemplateRowsInto(String worldId, String setId) {
        for (StarIncarnation t : starRepo.findByWorldId(worldId)) {
            starRepo.save(copyStar(t, setId));
        }
        for (WorldbookEntry t : entryRepo.findByWorldId(worldId)) {
            entryRepo.save(copyEntry(t, setId));
        }
    }

    private void copySetRowsInto(String srcSetId, String setId) {
        for (StarIncarnation s : starRepo.findBySetId(srcSetId)) {
            starRepo.save(copyStar(s, setId));
        }
        for (WorldbookEntry e : entryRepo.findBySetId(srcSetId)) {
            entryRepo.save(copyEntry(e, setId));
        }
    }

    /** 复制为 set 行：模板行（set_id NULL）source_ref 取稳定键；set 行（fork/导入）保留原 source_ref；category 随行携带（add-star-lorebook-category D2） */
    private StarIncarnation copyStar(StarIncarnation t, String setId) {
        StarIncarnation copy = new StarIncarnation();
        copy.setId(UUID.randomUUID().toString());
        copy.setWorldId(t.getWorldId());
        copy.setStarName(t.getStarName());
        copy.setWarlordName(t.getWarlordName());
        copy.setLore(t.getLore());
        copy.setAliases(t.getAliases());
        copy.setRollable(t.getRollable());
        copy.setSortOrder(t.getSortOrder());
        copy.setCategory(t.getCategory());
        copy.setSetId(setId);
        copy.setSourceRef(t.getSetId() == null ? t.getStarName() : t.getSourceRef());
        return copy;
    }

    private WorldbookEntry copyEntry(WorldbookEntry t, String setId) {
        WorldbookEntry copy = new WorldbookEntry();
        copy.setId(UUID.randomUUID().toString());
        copy.setWorldId(t.getWorldId());
        copy.setTitle(t.getTitle());
        copy.setKeywords(t.getKeywords());
        copy.setContent(t.getContent());
        copy.setEnabled(t.getEnabled());
        copy.setSortOrder(t.getSortOrder());
        copy.setSetId(setId);
        copy.setSourceRef(t.getSetId() == null ? t.getId() : t.getSourceRef());
        return copy;
    }

    private int nextSortOrder(List<? extends Object> rows) {
        int max = 0;
        for (Object row : rows) {
            Integer order = row instanceof StarIncarnation s ? s.getSortOrder()
                    : row instanceof WorldbookEntry e ? e.getSortOrder() : null;
            if (order != null && order > max) {
                max = order;
            }
        }
        return max + 1;
    }

    /** 懒迁移映射：按 source_ref=starName 在本 set 内定位物化出的武将行 */
    private StarIncarnation findSetStarBySourceRef(String setId, String starName) {
        if (starName == null) {
            return null;
        }
        for (StarIncarnation s : starRepo.findBySetId(setId)) {
            if (starName.equals(s.getSourceRef())) {
                return s;
            }
        }
        return null;
    }

    /** 懒迁移映射：按 source_ref=模板行 id 在本 set 内定位物化出的通用条目行 */
    private WorldbookEntry findSetEntryBySourceRef(String setId, String templateId) {
        if (templateId == null) {
            return null;
        }
        for (WorldbookEntry e : entryRepo.findBySetId(setId)) {
            if (templateId.equals(e.getSourceRef())) {
                return e;
            }
        }
        return null;
    }
}
