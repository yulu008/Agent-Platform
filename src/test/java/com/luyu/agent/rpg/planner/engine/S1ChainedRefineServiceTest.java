package com.luyu.agent.rpg.planner.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationCategory;
import com.luyu.agent.moderation.ModerationResult;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link S1ChainedRefineService} 链式编排测试（planner-s1-chained-refinement
 * tasks 2.5 / spec「块笔记逐块串行生成与落盘」「块级断点续跑」「汇总轮成稿与收口」
 * 「块进度事件协议」）。
 * <p>
 * 钉住：三块串行全事件序列（切块策略提示 + 分块提炼 k/N tool 事件对 + 汇总
 * content 流 + draft_ready 尾包）、块笔记落盘与汇总收口（快照 + AWAITING_CONFIRM +
 * 摘要行）、块轮不向前端流笔记 token、断点续跑跳过已落盘块（前块复用仅续剩余
 * 块 + 汇总）、quota 第 2 块拦截短路后续块（前块笔记保留）、CANCEL/doFinally 置
 * FAILED 可重试、返工仅汇总轮（块笔记零重生成、反馈历史注入）、笔记总量防御
 * （不静默截断）、汇总注入纪律（原文不进汇总 prompt）、旧 run 无 notes 目录
 * 零迁移（默认空 readChunkNotes 照常块轮全跑）、阈值防御、moderation 快速失败
 * 零副作用。
 * <p>
 * 真切块器（{@link SourceChunker}）走真链路；workspaceStore mock（笔记写读不
 * 联动，readChunkNotes 按用例覆写）。
 */
class S1ChainedRefineServiceTest {

    private static final String RUN_ID = "run-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 链式夹具参数：阈值 100 字（SOURCE 140 字超阈）、单块上限 60 字（三章各 ~46 字单块） */
    private static final int THRESHOLD = 100;
    private static final int CHUNK_MAX = 60;

    /** 三章 md 原文（真切块器恒得 3 块：HEADING 策略，块序 1-3） */
    private static final String SOURCE = "# 第一章 甲\n\n"
            + "甲的故事发生在很久以前，涉及一个关于成长与抉择的漫长旅程，充满了起伏与转折。\n\n"
            + "# 第二章 乙\n\n"
            + "乙的故事承接甲的结局，展开了新的冲突与悬念，推动主线走向高潮。\n\n"
            + "# 第三章 丙\n\n"
            + "丙的故事收束全部伏笔，终局对决一触即发，为整个篇章画上句号。";

    private static final String SUMMARY_LLM = "汇总稿：完整 IP 拆解报告";

    private PipelineRunService runService;
    private PlannerWorkspaceStore workspaceStore;
    private PipelineRunRepository runRepository;
    private ChatClientRegistry chatClientRegistry;
    private QuotaGuard quotaGuard;
    private ContentModerationGate moderationGate;

    /** 共享守卫（真实例，释放语义可断言） */
    private final PlannerGenerationGuard generationGuard = new PlannerGenerationGuard();

    private RpgPlannerProperties properties;

    private S1ChainedRefineService service;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    /** 块轮非流式调用 mock（未 stub 时 content() 返回 null → 空响应失败路径） */
    private ChatClient.CallResponseSpec callSpec;

    /** 最近一次 prompt（user 调用时捕获；块/汇总调用按 prompt 定位路由） */
    private final AtomicReference<String> lastPrompt = new AtomicReference<>();

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

        properties = new RpgPlannerProperties();
        properties.setChainThresholdChars(THRESHOLD);
        properties.setChunkMaxChars(CHUNK_MAX);

        service = new S1ChainedRefineService(runService, workspaceStore, runRepository,
                chatClientRegistry, quotaGuard, moderationGate, generationGuard, properties);

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
        // toolContext 桩（metering 租户归属透传，同 StageGenerationServiceTest 惯例）
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        // 块轮路由：按 prompt「第 N/3 块」定位（任务段与素材段各一处，块间不误中）
        when(callSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("第 2/3 块")) {
                return "笔记二：乙块设定";
            }
            if (prompt != null && prompt.contains("第 3/3 块")) {
                return "笔记三：丙块设定";
            }
            return "笔记一：甲块设定";
        });
        // 汇总轮流式输出（单 token，钉「块轮不流 content、仅汇总流」）
        when(streamSpec.content()).thenReturn(Flux.just(SUMMARY_LLM));

        when(runService.markStageCompleted(anyString(), any(), any())).thenReturn(1);

        // 标准可生成前置：S1 当前阶段 + NOT_STARTED（首生成/断点续跑态）；
        // manifest 未设 sourceMode（null → 非 ip_name），原料统一入口 readS1Material
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S1")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest("AWAITING", "S1"));
        when(workspaceStore.readS1Material(RUN_ID, null)).thenReturn(SOURCE);
        // 旧 run 零迁移基线：无 notes 目录（空列表）、单块笔记不存在
        when(workspaceStore.readChunkNote(eq(RUN_ID), anyInt())).thenReturn(null);
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of());
    }

    // ===== 正常流（三块串行 + 事件序列 + 笔记落盘 + 汇总收口） =====

    @Test
    void 三块串行生成事件序列与笔记落盘汇总收口() {
        // 汇总轮以最新落盘为准（mock 写读不联动，stub 全量笔记）
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of(
                new PlannerWorkspaceStore.ChunkNote(1, "笔记一：甲块设定"),
                new PlannerWorkspaceStore.ChunkNote(2, "笔记二：乙块设定"),
                new PlannerWorkspaceStore.ChunkNote(3, "笔记三：丙块设定")));

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 事件序列：stage_start → 切块策略 tool 对 → (分块提炼 k/N tool 对)×3
        // → content（仅汇总）→ draft_ready → [DONE]
        assertThat(events).isNotNull();
        assertThat(firstMap(events, "stage_start")).containsEntry("stage", "S1");
        assertThat(events).anySatisfy(e -> assertThat(e.data()).isEqualTo(
                Map.of("tool", "start", "name", "切块策略", "icon", "📖",
                        "description", "已按 Markdown 一级标题切块：3 块")));
        assertThat(events).anySatisfy(e -> assertThat(e.data()).isEqualTo(
                Map.of("tool", "start", "name", "分块提炼 1/3", "icon", "📖",
                        "description", "第一章 甲")));
        assertThat(events).anySatisfy(e -> assertThat(e.data()).isEqualTo(
                Map.of("tool", "end", "name", "分块提炼 1/3", "status", "ok")));
        assertThat(events).anySatisfy(e -> assertThat(e.data()).isEqualTo(
                Map.of("tool", "end", "name", "分块提炼 3/3", "status", "ok")));

        // 块轮不向前端流笔记 token（spec「笔记不污染草稿区」）：content 事件仅汇总轮 1 个
        assertThat(events.stream()
                .filter(e -> e.data() instanceof Map<?, ?> m && m.containsKey("content"))
                .count()).isEqualTo(1);
        assertThat(events).anySatisfy(e -> assertThat(e.data())
                .isEqualTo(Map.of("content", SUMMARY_LLM)));

        // 尾包：draft_ready{stage,version}（S1 为 L1，无 candidates）+ [DONE]（无 error）
        Map<String, Object> draftReady = firstMap(events, "draft_ready");
        assertThat(draftReady).containsEntry("stage", "S1").containsEntry("version", 1);
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        assertThat(events).noneMatch(e -> e.data() instanceof Map<?, ?> m && m.containsKey("error"));

        // 逐块笔记落盘（chunk-NN 幂等覆盖语义由 store 层测试钉住，这里钉调用契约）
        verify(workspaceStore).writeChunkNote(RUN_ID, 1, "笔记一：甲块设定");
        verify(workspaceStore).writeChunkNote(RUN_ID, 2, "笔记二：乙块设定");
        verify(workspaceStore).writeChunkNote(RUN_ID, 3, "笔记三：丙块设定");
        // 汇总收口：产物落盘 + 版本快照 + AWAITING_CONFIRM + 摘要行（L1 全稿首行）
        verify(workspaceStore).writeStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS, SUMMARY_LLM);
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S1_IP_ANALYSIS, 1, SUMMARY_LLM);
        verify(runService).markStageCompleted(RUN_ID, PipelineStage.S1_IP_ANALYSIS, SUMMARY_LLM);
        // 治理：quota 请求线程 1 + 块 2/3 流内 2 + 汇总前 1 = 4 次
        verify(quotaGuard, times(4)).check(any());
        // 计费：块 3 次 + 汇总 1 次 = 4 次 advisor（call type s1_refine）
        verify(requestSpec, times(4)).advisors(any(Consumer.class));
        // 流结束守卫释放
        assertThat(generationGuard.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void 汇总注入全部笔记不注入原文全文() {
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of(
                new PlannerWorkspaceStore.ChunkNote(1, "笔记一：甲块设定"),
                new PlannerWorkspaceStore.ChunkNote(2, "笔记二：乙块设定"),
                new PlannerWorkspaceStore.ChunkNote(3, "笔记三：丙块设定")));

        service.generateChained(RUN_ID, null).collectList().block(TIMEOUT);

        // 汇总 prompt = 第 4 次 user 调用（3 块 + 1 汇总）
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(4)).user(promptCaptor.capture());
        String summaryPrompt = promptCaptor.getAllValues().get(3);

        // 素材说明 + 全部笔记（分节头）+ faithful 成稿模板（复用 IpAnalysisGenerator 指令）
        assertThat(summaryPrompt).contains("【素材说明】");
        assertThat(summaryPrompt).contains("### 块 1 笔记");
        assertThat(summaryPrompt).contains("笔记二：乙块设定");
        assertThat(summaryPrompt).contains("出处对照表");
        // 注入纪律：原文全文不进汇总 prompt（素材源是笔记，防上下文墙回归）
        assertThat(summaryPrompt).doesNotContain("甲的故事发生在很久以前");
        // 块 prompt 注入纪律：块 2 prompt 含本块原文、不含块 1 原文
        String chunk2Prompt = promptCaptor.getAllValues().get(1);
        assertThat(chunk2Prompt).contains("乙的故事承接甲的结局");
        assertThat(chunk2Prompt).doesNotContain("甲的故事发生在很久以前");
        assertThat(chunk2Prompt).contains("## 伏笔与悬念");
    }

    // ===== 断点续跑 =====

    @Test
    void 断点续跑跳过已落盘块仅续剩余块与汇总() {
        // 块 1 笔记已落盘（上次失败前）；汇总读到的笔记含旧块 1
        when(workspaceStore.readChunkNote(RUN_ID, 1)).thenReturn("旧块一笔记");
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of(
                new PlannerWorkspaceStore.ChunkNote(1, "旧块一笔记"),
                new PlannerWorkspaceStore.ChunkNote(2, "笔记二：乙块设定"),
                new PlannerWorkspaceStore.ChunkNote(3, "笔记三：丙块设定")));

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 块 1 跳过（不覆盖写、不耗 token）：LLM 仅块 2/3 + 汇总 = 3 次
        verify(workspaceStore, never()).writeChunkNote(eq(RUN_ID), eq(1), anyString());
        verify(requestSpec, times(3)).user(anyString());
        // 跳过块照常出 tool 事件对（k/N 进度行完整可观察）
        assertThat(events).anySatisfy(e -> assertThat(e.data()).isEqualTo(
                Map.of("tool", "end", "name", "分块提炼 1/3", "status", "ok")));
        // 汇总注入旧块 1 笔记（断点续跑语义：已落盘笔记直接复用）
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(3)).user(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(2)).contains("旧块一笔记");
        // 尾包正常收口
        assertThat(firstMap(events, "draft_ready")).containsEntry("version", 1);
    }

    // ===== 治理拦截与失败短路 =====

    @Test
    void quota第2块拦截短路后续块且前块笔记保留() {
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of());

        // 第 1 次（请求线程）放行，第 2 次（块 2 流内）起拦截
        AtomicInteger quotaCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (quotaCalls.incrementAndGet() >= 2) {
                throw new QuotaExceededException(1000, 1000);
            }
            return null;
        }).when(quotaGuard).check(any());

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 拦截：error + [DONE]；块 2 起短路（LLM 仅块 1 调用），无汇总调用
        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("配额已用尽"));
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");
        assertThat(events).noneMatch(e -> e.data() instanceof Map<?, ?> m
                && m.containsKey("type") && "draft_ready".equals(m.get("type")));
        verify(requestSpec, times(1)).user(anyString());
        // 块 1 笔记已落盘保留（spec「块轮配额逐次检查」：已落盘前块保留）
        verify(workspaceStore).writeChunkNote(RUN_ID, 1, "笔记一：甲块设定");
        verify(workspaceStore, never()).writeChunkNote(eq(RUN_ID), eq(2), anyString());
        // 阶段置 FAILED 可重试（断点续跑）
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
        assertThat(generationGuard.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void 取消流置FAILED可重试() {
        // take(2)：收到 stage_start + 切块策略 start 后 cancel（块轮未订阅、零 LLM 调用）
        service.generateChained(RUN_ID, null).take(2).collectList().block(TIMEOUT);

        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
        verify(requestSpec, never()).user(anyString());
        assertThat(generationGuard.activeStage(RUN_ID)).isEmpty();
    }

    @Test
    void 笔记总量超防御上限置FAILED不静默截断() {
        properties.setNotesTotalMaxChars(10);
        // 3 条齐笔记（过「笔记不全」检查）但总量 3×19 字超 10 字防御上限，
        // 钉「不静默截断」：置 FAILED + 引导拆 run，而非截断笔记硬汇总
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of(
                new PlannerWorkspaceStore.ChunkNote(1, "超长笔记内容超过十个字的防御上限"),
                new PlannerWorkspaceStore.ChunkNote(2, "超长笔记内容超过十个字的防御上限"),
                new PlannerWorkspaceStore.ChunkNote(3, "超长笔记内容超过十个字的防御上限")));

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 防御：不发起模型调用（块轮全跑后汇总前拦截）+ 可读错误引导拆 run
        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("防御上限").contains("拆分"));
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
        assertThat(events).noneMatch(e -> e.data() instanceof Map<?, ?> m
                && m.containsKey("type") && "draft_ready".equals(m.get("type")));
    }

    // ===== 返工（仅汇总轮） =====

    @Test
    void 返工仅汇总轮块笔记零重生成() {
        // AWAITING_CONFIRM（返工窗）+ 阶段级 rounds + 全量笔记已就绪
        PipelineManifest manifest = manifest("AWAITING", "S1");
        manifest.stage("S1").setStatus("AWAITING_CONFIRM");
        manifest.stage("S1").setRounds(List.of(round(1, "出处对照不全")));
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of(
                new PlannerWorkspaceStore.ChunkNote(1, "笔记一：甲块设定"),
                new PlannerWorkspaceStore.ChunkNote(2, "笔记二：乙块设定"),
                new PlannerWorkspaceStore.ChunkNote(3, "笔记三：丙块设定")));
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS))
                .thenReturn("上轮拆解稿");

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, "出处对照不全")
                .collectList().block(TIMEOUT);

        // 仅汇总 1 次模型调用；块笔记零重生成（确定性派生物复用）
        verify(requestSpec, times(1)).user(anyString());
        verify(workspaceStore, never()).writeChunkNote(anyString(), anyInt(), anyString());
        // 汇总 prompt：反馈历史 + 上轮全稿 + 全部笔记（无块轮原文注入）
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());
        String summaryPrompt = promptCaptor.getValue();
        assertThat(summaryPrompt).contains("第 1 轮反馈：出处对照不全");
        assertThat(summaryPrompt).contains("【上轮全稿】");
        assertThat(summaryPrompt).contains("笔记一：甲块设定");
        // 收口照常（v N+1）
        assertThat(firstMap(events, "draft_ready")).containsEntry("version", 1);
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, PipelineStage.S1_IP_ANALYSIS, 1, SUMMARY_LLM);
    }

    @Test
    void 笔记不全时不发起汇总调用() {
        // 防御路径：块轮完成后笔记数 < 块数（文件损坏等）——快速失败可重试
        when(workspaceStore.readChunkNotes(RUN_ID)).thenReturn(List.of(
                new PlannerWorkspaceStore.ChunkNote(1, "笔记一：甲块设定")));

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, null)
                .collectList().block(TIMEOUT);

        // 块轮 3 次调用照常，汇总被拦截（无第 4 次 user 调用、无 draft_ready）
        verify(requestSpec, times(3)).user(anyString());
        assertThat(events).anySatisfy(e -> assertThat(String.valueOf(e.data()))
                .contains("块笔记不全"));
        assertThat(events).noneMatch(e -> e.data() instanceof Map<?, ?> m
                && m.containsKey("type") && "draft_ready".equals(m.get("type")));
        verify(runService).markStageFailed(RUN_ID, PipelineStage.S1_IP_ANALYSIS);
    }

    // ===== 前置快速失败 =====

    @Test
    void 原料不超阈值防御拒绝链式() {
        when(workspaceStore.readS1Material(RUN_ID, null)).thenReturn("短原文不足百字");

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, null)
                .collectList().block(TIMEOUT);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).isEqualTo(Map.of("error", "S1 链式提炼需原料长度超过链式阈值（当前 "
                + THRESHOLD + " 字），请走单流生成路径"));
        assertThat(events.get(1).data()).isEqualTo("[DONE]");
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(requestSpec, never()).user(anyString());
    }

    @Test
    void 返工反馈命中审查快速失败零副作用() {
        when(moderationGate.isEnabled()).thenReturn(true);
        when(moderationGate.check(anyString())).thenReturn(
                ModerationResult.blocked(Set.of(ModerationCategory.PROFANITY)));
        when(moderationGate.refusalMessage()).thenReturn("内容不适宜，请调整后重试。");

        PipelineManifest manifest = manifest("AWAITING", "S1");
        manifest.stage("S1").setStatus("AWAITING_CONFIRM");
        when(workspaceStore.readManifest(RUN_ID)).thenReturn(manifest);

        List<ServerSentEvent<Object>> events = service.generateChained(RUN_ID, "违规反馈文本")
                .collectList().block(TIMEOUT);

        // 拒答话术 + [DONE]；零副作用：不置 RUNNING、不占位、不调 LLM
        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).isEqualTo(Map.of("error", "内容不适宜，请调整后重试。"));
        assertThat(events.get(1).data()).isEqualTo("[DONE]");
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(requestSpec, never()).user(anyString());
        assertThat(generationGuard.activeStage(RUN_ID)).isEmpty();
    }

    // ===== 工具 =====

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
        // 真链路 createRun 必设（StyleMode.code），StyleMode.fromCode 对 null 会抛
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
