package com.luyu.agent.rpg.controller;

import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.service.LorebookSetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界书副本层 REST API（add-rpg-lorebook-sets D9）。
 * <p>
 * 游戏内弹窗的双轨（冥星武将录 + 通用世界书）就地编辑器后端，独立于工坊模板端点：
 * 所有写操作仅作用于当前存档引用的 lorebook set（经 {@link LorebookSetService} 限定归属），
 * 绝不触碰世界模板层或其他存档的 set。
 * <p>
 * 租户上下文由 {@code JwtTenantFilter} 建立、仓储层强制 {@code tenant_id} 过滤——跨租户
 * set/条目访问自然落到 404（对齐 {@code StarLorebookController}）。
 * <p>
 * 按轨门控（D9）：武将录写操作看 {@code rpg.lorebook.enabled}、通用写操作看
 * {@code rpg.worldbook.enabled}，关闭返回 503；读端点（current / import-candidates / sources）
 * 恒 200，改由响应内的 {@code lorebookEnabled}/{@code worldbookEnabled} 标志驱动前端只读降级。
 */
@RestController
@RequestMapping("/rpg/lorebook-set")
public class LorebookSetController {

    private static final Logger log = LoggerFactory.getLogger(LorebookSetController.class);

    private final LorebookSetService lorebookSetService;
    private final RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private final RpgLorebookProperties lorebookProperties;
    private final RpgWorldbookProperties worldbookProperties;

    public LorebookSetController(LorebookSetService lorebookSetService,
                                 RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                                 RpgLorebookProperties lorebookProperties,
                                 RpgWorldbookProperties worldbookProperties) {
        this.lorebookSetService = lorebookSetService;
        this.starRuntimeRepo = starRuntimeRepo;
        this.lorebookProperties = lorebookProperties;
        this.worldbookProperties = worldbookProperties;
    }

    // ==================== 弹窗读取 ====================

    /**
     * 当前存档的世界书双轨视图（弹窗抬头 + 条目 + 揭示/抢星徽章 + 双轨开关标志）。
     * <p>
     * 无 set 的老存档回退模板行、{@code materialized=false}（前端出处行标示未物化）；
     * 存档不存在 404。始终 200（读端点不门控），单轨停用由响应标志驱动前端只读降级。
     */
    @GetMapping("/current")
    public ResponseEntity<?> current(@RequestParam String gameStateId) {
        LorebookSetService.ResolvedView view = lorebookSetService.resolve(gameStateId);
        if (view == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "存档不存在: " + gameStateId));
        }
        // 揭示/抢星徽章：runtime 行以 star_name 为稳定键合并（对齐 StarLorebookController）
        Map<String, StarIncarnationRuntime> runtimeByName = new HashMap<>();
        for (StarIncarnationRuntime rt : starRuntimeRepo.findByGameStateId(gameStateId)) {
            if (rt.getStarName() != null) {
                runtimeByName.put(rt.getStarName(), rt);
            }
        }
        List<StarView> stars = new ArrayList<>();
        for (StarIncarnation s : view.stars()) {
            StarIncarnationRuntime rt = runtimeByName.get(s.getStarName());
            stars.add(new StarView(s.getId(), s.getStarName(), s.getWarlordName(), s.getLore(),
                    s.getAliases(), Boolean.TRUE.equals(s.getRollable()), s.getSortOrder(), s.getSourceRef(), s.getCategory(),
                    rt != null, rt != null && Boolean.TRUE.equals(rt.getClaimedByPlayer()),
                    rt != null ? rt.getActivatedTurn() : null));
        }
        return ResponseEntity.ok(new CurrentView(view.set(), view.set() != null, stars, view.entries(),
                lorebookProperties.isEnabled(), worldbookProperties.isEnabled()));
    }

    // ==================== 条目编辑（按轨门控） ====================

    /**
     * 保存条目（编辑/新增/停用同路径）。{@code track=star} 走武将录轨（lorebook 开关门控），
     * {@code track=worldbook} 走通用轨（worldbook 开关门控）。目标行不属于本存档 set 时 404。
     */
    @PostMapping("/entry")
    public ResponseEntity<?> saveEntry(@RequestBody EntryRequest req) {
        if (req.gameStateId() == null || req.gameStateId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gameStateId 不能为空"));
        }
        if ("star".equals(req.track())) {
            ResponseEntity<?> disabled = lorebookDisabled();
            if (disabled != null) {
                return disabled;
            }
            if (req.star() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "star 载荷不能为空"));
            }
            StarIncarnation saved;
            try {
                saved = lorebookSetService.saveStar(req.gameStateId(), req.star());
            } catch (IllegalArgumentException e) {
                // 分类超长等输入校验拒绝（add-star-lorebook-category，对齐 WorkshopController 惯例）
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            return saved == null
                    ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "存档或条目不存在"))
                    : ResponseEntity.ok(saved);
        }
        if ("worldbook".equals(req.track())) {
            ResponseEntity<?> disabled = worldbookDisabled();
            if (disabled != null) {
                return disabled;
            }
            if (req.worldbook() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "worldbook 载荷不能为空"));
            }
            WorldbookEntry saved = lorebookSetService.saveEntry(req.gameStateId(), req.worldbook());
            return saved == null
                    ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "存档或条目不存在"))
                    : ResponseEntity.ok(saved);
        }
        return ResponseEntity.badRequest().body(Map.of("error", "track 应为 star 或 worldbook"));
    }

    /** 删除条目：{@code track} 决定门控与目标表；成功 204、非本 set 或不存在 404。 */
    @DeleteMapping("/entry/{id}")
    public ResponseEntity<?> deleteEntry(@PathVariable String id,
                                         @RequestParam String gameStateId,
                                         @RequestParam String track) {
        if ("star".equals(track)) {
            ResponseEntity<?> disabled = lorebookDisabled();
            if (disabled != null) {
                return disabled;
            }
            return lorebookSetService.deleteStar(gameStateId, id) > 0
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        }
        if ("worldbook".equals(track)) {
            ResponseEntity<?> disabled = worldbookDisabled();
            if (disabled != null) {
                return disabled;
            }
            return lorebookSetService.deleteEntry(gameStateId, id) > 0
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        }
        return ResponseEntity.badRequest().body(Map.of("error", "track 应为 star 或 worldbook"));
    }

    // ==================== 模板单向导入（D6） ====================

    /**
     * 导入候选：世界模板中 ref 不在当前 set 的条目。单轨停用时该轨候选清空
     * （前端该轨只读，导入无意义）；无 set（未物化）时两轨皆空。始终 200。
     */
    @GetMapping("/import-candidates")
    public ResponseEntity<?> importCandidates(@RequestParam String gameStateId) {
        LorebookSetService.ImportCandidates c = lorebookSetService.importCandidates(gameStateId);
        List<StarIncarnation> stars = lorebookProperties.isEnabled() ? c.stars() : List.of();
        List<WorldbookEntry> entries = worldbookProperties.isEnabled() ? c.entries() : List.of();
        return ResponseEntity.ok(new LorebookSetService.ImportCandidates(stars, entries));
    }

    /**
     * 导入选中模板条目为 set 行（保留 source_ref）。按 refs 非空的轨分别门控：
     * starRefs 非空看 lorebook 开关、entryRefs 非空看 worldbook 开关，关闭 503。
     */
    @PostMapping("/import")
    public ResponseEntity<?> doImport(@RequestBody ImportRequest req) {
        if (req.gameStateId() == null || req.gameStateId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "gameStateId 不能为空"));
        }
        boolean hasStars = req.starRefs() != null && !req.starRefs().isEmpty();
        boolean hasEntries = req.entryRefs() != null && !req.entryRefs().isEmpty();
        if (hasStars) {
            ResponseEntity<?> disabled = lorebookDisabled();
            if (disabled != null) {
                return disabled;
            }
        }
        if (hasEntries) {
            ResponseEntity<?> disabled = worldbookDisabled();
            if (disabled != null) {
                return disabled;
            }
        }
        int imported = lorebookSetService.importEntries(req.gameStateId(), req.starRefs(), req.entryRefs());
        log.info("世界书导入: gameStateId={}, imported={}", req.gameStateId(), imported);
        return ResponseEntity.ok(Map.of("imported", imported));
    }

    // ==================== 开局来源与孤儿生命周期（D7/D9） ====================

    /**
     * 开局「世界书来源」候选：本世界全部 set（含孤儿，标 {@code orphan}），按创建时间序。
     * 前端另附一条 id=null 的「世界默认」合成项（对应 inheritSetId 缺省）。
     */
    @GetMapping("/sources")
    public ResponseEntity<?> sources(@RequestParam String worldId) {
        List<SourceView> list = new ArrayList<>();
        for (LorebookSet s : lorebookSetService.listSources(worldId)) {
            list.add(new SourceView(s.getId(), s.getName(), s.getOrigin(), s.getGameStateId(),
                    s.getCreatedAt(), s.getGameStateId() == null));
        }
        return ResponseEntity.ok(list);
    }

    /**
     * 删除孤儿 set 及其双轨条目行：set 不存在 404、仍被存档引用 409、成功 204。
     * 无轨门控（set 级生命周期操作，非单轨内容写入）。
     */
    @DeleteMapping("/{setId}")
    public ResponseEntity<?> deleteSet(@PathVariable String setId) {
        int r = lorebookSetService.deleteOrphanSet(setId);
        if (r == -1) {
            return ResponseEntity.notFound().build();
        }
        if (r == -2) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "该世界书仍被存档引用，无法删除"));
        }
        return ResponseEntity.noContent().build();
    }

    // ==================== 门控辅助 ====================

    private ResponseEntity<?> lorebookDisabled() {
        if (!lorebookProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "设定集功能已停用（rpg.lorebook.enabled=false）"));
        }
        return null;
    }

    private ResponseEntity<?> worldbookDisabled() {
        if (!worldbookProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "通用世界书功能已停用（rpg.worldbook.enabled=false）"));
        }
        return null;
    }

    // ==================== 响应/请求形状 ====================

    /**
     * 弹窗双轨视图。
     *
     * @param set             set 元数据（老存档未物化时为 null）
     * @param materialized    set 是否已物化（false = 老存档回退模板只读语义）
     * @param stars           武将录条目（含揭示/抢星徽章）
     * @param entries         通用世界书条目
     * @param lorebookEnabled 武将录轨开关（false 时前端该轨只读降级）
     * @param worldbookEnabled 通用轨开关（false 时前端该轨只读降级）
     */
    public record CurrentView(LorebookSet set,
                              boolean materialized,
                              List<StarView> stars,
                              List<WorldbookEntry> entries,
                              boolean lorebookEnabled,
                              boolean worldbookEnabled) {}

    /** 武将录条目视图（可编辑字段含 category + 揭示/抢星徽章，徽章以 star_name 稳定键合并 runtime） */
    public record StarView(String id,
                           String starName,
                           String warlordName,
                           String lore,
                           String aliases,
                           boolean rollable,
                           Integer sortOrder,
                           String sourceRef,
                           String category,
                           boolean activated,
                           boolean claimedByPlayer,
                           Integer activatedTurn) {}

    /** 开局来源项：{@code orphan=true} 即存档已删、仅作继承来源 */
    public record SourceView(String id,
                             String name,
                             String origin,
                             String gameStateId,
                             LocalDateTime createdAt,
                             boolean orphan) {}

    /** 条目保存请求：track 决定门控与目标轨；对应载荷字段二选一 */
    public record EntryRequest(String gameStateId, String track, StarIncarnation star, WorldbookEntry worldbook) {}

    /** 导入请求：starRefs 为武将 starName 集、entryRefs 为模板行 id 集 */
    public record ImportRequest(String gameStateId, List<String> starRefs, List<String> entryRefs) {}
}
