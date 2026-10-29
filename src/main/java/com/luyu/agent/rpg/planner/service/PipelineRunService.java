package com.luyu.agent.rpg.planner.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.engine.PipelineStateMachine;
import com.luyu.agent.rpg.planner.engine.PlannerGenerationGuard;
import com.luyu.agent.rpg.planner.engine.StagePromptAssembler;
import com.luyu.agent.rpg.planner.engine.StageVersionDiffer;
import com.luyu.agent.rpg.planner.model.DraftItemStatus;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineRunStatus;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.PipelineStageStatus;
import com.luyu.agent.rpg.planner.model.RunDetail;
import com.luyu.agent.rpg.planner.model.SourceMode;
import com.luyu.agent.rpg.planner.model.StyleMode;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;

/**
 * 管线 run 生命周期编排（add-planner-pipeline design D3/D6）。
 * <p>
 * <b>写路径单点</b>：manifest（文件真源）与库表 {@code rpg_pipeline_run} 的状态同步
 * 统一经本类——先写 manifest 后同步库表（漂移风险的对冲约定）。
 * <p>
 * 异常约定（对齐 {@code WorldPublishService} 惯例，由 controller 统一映射）：
 * {@link IllegalArgumentException} → 400（校验拒绝）、
 * {@link NoSuchElementException} → 404（run 不存在）、
 * {@link IllegalStateException} → 409（守卫拒绝/终态冲突/工作区损坏）。
 * <p>
 * 跨天恢复（D6）：gate 之间的等待落库为 AWAITING（持久化状态而非连接挂起），
 * 详情端点每次从库表 + manifest 现算渲染，无会话内存依赖。
 */
@Service
public class PipelineRunService {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunService.class);

    /** text 模式展示名截断长度 */
    private static final int NAME_TRUNCATE = 20;

    /** 详情页原始输入预览长度 */
    private static final int SOURCE_PREVIEW = 200;

    private final PipelineRunRepository runRepository;
    private final PlannerWorkspaceStore workspaceStore;
    private final RpgPlannerProperties properties;
    private final PlannerGenerationGuard generationGuard;

    public PipelineRunService(PipelineRunRepository runRepository,
                              PlannerWorkspaceStore workspaceStore,
                              RpgPlannerProperties properties,
                              PlannerGenerationGuard generationGuard) {
        this.runRepository = runRepository;
        this.workspaceStore = workspaceStore;
        this.properties = properties;
        this.generationGuard = generationGuard;
    }

    // ==================== 创建（tasks 2.1） ====================

    /**
     * 创建 run：模式校验 → 工作区初始化（source.md → pages 双文件 → manifest，
     * planner-html-source D5 写序）→ 库表落行。
     * <p>
     * 输入内容审查在 controller 层前置（对齐 WorkshopController 惯例），命中即不进本方法。
     * 写序「先工作区后库表」：任一文件落盘失败时库表无行，不产生脏 run
     * （spec「必填校验」的零副作用要求在存储层同样成立）。
     *
     * @param sourceMode  text / ip_name（未知码 400）
     * @param styleMode   faithful / remix（未知码 400）
     * @param text        text 模式的原文（该模式下必填）
     * @param ipName      ip_name 模式的 IP 名（该模式下必填）
     * @param displayName 展示名（缺省自动推导：ip_name 用 IP 名 / text 用原文截断）
     * @param pages       ip_name 模式的 HTML 资料页清洗产物（原始字节 + 清洗文本，
     *                    该模式下至少 1 份——controller 校验链先行，本方法兑底；
     *                    text 模式恒空）
     */
    public PipelineRun createRun(String sourceMode, String styleMode,
                                 String text, String ipName, String displayName) {
        return createRun(sourceMode, styleMode, text, ipName, displayName, null);
    }

    /** 见 {@link #createRun(String, String, String, String, String, List)}（含 HTML 资料页）。 */
    public PipelineRun createRun(String sourceMode, String styleMode,
                                 String text, String ipName, String displayName,
                                 List<HtmlSourceService.SourcePage> pages) {
        SourceMode source = SourceMode.fromCode(sourceMode);
        StyleMode style = StyleMode.fromCode(styleMode);

        String sourceContent;
        String fallbackName;
        if (source == SourceMode.TEXT) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text 模式下原文不能为空");
            }
            if (pages != null && !pages.isEmpty()) {
                throw new IllegalArgumentException("text 模式不支持 HTML 资料页（仅 ip_name 模式）");
            }
            sourceContent = text.strip();
            fallbackName = truncate(text.strip().replaceAll("\\s+", " "));
        } else {
            if (ipName == null || ipName.isBlank()) {
                throw new IllegalArgumentException("ip_name 模式下 IP 名不能为空");
            }
            if (pages == null || pages.isEmpty()) {
                throw new IllegalArgumentException(
                        "ip_name 模式下 HTML 资料页不能为空（至少 1 份，请上传浏览器另存的 HTML）");
            }
            sourceContent = ipName.strip();
            fallbackName = ipName.strip();
        }

        String runId = UUID.randomUUID().toString();
        String name = (displayName != null && !displayName.isBlank())
                ? displayName.strip() : fallbackName;
        String now = LocalDateTime.now().toString();

        PipelineRun run = new PipelineRun();
        run.setId(runId);
        run.setName(name);
        run.setSourceMode(source.code());
        run.setStyleMode(style.code());
        run.setCurrentStage(PipelineStage.S1_IP_ANALYSIS.code());
        run.setStatus(PipelineRunStatus.CREATED.name());

        PipelineManifest manifest = new PipelineManifest();
        manifest.setRunId(runId);
        manifest.setName(name);
        manifest.setSourceMode(source.code());
        manifest.setStyleMode(style.code());
        manifest.setSourceFile(PlannerWorkspaceStore.SOURCE_FILE);
        if (source == SourceMode.IP_NAME) {
            List<String> pageNames = new ArrayList<>(pages.size());
            for (int i = 1; i <= pages.size(); i++) {
                pageNames.add(String.format("page-%02d", i));
            }
            manifest.setSourcePages(pageNames);
        }
        manifest.setCurrentStage(PipelineStage.S1_IP_ANALYSIS.code());
        manifest.setStatus(PipelineRunStatus.CREATED.name());
        manifest.setCreatedAt(now);
        manifest.setUpdatedAt(now);

        // D5 写序：source.md → pages 双文件 → manifest（含 sourcePages）→ 库表行；
        // 任一步失败（IllegalStateException）库表无行，不产生脏 run
        workspaceStore.initRunDir(runId, sourceContent);
        if (source == SourceMode.IP_NAME) {
            for (int i = 0; i < pages.size(); i++) {
                HtmlSourceService.SourcePage page = pages.get(i);
                workspaceStore.writeSourcePage(runId, i + 1, page.htmlBytes(), page.cleanedText());
            }
        }
        workspaceStore.writeManifest(runId, manifest);
        runRepository.insert(run);
        log.info("管线 run 已创建: runId={}, source={}, style={}, pages={}",
                runId, source.code(), style.code(), source == SourceMode.IP_NAME ? pages.size() : 0);
        return run;
    }

    // ==================== 查询（tasks 2.2 / 2.3） ====================

    /** 本租户全部 run（列表页数据源：库表查询而非目录遍历，含已放弃）。 */
    public List<PipelineRun> listRuns() {
        return runRepository.findAll();
    }

    /**
     * run 详情（库表元数据 + manifest 阶段细粒度状态合成渲染）。
     * <p>
     * 支撑跨天恢复：每次现算，无会话内存依赖。
     *
     * @throws NoSuchElementException run 不存在或跨租户（404）
     * @throws IllegalStateException   工作区 manifest 缺失（数据损坏，409 语义）
     */
    public RunDetail getRunDetail(String runId) {
        PipelineRun run = requireRun(runId);
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }
        return buildDetail(run, manifest);
    }

    // ==================== 版本历史与 diff（tasks 4.6） ====================

    /**
     * 阶段版本历史（spec「历史可回看」）：全部版本号（快照目录升序）+
     * 每轮返工反馈记录（manifest rounds 真源）。只读——任意阶段状态
     * （含锁定态与 S6）均可回看；无快照返回空列表（S6 无版本树）。
     *
     * @throws NoSuchElementException run 不存在或跨租户（404）
     * @throws IllegalStateException   工作区 manifest 缺失（409）
     */
    public Map<String, Object> listStageVersions(String runId, PipelineStage stage) {
        requireRun(runId);
        PipelineManifest manifest = requireManifest(runId);
        List<Integer> versions = workspaceStore.listVersionSnapshots(runId, stage);
        return Map.of(
                "stage", stage.code(),
                "versions", versions,
                "rounds", manifest.stage(stage.code()).getRounds());
    }

    /**
     * 版本快照全文回看（spec「原 confirmed 版本仍可回看」——存档不因回退
     * 解锁或后续编辑消失）。
     *
     * @throws NoSuchElementException run 不存在或跨租户（404）
     * @throws IllegalArgumentException 版本号越界或快照未落盘（400）
     */
    public Map<String, Object> readStageVersion(String runId, PipelineStage stage, int version) {
        requireRun(runId);
        requireManifest(runId);
        String content = workspaceStore.readVersionSnapshot(runId, stage, version);
        if (content == null) {
            throw new IllegalArgumentException(
                    "版本快照不存在: v" + version + "（阶段 " + stage.code() + "）");
        }
        return Map.of(
                "stage", stage.code(),
                "version", version,
                "content", content);
    }

    /**
     * 相邻版本 diff（spec「diff 核对」）：行级 context/removed/added
     * （{@link StageVersionDiffer}）供前端高亮核对——D5：返工整轮重生成后
     * 核对未被反馈要求改动的部分是否被意外修改。
     * <p>
     * from/to 缺省取最新两版（to=最新，from=to-1）；显式指定时可核对任意
     * 版本对。确认动作只对最新版本生效（spec），diff 不改变任何状态。
     *
     * @throws NoSuchElementException run 不存在或跨租户（404）
     * @throws IllegalArgumentException 不足两版、from ≥ to、或引用的版本快照不存在（400）
     */
    public Map<String, Object> diffStageVersions(String runId, PipelineStage stage,
                                                  Integer from, Integer to) {
        requireRun(runId);
        requireManifest(runId);
        List<Integer> versions = workspaceStore.listVersionSnapshots(runId, stage);
        if (versions.size() < 2) {
            throw new IllegalArgumentException("该阶段暂无相邻版本可对比（需至少两个版本快照）");
        }
        int target = to != null ? to : versions.get(versions.size() - 1);
        int source = from != null ? from : target - 1;
        if (source >= target) {
            throw new IllegalArgumentException("起始版本须小于目标版本: v" + source + " → v" + target);
        }
        String oldText = workspaceStore.readVersionSnapshot(runId, stage, source);
        if (oldText == null) {
            throw new IllegalArgumentException(
                    "版本快照不存在: v" + source + "（阶段 " + stage.code() + "）");
        }
        String newText = workspaceStore.readVersionSnapshot(runId, stage, target);
        if (newText == null) {
            throw new IllegalArgumentException(
                    "版本快照不存在: v" + target + "（阶段 " + stage.code() + "）");
        }
        return Map.of(
                "stage", stage.code(),
                "from", source,
                "to", target,
                "lines", StageVersionDiffer.diff(oldText, newText));
    }

    // ==================== 放弃（tasks 2.4） ====================

    /**
     * 放弃 run：终态守卫（COMPLETED/ABANDONED 拒绝重放弃）→ 先写 manifest 后同步库表。
     * 不删工作区文件树（spec「放弃保留产物」）。
     *
     * @throws NoSuchElementException run 不存在或跨租户（404）
     * @throws IllegalStateException   run 已处于终态（409）
     */
    public void abandonRun(String runId) {
        PipelineRun run = requireRun(runId);
        PipelineRunStatus current = PipelineRunStatus.valueOf(run.getStatus());
        PipelineStateMachine.requireTransition(current, PipelineRunStatus.ABANDONED);

        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest != null) {
            manifest.setStatus(PipelineRunStatus.ABANDONED.name());
            manifest.setUpdatedAt(LocalDateTime.now().toString());
            workspaceStore.writeManifest(runId, manifest);
        } else {
            // 工作区文件树缺失不阻塞放弃动作（库表是列表页真源）；留痕待对账
            log.warn("放弃时 manifest 缺失，仅更新库表状态: runId={}", runId);
        }
        runRepository.abandon(runId);
        log.info("管线 run 已放弃: runId={}", runId);
    }

    // ==================== 硬删除（planner-run-delete tasks 1.3） ====================

    /**
     * 硬删除 run：库表行 + 工作区目录全清（spec「run 硬删除」，与 abandon
     * 软终态保留互补）。写序「先库表后工作区」（创建写序的镜像反转）：库表行
     * 删除后列表即刻不可见，工作区删除失败仅 warn 不阻断——孤儿目录不影响
     * 任何功能（详情页经库表行查询 404 已兜底）。生成中（run 级活跃流存在）
     * 拒绝删除（409）：SSE 流回调持续写工作区/更新库表行，先删必然竞态；
     * run 不存在或跨租户 404（重复删除幂等走同一路径）。
     */
    public void deleteRun(String runId) {
        requireRun(runId);
        if (generationGuard.activeStage(runId).isPresent()) {
            throw new IllegalStateException("run 正在生成中，请等待完成后再删除（活跃流守卫）");
        }
        runRepository.deleteById(runId);
        try {
            workspaceStore.deleteRunDir(runId);
        } catch (Exception e) {
            // 孤儿目录无害（库表行已删，功能面 404 兜底），对齐封面 deleteIfExists 静默惯例
            log.warn("run 工作区目录删除失败（孤儿目录无害，可重删回收）: runId={}", runId, e);
        }
        log.info("管线 run 已删除: runId={}", runId);
    }

    // ==================== 单阶段 RUNNING 守卫（tasks 2.5） ====================

    /**
     * 生成/确认前置守卫：同一 run 同时只允许一个阶段 RUNNING（spec「单阶段 RUNNING 守卫」，
     * 防双开标签页并发推进）。任一阶段生成中，该 run 的新生成或确认请求被拒（409 语义）。
     * <p>
     * 由阶段生成与确认门端点（任务 3/4）在动作前调用；放弃端点<b>不</b>走本守卫
     * （spec「run 放弃」：随时可放弃，包括生成中——由 SSE 流的完成回调收敛最终状态）。
     *
     * @throws NoSuchElementException run 不存在或跨租户（404）
     * @throws IllegalStateException   run 终态、或有阶段正在生成、或 manifest 缺失（409）
     */
    public PipelineRun requireNoRunningStage(String runId) {
        PipelineRun run = requireRun(runId);
        PipelineRunStatus status = PipelineRunStatus.valueOf(run.getStatus());
        if (status == PipelineRunStatus.COMPLETED || status == PipelineRunStatus.ABANDONED) {
            throw new IllegalStateException("run 已处于终态，不可再推进: " + status);
        }
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }
        for (PipelineManifest.StageState stage : manifest.stages().values()) {
            if (PipelineStageStatus.RUNNING.name().equals(stage.getStatus())) {
                throw new IllegalStateException(
                        "该 run 有阶段正在生成中，请等待完成后再操作（单阶段 RUNNING 守卫）");
            }
        }
        return run;
    }

    // ==================== 阶段生成状态推进（tasks 3.1 / 3.7，生成引擎专用） ====================

    /**
     * 阶段置 RUNNING：状态机校验（NOT_STARTED/FAILED/AWAITING_CONFIRM/CONFIRMED 回退解锁
     * 均可进入 RUNNING）→ 先写 manifest（阶段 + run 双状态）后同步库表。
     * 残留 RUNNING（服务重启后 manifest 停在 RUNNING 且无活跃流）先复位 FAILED 再重入。
     *
     * @throws IllegalStateException 转换非法（如终态 run）
     */
    public void markStageRunning(String runId, PipelineStage stage, boolean staleRunningReset) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        if (staleRunningReset && PipelineStageStatus.RUNNING.name().equals(state.getStatus())) {
            log.warn("检测到残留 RUNNING（服务重启/崩溃后无活跃流），复位为 FAILED 后重入: runId={}, stage={}",
                    runId, stage.code());
            state.setStatus(PipelineStageStatus.FAILED.name());
        }
        PipelineStateMachine.requireTransition(
                PipelineStageStatus.valueOf(state.getStatus()), PipelineStageStatus.RUNNING);
        state.setStatus(PipelineStageStatus.RUNNING.name());
        advanceRun(manifest, runId, stage, PipelineRunStatus.RUNNING);
    }

    /**
     * 阶段生成完成：产物落盘后调用——阶段 RUNNING→AWAITING_CONFIRM、版本 v(N+1)、
     * manifest 摘要行更新（planner-stage-generation spec：草稿版本与摘要行一起落盘，
     * 前端步骤条与上游摘要段共用的数据源），run 状态置 AWAITING（跨天 gate 等待，
     * D6：持久化状态而非连接挂起）。summary 为空（null/blank）时保留既有摘要
     * （返工轮提取失败不抹旧值）。
     *
     * @return 推进后的阶段版本号 v(N+1)（供生成流的 draft_ready 尾包回报）
     */
    public int markStageCompleted(String runId, PipelineStage stage, String summary) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        PipelineStateMachine.requireTransition(
                PipelineStageStatus.valueOf(state.getStatus()), PipelineStageStatus.AWAITING_CONFIRM);
        state.setStatus(PipelineStageStatus.AWAITING_CONFIRM.name());
        state.setVersion(state.getVersion() + 1);
        // 上游版本基线（重算检测）：记录本次生成所读上游的版本号——上游解锁后
        // 编辑/返工会递增 version，高于基线即「上游已变」；S1 无上游不记（null）。
        // 收口时上游必为 CONFIRMED（生成前置），无并发窗口。
        if (stage.upstream() != null) {
            state.setUpstreamVersion(manifest.stage(stage.upstream().code()).getVersion());
        }
        if (summary != null && !summary.isBlank()) {
            state.setSummary(summary);
        }
        advanceRun(manifest, runId, stage, PipelineRunStatus.AWAITING);
        return state.getVersion();
    }

    /**
     * 阶段生成失败（D7 风险表：崩溃冻结单阶段可重试，不连锁）——阶段 RUNNING→FAILED、
     * run 状态置 FAILED。防御性：任何状态下的失败标记都吞异常记日志（失败标记自身
     * 不能再抛，否则丢掉原始错误）。
     */
    public void markStageFailed(String runId, PipelineStage stage) {
        try {
            PipelineManifest manifest = requireManifest(runId);
            PipelineManifest.StageState state = manifest.stage(stage.code());
            if (!PipelineStageStatus.RUNNING.name().equals(state.getStatus())) {
                return;
            }
            state.setStatus(PipelineStageStatus.FAILED.name());
            advanceRun(manifest, runId, stage, PipelineRunStatus.FAILED);
        } catch (Exception e) {
            log.error("阶段失败标记写入异常（保留原始错误，忽略本次标记失败）: runId={}, stage={}",
                    runId, stage.code(), e);
        }
    }

    // ==================== 确认门状态推进（tasks 4.1，门操作专用单点） ====================

    /**
     * 阶段整稿通过：AWAITING_CONFIRM → CONFIRMED、confirmedVersion 固化（确认稿存档
     * 标记）；currentStage 推进到下一阶段，run 保持 AWAITING（等待下一阶段被触发）。
     * <p>
     * L2 pending 清零前置校验由门操作层（StageGateService，tasks 4.2/4.3）先做；
     * S6 不走通用确认门（成稿安装端点专用语义，tasks 5.2 置 COMPLETED）。
     *
     * @throws IllegalStateException 阶段非 AWAITING_CONFIRM（未生成完/锁定/失败冻结）或 S6
     */
    public void confirmStage(String runId, PipelineStage stage) {
        if (stage == PipelineStage.S6_INSTALL) {
            throw new IllegalStateException(
                    "成稿安装阶段不走通用确认门（安装端点专用语义）");
        }
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        PipelineStateMachine.requireTransition(
                PipelineStageStatus.valueOf(state.getStatus()), PipelineStageStatus.CONFIRMED);
        state.setStatus(PipelineStageStatus.CONFIRMED.name());
        state.setConfirmedVersion(state.getVersion());

        PipelineStage next = PipelineStateMachine.nextStageAfterConfirm(stage);
        requireRunStatus(manifest, PipelineRunStatus.AWAITING);
        manifest.setCurrentStage(next.code());
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        runRepository.updateProgress(runId, next.code(), PipelineRunStatus.AWAITING.name());
        log.info("阶段已整稿通过: runId={}, stage={}, confirmedVersion={}",
                runId, stage.code(), state.getConfirmedVersion());
    }

    // ==================== 安装完成状态推进（tasks 5.1，安装端点专用单点） ====================

    /**
     * 安装成功收口：S6 阶段置 CONFIRMED、run 置 COMPLETED——由
     * {@code PlannerWorldInstaller} 在全部物化写入事务成功后调用（失败时零调用，
     * run 停在 AWAITING 可重试，重试产出新 world）。
     * <p>
     * S6 不走通用 {@code confirmStage}（后者显式拒绝 S6）也不走
     * {@code PipelineStateMachine.requireTransition} 的阶段边：给状态机加
     * NOT_STARTED→CONFIRMED 通用边会放开 S1-S5 跳过生成直接确认，安装端点的
     * 守卫链（S1-S5 全 CONFIRMED）才是 S6 前置语义——这里直写并在 javadoc 固化。
     * 重复安装（run 已 COMPLETED）幂等跳过转换，仅重写 S6 状态与时间戳。
     */
    public void markInstallCompleted(String runId) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(PipelineStage.S6_INSTALL.code());
        state.setStatus(PipelineStageStatus.CONFIRMED.name());

        PipelineRunStatus current = PipelineRunStatus.valueOf(manifest.getStatus());
        if (current != PipelineRunStatus.COMPLETED) {
            PipelineStateMachine.requireTransition(current, PipelineRunStatus.COMPLETED);
            manifest.setStatus(PipelineRunStatus.COMPLETED.name());
        }
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        runRepository.updateProgress(runId, PipelineStage.S6_INSTALL.code(),
                PipelineRunStatus.COMPLETED.name());
        log.info("管线 run 安装完成收口: runId={}", runId);
    }

    /**
     * 回退解锁（spec「确认稿幂等锁定」）：CONFIRMED → AWAITING_CONFIRM，
     * confirmedVersion 保留（原确认版本存档标记，历史确认记录不可删除）；
     * currentStage 回退到本阶段（确认时曾推进），run 保持 AWAITING。
     * 解锁后可编辑/返工/再确认（都走既有出边，不与单阶段 RUNNING 守卫冲突）。
     * <p>
     * 下游生成中的防御：run 非 AWAITING（如下游 RUNNING）时拒绝解锁——并发
     * 回退与生成会造成产物撕裂，门操作层另有 requireNoRunningStage 守卫前置。
     *
     * @throws IllegalStateException 阶段非 CONFIRMED、run 非 AWAITING 或 S6 终态
     */
    public void unlockStage(String runId, PipelineStage stage) {
        if (stage == PipelineStage.S6_INSTALL) {
            throw new IllegalStateException("终态阶段不可回退解锁: " + stage.code());
        }
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        PipelineStateMachine.requireTransition(
                PipelineStageStatus.valueOf(state.getStatus()), PipelineStageStatus.AWAITING_CONFIRM);
        requireRunStatus(manifest, PipelineRunStatus.AWAITING);
        state.setStatus(PipelineStageStatus.AWAITING_CONFIRM.name());
        manifest.setCurrentStage(stage.code());
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        runRepository.updateProgress(runId, stage.code(), PipelineRunStatus.AWAITING.name());
        log.info("阶段已回退解锁（原确认版本存档保留）: runId={}, stage={}, archivedVersion={}",
                runId, stage.code(), state.getConfirmedVersion());
    }

    /**
     * 人工编辑保存记账（tasks 4.2/4.3 门操作调用）：AWAITING_CONFIRM 态版本递增，
     * 返回新版本号 v(N+1)（调用方据此写产物与版本快照）；阶段级状态不变。
     * 锁定态 CONFIRMED 拒绝（spec「锁定后不可静默改，须先回退解锁」）。
     */
    public int recordDraftEdit(String runId, PipelineStage stage) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        requireAwaitingConfirm(state);
        state.setVersion(state.getVersion() + 1);
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        return state.getVersion();
    }

    /**
     * 返工轮记账（tasks 4.4 返工端点调用，反馈已过 moderation）：rounds 追加一条，
     * 后续触发的生成走 markStageRunning 正常流（阶段状态不变）。锁定态拒绝。
     *
     * @return 新返工轮号（rounds.size() + 1，与生成后版本 v(N+1) 对应）
     */
    public int recordReworkRound(String runId, PipelineStage stage, String feedback) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        requireAwaitingConfirm(state);
        int round = state.getRounds().size() + 1;
        state.getRounds().add(new PipelineManifest.ReRound(round, feedback,
                LocalDateTime.now().toString()));
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        return round;
    }

    /**
     * 生成完成时初始化候选状态（StageGenerationService.finalizeDraft L2 分支调用，
     * tasks 4.1）：每轮整轮重生成（D5）→ 候选状态全量重置 PENDING，id/title 与
     * 候选文件对齐（title 由 StageDraftSet.titleOf 提取）。旧轮被毙候选的排除表
     * 记忆已在上轮返工 prompt 里消费过，本轮重置即「新候选全新状态」。
     */
    public void initStageCandidates(String runId, PipelineStage stage, List<String> titles) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        List<PipelineManifest.CandidateState> fresh = new ArrayList<>(titles.size());
        for (int i = 0; i < titles.size(); i++) {
            fresh.add(new PipelineManifest.CandidateState(
                    String.format("candidate-%02d", i + 1), titles.get(i),
                    DraftItemStatus.PENDING.name()));
        }
        state.setCandidates(fresh);
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
    }

    // ==================== S3 分批记账（planner-s3-batch-generate tasks 2.1/2.3/2.4） ====================

    /**
     * 批计划记账（S3 分批编排开始时调用，markStageRunning 占位之后）：本次编排
     * 的批 append 进 {@code StageState.batches}（index 全局递增 = 既有最大 + 1，
     * 状态 PENDING）——批次轨迹是诊断真源（断点续跑的剩余推导走 manifest 候选行，
     * D2 幂等键 = 批内人名清单）。既有批记录（含历史 DONE/FAILED）不重置。
     *
     * @param nameBatches 本次编排的批划分（每批 = 批内人名清单）
     * @return 新追加的批记账（与入参顺序对应，供编排层与预拼批对齐）
     */
    public List<PipelineManifest.BatchState> recordBatchPlan(String runId, PipelineStage stage,
                                                             List<List<String>> nameBatches) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        int nextIndex = state.getBatches().stream()
                .mapToInt(PipelineManifest.BatchState::getIndex).max().orElse(0);
        List<PipelineManifest.BatchState> planned = new ArrayList<>(nameBatches.size());
        for (List<String> names : nameBatches) {
            planned.add(new PipelineManifest.BatchState(++nextIndex, names, "PENDING"));
        }
        state.getBatches().addAll(planned);
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        return planned;
    }

    /**
     * 批状态流转（S3 分批收口/失败时调用）：DONE（已追加落盘）/ FAILED（冻结）。
     * 未知批序号忽略（防御，不抛——失败标记路径不能因记账缺失丢原始错误）。
     */
    public void setBatchStatus(String runId, PipelineStage stage, int batchIndex, String status) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        state.getBatches().stream()
                .filter(b -> b.getIndex() == batchIndex)
                .findFirst()
                .ifPresent(b -> b.setStatus(status));
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
    }

    /**
     * 候选追加记账（S3 分批批收口调用，文件追加落盘之后）：manifest 候选列表
     * append（id 与落盘文件一一对应、title 由 StageDraftSet.titleOf 提取、
     * 新卡 PENDING）——既有候选的逐卡状态（PENDING/CONFIRMED/REJECTED）不动
     *（spec「追加不重置」，返工整轮重置走 initStageCandidates）。
     */
    public void appendStageCandidates(String runId, PipelineStage stage,
                                      List<String> candidateIds, List<String> titles) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        for (int i = 0; i < candidateIds.size(); i++) {
            state.getCandidates().add(new PipelineManifest.CandidateState(
                    candidateIds.get(i), titles.get(i), DraftItemStatus.PENDING.name()));
        }
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
    }

    /**
     * 候选状态流转（tasks 4.3 门操作调用）：毙掉（幂等——同状态再点不重写）
     * 或逐卡确认（确认后可改毙）。REJECTED 为终态：不可再确认（救回走返工
     * 重生成，D5）；未知 id 拒绝（整轮覆盖后旧 id 失效）。锁定态阶段拒绝。
     * 候选级状态不涉库表，只写 manifest（状态真源在文件树，D3）。
     */
    public void setCandidateStatus(String runId, PipelineStage stage, String candidateId,
                                   DraftItemStatus target) {
        if (target != DraftItemStatus.CONFIRMED && target != DraftItemStatus.REJECTED) {
            throw new IllegalArgumentException("非法的候选目标状态: " + target);
        }
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        requireAwaitingConfirm(state);
        PipelineManifest.CandidateState candidate = state.candidate(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("候选不存在或已过期: " + candidateId);
        }
        if (DraftItemStatus.REJECTED.name().equals(candidate.getStatus())
                && target == DraftItemStatus.CONFIRMED) {
            throw new IllegalStateException("已毙候选不可确认（救回走返工重生成）");
        }
        if (target.name().equals(candidate.getStatus())) {
            return;   // 幂等：同状态再点零重写
        }
        candidate.setStatus(target.name());
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        log.info("候选状态流转: runId={}, stage={}, candidate={}, status={}",
                runId, stage.code(), candidateId, target);
    }

    /**
     * 候选批量确认（planner-s3-batch-confirm tasks 1.1，spec「L2 门批量确认」）：
     * 单次读改写把全部 PENDING 候选置 CONFIRMED——REJECTED（终态，救回走
     * 返工重生成）与既有 CONFIRMED 均不动；阶段级状态不变（仍 AWAITING_CONFIRM，
     * 整稿通过另有单点）。幂等：无 PENDING 零重写、返回 0。
     *
     * @return 本次确认张数（confirmedCount）
     */
    public int confirmAllCandidates(String runId, PipelineStage stage) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        requireAwaitingConfirm(state);
        int confirmedCount = 0;
        for (PipelineManifest.CandidateState candidate : state.getCandidates()) {
            if (DraftItemStatus.PENDING.name().equals(candidate.getStatus())) {
                candidate.setStatus(DraftItemStatus.CONFIRMED.name());
                confirmedCount++;
            }
        }
        if (confirmedCount > 0) {
            manifest.setUpdatedAt(LocalDateTime.now().toString());
            workspaceStore.writeManifest(runId, manifest);
        }
        log.info("候选批量确认: runId={}, stage={}, confirmedCount={}",
                runId, stage.code(), confirmedCount);
        return confirmedCount;
    }

    /**
     * 幕级回退解锁候选复位（story-graph-authoring 2.6，spec「已确认幕显式回退
     * 解锁」）：CONFIRMED 幕候选置回 PENDING（阶段保持 AWAITING_CONFIRM、
     * 其他幕候选状态不动——单幕解锁不波及他幕）。拼接图失效处理在门操作层
     * （{@code StageGateService.unlockAct} 删除 stage artifact 标记待重拼，
     * 产物面不在状态单点职责内）。非 CONFIRMED 拒绝（无从解锁）。
     */
    public void demoteCandidate(String runId, PipelineStage stage, String candidateId) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        requireAwaitingConfirm(state);
        PipelineManifest.CandidateState candidate = state.candidate(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("候选不存在或已过期: " + candidateId);
        }
        if (!DraftItemStatus.CONFIRMED.name().equals(candidate.getStatus())) {
            throw new IllegalStateException("仅已确认候选可回退解锁，当前状态: " + candidate.getStatus());
        }
        candidate.setStatus(DraftItemStatus.PENDING.name());
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        log.info("候选已回退解锁（幕级）: runId={}, stage={}, candidate={}",
                runId, stage.code(), candidateId);
    }

    /**
     * 幕级返工救回候选复活（story-graph-authoring 2.6）：REJECTED 幕候选置回
     * PENDING（D5「救回走返工重生成」的单幕具体化——幕级返工重生成救回被毙幕）；
     * PENDING 幂等零重写；CONFIRMED 拒绝（须先 demoteCandidate 回退解锁）。
     */
    public void reviveCandidate(String runId, PipelineStage stage, String candidateId) {
        PipelineManifest manifest = requireManifest(runId);
        PipelineManifest.StageState state = manifest.stage(stage.code());
        requireAwaitingConfirm(state);
        PipelineManifest.CandidateState candidate = state.candidate(candidateId);
        if (candidate == null) {
            throw new IllegalArgumentException("候选不存在或已过期: " + candidateId);
        }
        if (DraftItemStatus.CONFIRMED.name().equals(candidate.getStatus())) {
            throw new IllegalStateException("已确认候选须先回退解锁（unlockAct）后返工");
        }
        if (DraftItemStatus.PENDING.name().equals(candidate.getStatus())) {
            return;   // 幂等：已待定零重写
        }
        candidate.setStatus(DraftItemStatus.PENDING.name());
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        log.info("候选已复活（幕级返工救回）: runId={}, stage={}, candidate={}",
                runId, stage.code(), candidateId);
    }

    private void requireAwaitingConfirm(PipelineManifest.StageState state) {
        PipelineStageStatus current = PipelineStageStatus.valueOf(state.getStatus());
        if (current != PipelineStageStatus.AWAITING_CONFIRM) {
            throw new IllegalStateException(
                    "阶段当前状态不可操作（锁定态须先回退解锁）: " + current);
        }
    }

    private void requireRunStatus(PipelineManifest manifest, PipelineRunStatus expected) {
        PipelineRunStatus current = PipelineRunStatus.valueOf(manifest.getStatus());
        if (current != expected) {
            throw new IllegalStateException("run 当前状态不匹配操作前置: " + current);
        }
    }

    /** 双状态同步写：先 manifest 后库表（D3 写序约定）。 */
    private void advanceRun(PipelineManifest manifest, String runId, PipelineStage stage,
                            PipelineRunStatus runStatus) {
        PipelineRunStatus current = PipelineRunStatus.valueOf(manifest.getStatus());
        PipelineStateMachine.requireTransition(current, runStatus);
        manifest.setStatus(runStatus.name());
        manifest.setCurrentStage(stage.code());
        manifest.setUpdatedAt(LocalDateTime.now().toString());
        workspaceStore.writeManifest(runId, manifest);
        runRepository.updateProgress(runId, stage.code(), runStatus.name());
    }

    /** 读 manifest；缺失即数据损坏（409 语义，与 requireNoRunningStage 一致）。 */
    private PipelineManifest requireManifest(String runId) {
        PipelineManifest manifest = workspaceStore.readManifest(runId);
        if (manifest == null) {
            throw new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: " + runId);
        }
        return manifest;
    }

    // ==================== 内部 ====================

    /** 查 run；不存在或跨租户抛 NoSuchElementException（404 惯例）。 */
    private PipelineRun requireRun(String runId) {
        Optional<PipelineRun> run = runRepository.findById(runId);
        return run.orElseThrow(() -> new NoSuchElementException("管线 run 不存在: " + runId));
    }

    /** 库表元数据 + manifest 阶段状态合成详情视图。 */
    private RunDetail buildDetail(PipelineRun run, PipelineManifest manifest) {
        RunDetail detail = new RunDetail();
        detail.setId(run.getId());
        detail.setName(run.getName());
        detail.setSourceMode(run.getSourceMode());
        detail.setStyleMode(run.getStyleMode());
        detail.setStatus(manifest.getStatus() != null ? manifest.getStatus() : run.getStatus());
        detail.setCurrentStage(run.getCurrentStage());
        detail.setCreatedAt(formatTime(run.getCreatedAt()));
        detail.setUpdatedAt(formatTime(run.getUpdatedAt()));

        String source = workspaceStore.readSource(run.getId());
        if (source != null && !source.isBlank()) {
            detail.setSourcePreview(source.length() > SOURCE_PREVIEW
                    ? source.substring(0, SOURCE_PREVIEW) + "…" : source);
        }
        detail.setSourceUrls(manifest.getSourceUrls());
        detail.setSourcePages(manifest.getSourcePages());
        // S1 链式派生原料（与生成分叉同判的 readS1Material；text = source 原文）
        String s1Material = workspaceStore.readS1Material(run.getId(), run.getSourceMode());

        for (PipelineStage stage : PipelineStage.values()) {
            PipelineManifest.StageState state = manifest.stage(stage.code());
            RunDetail.StageView view = new RunDetail.StageView();
            view.setCode(stage.code());
            view.setLabel(stage.label());
            view.setGate(stage.gate().name());
            view.setStatus(state.getStatus());
            view.setVersion(state.getVersion());
            view.setSummary(state.getSummary());
            view.setRounds(state.getRounds());
            // S1 链式派生标记（planner-s1-chained-refinement tasks 3.3；planner-html-source
            // D6 与生成分叉同判——原料统一 readS1Material，ip_name 资料页合并虚拟原文
            // 同样可入链式档）：前端 L1 门据此渲染链式返工窗；其余恒 false
            view.setChained(stage == PipelineStage.S1_IP_ANALYSIS
                    && s1Material != null && s1Material.length() > properties.getChainThresholdChars());
            // 上游已变重算提示（重算检测）：上游 CONFIRMED 且版本高于本阶段生成
            // 基线（upstreamVersion null = S1 无上游/旧 run 无基线，保守不提示）；
            // 上游解锁编辑中不提示（重算须先等上游重新确认，门级链路才干净）
            PipelineStage upstream = stage.upstream();
            if (upstream != null) {
                view.setUpstreamCode(upstream.code());
                PipelineManifest.StageState upstreamState = manifest.stage(upstream.code());
                view.setUpstreamChanged(state.getUpstreamVersion() != null
                        && PipelineStageStatus.CONFIRMED.name().equals(upstreamState.getStatus())
                        && upstreamState.getVersion() > state.getUpstreamVersion());
            }
            // S3 名册剩余派生（planner-s3-batch-generate tasks 5.1）：名册 − 已产出 −
            // 已毙（毙掉即出局，spec「已毙人物不复活」；与 S3BatchGenerationService
            // 分派同口径——含 REJECTED 算出局，否则前端提示数与后端分派数撕裂）。
            // 前端「补一批/全量生成」按钮提示与 confirm 预估批数；S1 无名册
            // （remix / 旧稿）不透出（按钮不渲染，后端补批守卫兑底拒绝）
            if (stage == PipelineStage.S3_CHARACTERS) {
                view.setBatchCapacity(properties.getMaxCandidates());
                List<String> rosterNames = StagePromptAssembler.extractRosterNames(
                        StagePromptAssembler.extractRosterSection(
                                workspaceStore.readStageArtifact(run.getId(), PipelineStage.S1_IP_ANALYSIS)));
                if (!rosterNames.isEmpty()) {
                    List<String> producedTitles = new ArrayList<>();
                    for (PipelineManifest.CandidateState candidate : state.getCandidates()) {
                        producedTitles.add(candidate.getTitle());
                    }
                    view.setRosterRemaining(
                            StagePromptAssembler.rosterRemaining(rosterNames, producedTitles).size());
                }
            }
            // L2 候选卡片集（tasks 6.6）：manifest 状态行 + 候选文件 payload 全文
            // 合成（卡片集预览/编辑零额外请求）；L1 阶段候选恒空不进入。
            for (PipelineManifest.CandidateState candidate : state.getCandidates()) {
                RunDetail.CandidateView cv = new RunDetail.CandidateView();
                cv.setId(candidate.getId());
                cv.setTitle(candidate.getTitle());
                cv.setStatus(candidate.getStatus());
                cv.setPayload(workspaceStore.readCandidateItem(run.getId(), stage, candidate.getId()));
                view.getCandidates().add(cv);
            }
            detail.getStages().add(view);
        }
        return detail;
    }

    private static String truncate(String text) {
        return text.length() > NAME_TRUNCATE ? text.substring(0, NAME_TRUNCATE) + "…" : text;
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : time.toString();
    }
}
