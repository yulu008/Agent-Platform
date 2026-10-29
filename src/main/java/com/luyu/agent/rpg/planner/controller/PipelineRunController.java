package com.luyu.agent.rpg.planner.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.engine.StageGenerationService;
import com.luyu.agent.rpg.planner.engine.StoryGraphGenerationService;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.RunDetail;
import com.luyu.agent.rpg.planner.model.SourceMode;
import com.luyu.agent.rpg.planner.service.HtmlSourceService;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorldInstaller;

import reactor.core.publisher.Flux;

/**
 * 策划管线 REST API（add-planner-pipeline spec planner-pipeline-run）。
 * <p>
 * run 生命周期端点：创建（multipart 表单 + ip_name HTML 资料页上传，治理前置：
 * moderation 审原始输入与逐份清洗文本，planner-html-source D1/D7）→ 列表（库表查询）→
 * 详情（manifest 渲染，支撑跨天恢复）→ 放弃（置 ABANDONED，工作区保留）。阶段
 * 生成 SSE / 确认门 / 安装端点由任务
 * 3-5 的控制器挂载在本路由下；版本历史与 diff 查询端点（tasks 4.6）也在
 * 本控制器（只读与 run 详情同族）。
 * <p>
 * {@code rpg.planner.enabled=false} 时全部端点 503（对齐 {@code WorkshopController}
 * worldbook 开关惯例）；异常映射（对齐 {@code WorldPublishController}）：
 * IllegalArgumentException → 400、NoSuchElementException → 404、
 * IllegalStateException → 409、其余 → 500。
 */
@RestController
@RequestMapping("/rpg/planner")
public class PipelineRunController {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunController.class);

    private static final String ERR = "error";

    private final PipelineRunService runService;
    private final StageGenerationService stageGenerationService;
    private final StoryGraphGenerationService storyGraphGenerationService;
    private final ContentModerationGate moderationGate;
    private final RpgPlannerProperties plannerProperties;
    private final PlannerWorldInstaller worldInstaller;
    private final HtmlSourceService htmlSourceService;

    public PipelineRunController(PipelineRunService runService,
                                 StageGenerationService stageGenerationService,
                                 StoryGraphGenerationService storyGraphGenerationService,
                                 ContentModerationGate moderationGate,
                                 RpgPlannerProperties plannerProperties,
                                 PlannerWorldInstaller worldInstaller,
                                 HtmlSourceService htmlSourceService) {
        this.runService = runService;
        this.stageGenerationService = stageGenerationService;
        this.storyGraphGenerationService = storyGraphGenerationService;
        this.moderationGate = moderationGate;
        this.plannerProperties = plannerProperties;
        this.worldInstaller = worldInstaller;
        this.htmlSourceService = htmlSourceService;
    }

    // ==================== 创建（tasks 2.1） ====================

    /**
     * 创建管线 run（multipart/form-data，planner-html-source D1）。
     * <p>
     * 治理前置（对齐工坊生成三段式的输入侧）：表单字段必填校验（400）→
     * pages 文件校验链（D2：text 拒文件 400 / ip_name 至少 1 份 400 / 数量上限
     * 400 / 单份大小 413 / 扩展名白名单 400；文件名仅取扩展名判断，落盘名
     * 服务端生成防穿越）→ charset 嗅探 + jsoup 清洗（HtmlSourceService）→
     * moderation（text 审原文全文 / ip_name 审 IP 名 + 逐份清洗文本，D7 每份
     * 独立送审限制单次输入规模；命中 422 拒答结构体且零副作用不创建 run）→
     * 落库与工作区初始化（service 内 D5 写序：source.md → pages 双文件 →
     * manifest → 库表行）。配额与 LLM 消耗发生在阶段生成时，创建动作本身
     * 不耗 token 故无 QuotaGuard 前置。
     * <p>
     * 表单字段：{@code sourceMode}（text/ip_name）、{@code styleMode}
     * （faithful/remix）、{@code text}（text 模式必填）、{@code ipName}
     * （ip_name 模式必填）、{@code name}（可选展示名，缺省自动推导）、
     * {@code pages}（ip_name 模式必填的 HTML 文件数组，浏览器另存的
     * .html/.htm，至少 1 份；text 模式不传）。全局 multipart 尺寸闸
     * （spring.servlet.multipart）超限由 {@code @ExceptionHandler} 兜底 413。
     */
    @PostMapping("/runs")
    public ResponseEntity<?> createRun(
            @RequestParam(value = "sourceMode", required = false) String sourceMode,
            @RequestParam(value = "styleMode", required = false) String styleMode,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "ipName", required = false) String ipName,
            @RequestParam(value = "name", required = false) String displayName,
            @RequestParam(value = "pages", required = false) List<MultipartFile> pages) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        sourceMode = trim(sourceMode);
        styleMode = trim(styleMode);
        text = trim(text);
        ipName = trim(ipName);
        displayName = trim(displayName);
        if (sourceMode == null || styleMode == null) {
            return badRequest("sourceMode 与 styleMode 不能为空");
        }
        if (SourceMode.TEXT.code().equals(sourceMode) && text == null) {
            return badRequest("text 模式下原文不能为空");
        }
        if (SourceMode.IP_NAME.code().equals(sourceMode) && ipName == null) {
            return badRequest("ip_name 模式下 IP 名不能为空");
        }

        // text 原文总字数硬上限（planner-s1-chained-refinement tasks 3.2，spec
        // 「超硬上限被拒」）：超出 400 拒绝且不落库不落盘，防极端 payload/落盘/
        // moderation 扫描（对齐下方 moderation 前置位次；无结构超长原文不拒，
        // 仅影响后续 S1 切块质量）；链式提炼理论安全上限见 notesTotalMaxChars javadoc
        if (SourceMode.TEXT.code().equals(sourceMode)
                && text.length() > plannerProperties.getMaxSourceChars()) {
            return badRequest("text 原文超出字数硬上限（" + text.length() + " > "
                    + plannerProperties.getMaxSourceChars()
                    + " 字，配置 rpg.planner.max-source-chars），请拆分为多个 run 分别创建");
        }

        // pages 文件校验链（D2 封面范式；text 模式无文件时表单字段语义与旧 JSON
        // 端点逐字段等价，text 链路零变化）
        boolean hasPages = pages != null && !pages.isEmpty();
        if (SourceMode.TEXT.code().equals(sourceMode) && hasPages) {
            return badRequest("text 模式不支持 HTML 资料页（仅 ip_name 模式）");
        }
        if (SourceMode.IP_NAME.code().equals(sourceMode) && !hasPages) {
            return badRequest("ip_name 模式下 HTML 资料页不能为空（至少 1 份，请上传浏览器另存的 HTML）");
        }
        if (hasPages) {
            if (pages.size() > plannerProperties.getMaxSourcePages()) {
                return badRequest("HTML 资料页最多 " + plannerProperties.getMaxSourcePages()
                        + " 份（当前 " + pages.size() + " 份，配置 rpg.planner.max-source-pages）");
            }
            for (MultipartFile file : pages) {
                String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
                if (file.isEmpty()) {
                    return badRequest("资料页文件不能为空: " + filename);
                }
                if (file.getSize() > plannerProperties.getMaxPageBytes()) {
                    return payloadTooLarge(
                            "HTML 资料页单份超过大小上限（rpg.planner.max-page-bytes）: " + filename);
                }
                String lower = filename.toLowerCase();
                if (!lower.endsWith(".html") && !lower.endsWith(".htm")) {
                    return badRequest("资料页须为 .html/.htm 文件（浏览器另存 HTML）: " + filename);
                }
            }
        }

        // charset 嗅探 + jsoup 清洗（D3/D4）：清洗文本是 S1 生成原料与 moderation 面
        List<HtmlSourceService.SourcePage> sourcePages = null;
        if (SourceMode.IP_NAME.code().equals(sourceMode)) {
            sourcePages = new ArrayList<>(pages.size());
            for (MultipartFile file : pages) {
                byte[] bytes;
                try {
                    bytes = file.getBytes();
                } catch (IOException e) {
                    log.error("资料页读取失败: {}", file.getOriginalFilename(), e);
                    return ResponseEntity.internalServerError().body(Map.of(ERR, "资料页读取失败"));
                }
                sourcePages.add(new HtmlSourceService.SourcePage(bytes, htmlSourceService.clean(bytes)));
            }
        }

        // 输入内容审查（spec「创建输入过内容审查」）：命中即拒答且不创建 run；
        // ip_name 模式 D7 每份清洗文本独立与 IP 名拼接送审（限制单次输入规模）；
        // text 模式审原文全文（语义与旧 JSON 端点等价）
        if (SourceMode.IP_NAME.code().equals(sourceMode)) {
            for (HtmlSourceService.SourcePage page : sourcePages) {
                if (moderationRefusal(moderationGate.check(ipName + "\n" + page.cleanedText()))) {
                    return moderationRefusalResponse();
                }
            }
        } else {
            if (moderationRefusal(moderationGate.check(text))) {
                return moderationRefusalResponse();
            }
        }

        try {
            PipelineRun run = runService.createRun(sourceMode, styleMode, text, ipName,
                    displayName, sourcePages);
            return ResponseEntity.ok(run);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("创建管线 run 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "创建管线 run 失败"));
        }
    }

    // ==================== 列表与详情（tasks 2.2 / 2.3） ====================

    /** 本租户全部 run（含进行中/已完成/已放弃），库表查询而非目录遍历。 */
    @GetMapping("/runs")
    public ResponseEntity<?> listRuns() {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        return ResponseEntity.ok(runService.listRuns());
    }

    /**
     * run 详情：manifest 渲染各阶段确认状态、版本、摘要与返工历史，支撑跨天恢复。
     * S3（人物设定）附带最近静态层 eval 概要（tasks 8.1，仅展示建议不拦截）。
     * 不存在或跨租户 404；工作区损坏 409。
     */
    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> getRunDetail(@PathVariable String runId) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        try {
            RunDetail detail = runService.getRunDetail(runId);
            return ResponseEntity.ok(detail);
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    // ==================== 放弃（tasks 2.4） ====================

    /**
     * 放弃 run：置 ABANDONED（终态），工作区文件树保留可查看。
     * 不存在或跨租户 404；已终态 409；成功 204。
     */
    @PostMapping("/runs/{runId}/abandon")
    public ResponseEntity<?> abandonRun(@PathVariable String runId) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        try {
            runService.abandonRun(runId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    // ==================== 硬删除（planner-run-delete tasks 1.4） ====================

    /**
     * 硬删除 run：库表行 + 工作区目录（manifest、阶段产物、版本快照、research
     * 搜索轮资料）全部清除——与「放弃」（置 ABANDONED 终态、工作区保留可回看）
     * 语义互斥互补。生成中（run 级活跃流）409；不存在或跨租户 404（重复删除
     * 幂等走同路径）；已安装世界（S6 物化产物是独立 world 资产）不受波及；
     * 成功 204（同 abandon 无 Body 惯例）。
     */
    @DeleteMapping("/runs/{runId}")
    public ResponseEntity<?> deleteRun(@PathVariable String runId) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        try {
            runService.deleteRun(runId);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    // ==================== 阶段生成 SSE（tasks 3.1） ====================

    /**
     * 阶段生成 SSE 流：事件协议 stage_start → content*（token 流）→
     * draft_ready{stage,version} 或 error → [DONE]（详见
     * {@link StageGenerationService} 类 javadoc）。
     * <p>
     * S4.5 首轮生成经本通用端点转发幕级编排（planner-s1-chained-refinement
     * tasks 5.1，修复 UI 死锁：门操作要求 AWAITING_CONFIRM 而 AWAITING_CONFIRM
     * 只能从生成成功来——前端生成入口唯一，专用端点 /story-graph/generate
     * 前端从未调用）；转发与专用端点首轮语义一致，与通用生成共享 RUNNING
     * 守卫互斥（generateActs 自持守卫占位）。
     * <p>
     * 治理三段式的 quota/LLM 段在 service 流内前置执行；moderation 不适用
     * （阶段生成无新用户输入，原始输入创建时已审，返工反馈的审查在任务 4 返工端点）。
     * 开关停用时以 error 事件 + [DONE] 收流（SSE 连接已建立，状态码不可达）。
     */
    @PostMapping(value = "/runs/{runId}/stages/{stageCode}/generate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> generateStage(@PathVariable String runId,
                                                       @PathVariable String stageCode) {
        if (!plannerProperties.isEnabled()) {
            return Flux.just(
                    ServerSentEvent.<Object>builder()
                            .data(Map.of(ERR, "策划管线功能已停用（rpg.planner.enabled=false）"))
                            .build(),
                    ServerSentEvent.<Object>builder().data("[DONE]").build());
        }
        if (PipelineStage.S4P5_STORY_GRAPH.code().equals(stageCode)) {
            // 首轮无用户输入（返工反馈经 4.x 返工端点传 generateActs 第三参）
            return storyGraphGenerationService.generateActs(runId, null);
        }
        return stageGenerationService.generateStage(runId, stageCode);
    }

    // ==================== S4.5 逐幕生成 SSE（story-graph-authoring tasks 2.3） ====================

    /**
     * S4.5 逐幕生成 SSE 流：幕划分真源是 S4 确认稿「## 分幕结构」小节，
     * 逐幕串行小契约调用（每幕 ≤25 节点独立 LLM 调用）。事件协议
     * stage_start →（act_start → content*{act} → act_done）×N →
     * draft_ready{stage,version,candidates} 或 error → [DONE]（详见
     * {@link StoryGraphGenerationService} 类 javadoc）。
     * <p>
     * 治理三段式（moderation/quota）在 service 流内每幕前置（spec
     * 「每次幕生成调用前 SHALL 过三段式」）；与通用阶段生成共享 RUNNING
     * 守卫（并发生成互斥）。开关停用时以 error 事件 + [DONE] 收流（同
     * generateStage 惯例）。
     */
    @PostMapping(value = "/runs/{runId}/story-graph/generate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> generateStoryGraph(@PathVariable String runId) {
        if (!plannerProperties.isEnabled()) {
            return Flux.just(
                    ServerSentEvent.<Object>builder()
                            .data(Map.of(ERR, "策划管线功能已停用（rpg.planner.enabled=false）"))
                            .build(),
                    ServerSentEvent.<Object>builder().data("[DONE]").build());
        }
        // 首轮生成无用户输入（返工反馈文本 2.6 返工端点接线后传入）
        return storyGraphGenerationService.generateActs(runId, null);
    }

    // ==================== 版本历史与 diff（tasks 4.6） ====================

    /**
     * 阶段版本历史（spec「历史可回看」）：全部版本号（升序）与每轮返工
     * 反馈记录（rounds）。只读——任意阶段状态可回看（含锁定态——存档
     * 与历史确认记录不可删除）；S6 无版本树返回空列表。
     * 成功 200 {@code {stage, versions, rounds}}。
     */
    @GetMapping("/runs/{runId}/stages/{stageCode}/versions")
    public ResponseEntity<?> listStageVersions(@PathVariable String runId,
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
            return ResponseEntity.ok(runService.listStageVersions(runId, stage));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("版本历史查询失败: runId={}, stage={}", runId, stageCode, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "版本历史查询失败"));
        }
    }

    /**
     * 版本快照全文回看（spec「原 confirmed 版本仍可回看」）。
     * 成功 200 {@code {stage, version, content}}；版本不存在 400。
     */
    @GetMapping("/runs/{runId}/stages/{stageCode}/versions/{version}")
    public ResponseEntity<?> readStageVersion(@PathVariable String runId,
                                              @PathVariable String stageCode,
                                              @PathVariable int version) {
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
            return ResponseEntity.ok(runService.readStageVersion(runId, stage, version));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("版本快照查询失败: runId={}, stage={}, version={}", runId, stageCode, version, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "版本快照查询失败"));
        }
    }

    /**
     * 相邻版本 diff（spec「diff 核对」，D5）：行级 context/removed/added
     * 供前端高亮核对（变更部分高亮，未变更条目显示一致）。查询参数
     * {@code from}/{@code to} 可选，缺省对比最新两版（to=最新，from=to-1）。
     * 成功 200 {@code {stage, from, to, lines}}；不足两版或引用版本不存在
     * 400。
     */
    @GetMapping("/runs/{runId}/stages/{stageCode}/diff")
    public ResponseEntity<?> diffStageVersions(@PathVariable String runId,
                                               @PathVariable String stageCode,
                                               @RequestParam(required = false) Integer from,
                                               @RequestParam(required = false) Integer to) {
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
            return ResponseEntity.ok(runService.diffStageVersions(runId, stage, from, to));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("版本 diff 查询失败: runId={}, stage={}", runId, stageCode, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "版本 diff 查询失败"));
        }
    }

    // ==================== 成稿安装（tasks 5.1） ====================

    /**
     * 成稿安装（spec planner-world-install）：S1-S5 全确认后把工作区确认稿
     * 物化为新世界（每次安装产出独立 world_id，重复安装产出新世界；全部写入
     * 单事务原子落库，失败零残留可重试），成功后 run 收口 COMPLETED。
     * <p>
     * 成功 200 {@code {worldId, worldName, entryCount, storyGraphId, storyGraphNodeCount}}
     * （storyGraph* 为 S4.5 拼接产物物化的模板层故事图定位，story-graph-authoring
     * tasks 5.2）；不存在或跨租户 404；前置阶段未全确认/终态不可装/产物不完整
     * 409；物化失败 500（事务已回滚零残留，run 停在 AWAITING 可重试）。无请求体。
     */
    @PostMapping("/runs/{runId}/install")
    public ResponseEntity<?> installWorld(@PathVariable String runId) {
        ResponseEntity<?> disabled = plannerDisabled();
        if (disabled != null) {
            return disabled;
        }
        try {
            PlannerWorldInstaller.InstallResult result = worldInstaller.install(runId);
            return ResponseEntity.ok(Map.of(
                    "worldId", result.worldId(),
                    "worldName", result.worldName(),
                    "entryCount", result.entryCount(),
                    "storyGraphId", result.storyGraphId() == null ? "" : result.storyGraphId(),
                    "storyGraphNodeCount", result.storyGraphNodeCount()));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("成稿安装失败（事务已回滚零残留，可重试）: runId={}", runId, e);
            return ResponseEntity.internalServerError().body(Map.of(ERR, "成稿安装失败，请重试"));
        }
    }

    // ==================== 门控与异常映射 ====================

    /** 配置总开关（tasks 2.6）：false 时全部管线端点 503，工坊/RPG 不受影响。 */
    private ResponseEntity<?> plannerDisabled() {
        if (!plannerProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(ERR, "策划管线功能已停用（rpg.planner.enabled=false）"));
        }
        return null;
    }

    /** trim 且空白归一 null（必填校验用）。 */
    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    /** moderation 命中判定（D7 逐份送审后统一拒绝面）。 */
    private static boolean moderationRefusal(ModerationResult moderation) {
        return moderation.blocked();
    }

    /** 422 拒答结构体（命中零副作用：不落盘不建 run，语义与旧端点一致）。 */
    private ResponseEntity<Map<String, Object>> moderationRefusalResponse() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("moderation", true, ERR, moderationGate.refusalMessage()));
    }

    /** 全局 multipart 尺寸闸（spring.servlet.multipart 4MB/16MB）超限 → 413（封面同款；
     * 单份 2MB 语义闸在 createRun 校验链里前置返回同状态码）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传内容超出全局 multipart 闸: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of(ERR, "上传内容过大（单份资料页 ≤2MB、总数 ≤5 份），请压缩后重试"));
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(ERR, message));
    }

    /** 413（单份大小语义闸；全局 multipart 闸由 @ExceptionHandler 兜底同状态码）。 */
    private static ResponseEntity<Map<String, String>> payloadTooLarge(String message) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(ERR, message));
    }

    private static ResponseEntity<Map<String, String>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(ERR, message));
    }

    private static ResponseEntity<Map<String, String>> conflict(String message) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(ERR, message));
    }
}
