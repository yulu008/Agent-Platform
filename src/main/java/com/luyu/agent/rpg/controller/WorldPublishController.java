package com.luyu.agent.rpg.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.luyu.agent.rpg.model.SharedWorld;
import com.luyu.agent.rpg.service.WorldPublishService;
import com.luyu.agent.rpg.service.WorldCoverStorageService;

/**
 * 共享世界发布端点（works-view-and-world-publish，design D8）。
 * <p>
 * 全部端点在 {@code JwtTenantFilter} 之后（{@code /rpg/} 前缀不在 PUBLIC_PATHS 白名单，
 * 天然要求登录）：发布/撤销/导入依赖 TenantContext 判权；橱窗列表/详情/封面仅依赖
 * "已登录"（读侧全平台可见，无租户过滤）。异常映射参照 SharedPackController 惯例：
 * IllegalArgumentException → 400（校验拒绝）、IllegalStateException → 409（发布者
 * 本人导入被拒）、NoSuchElementException → 404（包不存在/已下架）。
 * <p>
 * 纯复制运算（DB 快照读写 + 文件复制），刻意不套 QuotaGuard 与审查闸门。
 */
@RestController
@RequestMapping("/rpg/shared-worlds")
public class WorldPublishController {

    private static final Logger log = LoggerFactory.getLogger(WorldPublishController.class);

    /** shared 区文件名 {packId}.{ext}：packId 即版本（覆盖重发原地刷），可安全长缓存 */
    private static final CacheControl COVER_CACHE =
            CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic();

    private final WorldPublishService publishService;
    private final WorldCoverStorageService coverStorage;

    public WorldPublishController(WorldPublishService publishService,
                                  WorldCoverStorageService coverStorage) {
        this.publishService = publishService;
        this.coverStorage = coverStorage;
    }

    /**
     * 橱窗共享列表：全平台在架包（读侧无租户过滤，登录即可见）。
     * <p>
     * 响应: 200 [{id, name, blurb, cover, publisherTenantId, originWorldName, entryCount, ...}]
     */
    @GetMapping
    public List<SharedWorld> listPacks() {
        return publishService.listVisible();
    }

    /**
     * 包详情（包级元数据，不含整包快照）；不存在或已撤销 404。
     * <p>
     * 响应: 200 {id, name, blurb, cover, publisherTenantId, originWorldName, entryCount, ...}
     */
    @GetMapping("/{id}")
    public ResponseEntity<SharedWorld> getPack(@PathVariable String id) {
        SharedWorld pack = publishService.findVisibleById(id);
        return pack != null ? ResponseEntity.ok(pack) : ResponseEntity.notFound().build();
    }

    /**
     * 发布 / 覆盖更新世界全家桶快照（仅发布者本人可覆盖，design D4）。
     * <p>
     * 请求: POST /rpg/shared-worlds，体 {worldId}<br>
     * 响应: 200 {packId, overwrite, entryCount}；世界不存在/非本租户/预设世界 400
     */
    @PostMapping
    public ResponseEntity<?> publish(@RequestBody Map<String, Object> request) {
        String worldId = str(request, "worldId");
        try {
            WorldPublishService.PublishResult result = publishService.publish(worldId);
            return ResponseEntity.ok(Map.of(
                    "packId", result.packId(),
                    "overwrite", result.overwrite(),
                    "entryCount", result.entryCount()));
        } catch (IllegalArgumentException e) {
            log.warn("共享世界发布被拒: world={}, reason={}", worldId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 撤销（软下架）自己的包；非发布者或不存在 404；成功 204。
     * <p>
     * 已导入副本不受影响（快照哲学，无回链）。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        boolean revoked = publishService.revoke(id);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * 导入共享世界快照为本地新世界（design D1：各导入各的，多副本独立）。
     * <p>
     * 请求: POST /rpg/shared-worlds/{id}/import（无请求体）<br>
     * 响应: 200 {newWorldId, entryCount}；包不存在/已下架 404；发布者本人 409
     * （前端经 showcase 的 publisherSelf 标志直接深链开局，此为 API 防线）
     */
    @PostMapping("/{id}/import")
    public ResponseEntity<?> importWorld(@PathVariable String id) {
        try {
            WorldPublishService.ImportResult result = publishService.importWorld(id);
            return ResponseEntity.ok(Map.of(
                    "newWorldId", result.newWorldId(),
                    "entryCount", result.entryCount()));
        } catch (IllegalStateException e) {
            log.info("共享世界导入被拒（发布者本人）: pack={}", id);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (java.util.NoSuchElementException e) {
            log.info("共享世界导入目标不存在: pack={}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 伺服共享包封面：shared 区文件命中 → 流式返回；再否则 404（前端渐变兜底）。
     * <p>
     * 登录即可见（跨租户共享内容）；文件名 {packId}.{ext}，覆盖重发原地刷，
     * 长缓存由文件名版本性保证（与私有封面同策略）。
     */
    @GetMapping("/{id}/cover")
    public ResponseEntity<Resource> getCover(@PathVariable String id) {
        SharedWorld pack = publishService.findVisibleById(id);
        if (pack == null || pack.getCover() == null || pack.getCover().isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Optional<java.nio.file.Path> path = coverStorage.readShared(pack.getCover());
        if (path.isEmpty()) {
            log.warn("shared 区封面文件缺失: packId={}, cover={}", id, pack.getCover());
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        coverStorage.resolveMimeType(pack.getCover()).toString()))
                .cacheControl(COVER_CACHE)
                .body(new FileSystemResource(path.get()));
    }

    /** Map 取字符串（缺省 null） */
    private static String str(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value != null ? value.toString() : null;
    }
}
