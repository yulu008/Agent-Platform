package com.luyu.agent.rpg.planner.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.codec.ServerSentEvent;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorkspaceStore;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StageGenerationService} 单元测试（纯 Mockito，贴 {@code RpgGameControllerTest} 惯例）。
 * <p>
 * 钉住（tasks 3.1 / 3.7，spec planner-stage-generation）：前置快速失败零副作用
 * （未知码 / run 不存在 / 终态 / 跨阶段跳过 / S6 拒绝 / 配额）、单阶段 RUNNING
 * 内存守卫拒真并发且流结束释放、正常流（token 透传 → 落盘 → AWAITING_CONFIRM →
 * draft_ready{version} → [DONE]）、LLM 异常/空响应/落盘失败/取消全路径冻结 FAILED
 * 不连锁（D7 风险表）、上下文装配（sourceText / 直接上游确认稿，D7）。
 */
class StageGenerationServiceTest {

    private static final String RUN_ID = "run-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private PipelineRunService runService;
    private PlannerWorkspaceStore workspaceStore;
    private PipelineRunRepository runRepository;
    private ChatClientRegistry chatClientRegistry;
    private QuotaGuard quotaGuard;
    private StageGenerator generator;
    /** S1 链式分叉目标（mock：本测试未直接覆盖链式内部，见 S1ChainedRefineServiceTest） */
    private S1ChainedRefineService s1ChainedRefineService;
    /** S3 分批分叉目标（mock：本测试未直接覆盖分批内部，见 S3BatchGenerationServiceTest） */
    private S3BatchGenerationService s3BatchGenerationService;
    /** 默认阈值 10 万字——setUp 的 7 字 source 恒不误触发分叉 */
    private final RpgPlannerProperties properties = new RpgPlannerProperties();
    /** 共享守卫（与 S4.5 幕级编排同构，story-graph-authoring tasks 2.3）：每测试实例独立 */
    private final PlannerGenerationGuard generationGuard = new PlannerGenerationGuard();
    private StageGenerationService service;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runService = mock(PipelineRunService.class);
        workspaceStore = mock(PlannerWorkspaceStore.class);
        runRepository = mock(PipelineRunRepository.class);
        chatClientRegistry = mock(ChatClientRegistry.class);
        quotaGuard = mock(QuotaGuard.class);
        generator = mock(StageGenerator.class);
        s1ChainedRefineService = mock(S1ChainedRefineService.class);
        s3BatchGenerationService = mock(S3BatchGenerationService.class);

        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClientRegistry.forRole("planner")).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("拆解", "内容"));

        when(generator.stage()).thenReturn(PipelineStage.S1_IP_ANALYSIS);
        when(generator.buildPrompt(any())).thenReturn("S1 prompt");
        when(runService.markStageCompleted(anyString(), any(), any())).thenReturn(2);

        service = new StageGenerationService(runService, workspaceStore, runRepository,
                chatClientRegistry, quotaGuard, generationGuard,
                s1ChainedRefineService, s3BatchGenerationService, properties, List.of(generator));

        // 标准可生成前置：run CREATED/S1 + manifest currentStage S1（S1 状态 NOT_STARTED）
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("CREATED", "S1")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("CREATED", "S1"));
        // S1 原料统一入口（planner-html-source D6）：manifest 未设 sourceMode（null →
        // 非 ip_name）走 readS1Material；非 S1 阶段 buildContext 仍走 readSource
        when(workspaceStore.readS1Material(RUN_ID, null)).thenReturn("原始输入文本");
        when(workspaceStore.readSource(RUN_ID)).thenReturn("原始输入文本");
    }

    // ===== 前置快速失败（零副作用） =====

    @Test
    void 未知阶段码发error事件且零副作用() {
        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S9")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .containsExactly(Map.of("error", "未知的管线阶段码: S9"), "[DONE]");
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void run不存在发error事件() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("管线 run 不存在"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void run终态发error事件() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("ABANDONED", "S1")));

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("终态"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void 跨阶段跳过被拒() {
        // currentStage=S1 但请求 S2：状态机要求 currentStage == target
        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S2")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("跨阶段跳过被拒"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void S6安装阶段拒绝生成() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S6")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("AWAITING", "S6"));

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S6")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data))
                        .contains("成稿安装阶段不经生成端点"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void S45直调拒绝防御兜底消息保留() {
        // planner-s1-chained-refinement tasks 5.2：controller 已对 S4.5 转发幕级
        // 编排，但直调 generateStage 的路径仍拒（防配置漂移/新调用点绕过）——
        // 专用端点提示消息保留作防御兜底
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S4.5")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("AWAITING", "S4.5"));

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S4.5")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data))
                        .contains("故事图骨架阶段不经通用生成端点"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void 配额超限发error事件且不置RUNNING() {
        doThrow(new QuotaExceededException(100L, 100L)).when(quotaGuard).check(any());

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("配额已用尽"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void 前置推进被拒时释放活跃流占位() {
        doThrow(new IllegalStateException("非法的 run 状态转换"))
                .when(runService).markStageRunning(eq(RUN_ID), any(), anyBoolean());

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("非法的 run 状态转换"));
        // 占位必须释放，否则该 run 永久卡死在内存守卫
        assertThat(service.activeStage(RUN_ID)).isEmpty();
    }

    // ===== 单阶段 RUNNING 内存守卫（tasks 2.5 / 3.7） =====

    @Test
    void 活跃流未释放时拒绝第二次生成() {
        Flux<ServerSentEvent<Object>> first = service.generateStage(RUN_ID, "S1"); // 组装即占位
        List<ServerSentEvent<Object>> second = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(second).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("单阶段 RUNNING 守卫"));
        verify(runService, times(1)).markStageRunning(anyString(), any(), anyBoolean());

        // 第一次流完成后守卫释放，可再次生成（mock 下状态推进无副作用）
        first.collectList().block(TIMEOUT);
        assertThat(service.activeStage(RUN_ID)).isEmpty();
        List<ServerSentEvent<Object>> third = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);
        assertThat(third).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("draft_ready"));
    }

    // ===== 正常流（生成 → 落盘 → 推进 → draft_ready → [DONE]） =====

    @Test
    void 正常流token透传落盘推进并尾包draft_ready() {
        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).isNotNull();
        // 序言 stage_start 恰一次，含阶段码与标签
        Optional<ServerSentEvent<Object>> stageStart = events.stream().filter(e ->
                e.data() instanceof Map<?, ?> m && "stage_start".equals(m.get("type"))).findFirst();
        assertThat(stageStart).isPresent();
        assertThat(((Map<?, ?>) stageStart.get().data()).get("stage")).isEqualTo("S1");
        assertThat(((Map<?, ?>) stageStart.get().data()).get("label")).isEqualTo("IP 拆解");
        
        // token 流全量透传
        assertThat(joinedContent(events)).isEqualTo("拆解内容");
        
        // 尾包 draft_ready 带 version=2（markStageCompleted 推进值）
        Optional<ServerSentEvent<Object>> draftReady = events.stream().filter(e ->
                e.data() instanceof Map<?, ?> m && "draft_ready".equals(m.get("type"))).findFirst();
        assertThat(draftReady).isPresent();
        assertThat(((Map<?, ?>) draftReady.get().data()).get("version")).isEqualTo(2);

        // [DONE] 为流终结标记
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");

        // 落盘 + 状态推进 + 守卫释放
        verify(workspaceStore).writeStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS, "拆解内容");
                verify(runService).markStageCompleted(RUN_ID, PipelineStage.S1_IP_ANALYSIS, "拆解内容");
        assertThat(service.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void 生成上下文装配sourceText供S1() {
        service.generateStage(RUN_ID, "S1").collectList().block(TIMEOUT);

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(generator).buildPrompt(captor.capture());
        GenerationContext context = captor.getValue();
        assertThat(context.sourceText()).isEqualTo("原始输入文本");
        assertThat(context.upstreamConfirmed()).isNull(); // S1 无上游
        assertThat(context.currentDraft()).isNull();       // 首轮无上轮全稿
        assertThat(context.rejectedTitles()).isEmpty();    // L1 无候选状态 → 空列表（非 null）
    }

    @Test
    void 生成上下文装配直接上游确认稿供下游阶段() {
        // 独立 S2 generator：生成器映射在 service 构造时按 stage() 固化，
        // setUp 的 generator 已绑 S1，改返 S2 不生效
        StageGenerator s2Generator = mock(StageGenerator.class);
        when(s2Generator.stage()).thenReturn(PipelineStage.S2_WORLD_SETTING);
        when(s2Generator.buildPrompt(any())).thenReturn("S2 prompt");
        StageGenerationService s2Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s2Generator));

        // S2 可生成前置：currentStage=S2（S1 视为已确认）+ 返工轮历史
        PipelineManifest manifest = manifest("AWAITING", "S2");
        manifest.stage("S1").setStatus("CONFIRMED");
        List<com.luyu.agent.rpg.planner.model.PipelineManifest.ReRound> rounds =
                List.of(round(1, "人物太扁平"));
        manifest.stage("S2").setRounds(rounds);
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S2")));
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS))
                .thenReturn("S1 确认稿全文");
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S2_WORLD_SETTING))
                .thenReturn("S2 上轮全稿");

        s2Service.generateStage(RUN_ID, "S2").collectList().block(TIMEOUT);

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(s2Generator).buildPrompt(captor.capture());
        GenerationContext context = captor.getValue();
        assertThat(context.sourceText()).isEqualTo("原始输入文本");
        assertThat(context.upstreamConfirmed()).isEqualTo("S1 确认稿全文");
        assertThat(context.currentDraft()).isEqualTo("S2 上轮全稿");
        assertThat(context.rounds()).isEqualTo(rounds);
        assertThat(context.isRework()).isTrue();
    }

    // ===== 失败冻结（D7 风险表：FAILED 不连锁、可重试） =====

    @Test
    void LLM流异常置FAILED并发error事件收流() {
        when(streamSpec.content()).thenReturn(Flux.error(new RuntimeException("模型超时")));

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("阶段生成异常"));
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        assertThat(events.stream().noneMatch(e ->
                e.data() instanceof Map<?, ?> m && "draft_ready".equals(m.get("type")))).isTrue();

        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
        verify(workspaceStore, never()).writeStageArtifact(anyString(), any(), anyString());
        assertThat(service.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void 空响应置FAILED不落盘() {
        when(streamSpec.content()).thenReturn(Flux.empty());

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("未产出草稿"));
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
        verify(workspaceStore, never()).writeStageArtifact(anyString(), any(), anyString());
    }

    @Test
    void 落盘失败置FAILED并发error事件() {
        doThrow(new IllegalStateException("阶段产物写入失败"))
                .when(workspaceStore).writeStageArtifact(anyString(), any(), anyString());

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("草稿落盘失败"));
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
    }

    @Test
    void 流被取消时置FAILED并释放守卫() {
        // take(1) 收到序言即取消下游：走 doFinally(CANCEL) 路径
        service.generateStage(RUN_ID, "S1").take(1).blockLast(TIMEOUT);

        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
        assertThat(service.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void FAILED阶段重试复用残留产物并走完整流程() {
        // run/阶段双双 FAILED（崩溃冻结后重试）：状态机允许 FAILED→RUNNING
        PipelineManifest m = manifest("FAILED", "S1");
        m.stage("S1").setStatus("FAILED");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(m);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("FAILED", "S1")));
        // 上轮失败时残留的部分产物：重试时作为上轮全稿注入（D7 断点复用）
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS))
                .thenReturn("上轮失败残留的部分草稿");

        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);

        // 重试进入 RUNNING（stale 复位开关开）→ 正常完成全链：落盘 + 推进 + 收流
        verify(runService).markStageRunning(RUN_ID, PipelineStage.S1_IP_ANALYSIS, true);
        verify(workspaceStore).writeStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS, "拆解内容");
                verify(runService).markStageCompleted(RUN_ID, PipelineStage.S1_IP_ANALYSIS, "拆解内容");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        assertThat(service.activeStage(RUN_ID)).isEmpty();

        // 残留产物作为 currentDraft 注入（复用已落盘中间产物，非重打鼓重开张）
        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(generator).buildPrompt(captor.capture());
        assertThat(captor.getValue().currentDraft()).isEqualTo("上轮失败残留的部分草稿");
    }

    // ===== S1 ip_name 资料页原料分派（planner-html-source tasks 4.1-4.3） =====
    
    @Test
    void ip_name模式S1原料来自资料页合并文本() {
        PipelineManifest manifest = manifest("CREATED", "S1");
        manifest.setSourceMode("ip_name");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(workspaceStore.readS1Material(RUN_ID, "ip_name"))
                .thenReturn("目标 IP：三体\n\n===== 资料页 01 =====\n# 三体\n");
    
        service.generateStage(RUN_ID, "S1").collectList().block(TIMEOUT);
    
        // 短原料（未超链式阈值）走单流生成器，原料注入上下文（D6 统一分派）
        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(generator).buildPrompt(captor.capture());
        assertThat(captor.getValue().sourceText())
                .isEqualTo("目标 IP：三体\n\n===== 资料页 01 =====\n# 三体\n");
        verify(s1ChainedRefineService, never()).generateChained(anyString(), any());
    }
    
    @Test
    void ip_name模式超链式阈值分叉链式提炼() {
        properties.setChainThresholdChars(10);
        PipelineManifest manifest = manifest("CREATED", "S1");
        manifest.setSourceMode("ip_name");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        // pages 合并虚拟原文超阈值：与 text 原文同判分叉（D6 分档收敛）
        when(workspaceStore.readS1Material(RUN_ID, "ip_name")).thenReturn("字".repeat(11));
        when(s1ChainedRefineService.generateChained(RUN_ID, null)).thenReturn(Flux.just(
                ServerSentEvent.<Object>builder().data("[DONE]").build()));
    
        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);
    
        verify(s1ChainedRefineService).generateChained(RUN_ID, null);
        // 单流生成器不参与（分叉直接转入链式服务，事件流由其接管）
        verify(generator, never()).buildPrompt(any());
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
    }
    
    @Test
    void ip_name存量run无pages落盘快速失败() {
        PipelineManifest manifest = manifest("CREATED", "S1");
        manifest.setSourceMode("ip_name");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        // scanUrls 时代存量 run：无 pages 落盘 → readS1Material 返回 null
        when(workspaceStore.readS1Material(RUN_ID, "ip_name")).thenReturn(null);
    
        List<ServerSentEvent<Object>> events = service.generateStage(RUN_ID, "S1")
                .collectList().block(TIMEOUT);
    
        assertThat(events).extracting(ServerSentEvent::data)
                .anySatisfy(data -> assertThat(String.valueOf(data)).contains("资料页缺失"));
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        // 快速失败在占位与状态推进之前（零副作用，阶段状态不变）
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(generator, never()).buildPrompt(any());
    }
    
    @Test
    void ip_name模式S2仍走普通生成器() {
        // ip_name run 的后续阶段：原料是 S1 产物，正常单流生成（资料页原料只属 S1）
        StageGenerator s2Generator = mock(StageGenerator.class);
        when(s2Generator.stage()).thenReturn(PipelineStage.S2_WORLD_SETTING);
        when(s2Generator.buildPrompt(any())).thenReturn("S2 prompt");
        StageGenerationService s2Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s2Generator));
    
        PipelineManifest manifest = manifest("AWAITING", "S2");
        manifest.setSourceMode("ip_name");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S2")));
    
        s2Service.generateStage(RUN_ID, "S2").collectList().block(TIMEOUT);
    
        // 非 S1 阶段原料走 readSource（D6：readS1Material 只在 S1 消费）；
        // 联网抓取链路已退役，请求不挂任何工具回调
        verify(s2Generator).buildPrompt(any());
        verify(workspaceStore, never()).readS1Material(anyString(), anyString());
        verify(requestSpec, never()).toolCallbacks(any(ToolCallback[].class));
    }

    // ===== L2 候选集阶段（tasks 3.6：S3/S5 拆分落盘 + 上下文聚合） =====

    @Test
    void S3上下文装配S1名册节() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("AWAITING", "S3"));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S3")));
        // S1 确认稿带名册节（faithful 成稿）：S3 生成时提取为对照池直通 prompt 装配
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS))
                .thenReturn("## 一、核心概念\n概念\n## 三、人物全量名册\n- **甲**：身份\n## 四、主线\n主线");
        when(streamSpec.content()).thenReturn(Flux.just("## 人物 1：甲\n- 身份：X"));

        s3Service.generateStage(RUN_ID, "S3").collectList().block(TIMEOUT);

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(s3Generator).buildPrompt(captor.capture());
        assertThat(captor.getValue().roster())
                .startsWith("## 三、人物全量名册")
                .contains("- **甲**：身份")
                .doesNotContain("## 四");
    }

    @Test
    void S3候选集流拆分逐候选落盘并带candidates尾包() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("AWAITING", "S3"));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S3")));
        when(streamSpec.content()).thenReturn(Flux.just(
                "导语丢弃\n",
                "## 人物 1：甲\n- 身份：X\n\n",
                "## 人物 2：乙\n- 身份：Y"));

        List<ServerSentEvent<Object>> events = s3Service.generateStage(RUN_ID, "S3")
                .collectList().block(TIMEOUT);

        // 拆分落盘（导语丢弃，两候选按序）+ 不走单文件路径
        verify(workspaceStore).writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("## 人物 1：甲\n- 身份：X", "## 人物 2：乙\n- 身份：Y"));
        verify(workspaceStore, never()).writeStageArtifact(eq(RUN_ID), eq(PipelineStage.S3_CHARACTERS), anyString());
                verify(runService).markStageCompleted(RUN_ID, PipelineStage.S3_CHARACTERS,
                "2 个候选：人物 1：甲、人物 2：乙");
        // draft_ready 尾包带 candidates 数量
        Optional<ServerSentEvent<Object>> draftReady = events.stream()
                .filter(e -> e.data() instanceof Map<?, ?> m && "draft_ready".equals(m.get("type")))
                .findFirst();
        assertThat(draftReady).isPresent();
        assertThat(((Map<?, ?>) draftReady.get().data()).get("candidates")).isEqualTo(2);
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
    }

    @Test
    void S3无候选标题时降级单候选落盘() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("AWAITING", "S3"));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S3")));
        // 无任何 ## 二级标题 → CandidateSplitter 降级为单候选（格式漂移不丢内容）
        when(streamSpec.content()).thenReturn(Flux.just("模型格式漂移的纯文本"));

        List<ServerSentEvent<Object>> events = s3Service.generateStage(RUN_ID, "S3")
                .collectList().block(TIMEOUT);

        // 单候选落盘（非 FAILED）+ 尾包 candidates=1
        verify(workspaceStore).writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("模型格式漂移的纯文本"));
        verify(runService).markStageCompleted(RUN_ID, PipelineStage.S3_CHARACTERS,
                "1 个候选：模型格式漂移的纯文本");
        Optional<ServerSentEvent<Object>> draftReady = events.stream()
                .filter(e -> e.data() instanceof Map<?, ?> m && "draft_ready".equals(m.get("type")))
                .findFirst();
        assertThat(draftReady).isPresent();
        assertThat(((Map<?, ?>) draftReady.get().data()).get("candidates")).isEqualTo(1);
    }

    @Test
    void S4生成时上游S3以紧凑索引注入() {
        StageGenerator s4Generator = mock(StageGenerator.class);
        when(s4Generator.stage()).thenReturn(PipelineStage.S4_PLOT_STRUCTURE);
        when(s4Generator.buildPrompt(any())).thenReturn("S4 prompt");
        StageGenerationService s4Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s4Generator));

        // S3 已确认（阶段级）：两候选 CONFIRMED——候选状态行整份交 store 语义聚合
        PipelineManifest s4Manifest = manifest("AWAITING", "S4");
        s4Manifest.stage("S3").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "人物 1：甲", "CONFIRMED"),
                new PipelineManifest.CandidateState("candidate-02", "人物 2：乙", "CONFIRMED")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(s4Manifest);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S4")));
        // mock 契约：store 语义聚合（过滤与逐卡读取的真实逻辑归 store 测试覆盖）
        when(workspaceStore.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS,
                s4Manifest.stage("S3").getCandidates()))
                .thenReturn("## 人物 1：甲\n- 身份：谋士\n\n## 人物 2：乙\n- 身份：茶屋主");

        s4Service.generateStage(RUN_ID, "S4").collectList().block(TIMEOUT);

        // D6 紧凑特化：S4 的上游确认稿 = S3 语义聚合全稿切卡后每非毙卡一行
        // 「人名：身份钩子」（紧凑索引远低于截断阈值，spec「S4 注入不膨胀」）；
        // 不走单文件读
        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(s4Generator).buildPrompt(captor.capture());
        assertThat(captor.getValue().upstreamConfirmed())
                .isEqualTo("- 甲：谋士\n- 乙：茶屋主\n");
        verify(workspaceStore, never()).readStageArtifact(RUN_ID, PipelineStage.S3_CHARACTERS);
    }

    @Test
    void S3返工currentDraft同样走候选聚合() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        PipelineManifest m = manifest("AWAITING", "S3");
        m.stage("S3").setRounds(List.of(round(1, "第一卡太扁")));
        m.stage("S3").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "候选 A", "PENDING"),
                new PipelineManifest.CandidateState("candidate-02", "候选 B", "PENDING")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(m);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S3")));
        when(workspaceStore.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS,
                m.stage("S3").getCandidates()))
                .thenReturn("上轮候选 A\n\n上轮候选 B");

        s3Service.generateStage(RUN_ID, "S3").collectList().block(TIMEOUT);

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(s3Generator).buildPrompt(captor.capture());
        assertThat(captor.getValue().currentDraft()).isEqualTo("上轮候选 A\n\n上轮候选 B");
        assertThat(captor.getValue().isRework()).isTrue();
    }

    // ===== S3 分批分叉的返工后续跑分派（run 5ffd6d5b 修复） =====

    @Test
    void S3返工后FAILED且曾有分批续走分批编排() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        // run 5ffd6d5b 形态：S3 FAILED + rounds 非空（返工反馈历史）+ batches 非空
        //（分批模式曾运行过）——旧条件 rounds.isEmpty() 会误落单流巨型 prompt 撞线
        PipelineManifest m = manifest("FAILED", "S3");
        m.stage("S3").setRounds(List.of(round(1, "人物不全")));
        m.stage("S3").setBatches(List.of(new PipelineManifest.BatchState()));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(m);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("FAILED", "S3")));
        // 名册 2 人 > 批容量 1（超容量才分批）
        properties.setMaxCandidates(1);
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS))
                .thenReturn("## 三、人物全量名册\n- **甲**：身份\n- **乙**：身份");
        when(s3BatchGenerationService.generateRemainingBatches(RUN_ID, Integer.MAX_VALUE))
                .thenReturn(Flux.just(ServerSentEvent.builder().data("[DONE]").build()));

        s3Service.generateStage(RUN_ID, "S3").collectList().block(TIMEOUT);

        // 分批编排接管（守卫占位前转入，单流零副作用）
        verify(s3BatchGenerationService).generateRemainingBatches(RUN_ID, Integer.MAX_VALUE);
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void S3返工后FAILED无分批历史维持单流() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        // rounds 非空但 batches 空（返工单流失败）：维持单流重跑既有语义
        PipelineManifest m = manifest("FAILED", "S3");
        m.stage("S3").setRounds(List.of(round(1, "人物不全")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(m);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("FAILED", "S3")));
        properties.setMaxCandidates(1);
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS))
                .thenReturn("## 三、人物全量名册\n- **甲**：身份\n- **乙**：身份");
        when(streamSpec.content()).thenReturn(Flux.just("## 人物 1：甲\n- 身份：X"));

        s3Service.generateStage(RUN_ID, "S3").collectList().block(TIMEOUT);

        // 无分批历史 → 不分批，单流正常生成
        verify(s3BatchGenerationService, never()).generateRemainingBatches(anyString(), anyInt());
        verify(s3Generator).buildPrompt(any());
        verify(runService).markStageRunning(RUN_ID, PipelineStage.S3_CHARACTERS, true);
    }

    // ===== 版本快照与候选状态同步（tasks 4.1） =====

    @Test
    void 生成完成落版本快照与候选初始化() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("AWAITING", "S3"));
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S3")));
        when(streamSpec.content()).thenReturn(Flux.just(
                "## 人物 1：甲\n- 身份：谋士",
                "\n\n## 人物 2：乙\n- 身份：茶屋主"));

        s3Service.generateStage(RUN_ID, "S3").collectList().block(TIMEOUT);

        // 版本快照：本轮版本 = 当前 version(0) + 1，内容为候选聚合（版本树真源）
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S3_CHARACTERS, 1,
                "## 人物 1：甲\n- 身份：谋士\n\n## 人物 2：乙\n- 身份：茶屋主");
        // 候选状态初始化：id 与文件对齐、title 为标题行、全 PENDING（D5 整轮重置）
        verify(runService).initStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("人物 1：甲", "人物 2：乙"));
        // 记账在快照/初始化之后（顺序：产物 → 快照 → 候选初始化 → markStageCompleted）
        // summary 摘要行随完成一起写入（L2 = 候选标题列表，spec 草稿版本与摘要行同落盘）
        verify(runService).markStageCompleted(RUN_ID, PipelineStage.S3_CHARACTERS,
                "2 个候选：人物 1：甲、人物 2：乙");
    }

    @Test
    void L1生成完成同样落版本快照() {
        service.generateStage(RUN_ID, "S1").collectList().block(TIMEOUT);

        // L1 阶段快照 = 全文，版本 = 当前 version(0) + 1
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S1_IP_ANALYSIS, 1, "拆解内容");
        // L1 阶段不初始化候选状态（S3/S5 专用）
        verify(runService, never()).initStageCandidates(anyString(), any(), anyList());
    }

    @Test
    void 返工prompt注入已毙候选排除表() {
        StageGenerator s3Generator = mock(StageGenerator.class);
        when(s3Generator.stage()).thenReturn(PipelineStage.S3_CHARACTERS);
        when(s3Generator.buildPrompt(any())).thenReturn("S3 prompt");
        StageGenerationService s3Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s3Generator));

        // manifest 候选状态：candidate-01 已毙（记忆在 manifest，文件可已被整轮覆盖）
        PipelineManifest m = manifest("AWAITING", "S3");
        m.stage("S3").setRounds(List.of(round(1, "犬千代不要了")));
        m.stage("S3").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "人物 1：犬千代", "REJECTED"),
                new PipelineManifest.CandidateState("candidate-02", "人物 2：光秀", "PENDING")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(m);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S3")));
        // 语义全稿（真实 store 过滤被毙候选）只回光秀卡
        when(workspaceStore.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS,
                m.stage("S3").getCandidates()))
                .thenReturn("## 人物 2：光秀\n改写版");

        s3Service.generateStage(RUN_ID, "S3").collectList().block(TIMEOUT);

        // 排除表从 manifest 候选状态取（D5：被毙候选不复活）；PENDING 的不进
        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(s3Generator).buildPrompt(captor.capture());
        assertThat(captor.getValue().rejectedTitles()).containsExactly("人物 1：犬千代");
        // 被毙候选同时不进「上轮全稿」（语义稿与排除表双保险；过滤逻辑归 store 测试）
        assertThat(captor.getValue().currentDraft()).isEqualTo("## 人物 2：光秀\n改写版");
    }

    @Test
    void S4紧凑索引天然排除S3被毙候选() {
        StageGenerator s4Generator = mock(StageGenerator.class);
        when(s4Generator.stage()).thenReturn(PipelineStage.S4_PLOT_STRUCTURE);
        when(s4Generator.buildPrompt(any())).thenReturn("S4 prompt");
        StageGenerationService s4Service = new StageGenerationService(runService, workspaceStore,
                runRepository, chatClientRegistry, quotaGuard, generationGuard, s1ChainedRefineService, s3BatchGenerationService, properties, List.of(s4Generator));

        // S3 整稿通过后：1 毙 1 确认——语义聚合只含确认候选（D7 天然隔离被毙
        // 候选，紧凑索引在聚合结果上现算，被毙卡不进索引行）
        PipelineManifest s4Manifest = manifest("AWAITING", "S4");
        s4Manifest.stage("S3").setStatus("CONFIRMED");
        s4Manifest.stage("S3").setCandidates(List.of(
                new PipelineManifest.CandidateState("candidate-01", "人物 1：犬千代", "REJECTED"),
                new PipelineManifest.CandidateState("candidate-02", "人物 2：光秀", "CONFIRMED")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(s4Manifest);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S4")));
        // stub 回真实 store 的过滤结果（只含确认卡）
        when(workspaceStore.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS,
                s4Manifest.stage("S3").getCandidates()))
                .thenReturn("## 人物 2：光秀\n确认版");

        s4Service.generateStage(RUN_ID, "S4").collectList().block(TIMEOUT);

        ArgumentCaptor<GenerationContext> captor = ArgumentCaptor.forClass(GenerationContext.class);
        verify(s4Generator).buildPrompt(captor.capture());
        assertThat(captor.getValue().upstreamConfirmed()).isEqualTo("- 光秀\n");
        // 候选状态行（含 REJECTED）整份传入 store 语义聚合——过滤与「被毙候选连
        // 文件都不读」的隔离由 PlannerWorkspaceStoreTest 验证
        verify(workspaceStore).readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS,
                s4Manifest.stage("S3").getCandidates());
    }

    // ===== 辅助 =====

    private static PipelineRun run(String status, String currentStage) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setCurrentStage(currentStage);
        return run;
    }

    private static PipelineManifest manifest(String status, String currentStage) {
        PipelineManifest manifest = new PipelineManifest();
        manifest.setRunId(RUN_ID);
        manifest.setStatus(status);
        manifest.setCurrentStage(currentStage);
        return manifest;
    }

    private static PipelineManifest.ReRound round(int round, String feedback) {
        PipelineManifest.ReRound r = new PipelineManifest.ReRound();
        r.setRound(round);
        r.setFeedback(feedback);
        return r;
    }

    private static String joinedContent(List<ServerSentEvent<Object>> events) {
        return events.stream()
                .map(e -> e.data() instanceof Map<?, ?> m && m.get("content") != null
                        ? (String) m.get("content") : null)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }
}
