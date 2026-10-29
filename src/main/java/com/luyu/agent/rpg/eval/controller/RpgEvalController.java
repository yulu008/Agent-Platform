package com.luyu.agent.rpg.eval.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.engine.EvalNotEnabledException;
import com.luyu.agent.rpg.eval.model.EvalRecord;
import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.rpg.eval.service.EvalReviewService;
import com.luyu.agent.rpg.eval.service.EvalRunService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估批跑与人工复核端点（content-eval tasks 6.2 / 7.2，design D12 / D10）。
 * <p>
 * 批跑域：{@code POST /rpg/eval/run}（同步返回 run id，后台异步执行）+
 * {@code GET /rpg/eval/runs}（列表）+ {@code GET /rpg/eval/runs/{id}}（汇总 +
 * records 明细，verdict/subject 过滤）。
 * <p>
 * 复核域：{@code GET /rpg/eval/review-queue}（队列：borderline + 分歧标记行）+
 * {@code POST /rpg/eval/records/{id}/review}（人工终裁——override 保留机器
 * 原判于 verdict 列，终裁写 review_final_verdict；final=fail 一键沉淀回归集）。
 * <p>
 * 鉴权走 admin 域（{@code AdminWebConfig} 已把 {@code /rpg/eval/**} 纳入
 * {@code AdminAccessInterceptor}——仅 platform_admin 放行）。
 * <p>
 * 错误语义（对齐 WorkshopController 惯例）：参数校验拒绝 → 400；eval 未启用
 * → 503 + 配置指引（D1 明确报错，绝不静默 fallback）；run/record 不存在 → 404。
 * 批跑仅由手动触发，不接入发布链路或生成时流程（spec）。
 */
@RestController
@RequestMapping("/rpg/eval")
public class RpgEvalController {

    /** JSON 列解析用（容器自动装配的是 Jackson 3，此处自建实例对齐 StaticEvalCaseAssembler 惯例） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EvalRunService runService;
    private final EvalReviewService reviewService;

    public RpgEvalController(EvalRunService runService, EvalReviewService reviewService) {
        this.runService = runService;
        this.reviewService = reviewService;
    }

    /** POST /rpg/eval/run 请求体：layer 必填二值；scope 按层必填；windowTurns 可选 */
    public record RunRequest(String layer, String worldId, String gameStateId, Integer windowTurns) {
    }

    /** 触发批跑：立即返回 run id，执行在后台虚拟线程（同步收口仅为受理回执） */
    @PostMapping("/run")
    public ResponseEntity<?> startRun(@RequestBody RunRequest request) {
        String layer = request.layer();
        if (!EvalRun.LAYER_STATIC.equals(layer) && !EvalRun.LAYER_DYNAMIC.equals(layer)) {
            return ResponseEntity.badRequest().body(Map.of("error", "layer 须为 static 或 dynamic"));
        }
        if (EvalRun.LAYER_STATIC.equals(layer)
                && (request.worldId() == null || request.worldId().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "静态层评估须指定 worldId"));
        }
        if (EvalRun.LAYER_DYNAMIC.equals(layer)
                && (request.gameStateId() == null || request.gameStateId().isBlank())) {
            return ResponseEntity.badRequest().body(Map.of("error", "动态层评估须指定 gameStateId"));
        }
        try {
            String runId = runService.startRun(layer, request.worldId(),
                    request.gameStateId(), request.windowTurns());
            return ResponseEntity.ok(Map.of("runId", runId));
        } catch (EvalNotEnabledException e) {
            // 503：eval 未启用（agent.models 无 judge 角色），附配置指引
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", EvalNotEnabledException.CONFIG_HINT));
        }
    }

    /** run 列表（倒序取最近 limit 条，默认 20 上限 100 防大响应） */
    @GetMapping("/runs")
    public ResponseEntity<List<EvalRun>> listRuns(@RequestParam(defaultValue = "20") int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return ResponseEntity.ok(runService.listRecent(bounded));
    }

    /** run 详情：汇总 + records 明细（verdict/subject 可选过滤；verdict=invalid 单列作废行） */
    @GetMapping("/runs/{id}")
    public ResponseEntity<?> runDetail(@PathVariable String id,
                                       @RequestParam(required = false) String verdict,
                                       @RequestParam(required = false) String subject) {
        EvalRun run = runService.findRun(id);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        List<EvalRecord> records = runService.listRecords(id, verdict, subject);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", run.getId());
        body.put("layer", run.getLayer());
        body.put("scope", parseJson(run.getScopeJson()));
        body.put("judgeModel", run.getJudgeModel());
        body.put("rubricVersion", run.getRubricVersion());
        body.put("status", run.getStatus());
        body.put("summary", parseJson(run.getSummaryJson()));
        body.put("costTokens", run.getCostTokens());
        body.put("costAmountMicro", run.getCostAmountMicro());
        body.put("createdAt", run.getCreatedAt());
        body.put("finishedAt", run.getFinishedAt());
        body.put("records", records);
        return ResponseEntity.ok(body);
    }

    /** POST /rpg/eval/records/{id}/review 请求体：finalVerdict 三档；regression 可选（仅 fail 可沉淀） */
    public record ReviewRequest(String finalVerdict, Boolean regression) {
    }

    /** 复核队列（borderline 自动入队 + 分歧标记行，倒序最近 limit 条，默认 50 上限 200） */
    @GetMapping("/review-queue")
    public ResponseEntity<List<EvalRecord>> reviewQueue(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(reviewService.reviewQueue(limit));
    }

    /**
     * 复核收口（人工终裁）：终裁 vs 机器 verdict 一致 confirmed / 不一致
     * override（机器原判保留于 verdict 列不覆盖）；final=fail 且 regression=true
     * 一键沉淀回归集（后续批跑必含）。终裁非三档 / 非 fail 要求沉淀 → 400。
     */
    @PostMapping("/records/{id}/review")
    public ResponseEntity<?> review(@PathVariable String id, @RequestBody ReviewRequest request) {
        if (request.finalVerdict() == null || request.finalVerdict().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "finalVerdict 须为 pass / borderline / fail"));
        }
        boolean regression = Boolean.TRUE.equals(request.regression());
        try {
            EvalRecord updated = reviewService.review(id, request.finalVerdict(), regression);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** JSON 列转对象（null 安全；解析失败原文返回——前端按字符串显示，防御不 500） */
    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
