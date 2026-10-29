package com.luyu.agent.rpg.story.controller;

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

import com.luyu.agent.rpg.story.model.SharedStoryGraph;
import com.luyu.agent.rpg.story.service.SharedStoryGraphService;

/**
 * 共享故事图包端点（story-graph-authoring tasks 3.3，对齐 SharedPackController）。
 * <p>
 * 全部端点在 {@code JwtTenantFilter} 之后（{@code /rpg/} 前缀不在 PUBLIC_PATHS
 * 白名单，天然要求登录）：发布/导入/撤回依赖 TenantContext 判权；画廊列表与
 * 详情仅依赖"已登录"（读侧全平台可见，无租户过滤）。异常映射参照
 * WorkshopController/SharedPackController 惯例：IllegalArgumentException →
 * 400（校验拒绝）、IllegalStateException → 409（未过整稿链不能发布）、
 * NoSuchElementException → 404（包不存在）。
 */
@RestController
@RequestMapping("/rpg/shared-story-graphs")
public class SharedStoryGraphController {

    private static final Logger log = LoggerFactory.getLogger(SharedStoryGraphController.class);

    private final SharedStoryGraphService sharedStoryGraphService;

    public SharedStoryGraphController(SharedStoryGraphService sharedStoryGraphService) {
        this.sharedStoryGraphService = sharedStoryGraphService;
    }

    /**
     * 画廊列表：全平台在架包（读侧无租户过滤，登录即可见）。
     * <p>
     * 响应: 200 [{id, name, blurb, publisherTenantId, originWorldName, originGraphId,
     * nodeCount, snapshotVersion, updatedAt, ...}]
     */
    @GetMapping
    public List<SharedStoryGraph> listPacks() {
        return sharedStoryGraphService.listVisible();
    }

    /**
     * 包详情（含 snapshot_json 全图快照，导入预览用）；不存在或已撤回 404。
     */
    @GetMapping("/{id}")
    public ResponseEntity<SharedStoryGraph> getPack(@PathVariable String id) {
        SharedStoryGraph pack = sharedStoryGraphService.loadPack(id);
        return pack != null ? ResponseEntity.ok(pack) : ResponseEntity.notFound().build();
    }

    /**
     * 发布 / 覆盖更新共享故事图包（仅发布者本人可覆盖）。
     * <p>
     * 请求: POST /rpg/shared-story-graphs，体 {graphId, name, blurb}<br>
     * 响应: 200 {packId, overwrite, nodeCount}；参数/图无效 400；
     * 图未过整稿校验链 409（error 带逐条失败清单）
     */
    @PostMapping
    public ResponseEntity<?> publish(@RequestBody Map<String, String> request) {
        String graphId = request != null ? request.get("graphId") : null;
        String name = request != null ? request.get("name") : null;
        String blurb = request != null ? request.get("blurb") : null;
        try {
            SharedStoryGraphService.PublishResult result =
                    sharedStoryGraphService.publish(graphId, name, blurb);
            return ResponseEntity.ok(Map.of(
                    "packId", result.packId(),
                    "overwrite", result.overwrite(),
                    "nodeCount", result.nodeCount()));
        } catch (IllegalArgumentException e) {
            log.warn("共享故事图发布被拒: graph={}, reason={}", graphId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("共享故事图发布未过整稿链: graph={}", graphId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 导入共享故事图包到当前租户的目标世界模板层（物化副本）。
     * <p>
     * 请求: POST /rpg/shared-story-graphs/{id}/import，体 {worldId: 目标世界id}<br>
     * 响应: 200 {graphId, targetWorldId, name}；包不存在 404；
     * preset- 前缀目标/目标世界无效/坏快照 400
     */
    @PostMapping("/{id}/import")
    public ResponseEntity<?> importPack(@PathVariable String id, @RequestBody Map<String, String> request) {
        String worldId = request != null ? request.get("worldId") : null;
        try {
            SharedStoryGraphService.ImportResult result =
                    sharedStoryGraphService.importGraph(id, worldId);
            return ResponseEntity.ok(Map.of(
                    "graphId", result.graphId(),
                    "targetWorldId", result.targetWorldId(),
                    "name", result.name()));
        } catch (IllegalArgumentException e) {
            log.warn("共享故事图导入被拒: pack={}, world={}, reason={}", id, worldId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (java.util.NoSuchElementException e) {
            log.info("共享故事图导入目标不存在: pack={}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 撤回（软下架）自己的包；非发布者或不存在 404；成功 204。
     * <p>
     * 已导入副本不受影响（快照哲学，无回链）。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        boolean revoked = sharedStoryGraphService.revoke(id);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
