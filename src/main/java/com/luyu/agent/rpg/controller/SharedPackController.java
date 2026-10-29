package com.luyu.agent.rpg.controller;

import java.util.List;
import java.util.Map;

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
import org.springframework.web.bind.annotation.RestController;

import com.luyu.agent.rpg.model.SharedLorebookPack;
import com.luyu.agent.rpg.service.SharedPackService;

/**
 * 共享世界书包端点（add-shared-worldbook-publish，design D7）。
 * <p>
 * 全部端点在 {@code JwtTenantFilter} 之后（{@code /rpg/} 前缀不在 PUBLIC_PATHS 白名单，
 * 天然要求登录）：发布/导入/撤回依赖 TenantContext 判权；画廊列表与详情仅依赖"已登录"
 * （读侧全平台可见，无租户过滤）。异常映射参照 WorkshopController 惯例：
 * IllegalArgumentException → 400（校验拒绝）、IllegalStateException → 409（同名星冲突）、
 * NoSuchElementException → 404（包不存在）、详情/撤回查不到 → 404。
 */
@RestController
@RequestMapping("/rpg/shared-packs")
public class SharedPackController {

    private static final Logger log = LoggerFactory.getLogger(SharedPackController.class);

    private final SharedPackService sharedPackService;

    public SharedPackController(SharedPackService sharedPackService) {
        this.sharedPackService = sharedPackService;
    }

    /**
     * 画廊列表：全平台在架包（读侧无租户过滤，登录即可见）。
     * <p>
     * 响应: 200 [{id, name, blurb, publisherTenantId, originWorldName, entryCount, updatedAt, ...}]
     */
    @GetMapping
    public List<SharedLorebookPack> listPacks() {
        return sharedPackService.listVisible();
    }

    /**
     * 包详情（含双轨条目）；不存在或已撤回 404。
     * <p>
     * 响应: 200 {pack, worldbookEntries, starEntries}
     */
    @GetMapping("/{id}")
    public ResponseEntity<SharedPackService.PackDetail> getPack(@PathVariable String id) {
        SharedPackService.PackDetail detail = sharedPackService.loadPack(id);
        return detail != null ? ResponseEntity.ok(detail) : ResponseEntity.notFound().build();
    }

    /**
     * 发布 / 覆盖更新共享包（仅发布者本人可覆盖，design D4）。
     * <p>
     * 请求: POST /rpg/shared-packs，体 {worldId, name, blurb, worldbookIds: [], starIds: []}<br>
     * 响应: 200 {packId, overwrite, entryCount}；校验拒绝 400
     */
    @PostMapping
    public ResponseEntity<?> publish(@RequestBody Map<String, Object> request) {
        String worldId = str(request, "worldId");
        String name = str(request, "name");
        String blurb = str(request, "blurb");
        try {
            SharedPackService.PublishResult result = sharedPackService.publish(
                    worldId, name, blurb,
                    strList(request, "worldbookIds"),
                    strList(request, "starIds"));
            return ResponseEntity.ok(Map.of(
                    "packId", result.packId(),
                    "overwrite", result.overwrite(),
                    "entryCount", result.entryCount()));
        } catch (IllegalArgumentException e) {
            log.warn("共享包发布被拒: world={}, reason={}", worldId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 撤回（软下架）自己的包；非发布者或不存在 404；成功 204。
     * <p>
     * 已导入副本不受影响（快照哲学，无回链）。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        boolean revoked = sharedPackService.revoke(id);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * 导入共享包到当前租户的目标世界模板层（design D5）。
     * <p>
     * 请求: POST /rpg/shared-packs/{id}/import，体 {worldId: 目标世界id}<br>
     * 响应: 200 {worldbookCount, starCount, targetWorldId}；包不存在 404；
     * preset- 前缀目标/目标世界无效 400；同名星冲突 409（error 带冲突星名清单）
     */
    @PostMapping("/{id}/import")
    public ResponseEntity<?> importPack(@PathVariable String id, @RequestBody Map<String, String> request) {
        String worldId = request != null ? request.get("worldId") : null;
        try {
            SharedPackService.ImportResult result = sharedPackService.importPack(id, worldId);
            return ResponseEntity.ok(Map.of(
                    "worldbookCount", result.worldbookCount(),
                    "starCount", result.starCount(),
                    "targetWorldId", result.targetWorldId()));
        } catch (IllegalArgumentException e) {
            log.warn("共享包导入被拒: pack={}, world={}, reason={}", id, worldId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("共享包导入同名星冲突: pack={}, world={}, reason={}", id, worldId, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (java.util.NoSuchElementException e) {
            log.info("共享包导入目标不存在: pack={}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /** Map 取字符串（缺省 null） */
    private static String str(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value != null ? value.toString() : null;
    }

    /** Map 取字符串列表（缺省空列表，容忍单值） */
    private static List<String> strList(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return value != null && !String.valueOf(value).isBlank()
                ? List.of(String.valueOf(value)) : List.of();
    }
}
