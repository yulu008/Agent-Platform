package com.luyu.agent.rpg.planner.controller;

import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.service.StageGateService;

import reactor.core.publisher.Flux;

/**
 * 确认门操作端点（add-planner-pipeline tasks 4.2-4.5，spec planner-confirm-gates；
 * 挂在 {@code /rpg/planner} 路由下，与 {@code PipelineRunController} 分工——
 * run 生命周期与生成 SSE 在彼，门操作在此）。
 * <p>
 * L1 门（tasks 4.2）：草稿编辑保存 → 整稿通过（阶段 CONFIRMED 锁定）。
 * L2 逐卡操作（tasks 4.3）、返工（tasks 4.4）与回退解锁（tasks 4.5）
 * 已挂载；S4.5 幕级返工/回退解锁（story-graph-authoring 2.6，spec
 * 「幕级 L2 返工门」）以候选级端点形态挂载。编辑保存不做 moderation
 * （对齐工坊保存端点惯例：审查只覆盖进入 LLM 的输入线）；返工反馈是
 * 唯一新输入线，rework 类端点前置审查（D11，命中整流拒答且零副作用）。
 * <p>
 * {@code rpg.planner.enabled=false} 时 503；异常映射（对齐
 * {@code PipelineRunController}）：IllegalArgumentException → 400、
 * NoSuchElementException → 404、IllegalStateException → 409、其余 → 500。
 */
@RestController
@RequestMapping("/rpg/planner")
public class StageGateController {

    private static final Logger log = LoggerFactory.getLogger(StageGateController.class);

    private static final String ERR = "error";

    private final StageGateService gateService;
    private final ContentModerationGate moderationGate;
    private final RpgPlannerProperties plannerProperties;

    public StageGateController(StageGateService gateService,
                               ContentModerationGate moderationGate,
                               RpgPlannerProperties plannerProperties) {
        this.gateService = gateService;
        this.moderationGate = moderationGate;
        this.plannerProperties = plannerProperties;
    }

    // ==================== L1 门操作（tasks 4.2） ====================

    /**
     * L1 草稿编辑保存（S1/S4 Markdown / S2 表单序列化 Markdown）：编辑内容
     * 覆盖工作区产物 + 同版本号快照落盘 + 版本号递增（原稿经版本历史回看）。
     * <p>
     * 请求体：{@code content}（必填，编辑后全稿——空白内容视为空编辑拒绝）。
     * 成功 200 {@code {stage, version}}；锁定态 409（须先回退解锁）；
     * S3/S5 409（走逐卡操作）；S6 409（只读预览）；run 不存在 404。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/draft")
    public ResponseEntity<?> saveDraft(@PathVariable String runId,
                                       @PathVariable String stageCode,
                                       @RequestBody Map<String, String> request) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        String content = request.get("content");
        if (content == null || content.isBlank()) {
            return badRequest("content 不能为空");
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.saveL1Draft(runId, stage, content));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("草稿编辑保存失败: runId={}, stage={}", runId, stageCode, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "草稿编辑保存失败"));
        }
    }

    /**
     * 整稿通过：阶段置 CONFIRMED 锁定（幂等锁定，成为下游阶段唯一输入）、
     * currentStage 推进下一阶段。L2 阶段（S3/S5）须候选无 pending
     * （存在即 409 提示逐个处置）；S6 拒绝（安装端点专用语义）。
     * 成功 200 {@code {stage, confirmedVersion, nextStage}}。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/confirm")
    public ResponseEntity<?> confirmStage(@PathVariable String runId,
                                          @PathVariable String stageCode) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.confirmStage(runId, stage));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("整稿通过失败: runId={}, stage={}", runId, stageCode, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "整稿通过失败"));
        }
    }

    // ==================== L2 门逐卡操作（tasks 4.3） ====================

    /**
     * 逐卡毙掉（S3/S5 专用，spec「候选独立状态」）：REJECTED 幂等、确认后可
     * 改毙；已毙候选不可再确认/编辑（终态，救回走返工重生成）。
     * 成功 200 {@code {stage, candidateId, status}}。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/candidates/{candidateId}/reject")
    public ResponseEntity<?> rejectCandidate(@PathVariable String runId,
                                             @PathVariable String stageCode,
                                             @PathVariable String candidateId) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.rejectCandidate(runId, stage, candidateId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("逐卡毙掉失败: runId={}, stage={}, candidate={}", runId, stageCode, candidateId, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "逐卡毙掉失败"));
        }
    }

    /**
     * 逐卡确认（S3/S5 专用）：PENDING→CONFIRMED（幂等）；REJECTED 拒绝。
     * 成功 200 {@code {stage, candidateId, status}}。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/candidates/{candidateId}/confirm")
    public ResponseEntity<?> confirmCandidate(@PathVariable String runId,
                                              @PathVariable String stageCode,
                                              @PathVariable String candidateId) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.confirmCandidate(runId, stage, candidateId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("逐卡确认失败: runId={}, stage={}, candidate={}", runId, stageCode, candidateId, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "逐卡确认失败"));
        }
    }

    /**
     * 批量确认（L2 门通用，planner-s3-batch-confirm，spec「L2 门批量确认」）：
     * 全部 PENDING 候选一键置 CONFIRMED（REJECTED 终态不动）；幂等——无
     * PENDING 返回 confirmedCount=0。不锁定阶段（整稿通过仍须显式操作）。
     * 成功 200 {@code {stage, confirmedCount}}；L1 阶段 409。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/candidates/confirm-all")
    public ResponseEntity<?> confirmAllCandidates(@PathVariable String runId,
                                                  @PathVariable String stageCode) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.confirmAllCandidates(runId, stage));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("批量确认失败: runId={}, stage={}", runId, stageCode, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "批量确认失败"));
        }
    }

    /**
     * S3 确认并整稿通过（组合动作，planner-s3-batch-confirm，spec「S3 确认并
     * 整稿通过」）：批量确认 + 整稿通过原子化——阶段直达 CONFIRMED、
     * currentStage 前移。仅 S3 开放（S4.5/S5 409）；锁定态 409。
     * 成功 200 {@code {stage, confirmedCount, confirmedVersion, nextStage}}。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/confirm-remaining")
    public ResponseEntity<?> confirmRemainingAndPass(@PathVariable String runId,
                                                     @PathVariable String stageCode) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.confirmRemainingAndPass(runId, stage));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("确认并整稿通过失败: runId={}, stage={}", runId, stageCode, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "确认并整稿通过失败"));
        }
    }

    /**
     * 逐卡编辑保存（S3/S5 专用）：单卡覆盖写 + 阶段版本递增 + 语义稿快照
     * （非毙候选聚合）。REJECTED 不可编辑。
     * <p>
     * 请求体：{@code content}（必填，编辑后单卡全稿）。
     * 成功 200 {@code {stage, candidateId, version}}。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/candidates/{candidateId}")
    public ResponseEntity<?> saveCandidate(@PathVariable String runId,
                                           @PathVariable String stageCode,
                                           @PathVariable String candidateId,
                                           @RequestBody Map<String, String> request) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        String content = request.get("content");
        if (content == null || content.isBlank()) {
            return badRequest("content 不能为空");
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.saveCandidateEdit(runId, stage, candidateId, content));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("逐卡编辑保存失败: runId={}, stage={}, candidate={}", runId, stageCode, candidateId, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "逐卡编辑保存失败"));
        }
    }

    // ==================== L2 返工（tasks 4.4） ====================

    /**
     * L2 返工动作（S3/S5 专用，spec「返工动作」）：提交反馈文本触发该阶段
     * 新一轮生成（v N+1），返回生成 SSE 流（事件协议同 {@code /generate}：
     * stage_start → content* → draft_ready/error → [DONE]）。
     * <p>
     * 治理前置（D11：返工反馈是既有体系未覆盖的唯一新用户输入线）：反馈
     * 先过内容审查，命中即整流拒答 + [DONE] 收流且零副作用（记账与生成
     * 均不执行——版本树停在当前版本、无生成消耗，对齐 gameTurn 的 SSE
     * 审查惯例）。请求体：{@code feedback}（必填）。
     * 锁定态 / 非当前阶段 / run 不存在以 SSE error 事件 + [DONE] 返回
     * （SSE 连接已建立，状态码不可达，同 generate 端点惯例）。
     */
    @PostMapping(value = "/runs/{runId}/stages/{stageCode}/rework",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> reworkStage(@PathVariable String runId,
                                                     @PathVariable String stageCode,
                                                     @RequestBody Map<String, String> request) {
        if (!plannerProperties.isEnabled()) {
            return sseFailure("策划管线功能已停用（rpg.planner.enabled=false）");
        }
        String feedback = request.get("feedback");
        if (feedback == null || feedback.isBlank()) {
            return sseFailure("feedback 不能为空");
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return sseFailure(e.getMessage());
        }
        // 输入内容审查闸门（D11）：命中即拒答且零副作用（记账与生成都不执行）
        ModerationResult moderation = moderationGate.check(feedback);
        if (moderation.blocked()) {
            return Flux.just(
                    ServerSentEvent.<Object>builder()
                            .data(Map.of("moderation", true, ERR, moderationGate.refusalMessage()))
                            .build(),
                    ServerSentEvent.<Object>builder().data("[DONE]").build());
        }
        try {
            return gateService.reworkStage(runId, stage, feedback);
        } catch (NoSuchElementException e) {
            return sseFailure(e.getMessage());
        } catch (IllegalStateException e) {
            return sseFailure(e.getMessage());
        } catch (Exception e) {
            log.error("返工触发失败: runId={}, stage={}", runId, stageCode, e);
            return sseFailure("返工触发失败");
        }
    }

    // ==================== 上游已变重算 ====================

    /**
     * 上游已变重算（S2-S5）：上游确认稿更新后不写反馈、按最新上游重新生成本
     * 阶段（v N+1，L2 候选集整轮重置）。事件协议同 rework（stage_start →
     * content* → draft_ready/error → [DONE]）。无请求体（无用户输入线，
     * moderation 不适用）；前置失败（S1/S6、上游未确认/未变、锁定态）以
     * SSE error 事件 + [DONE] 返回（SSE 连接已建立，同 rework 惯例）。
     */
    @PostMapping(value = "/runs/{runId}/stages/{stageCode}/regenerate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> regenerateStage(@PathVariable String runId,
                                                         @PathVariable String stageCode) {
        if (!plannerProperties.isEnabled()) {
            return sseFailure("策划管线功能已停用（rpg.planner.enabled=false）");
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return sseFailure(e.getMessage());
        }
        try {
            return gateService.regenerateStage(runId, stage);
        } catch (NoSuchElementException e) {
            return sseFailure(e.getMessage());
        } catch (IllegalStateException e) {
            return sseFailure(e.getMessage());
        } catch (Exception e) {
            log.error("重算触发失败: runId={}, stage={}", runId, stageCode, e);
            return sseFailure("重算触发失败");
        }
    }

    // ==================== S3 补批 / 全量生成（planner-s3-batch-generate tasks 3.2） ====================

    /**
     * S3 补一批（spec「补一批」门动作，SSE）：从名册剩余清单取下一批生成，
     * 增量追加候选集（既有逐卡状态不重置，追加后整稿版本 +1 且全量聚合快照）。
     * 事件协议（对齐既有 generate + 批粒度事件扩展，前端未知事件忽略）：
     * stage_start → batch_start/content*（并行批交错合流）→ batch_done →
     * draft_ready{candidates=总数}/error → [DONE]。
     * <p>
     * 无请求体无 moderation（补批/全量无新用户输入线，对齐重算口径）；
     * 前置拒绝（锁定 / 非当前阶段 / 无名册 / 剩余空 / 配额）以 SSE error
     * 事件 + [DONE] 返回（SSE 连接已建立，HTTP 状态码不可用）且零副作用。
     */
    @PostMapping(value = "/runs/{runId}/stages/{stageCode}/append-batch",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> appendBatch(@PathVariable String runId,
                                                     @PathVariable String stageCode) {
        if (!plannerProperties.isEnabled()) {
            return sseFailure("策划管线功能已停用（rpg.planner.enabled=false）");
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return sseFailure(e.getMessage());
        }
        try {
            return gateService.appendBatch(runId, stage);
        } catch (NoSuchElementException e) {
            return sseFailure(e.getMessage());
        } catch (IllegalStateException e) {
            return sseFailure(e.getMessage());
        } catch (Exception e) {
            log.error("补一批触发失败: runId={}, stage={}", runId, stageCode, e);
            return sseFailure("补一批触发失败");
        }
    }

    /**
     * S3 全量生成（spec「全量生成」门动作，SSE）：名册剩余清单全部批一次跑完
     * （批间并行度 {@code rpg.planner.batch-concurrency}）。事件协议、治理与
     * 守卫口径同 {@link #appendBatch}（D5 共用编排路径差参不差路径）。
     */
    @PostMapping(value = "/runs/{runId}/stages/{stageCode}/generate-full",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> generateFull(@PathVariable String runId,
                                                      @PathVariable String stageCode) {
        if (!plannerProperties.isEnabled()) {
            return sseFailure("策划管线功能已停用（rpg.planner.enabled=false）");
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return sseFailure(e.getMessage());
        }
        try {
            return gateService.generateFull(runId, stage);
        } catch (NoSuchElementException e) {
            return sseFailure(e.getMessage());
        } catch (IllegalStateException e) {
            return sseFailure(e.getMessage());
        } catch (Exception e) {
            log.error("全量生成触发失败: runId={}, stage={}", runId, stageCode, e);
            return sseFailure("全量生成触发失败");
        }
    }

    // ==================== 幕级 L2 返工门（story-graph-authoring tasks 2.6） ====================

    /**
     * 幕级返工（S4.5 专用，spec「单幕返工不波及他幕」）：对指定幕候选提交
     * 反馈触发单幕重生成（v N+1），非目标幕跳过且候选状态不动（幕 6 返工
     * 幕 1-5 确认状态不变）；被毙幕经此通道救回（REJECTED 复活 PENDING 后
     * 重生成）。已确认幕 409（SSE error 事件，须先 unlockAct 回退解锁）。
     * 事件协议同 S4.5 生成流（stage_start → act_start/content*（目标幕）→
     * act_done（非目标幕 resumed=true）→ draft_ready/error → [DONE]）。
     * <p>
     * 治理前置（D11）同 reworkStage：反馈先过内容审查，命中即拒答 +
     * [DONE] 收流且零副作用（记账与生成都不执行）。请求体：{@code feedback}
     * （必填）。其他前置失败以 SSE error 事件 + [DONE] 返回（SSE 连接已建立，
     * 状态码不可达，同 generate 端点惯例）。
     */
    @PostMapping(value = "/runs/{runId}/stages/{stageCode}/candidates/{candidateId}/rework",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> reworkAct(@PathVariable String runId,
                                                   @PathVariable String stageCode,
                                                   @PathVariable String candidateId,
                                                   @RequestBody Map<String, String> request) {
        if (!plannerProperties.isEnabled()) {
            return sseFailure("策划管线功能已停用（rpg.planner.enabled=false）");
        }
        String feedback = request.get("feedback");
        if (feedback == null || feedback.isBlank()) {
            return sseFailure("feedback 不能为空");
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return sseFailure(e.getMessage());
        }
        if (stage != PipelineStage.S4P5_STORY_GRAPH) {
            return sseFailure("幕级返工仅 S4.5 故事图骨架阶段可用（其他 L2 阶段走整轮 rework）");
        }
        // 输入内容审查闸门（D11）：命中即拒答且零副作用（记账与生成都不执行）
        ModerationResult moderation = moderationGate.check(feedback);
        if (moderation.blocked()) {
            return Flux.just(
                    ServerSentEvent.<Object>builder()
                            .data(Map.of("moderation", true, ERR, moderationGate.refusalMessage()))
                            .build(),
                    ServerSentEvent.<Object>builder().data("[DONE]").build());
        }
        try {
            return gateService.reworkAct(runId, candidateId, feedback);
        } catch (NoSuchElementException e) {
            return sseFailure(e.getMessage());
        } catch (IllegalStateException e) {
            return sseFailure(e.getMessage());
        } catch (Exception e) {
            log.error("幕级返工触发失败: runId={}, stage={}, candidate={}",
                    runId, stageCode, candidateId, e);
            return sseFailure("幕级返工触发失败");
        }
    }

    /**
     * 幕级回退解锁（S4.5 专用，spec「已确认幕显式回退解锁」）：已确认幕候选
     * 复位 PENDING（可编辑/返工），拼接图删除（下游衔接跨幕边标记待重拼——
     * 重新整稿通过时确定性重拼），其他幕状态不变。非已确认 409；非 S4.5
     * 400；阶段锁定态 409（须先走阶段级 unlock）；run 不存在 404。
     * 成功 200 {@code {stage, candidateId, status, graphInvalidated}}。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/candidates/{candidateId}/unlock")
    public ResponseEntity<?> unlockAct(@PathVariable String runId,
                                       @PathVariable String stageCode,
                                       @PathVariable String candidateId) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (stage != PipelineStage.S4P5_STORY_GRAPH) {
            return badRequest("幕级回退解锁仅 S4.5 故事图骨架阶段可用");
        }
        try {
            return ResponseEntity.ok(gateService.unlockAct(runId, candidateId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("幕级回退解锁失败: runId={}, stage={}, candidate={}",
                    runId, stageCode, candidateId, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "幕级回退解锁失败"));
        }
    }

    // ==================== 回退解锁（tasks 4.5） ====================

    /**
     * 回退解锁（spec「确认稿幂等锁定」）：阶段 CONFIRMED → AWAITING_CONFIRM、
     * currentStage 回退到本阶段；confirmedVersion 保留（原确认版本存档，
     * 版本历史可回看）。解锁后的下一次编辑保存/返工生成递增版本 v(N+1)
     * （新一轮）。
     * <p>
     * 未锁定 409（无从解锁）；跨级 409（须先回退解锁下游阶段）；S6 终态
     * 409（只读）；run 不存在 404。
     * 成功 200 {@code {stage, status, archivedVersion, currentStage}}。
     */
    @PostMapping("/runs/{runId}/stages/{stageCode}/unlock")
    public ResponseEntity<?> unlockStage(@PathVariable String runId,
                                         @PathVariable String stageCode) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        final PipelineStage stage;
        try {
            stage = PipelineStage.fromCode(stageCode);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        try {
            return ResponseEntity.ok(gateService.unlockStage(runId, stage));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("回退解锁失败: runId={}, stage={}", runId, stageCode, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "回退解锁失败"));
        }
    }

    // ==================== 门控与异常映射 ====================

    /** SSE 前置失败收流（对齐 generate 端点惯例：error 事件 + [DONE]）。 */
    private static Flux<ServerSentEvent<Object>> sseFailure(String message) {
        return Flux.just(
                ServerSentEvent.<Object>builder().data(Map.of(ERR, message)).build(),
                ServerSentEvent.<Object>builder().data("[DONE]").build());
    }

    /** 配置总开关：false 时门操作端点 503（同 {@code PipelineRunController}）；SSE 端点以 error 事件收流。 */
    private ResponseEntity<?> plannerDisabled() {
        if (!plannerProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(ERR, "策划管线功能已停用（rpg.planner.enabled=false）"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(ERR, message));
    }

    private static ResponseEntity<Map<String, String>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(ERR, message));
    }

    private static ResponseEntity<Map<String, String>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ERR, message));
    }
}
