package com.luyu.agent.rpg.story.controller;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.luyu.agent.rpg.story.engine.StoryGraphFinalizeReport;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.service.StoryGraphWorkshopService;

/**
 * 工坊故事图 REST API（story-graph-authoring tasks 3.1，design D3 world 模板层）。
 * <p>
 * 建图（POST world 维度）/ 列表 / 详情 / 图级全量保存（幕/节点/边/变体都在
 * graph_json，前端表单组装提交）+ 保存时即时校验拒答（400 带 errors 清单，
 * spec「编辑视图修改变体并即时校验」）/ 整稿通过（全图校验链报告逐条定位，
 * 通过 200 修复后图、拒绝 409 报告）/ 删除（运行副本守卫 409）。
 * <p>
 * 异常映射惯例（对齐 WorkshopController / StageGateController）：
 * IllegalArgumentException → 400 / NoSuchElementException → 404（跨租户同
 * 语义，不泄露存在性）/ IllegalStateException → 409。
 */
@RestController
@RequestMapping("/rpg/workshop")
public class StoryGraphWorkshopController {

    private static final Logger log = LoggerFactory.getLogger(StoryGraphWorkshopController.class);

    private final StoryGraphWorkshopService service;

    public StoryGraphWorkshopController(StoryGraphWorkshopService service) {
        this.service = service;
    }

    // ==================== 建图 / 列表 / 详情 ====================

    /** 建图（body {name, improviseMode?, improviseDepthLimit?, stateSchema?}）；world 不存在 404 */
    @PostMapping("/worlds/{worldId}/story-graphs")
    public ResponseEntity<Object> create(@PathVariable String worldId,
                                         @RequestBody Map<String, String> body) {
        try {
            StoryGraph graph = service.create(worldId, body.get("name"),
                    body.get("improviseMode"), optionalInt(body, "improviseDepthLimit"),
                    body.get("stateSchema"));
            return ResponseEntity.ok(graph);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** world 维度列出全部故事图（一 world 多图，工坊编辑视图入口） */
    @GetMapping("/worlds/{worldId}/story-graphs")
    public List<StoryGraph> listByWorld(@PathVariable String worldId) {
        return service.listByWorld(worldId);
    }

    /** 图详情（graph_json 全量内联，编辑视图初始值零额外请求）；404 不泄露存在性 */
    @GetMapping("/story-graphs/{id}")
    public ResponseEntity<StoryGraph> get(@PathVariable String id) {
        StoryGraph graph = service.get(id);
        return graph != null ? ResponseEntity.ok(graph) : ResponseEntity.notFound().build();
    }

    // ==================== 保存（即时校验） ====================

    /**
     * 图级全量保存（body {name?, improviseMode?, improviseDepthLimit?, stateSchema?, graphJson?}，
     * 可空字段不更新）：保存时即时校验（词汇表/guard 语法/引用键白名单），
     * 错误 400 带完整清单不落库；成功 200 返回更新后图。
     */
    @PutMapping("/story-graphs/{id}")
    public ResponseEntity<Object> save(@PathVariable String id,
                                       @RequestBody Map<String, String> body) {
        try {
            StoryGraph graph = service.save(id, body.get("name"), body.get("improviseMode"),
                    optionalInt(body, "improviseDepthLimit"),
                    body.get("stateSchema"), body.get("graphJson"));
            return ResponseEntity.ok(graph);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /** 可选整数提取（即兴深度上限等）：空/缺失 → null；非整数 → 400 语义 IAE */
    private static Integer optionalInt(Map<String, String> body, String key) {
        String raw = body.get(key);
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数: " + raw);
        }
    }

    // ==================== 整稿通过（全图校验链） ====================

    /**
     * 整稿通过：全图确定性校验链（与 planner 产物同一校验器组件）。通过 200
     * {errors:[], warnings, repairs, graph}（修复后图已落库）；拒绝 409
     * {error, errors, warnings, repairs}（图保持编辑态，失败项逐条定位幕/节点/边 ID）。
     */
    @PostMapping("/story-graphs/{id}/finalize")
    public ResponseEntity<Object> finalizeGraph(@PathVariable String id) {
        try {
            StoryGraphFinalizeReport report = service.finalizeGraph(id);
            if (!report.ok()) {
                return ResponseEntity.status(409).body(Map.of(
                        "error", "整稿通过校验未通过（图未落库）",
                        "errors", report.getErrors(),
                        "warnings", report.getWarnings(),
                        "repairs", report.getRepairs()));
            }
            return ResponseEntity.ok(Map.of(
                    "errors", report.getErrors(),
                    "warnings", report.getWarnings(),
                    "repairs", report.getRepairs(),
                    "graph", report.getGraph()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("整稿通过序列化异常: id={}", id, e);
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 删除 ====================

    /** 删图：运行副本存在 409；不存在 404；成功 204 */
    @DeleteMapping("/story-graphs/{id}")
    public ResponseEntity<Object> delete(@PathVariable String id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }
}
