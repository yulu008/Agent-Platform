package com.luyu.agent.rpg.planner.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationCategory;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorkspaceStore;
import com.luyu.agent.rpg.story.engine.StoryGraphParser;
import com.luyu.agent.tenancy.TenantContext;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StoryGraphGenerationService} 幕级编排测试（story-graph-authoring
 * tasks 2.3 / story-graph-generation spec「生成治理与计费对齐」「单幕生成小契约」）。
 * <p>
 * 钉住：三幕串行全事件序列（act_start/content{act}/act_done 循环 + 幕级
 * draft_ready 尾包）、逐幕候选落盘与全幕收口（快照 + 候选初始化 + AWAITING_CONFIRM）、
 * 幕 2 注入幕 1 摘要（Digest 真链路，注入纪律）、断点续跑跳过已落盘幕、
 * 返工轮全量重生成注入反馈与上轮幕稿、第 2 幕配额超限拦截且后续幕短路
 * （spec Scenario「超额被拦」）、共享 RUNNING 守卫跨服务互斥（spec Scenario
 * 「并发生成被守卫拦截」）、moderation 命中快速失败零副作用、空响应守卫、
 * 前置快速失败（跨阶段跳过 / 缺分幕结构）。
 */
class StoryGraphGenerationServiceTest {

    private static final String RUN_ID = "run-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private PipelineRunService runService;
    private PlannerWorkspaceStore workspaceStore;
    private PipelineRunRepository runRepository;
    private ChatClientRegistry chatClientRegistry;
    private QuotaGuard quotaGuard;
    private ContentModerationGate moderationGate;

    /** 共享守卫（跨服务互斥测试用同实例构造两个 service） */
    private final PlannerGenerationGuard generationGuard = new PlannerGenerationGuard();

    private StoryGraphGenerationService service;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    /** 非流式修复调用 mock（2.4 解析链 LLM fallback；未 stub 时 content() 返回 null） */
    private ChatClient.CallResponseSpec callSpec;

    /**
     * 最近一次 prompt（user 调用时捕获；content() mock 按本幕定位路由）。
     * 与调用次序解耦：断点续跑跳过幕后剩余幕仍能拿到自己的幕文本；
     * re-stub 求值时旧 answer 会执行一次（lastPrompt 为 null 走 default，
     * Flux.just 仅创建不订阅，无副作用）。
     */
    private final AtomicReference<String> lastPrompt = new AtomicReference<>();

    /** S4 确认稿（含「## 分幕结构」三幕小节——幕划分真源） */
    private static final String PLOT_DRAFT = """
            ## 主线大纲
            - 开局锚点：夜袭织田邸
            ## 分幕结构
            - 第 1 幕｜主题：潜入与结识｜幕末拍子：拿到密信｜出口轨道：trust_信长 >= 30
            - 第 2 幕｜主题：分裂与抉择｜幕末拍子：清洲会议摊牌｜出口轨道：已获刀 == true
            - 第 3 幕｜主题：终局对决｜幕末拍子：比叡山火起｜出口轨道：终局 == true
            ## 叙事规则
            - 每章聚焦一名武将
            """;

    /** 幕 1 LLM 输出（合法幕 JSON，Digest 真链路可提取摘要：主题/锚点/轨道） */
    private static final String ACT1_LLM = """
            前言噪声。
            <story_graph>
            {
              "act": 1, "theme": "潜入与结识",
              "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 30}}],
              "nodes": [
                {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                {"id": "n1_07", "type": "anchor", "beat": "拿到密信"}],
              "edges": [{"from": "n1_01", "to": "n1_07", "label": "脱身"}]
            }
            </story_graph>
            尾部噪声。""";

    private static final String ACT2_LLM = "<story_graph>{\"act\": 2, \"theme\": \"分裂与抉择\","
            + " \"nodes\": [{\"id\": \"n2_01\", \"type\": \"scene\", \"beat\": \"清洲城密议\"}],"
            + " \"edges\": []}</story_graph>";

    private static final String ACT3_LLM = "<story_graph>{\"act\": 3, \"theme\": \"终局对决\","
            + " \"nodes\": [{\"id\": \"n3_01\", \"type\": \"scene\", \"beat\": \"比叡山火起\"}],"
            + " \"edges\": []}</story_graph>";

    /** 幕 2 首次输出的坏 JSON（尾逗号 + 不闭合括号，解析失败触发 2.4 修复链） */
    private static final String ACT2_BAD_LLM = "<story_graph>{\"act\": 2, \"theme\": \"分裂与抉择\","
            + " \"nodes\": [{\"id\": \"n2_01\", \"type\": \"scene\", \"beat\": \"清洲城密议\"},],"
            + " \"edges\": [}</story_graph>";

    /** 构造指定节点数的幕 2 JSON（超限夹具用，2.4 spec「幕超节点数上限被拒」）。 */
    private static String actWithNodes(int nodeCount) {
        StringBuilder sb = new StringBuilder("<story_graph>{\"act\": 2, \"theme\": \"分裂与抉择\", \"nodes\": [");
        for (int i = 1; i <= nodeCount; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"id\": \"n2_").append(String.format("%02d", i))
                    .append("\", \"type\": \"scene\", \"beat\": \"拍子").append(i).append("\"}");
        }
        sb.append("], \"edges\": []}</story_graph>");
        return sb.toString();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runService = mock(PipelineRunService.class);
        workspaceStore = mock(PlannerWorkspaceStore.class);
        runRepository = mock(PipelineRunRepository.class);
        chatClientRegistry = mock(ChatClientRegistry.class);
        quotaGuard = mock(QuotaGuard.class);
        moderationGate = mock(ContentModerationGate.class);

        // moderation 默认旁路（isEnabled=false 跳过 check，governanceCheck 语义）
        when(moderationGate.isEnabled()).thenReturn(false);

        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClientRegistry.forRole("planner")).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenAnswer(inv -> {
            lastPrompt.set(inv.getArgument(0));
            return requestSpec;
        });
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        // 按 prompt 本幕定位路由：「第 N 幕（共」仅出现于本幕任务定位段
        //（S4 大纲注入是竖线行、前幕摘要是书名号行，不会误中）
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2 幕（共")) {
                return Flux.just(ACT2_LLM);
            }
            if (prompt != null && prompt.contains("第 3 幕（共")) {
                return Flux.just(ACT3_LLM);
            }
            return Flux.just(ACT1_LLM);
        });

        when(runService.markStageCompleted(anyString(), any(), any())).thenReturn(1);

        // 真 promptBuilder / digest / parser（mock LLM 原文走真解析链，钉注入纪律）
        StoryGraphParser parser = new StoryGraphParser();
        service = new StoryGraphGenerationService(runService, workspaceStore, runRepository,
                chatClientRegistry, quotaGuard, moderationGate, generationGuard,
                new StoryGraphPromptBuilder(), new StoryGraphDigest(parser), parser);

        // 标准可生成前置：run AWAITING/S4.5 + S4 CONFIRMED + S4 确认稿
        PipelineManifest manifest = manifest("AWAITING", "S4.5");
        manifest.stage("S4").setStatus("CONFIRMED");
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S4.5")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S4_PLOT_STRUCTURE))
                .thenReturn(PLOT_DRAFT);
    }

    // ===== 正常流（三幕串行 + 事件序列 + 落盘 + 收口） =====

    @Test
    void 三幕串行生成事件序列与逐幕落盘收口() {
        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 事件序列：stage_start → (act_start → content → act_done)×3 → draft_ready → [DONE]
        assertThat(events).isNotNull();
        assertThat(firstMap(events, "stage_start")).containsEntry("stage", "S4.5");
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_start", "act", 1, "total", 3, "theme", "潜入与结识")));
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_start", "act", 2, "total", 3, "theme", "分裂与抉择")));
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 1, "resumed", false)));
        // token 事件带幕归属
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("content", ACT2_LLM, "act", 2)));
        // 尾包：draft_ready{stage,version,candidates=3} + [DONE]（成功路径无 error）
        Map<String, Object> draftReady = firstMap(events, "draft_ready");
        assertThat(draftReady).containsEntry("stage", "S4.5").containsEntry("version", 1)
                .containsEntry("candidates", 3);
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        assertThat(events).noneMatch(e -> e.data() instanceof Map<?, ?> m && m.containsKey("error"));

        // 逐幕候选落盘（LLM 原文，candidate-NN = 第 NN 幕）
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-01", ACT1_LLM);
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-02", ACT2_LLM);
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-03", ACT3_LLM);
        // 全幕收口：快照（聚合全历史）+ 候选初始化（幕主题 title，PENDING）+ AWAITING_CONFIRM
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, 1,
                ACT1_LLM + "\n\n" + ACT2_LLM + "\n\n" + ACT3_LLM);
        verify(runService).initStageCandidates(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                List.of("第 1 幕「潜入与结识」", "第 2 幕「分裂与抉择」", "第 3 幕「终局对决」"));
        verify(runService).markStageCompleted(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "3 幕：潜入与结识、分裂与抉择、终局对决");
        // 治理三段式：quota 每幕调用前（3 幕 = 3 次）+ advisor 挂账每幕（call type story_graph）
        verify(quotaGuard, times(3)).check(any());
        verify(requestSpec, times(3)).advisors(any(Consumer.class));
        // 流结束守卫释放
        assertThat(generationGuard.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void 幕2注入幕1摘要而非前幕全量JSON() {
        service.generateActs(RUN_ID, null).collectList().block(TIMEOUT);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(3)).user(promptCaptor.capture());
        String act2Prompt = promptCaptor.getAllValues().get(1);

        // 前幕摘要（Digest 真链路）：幕 1 主题 + 入口 + 锚点 + 轨道
        assertThat(act2Prompt).contains("第 1 幕「潜入与结识」");
        assertThat(act2Prompt).contains("入口 n1_01");
        assertThat(act2Prompt).contains("n1_07（拿到密信）");
        assertThat(act2Prompt).contains("信任线 = trust_信长 >= 30");
        // 注入纪律：幕 1 全量 JSON 的非锚点节点不进幕 2 prompt
        assertThat(act2Prompt).doesNotContain("夜探织田邸");
        // 第 2 幕 prompt 仍含本幕定位与 S4 全文（内容上游照常注入）
        assertThat(act2Prompt).contains("第 2 幕｜主题：分裂与抉择");
        assertThat(act2Prompt).contains("夜袭织田邸");
    }

    // ===== 断点续跑 / 返工重生成 =====

    @Test
    void 断点续跑跳过已落盘幕不重复耗token() {
        // NOT_STARTED 状态 + 幕 1 候选已存在（上次失败前已落盘）
        when(workspaceStore.readCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, "candidate-01"))
                .thenReturn(ACT1_LLM);

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 幕 1 跳过（resumed=true）；LLM 只调幕 2/3（2 次）
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 1, "resumed", true)));
        verify(requestSpec, times(2)).user(anyString());
        // 幕 1 不覆盖写（跳过幂等保留）
        verify(workspaceStore, never()).writeCandidateItem(eq(RUN_ID), any(), eq("candidate-01"), anyString());
        // 跳过幕仍进候选集（收口 candidates=3、快照含幕 1）
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, 1,
                ACT1_LLM + "\n\n" + ACT2_LLM + "\n\n" + ACT3_LLM);
        Map<String, Object> draftReady = firstMap(events, "draft_ready");
        assertThat(draftReady).containsEntry("candidates", 3);
    }

    @Test
    void 返工轮全量重生成注入反馈历史与上轮幕稿() {
        // AWAITING_CONFIRM（返工轮）+ 阶段级 rounds + 各幕已有候选（上轮稿）
        PipelineManifest manifest = manifest("AWAITING", "S4.5");
        manifest.stage("S4").setStatus("CONFIRMED");
        manifest.stage("S4.5").setStatus("AWAITING_CONFIRM");
        manifest.stage("S4.5").setRounds(List.of(round(1, "锚点拍子与主线不符")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(workspaceStore.readCandidateItem(eq(RUN_ID), eq(PipelineStage.S4P5_STORY_GRAPH), anyString()))
                .thenReturn("上轮该幕骨架稿");

        service.generateActs(RUN_ID, "锚点拍子与主线不符").collectList().block(TIMEOUT);

        // 全量重生成：三幕都调 LLM（跳过逻辑只在 NOT_STARTED/FAILED 续跑态生效）
        verify(requestSpec, times(3)).user(anyString());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(3)).user(promptCaptor.capture());
        String act1Prompt = promptCaptor.getAllValues().get(0);
        // 返工段：轮次反馈 + 上轮幕稿 + 保持不变约束
        assertThat(act1Prompt).contains("第 1 轮反馈：锚点拍子与主线不符");
        assertThat(act1Prompt).contains("【上轮本幕骨架稿】");
        assertThat(act1Prompt).contains("上轮该幕骨架稿");
        assertThat(act1Prompt).contains("反馈未提及的节点/边保持与上轮一致");
        // 候选覆盖写（重生成幂等）
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-01", ACT1_LLM);
    }

    // ===== 单幕返工（2.6，spec「幕级 L2 返工门」） =====

    @Test
    void 单幕返工仅重生目标幕他幕跳过不重置候选() {
        // AWAITING_CONFIRM（返工窗）+ 阶段级 rounds + 幕 1/3 已有候选（非目标幕）
        PipelineManifest manifest = manifest("AWAITING", "S4.5");
        manifest.stage("S4").setStatus("CONFIRMED");
        manifest.stage("S4.5").setStatus("AWAITING_CONFIRM");
        manifest.stage("S4.5").setRounds(List.of(round(1, "幕 2 锚点太弱")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(workspaceStore.readCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, "candidate-01"))
                .thenReturn(ACT1_LLM);
        when(workspaceStore.readCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, "candidate-02"))
                .thenReturn("上轮幕 2 稿");
        when(workspaceStore.readCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, "candidate-03"))
                .thenReturn(ACT3_LLM);

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, "幕 2 锚点太弱", 2)
                .collectList().block(TIMEOUT);

        // 仅目标幕调 LLM（1 次）；非目标幕跳过（resumed=true 事件，不耗 token）
        verify(requestSpec, times(1)).user(anyString());
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 1, "resumed", true)));
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 3, "resumed", true)));
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 2, "resumed", false)));
        // 目标幕覆盖写新稿；非目标幕不覆盖写（候选状态不动——不波及他幕）
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-02", ACT2_LLM);
        verify(workspaceStore, never()).writeCandidateItem(eq(RUN_ID), any(), eq("candidate-01"), anyString());
        verify(workspaceStore, never()).writeCandidateItem(eq(RUN_ID), any(), eq("candidate-03"), anyString());
        // 收口不重置候选状态（spec「幕 1-5 确认状态不变」），版本照常递增收口
        verify(runService, never()).initStageCandidates(anyString(), any(), any());
        verify(runService).markStageCompleted(eq(RUN_ID), eq(PipelineStage.S4P5_STORY_GRAPH), any());
        // 版本快照照常（目标幕新稿 + 其他幕原候选聚合，版本树连续）
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, 1,
                ACT1_LLM + "\n\n" + ACT2_LLM + "\n\n" + ACT3_LLM);
        // 治理不重复：仅目标幕（幕 2 走流内检查；非目标幕跳过在治理之前）
        verify(quotaGuard, times(1)).check(any());
        // 尾包正常：draft_ready candidates=3（含跳过幕）
        Map<String, Object> draftReady = firstMap(events, "draft_ready");
        assertThat(draftReady).containsEntry("candidates", 3);
        assertThat(generationGuard.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void 单幕返工幕间读取恢复租户上下文不误报无候选() {
        // 回归钉（5.2 E2E 实测）：幕间 defer 在 concat 尾流完成线程（LLM 流回调，
        // 无租户上下文）执行，候选读取走 workspaceStore（runDir 依赖 TenantContext）
        // 须恢复租户再读——否则非目标幕读取抛异常被吞成 null，误报「无候选」
        // 快速失败。mock readCandidateItem 模拟真实行为（无租户即抛）。
        PipelineManifest manifest = manifest("AWAITING", "S4.5");
        manifest.stage("S4").setStatus("CONFIRMED");
        manifest.stage("S4.5").setStatus("AWAITING_CONFIRM");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(workspaceStore.readCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, "candidate-01"))
                .thenAnswer(inv -> {
                    TenantContext.requireTenantId();
                    return ACT1_LLM;
                });
        when(workspaceStore.readCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, "candidate-02"))
                .thenAnswer(inv -> {
                    TenantContext.requireTenantId();
                    return "上轮幕 2 稿";
                });
        when(workspaceStore.readCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, "candidate-03"))
                .thenAnswer(inv -> {
                    TenantContext.requireTenantId();
                    return ACT3_LLM;
                });

        // 请求线程（有租户）调 generateActs：前置链同步执行并捕获 tenantId，
        // 返回的 Flux 惰性——订阅时才执行各幕 defer
        TenantContext.set("test", "t-regress");
        Flux<ServerSentEvent<Object>> flux;
        try {
            flux = service.generateActs(RUN_ID, "幕 2 锦点太弱", 2);
        } finally {
            TenantContext.clear();
        }
        // 此后测试线程无租户 = 模拟幕间回调线程（LLM 尾流完成线程）执行 defer
        List<ServerSentEvent<Object>> events = flux.collectList().block(TIMEOUT);

        // 非目标幕正常 resumed 跳过（非「无候选」误报失败），整流无 error
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 1, "resumed", true)));
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 3, "resumed", true)));
        assertThat(events).noneMatch(e -> e.data() instanceof Map<?, ?> m && m.containsKey("error"));
        Map<String, Object> draftReady = firstMap(events, "draft_ready");
        assertThat(draftReady).containsEntry("candidates", 3);
    }

    @Test
    void 单幕返工仅在待确认态可用() {
        // 默认 setUp：S4.5 NOT_STARTED（首生成未跑）——单幕返工窗未开
        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, "反馈", 2)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("单幕返工仅在阶段待确认").contains("NOT_STARTED"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(requestSpec, never()).user(anyString());
    }

    @Test
    void 单幕返工目标幕号越界快速失败() {
        PipelineManifest manifest = manifest("AWAITING", "S4.5");
        manifest.stage("S4").setStatus("CONFIRMED");
        manifest.stage("S4.5").setStatus("AWAITING_CONFIRM");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, "反馈", 9)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("目标幕号超出分幕结构范围").contains("9").contains("3"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(requestSpec, never()).user(anyString());
    }

    @Test
    void 单幕返工非目标幕缺候选快速失败() {
        PipelineManifest manifest = manifest("AWAITING", "S4.5");
        manifest.stage("S4").setStatus("CONFIRMED");
        manifest.stage("S4.5").setStatus("AWAITING_CONFIRM");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        // 幕 1 候选缺失（单幕返工前置缺失——建议走整轮返工）

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, "反馈", 2)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("第 1 幕").contains("前置缺失"));
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        // 阶段置 FAILED（零副作用重试）+ 目标幕未调 LLM
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
        verify(requestSpec, never()).user(anyString());
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
    }

    // ===== 治理三段式（spec「生成治理与计费对齐」） =====

    @Test
    void 幕生成归账story_graph计费并走planner角色client() {
        service.generateActs(RUN_ID, null).collectList().block(TIMEOUT);

        // spec「生成治理与计费对齐」（tasks 2.7）：call type story_graph 归账——
        // 捕获 advisor Consumer 执行到 mock AdvisorSpec 上钉 param 挂载（每幕一次）
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(requestSpec, times(3)).advisors(advisorCaptor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        advisorCaptor.getAllValues().forEach(c -> c.accept(advisorSpec));
        verify(advisorSpec, times(3)).param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "story_graph");
        // planner 角色 client（ChatClientRegistry.forRole 契约）：每幕独立取用
        verify(chatClientRegistry, times(3)).forRole("planner");
    }

    @Test
    void 第二幕配额超限拦截且后续幕短路() {
        // quota 第一次放行（幕 1 前置）第二次抛（幕 2 流内）。
        // check 是 void 方法，when() 包装不合法，必须走 doAnswer 风格
        AtomicInteger quotaCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (quotaCalls.incrementAndGet() >= 2) {
                throw new QuotaExceededException(1000, 1000);
            }
            return null;
        }).when(quotaGuard).check(any());

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 拦截：error + [DONE]；幕 1 已落盘保留（断点续跑语义），幕 3 短路不调 LLM
        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("配额已用尽"));
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        verify(requestSpec, times(1)).user(anyString());
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-01", ACT1_LLM);
        verify(workspaceStore, never()).writeCandidateItem(eq(RUN_ID), any(), eq("candidate-03"), anyString());
        // 阶段置 FAILED（重试走断点续跑）+ 无成功收口
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
        assertThat(firstMap(events, "draft_ready")).isNull();
    }

    @Test
    void 并发生成被共享守卫跨服务拦截() {
        // 幕生成 RUNNING（guard 占位 S4.5）→ 通用阶段生成被拒（spec Scenario
        // 「幕 4 生成 RUNNING 中再次触发本 run 任一阶段生成」）
        assertThat(generationGuard.tryAcquire(RUN_ID, PipelineStage.S4P5_STORY_GRAPH)).isTrue();

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("单阶段 RUNNING 守卫"));
        // 快速失败零副作用：不占位不推进状态不调 LLM
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(requestSpec, never()).user(anyString());

        // 反向：通用阶段生成持锁 → 幕生成被拒
        generationGuard.release(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
        assertThat(generationGuard.tryAcquire(RUN_ID, PipelineStage.S5_BRANCH_DESIGN)).isTrue();

        List<ServerSentEvent<Object>> rejected = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(rejected).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("单阶段 RUNNING 守卫"));
    }

    @Test
    void moderation命中快速失败零副作用() {
        when(moderationGate.isEnabled()).thenReturn(true);
        when(moderationGate.check(anyString())).thenReturn(
                ModerationResult.blocked(Set.of(ModerationCategory.PROFANITY)));
        when(moderationGate.refusalMessage()).thenReturn("内容不适宜，请调整后重试。");

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, "违规反馈文本")
                .collectList().block(TIMEOUT);

        // 拒答话术 + [DONE]；零副作用：不置 RUNNING、不占位、不调 LLM
        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).isEqualTo(Map.of("error", "内容不适宜，请调整后重试。"));
        assertThat(events.get(1).data()).isEqualTo("[DONE]");
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(requestSpec, never()).user(anyString());
        assertThat(generationGuard.activeStage(RUN_ID)).isEmpty();
    }

    // ===== 失败路径 =====

    @Test
    void 幕2空响应置FAILED且错误指明幕号() {
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2 幕（共")) {
                return Flux.empty();   // 幕 2 空响应
            }
            return Flux.just(ACT1_LLM);
        });

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("第 2 幕").contains("空内容"));
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
    }

    @Test
    void 幕2流异常不连锁后续幕() {
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2 幕（共")) {
                return Flux.error(new RuntimeException("模型连接中断"));
            }
            return Flux.just(ACT1_LLM);
        });

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("幕生成异常"));
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
        // 幕 3 短路（外层 onErrorResume 断流后尾包 error）
        verify(requestSpec, times(2)).user(anyString());
    }

    // ===== 2.4 解析链：LLM fallback 修复一次 + 仍失败 FAILED 可重试 =====

    @Test
    void 幕2解析失败修复一次成功落盘修复文() {
        // 幕 2 流式输出坏 JSON；非流式修复调用返回合法幕 2 文本
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2 幕（共")) {
                return Flux.just(ACT2_BAD_LLM);
            }
            if (prompt != null && prompt.contains("第 3 幕（共")) {
                return Flux.just(ACT3_LLM);
            }
            return Flux.just(ACT1_LLM);
        });
        when(callSpec.content()).thenReturn(ACT2_LLM);

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // act_repair 通知（含幕号与原始错误清单）
        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("act_repair").contains("2"));
        // 修复文落盘（候选 = 修复输出而非坏原文）
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-02", ACT2_LLM);
        // 幕 2 正常 act_done + 整轮成功收口（快照含修复文）
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("type", "act_done", "act", 2, "resumed", false)));
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S4P5_STORY_GRAPH, 1,
                ACT1_LLM + "\n\n" + ACT2_LLM + "\n\n" + ACT3_LLM);
        // 修复也是幕生成 LLM 调用：user/advisors 均 4 次（3 幕 + 1 修复，story_graph 计费）
        verify(requestSpec, times(4)).user(anyString());
        verify(requestSpec, times(4)).advisors(any(Consumer.class));
    }

    @Test
    void 坏JSON修复一次后仍非法则本幕FAILED可重试() {
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2 幕（共")) {
                return Flux.just(ACT2_BAD_LLM);
            }
            return Flux.just(ACT1_LLM);
        });
        when(callSpec.content()).thenReturn(ACT2_BAD_LLM);   // 修复输出同样坏

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // spec Scenario「坏 JSON 修复一次后仍坏则失败可重试」：本幕 FAILED、
        // run 状态不损坏、其他幕产物不受影响（幕 1 已落盘保留、幕 3 短路）
        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("第 2 幕").contains("修复一次后仍非法"));
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
        verify(workspaceStore).writeCandidateItem(RUN_ID, PipelineStage.S4P5_STORY_GRAPH,
                "candidate-01", ACT1_LLM);
        verify(workspaceStore, never()).writeCandidateItem(eq(RUN_ID), any(),
                eq("candidate-02"), anyString());
        // 流式 2 次（幕 1/2）+ 修复 1 次 = 3 次 LLM 调用；幕 3 短路
        verify(requestSpec, times(3)).user(anyString());
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
        assertThat(firstMap(events, "draft_ready")).isNull();
    }

    @Test
    void 幕超节点数上限被拒不落草稿() {
        // spec Scenario「幕超节点数上限被拒」：30 节点 → 报错指明上限 25，不落草稿
        String oversized = actWithNodes(30);
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2 幕（共")) {
                return Flux.just(oversized);
            }
            return Flux.just(ACT1_LLM);
        });
        when(callSpec.content()).thenReturn(oversized);   // 修复输出仍超限

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("节点数超限").contains("25"));
        verify(workspaceStore, never()).writeCandidateItem(eq(RUN_ID), any(),
                eq("candidate-02"), anyString());
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
    }

    @Test
    void 修复调用空响应则本幕FAILED() {
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2 幕（共")) {
                return Flux.just(ACT2_BAD_LLM);
            }
            return Flux.just(ACT1_LLM);
        });
        when(callSpec.content()).thenReturn(null);   // 修复调用无产出

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("修复调用未产出").contains("第 2 幕"));
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S4P5_STORY_GRAPH);
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
    }

    // ===== 前置快速失败 =====

    @Test
    void 跨阶段跳过被拒() {
        PipelineManifest manifest = manifest("AWAITING", "S3");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("不能直接生成故事图骨架"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void S4确认稿缺分幕结构小节快速失败() {
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S4_PLOT_STRUCTURE))
                .thenReturn("## 主线大纲\n- 无分幕结构小节的稿子");

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("缺少「## 分幕结构」小节"));
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void 终态run拒绝幕生成() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("COMPLETED", "S4.5")));

        List<ServerSentEvent<Object>> events = service.generateActs(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("终态"));
    }

    // ===== 辅助 =====

    /**
     * 首个指定 type 的 map 事件（无则 null）。事件 map 均为 String 键
     * （服务端 Map.of 构造），转 Map<String,Object> 供 containsEntry 断言
     * （Map<?, ?> 的 capture 泛型会让 containsEntry 编译不过）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMap(List<ServerSentEvent<Object>> events, String type) {
        return (Map<String, Object>) events.stream()
                .filter(e -> e.data() instanceof Map<?, ?> m && type.equals(m.get("type")))
                .map(ServerSentEvent::data)
                .findFirst()
                .orElse(null);
    }

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
        // 真链路 createRun 必设（StyleMode.code），真 PromptBuilder 的
        // StyleMode.fromCode 对 null 会抛 IllegalArgumentException
        manifest.setStyleMode("faithful");
        return manifest;
    }

    private static PipelineManifest.ReRound round(int round, String feedback) {
        PipelineManifest.ReRound r = new PipelineManifest.ReRound();
        r.setRound(round);
        r.setFeedback(feedback);
        return r;
    }
}
