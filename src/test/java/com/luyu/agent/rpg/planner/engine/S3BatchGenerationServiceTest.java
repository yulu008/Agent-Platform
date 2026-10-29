package com.luyu.agent.rpg.planner.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.model.DraftItemStatus;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.repository.PipelineRunRepository;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorkspaceStore;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link S3BatchGenerationService} 分批编排测试（planner-s3-batch-generate tasks 6.1 /
 * spec「名册切块与剩余清单」「批次记账与断点续跑」「候选追加不重置」「批间并行与
 * 守卫前置」「批粒度断点续跑」「重名丢弃」）。
 * <p>
 * 钉住：三批串行全事件序列（stage_start → batch_start{index,total,count} →
 * content → batch_done{done}×N → draft_ready{version,candidates} → [DONE]）
 * 与追加落盘收口（快照/摘要/记账/守卫释放）、批内 prompt 只含本批名册 +
 * 先序批人名索引（spec「批内 prompt 不膨胀」）、断点续跑（已产出候选排除 +
 * batchLimit 截断 + 既有逐卡状态不重置）、毙掉即出局（REJECTED 候选不进剩余
 * 清单）、前置快速失败零副作用（全产出拒绝 / 跨阶段 / run 不存在）、批间
 * quota 拦截冻结（成功批保留）、批全重名失败冻结、CANCEL 置 FAILED 可续跑。
 * <p>
 * 真实例：{@link CharactersGenerator}（批内形态真拼装）+ {@link PlannerGenerationGuard}
 * （占位/释放语义）；其余 mock。manifestRef 联动（readManifest 实时返回 +
 * recordBatchPlan/append/setBatchStatus 记账改写）模拟真实 manifest 读-改-写；
 * LLM 按批内名册块路由（批尾人名定界，同 S1ChainedRefineServiceTest 的
 * prompt 路由惯例）。
 */
class S3BatchGenerationServiceTest {

    private static final String RUN_ID = "run-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final PipelineStage S3 = PipelineStage.S3_CHARACTERS;

    /** S1 确认稿：名册 5 人（批容量 2 → 3 批 [甲,乙][丙,丁][戊]）。 */
    private static final String S1_ARTIFACT = """
            # IP 拆解报告

            ## 三、人物全量名册
            - **甲**：甲的身份
            - **乙**：乙的身份
            - **丙**：丙的身份
            - **丁**：丁的身份
            - **戊**：戊的身份

            ## 四、主线与关键剧情节点
            1. 甲发起事件
            """;

    private static final String S2_CONFIRMED = "S2 确认稿全文";

    /** 各批 LLM 输出（单 token；行首 ## 标题 = 切卡锚，标题序号顺延）。 */
    private static final String BATCH1_LLM = "## 人物 1：甲\n- 身份：甲的身份\n\n## 人物 2：乙\n- 身份：乙的身份";
    private static final String BATCH2_LLM = "## 人物 3：丙\n- 身份：丙的身份\n\n## 人物 4：丁\n- 身份：丁的身份";
    private static final String BATCH3_LLM = "## 人物 5：戊\n- 身份：戊的身份";
    /** 毙掉出局夹具的批 [乙,丙] 产出（甲被毙出局后名册前移） */
    private static final String BATCH_BC_LLM = "## 人物 2：乙\n- 身份：乙的身份\n\n## 人物 3：丙\n- 身份：丙的身份";

    private PipelineRunService runService;
    private PlannerWorkspaceStore workspaceStore;
    private PipelineRunRepository runRepository;
    private ChatClientRegistry chatClientRegistry;
    private QuotaGuard quotaGuard;

    /** 批容量 2 + 并行度 1（串行保事件序可 containsExactly 严格断言）。 */
    private final RpgPlannerProperties properties = new RpgPlannerProperties();
    /** 共享守卫（真实例：占位拒真并发与流结束释放可断言）。 */
    private final PlannerGenerationGuard generationGuard = new PlannerGenerationGuard();
    private final CharactersGenerator charactersGenerator = new CharactersGenerator(properties);
    private S3BatchGenerationService service;

    /** 可变 manifest 夹具（readManifest 实时返回；记账联动 mock 改写本对象）。 */
    private PipelineManifest manifestRef;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    /** 最近一次 prompt（user 调用捕获；批间按名册块内容路由）。 */
    private final AtomicReference<String> lastPrompt = new AtomicReference<>();
    /** 串行批调用序 prompt 全量（批内形态断言用；synchronizedList 防御并行批）。 */
    private List<String> prompts;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties.setMaxCandidates(2);
        properties.setBatchConcurrency(1);

        runService = mock(PipelineRunService.class);
        workspaceStore = mock(PlannerWorkspaceStore.class);
        runRepository = mock(PipelineRunRepository.class);
        chatClientRegistry = mock(ChatClientRegistry.class);
        quotaGuard = mock(QuotaGuard.class);

        service = new S3BatchGenerationService(runService, workspaceStore, runRepository,
                chatClientRegistry, quotaGuard, generationGuard, properties, charactersGenerator);

        // 标准可生成前置：run AWAITING/S3 + manifest currentStage S3、S3 门态
        // AWAITING_CONFIRM（门动作入口语义；首轮分叉进来时会被前置翻转）
        manifestRef = manifest();
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run("AWAITING", "S3")));
        when(workspaceStore.readManifest(RUN_ID)).thenAnswer(inv -> manifestRef);
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S1_IP_ANALYSIS))
                .thenReturn(S1_ARTIFACT);
        when(workspaceStore.readStageArtifact(RUN_ID, PipelineStage.S2_WORLD_SETTING))
                .thenReturn(S2_CONFIRMED);

        // ===== manifest 记账联动（模拟真实读-改-写，见 PipelineManifestBatchTest 类注释） =====
        // 阶段 RUNNING（含崩溃残留复位入参透传）
        doAnswer(inv -> {
            manifestRef.stage(S3.code()).setStatus("RUNNING");
            return null;
        }).when(runService).markStageRunning(eq(RUN_ID), any(), anyBoolean());
        // 批计划记账：index 全局递增、append PENDING 批、返回新批列表（编排用返回实例）
        when(runService.recordBatchPlan(eq(RUN_ID), any(), any())).thenAnswer(inv -> {
            List<List<String>> nameBatches = inv.getArgument(2);
            PipelineManifest.StageState s3 = manifestRef.stage(S3.code());
            int nextIndex = s3.getBatches().stream()
                    .mapToInt(PipelineManifest.BatchState::getIndex).max().orElse(0);
            List<PipelineManifest.BatchState> planned = new ArrayList<>(nameBatches.size());
            for (List<String> names : nameBatches) {
                planned.add(new PipelineManifest.BatchState(++nextIndex, names, "PENDING"));
            }
            s3.getBatches().addAll(planned);
            return planned;
        });
        // 候选文件追加（真实语义：目录序号顺延返回 id；mock 按候选行数等价顺延）
        when(workspaceStore.appendStageCandidates(eq(RUN_ID), any(), anyList()))
                .thenAnswer(inv -> {
                    List<String> kept = inv.getArgument(2);
                    int base = manifestRef.stage(S3.code()).getCandidates().size();
                    List<String> ids = new ArrayList<>(kept.size());
                    for (int i = 0; i < kept.size(); i++) {
                        ids.add(String.format("candidate-%02d", base + 1 + i));
                    }
                    return ids;
                });
        // manifest 候选行 append（新卡 PENDING；既有逐卡状态不动 =「追加不重置」）
        doAnswer(inv -> {
            List<String> ids = inv.getArgument(2);
            List<String> titles = inv.getArgument(3);
            PipelineManifest.StageState s3 = manifestRef.stage(S3.code());
            for (int i = 0; i < ids.size(); i++) {
                s3.getCandidates().add(new PipelineManifest.CandidateState(
                        ids.get(i), titles.get(i), DraftItemStatus.PENDING.name()));
            }
            return null;
        }).when(runService).appendStageCandidates(eq(RUN_ID), any(), anyList(), anyList());
        // 批状态流转（未知序号忽略）
        doAnswer(inv -> {
            int index = inv.getArgument(2);
            String status = inv.getArgument(3);
            manifestRef.stage(S3.code()).getBatches().stream()
                    .filter(b -> b.getIndex() == index).findFirst()
                    .ifPresent(b -> b.setStatus(status));
            return null;
        }).when(runService).setBatchStatus(eq(RUN_ID), any(), anyInt(), anyString());
        // 阶段完成：version+1、返回新版本（draft_ready 尾包数据源）
        when(runService.markStageCompleted(eq(RUN_ID), any(), any())).thenAnswer(inv -> {
            PipelineManifest.StageState s3 = manifestRef.stage(S3.code());
            s3.setStatus("AWAITING_CONFIRM");
            s3.setVersion(s3.getVersion() + 1);
            return s3.getVersion();
        });
        // 阶段失败冻结
        doAnswer(inv -> {
            manifestRef.stage(S3.code()).setStatus("FAILED");
            return null;
        }).when(runService).markStageFailed(eq(RUN_ID), any());
        // 聚合语义稿（滤 REJECTED 标题拼接——快照内容断言锚）
        when(workspaceStore.readStageFullText(eq(RUN_ID), any(), any())).thenAnswer(inv -> {
            List<PipelineManifest.CandidateState> all = inv.getArgument(2);
            return all.stream()
                    .filter(c -> !DraftItemStatus.REJECTED.name().equals(c.getStatus()))
                    .map(PipelineManifest.CandidateState::getTitle)
                    .collect(Collectors.joining("\n"));
        });

        // ===== ChatClient mock 链（按批内 prompt 路由，同 S1ChainedRefineServiceTest） =====
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        prompts = Collections.synchronizedList(new ArrayList<>());
        when(chatClientRegistry.forRole("planner")).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            lastPrompt.set(prompt);
            prompts.add(prompt);
            return requestSpec;
        });
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            // 批尾人名定界路由：戊 = [戊]；丁 = [丙,丁]；丙（无丁）= [乙,丙]
            // （毙掉出局夹具的批 1）；否则首轮 [甲,乙]
            if (prompt != null && prompt.contains("- **戊**")) {
                return Flux.just(BATCH3_LLM);
            }
            if (prompt != null && prompt.contains("- **丁**")) {
                return Flux.just(BATCH2_LLM);
            }
            if (prompt != null && prompt.contains("- **丙**")) {
                return Flux.just(BATCH_BC_LLM);
            }
            return Flux.just(BATCH1_LLM);
        });
    }

    // ===== 正常全量流（三批串行 + 事件序列 + 追加落盘收口） =====

    @Test
    void 三批串行生成事件序列与追加落盘收口() {
        List<ServerSentEvent<Object>> events = service
                .generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 事件序列（并行度 1 串行保序）：stage_start → 每批
        // (batch_start{index,total,count} → content → batch_done{done}) →
        // draft_ready{version,candidates} → [DONE]
        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 3, "count", 2),
                Map.of("content", BATCH1_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "batch_start", "stage", "S3", "index", 2, "seq", 2, "total", 3, "count", 2),
                Map.of("content", BATCH2_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 2, "seq", 2, "status", "done"),
                Map.of("type", "batch_start", "stage", "S3", "index", 3, "seq", 3, "total", 3, "count", 1),
                Map.of("content", BATCH3_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 3, "seq", 3, "status", "done"),
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 5),
                "[DONE]");

        // 追加落盘：三批逐批收口（批成功即持久化，spec「成功一批持久化一批」）
        verify(workspaceStore, times(3)).appendStageCandidates(eq(RUN_ID), eq(S3), anyList());
        verify(runService, times(3)).appendStageCandidates(eq(RUN_ID), eq(S3), anyList(), anyList());
        // 版本快照 v2 = 滤 REJECTED 聚合稿（全量候选标题，spec「版本快照含全量聚合」）
        verify(workspaceStore).writeVersionSnapshot(RUN_ID, S3, 2,
                "人物 1：甲\n人物 2：乙\n人物 3：丙\n人物 4：丁\n人物 5：戊");
        // 收口：AWAITING_CONFIRM + 摘要行（候选标题列表）
        verify(runService).markStageCompleted(RUN_ID, S3,
                "5 个候选：人物 1：甲、人物 2：乙、人物 3：丙、人物 4：丁、人物 5：戊");
        // 批记账：三批全 DONE
        verify(runService).setBatchStatus(RUN_ID, S3, 1, "DONE");
        verify(runService).setBatchStatus(RUN_ID, S3, 2, "DONE");
        verify(runService).setBatchStatus(RUN_ID, S3, 3, "DONE");
        // 治理三段式 quota：前置 1 次 + 每批启动前 1 次（3 批）；advisor 每批 1 次归账
        verify(quotaGuard, times(4)).check(any());
        verify(requestSpec, times(3)).advisors(any(Consumer.class));
        verify(runService, never()).markStageFailed(anyString(), any());

        // manifest 终态：候选 5 行（id 顺延 + 新卡 PENDING）+ 批 3 行 DONE + v2
        PipelineManifest.StageState s3 = manifestRef.stage(S3.code());
        assertThat(s3.getStatus()).isEqualTo("AWAITING_CONFIRM");
        assertThat(s3.getVersion()).isEqualTo(2);
        assertThat(s3.getCandidates()).extracting(
                        PipelineManifest.CandidateState::getId,
                        PipelineManifest.CandidateState::getTitle,
                        PipelineManifest.CandidateState::getStatus)
                .containsExactly(
                        tuple("candidate-01", "人物 1：甲", "PENDING"),
                        tuple("candidate-02", "人物 2：乙", "PENDING"),
                        tuple("candidate-03", "人物 3：丙", "PENDING"),
                        tuple("candidate-04", "人物 4：丁", "PENDING"),
                        tuple("candidate-05", "人物 5：戊", "PENDING"));
        assertThat(s3.getBatches()).extracting(
                        PipelineManifest.BatchState::getIndex,
                        PipelineManifest.BatchState::getNames,
                        PipelineManifest.BatchState::getStatus)
                .containsExactly(
                        tuple(1, List.of("甲", "乙"), "DONE"),
                        tuple(2, List.of("丙", "丁"), "DONE"),
                        tuple(3, List.of("戊"), "DONE"));

        // 守卫释放（流结束可再次占位）
        assertThat(generationGuard.tryAcquire(RUN_ID, S3)).isTrue();
        generationGuard.release(RUN_ID, S3);
    }

    // ===== 批流装配零工作区 IO（6.3 E2E 发现的租户上下文回归） =====

    @Test
    void S2上游稿编排期快照批流内零工作区读() {
        List<ServerSentEvent<Object>> events = service
                .generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 批流 defer 在订阅线程执行——批 3 起由上游完成信号驱动于 reactor 线程
        // 订阅，ThreadLocal 租户不在，批内读工作区会抛 TenantContextMissingException
        //（E2E 真实环境批 3 失败、单测 mock store 不走租户故全绿）。S2 稿须编排期
        //（请求线程）快照一次传入；修复前每批 buildBatchContext 重读 times(3)
        assertThat(events).isNotNull();
        verify(workspaceStore, times(1))
                .readStageArtifact(RUN_ID, PipelineStage.S2_WORLD_SETTING);
        // 流正常收口（无批失败冻结）
        verify(runService, never()).markStageFailed(anyString(), any());
    }

    // ===== 批内形态（spec「批内 prompt 不膨胀」） =====

    @Test
    void 批内prompt只含本批名册与先序批人名索引() {
        service.generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 批 1：本批名册 [甲,乙]；首轮无产出 → 无「已产出人物」段
        assertThat(prompts).hasSize(3);
        assertThat(prompts.get(0))
                .contains("【本批人物名册（S1 确认稿节选）】")
                .contains("- **甲**：甲的身份")
                .contains("- **乙**：乙的身份")
                .doesNotContain("- **丙**")
                .doesNotContain("- **戊**")
                .doesNotContain("已产出人物")
                .contains(S2_CONFIRMED);

        // 批 2：本批名册仅 [丙,丁]（不含甲乙戊行）；已产出索引含先序批预知 [甲,乙]
        assertThat(prompts.get(1))
                .contains("【本批人物名册（S1 确认稿节选）】")
                .contains("- **丙**：丙的身份")
                .contains("- **丁**：丁的身份")
                .doesNotContain("- **甲**")
                .doesNotContain("- **乙**")
                .doesNotContain("- **戊**")
                .contains("【已产出人物（其他批已生成候选，禁止重复产出）】")
                .contains("- 甲\n")
                .contains("- 乙\n")
                .doesNotContain("- 丙\n")
                .doesNotContain("- 丁\n")
                .contains(S2_CONFIRMED);

        // 批 3：已产出索引滚动含批 1+批 2 先序人名
        assertThat(prompts.get(2))
                .contains("- **戊**：戊的身份")
                .contains("- 甲\n").contains("- 乙\n")
                .contains("- 丙\n").contains("- 丁\n");
    }

    // ===== 断点续跑（D2：已产出排除 + batchLimit 截断 + 追加不重置） =====

    @Test
    void 断点续跑补一批截断且追加不重置既有候选() {
        // 前次编排已产出甲乙（甲 CONFIRMED / 乙 PENDING——既有逐卡状态不重置锚）
        seedCandidates(new PipelineManifest.CandidateState(
                "candidate-01", "人物 1：甲", DraftItemStatus.CONFIRMED.name()));
        seedCandidates(new PipelineManifest.CandidateState(
                "candidate-02", "人物 2：乙", DraftItemStatus.PENDING.name()));

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        // 剩余 [丙,丁,戊] 补一批截断为 1 批 [丙,丁]：total=1（本次编排口径）；
        // 收口 v2、总候选 4（甲乙丙丁）
        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 1, "count", 2),
                Map.of("content", BATCH2_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 4),
                "[DONE]");

        // quota：前置 + 仅 1 批（截断后不再有批 2/3 的批间检查）
        verify(quotaGuard, times(2)).check(any());
        verify(workspaceStore, times(1)).appendStageCandidates(eq(RUN_ID), eq(S3), anyList());
        // 追加不重置：甲仍 CONFIRMED（新卡丙丁 PENDING，spec「追加不重置」）
        assertThat(manifestRef.stage(S3.code()).getCandidates()).extracting(
                        PipelineManifest.CandidateState::getId,
                        PipelineManifest.CandidateState::getTitle,
                        PipelineManifest.CandidateState::getStatus)
                .containsExactly(
                        tuple("candidate-01", "人物 1：甲", "CONFIRMED"),
                        tuple("candidate-02", "人物 2：乙", "PENDING"),
                        tuple("candidate-03", "人物 3：丙", "PENDING"),
                        tuple("candidate-04", "人物 4：丁", "PENDING"));
        // 批内 prompt：名册块仅 [丙,丁]（断点续跑不重复耗 token），已产出含甲乙
        assertThat(prompts.get(0))
                .doesNotContain("- **甲**")
                .doesNotContain("- **乙**")
                .contains("- 甲\n")
                .contains("- 乙\n");
    }

    @Test
    void 毙掉候选不进剩余清单() {
        // 甲已产出且被毙（REJECTED——毙掉即出局，不进补批名册；重名检测基准
        // 不含已毙卡，但剩余清单排除使其不再请求产出）
        seedCandidates(new PipelineManifest.CandidateState(
                "candidate-01", "人物 1：甲", DraftItemStatus.REJECTED.name()));

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        // 剩余 = [乙,丙,丁,戊]（甲出局）→ 补一批 [乙,丙]
        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 1, "count", 2),
                Map.of("content", BATCH_BC_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 3),
                "[DONE]");
        // 批内名册块从乙起（甲的名册行不进批）
        assertThat(prompts.get(0)).doesNotContain("- **甲**").contains("- **乙**");
        // 快照滤 REJECTED：甲的标题不进聚合稿
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(workspaceStore).writeVersionSnapshot(eq(RUN_ID), eq(S3), eq(2), snapshot.capture());
        assertThat(snapshot.getValue())
                .doesNotContain("人物 1：甲")
                .contains("人物 2：乙");
    }

    // ===== 前置快速失败（零副作用） =====

    @Test
    void 名册人物已全产出拒绝补批零副作用() {
        for (int i = 1; i <= 5; i++) {
            seedCandidates(new PipelineManifest.CandidateState(
                    String.format("candidate-%02d", i), "人物 " + i + "："
                    + "甲乙丙丁戊".charAt(i - 1), DraftItemStatus.PENDING.name()));
        }

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("error", "名册人物已全部产出候选（5 人；毙掉的人物即出局不补），无可生成的剩余批次"),
                "[DONE]");
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
        verify(workspaceStore, never()).appendStageCandidates(anyString(), any(), anyList());
        verify(quotaGuard, never()).check(any());
    }

    @Test
    void 非当前阶段拒绝跨阶段生成() {
        manifestRef.setCurrentStage("S2");

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("error", "阶段前置条件不满足：当前阶段为 S2，不能直接生成 S3（跨阶段跳过被拒）"),
                "[DONE]");
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    @Test
    void run不存在拒绝() {
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data)
                .containsExactly(Map.of("error", "管线 run 不存在: run-1"), "[DONE]");
        verify(runService, never()).markStageRunning(anyString(), any(), anyBoolean());
    }

    // ===== 批间 quota 拦截（治理三段式：成功批保留 + 失败冻结） =====

    @Test
    void 批间配额拦截冻结且成功批保留() {
        // 第 3 次 check 起（前置 + 批 1 之后 = 批 2 启动前）超限拦截
        AtomicInteger quotaCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (quotaCalls.incrementAndGet() >= 3) {
                throw new QuotaExceededException(1000L, 1000L);
            }
            return null;
        }).when(quotaGuard).check(any());

        List<ServerSentEvent<Object>> events = service
                .generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 序列：批 1 正常（bs/content/bd done）→ 批 2 启动前拦截（无 batch_start，
        // 直接 batch_done{error}）→ 批 3 短路（failureRef，零事件零 token）→ error + [DONE]
        assertThat(events).hasSize(7);
        assertThat(events).extracting(ServerSentEvent::data).containsSubsequence(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 3, "count", 2),
                Map.of("content", BATCH1_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "batch_done", "stage", "S3", "index", 2, "seq", 2, "status", "error"));
        assertThat(events.get(events.size() - 2).data()).isInstanceOf(Map.class);
        Map<?, ?> error = (Map<?, ?>) events.get(events.size() - 2).data();
        assertThat((String) error.get("error"))
                .startsWith("配额超限，本轮第 2/3 批未启动（已成功批次保留，额度恢复后重试续跑）");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");

        // 成功批保留（批 1 落盘不动）+ 冻结（FAILED + 批 2 记账 FAILED）
        verify(workspaceStore, times(1)).appendStageCandidates(eq(RUN_ID), eq(S3), anyList());
        verify(runService).markStageFailed(RUN_ID, S3);
        verify(runService).setBatchStatus(RUN_ID, S3, 2, "FAILED");
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
        // 批 2 quota 拦截于 LLM 调用之前（零 token 耗费）+ 批 3 短路零 LLM 调用
        // → 仅批 1 发起过 LLM；quota = 前置 + 批 1 + 批 2（拦截那次）共 3 次
        verify(requestSpec, times(1)).advisors(any(Consumer.class));
        verify(quotaGuard, times(3)).check(any());
    }

    // ===== 批全重名（模型漂移重产出已有人物，spec「宁重复提示重跑该批」兜底） =====

    @Test
    void 批全重名候选失败冻结已成功批保留() {
        // 批 2（名册 [丙,丁]）漂移重产甲乙 → 全卡重名丢弃 → kept 空判批失败
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("- **丙**")) {
                return Flux.just("## 人物 6：甲\n- 身份：重复的甲\n\n## 人物 7：乙\n- 身份：重复的乙");
            }
            return Flux.just(BATCH1_LLM);
        });

        List<ServerSentEvent<Object>> events = service
                .generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 序列：批 1 正常收口 → 批 2 有 batch_start + content（token 已流）但无
        // batch_done（收口异常走 onErrorResume 冻结）→ 批 3 短路 → error + [DONE]
        assertThat(events).hasSize(8);
        assertThat(events).extracting(ServerSentEvent::data).containsSubsequence(
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "batch_start", "stage", "S3", "index", 2, "seq", 2, "total", 3, "count", 2),
                Map.of("content", "## 人物 6：甲\n- 身份：重复的甲\n\n## 人物 7：乙\n- 身份：重复的乙"));
        assertThat(events.stream().map(ServerSentEvent::data).toList())
                .doesNotContain(Map.of("type", "batch_done", "stage", "S3", "index", 2, "seq", 2, "status", "done"));
        Map<?, ?> error = (Map<?, ?>) events.get(events.size() - 2).data();
        assertThat((String) error.get("error")).startsWith("本轮第 2/3 批生成失败（已成功批次保留，可重试续跑）");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");

        // 批 1 落盘保留（1 次 append）；批 2 冻结 FAILED；无版本推进（无收口）
        verify(workspaceStore, times(1)).appendStageCandidates(eq(RUN_ID), eq(S3), anyList());
        verify(runService).markStageFailed(RUN_ID, S3);
        verify(runService).setBatchStatus(RUN_ID, S3, 2, "FAILED");
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
        verify(workspaceStore, never()).writeVersionSnapshot(anyString(), any(), anyInt(), any());
    }

    // ===== 批内名册对账（空壳卡批整体重跑，run 5ffd6d5b 批 16/17/19 生产教训） =====

    @Test
    void 空壳卡批产出对不上名册失败冻结() {
        // 批 2（名册 [丙,丁]）模型只回一张空壳卡「## 人物 1」（无有效名册人名）
        // ——修复前 kept=1 非空静默落盘废卡且阶段正常收口；对账（覆盖 0/2 低于
        // 半数）判该批失败冻结，漏的人由 rosterRemaining 断点续跑自愈
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("- **丁**")) {
                return Flux.just("## 人物 1");
            }
            return Flux.just(BATCH1_LLM);
        });

        List<ServerSentEvent<Object>> events = service
                .generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 批 1 正常收口；批 2 有 batch_start + content 但无 batch_done；批 3 短路
        assertThat(events).extracting(ServerSentEvent::data).containsSubsequence(
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "batch_start", "stage", "S3", "index", 2, "seq", 2, "total", 3, "count", 2),
                Map.of("content", "## 人物 1"));
        assertThat(events.stream().map(ServerSentEvent::data).toList())
                .doesNotContain(Map.of("type", "batch_done", "stage", "S3", "index", 2, "seq", 2, "status", "done"));
        Map<?, ?> error = (Map<?, ?>) events.get(events.size() - 2).data();
        assertThat((String) error.get("error")).startsWith("本轮第 2/3 批生成失败（已成功批次保留，可重试续跑）");
        assertThat((String) error.get("error")).contains("产出对不上批内名册（覆盖 0/2 人");
        assertThat(events.get(events.size() - 1).data()).isEqualTo("[DONE]");

        // 批 1 落盘保留；空壳卡不落盘；批 2 冻结 FAILED；无版本推进
        verify(workspaceStore, times(1)).appendStageCandidates(eq(RUN_ID), eq(S3), anyList());
        verify(runService).markStageFailed(RUN_ID, S3);
        verify(runService).setBatchStatus(RUN_ID, S3, 2, "FAILED");
        verify(runService, never()).markStageCompleted(anyString(), any(), any());
    }

    @Test
    void 轻微缺漏批容忍漏人进下轮剩余() {
        // 批 2（名册 [丙,丁]）只回丙 1 张（覆盖 1/2 恰达半数）：静默容忍不判
        // 失败（漏掉的丁自然进下一轮 rosterRemaining 补齐——宁重跑不落废卡
        // 只拦空壳/截断形态，轻微缺漏不烧整批重跑 token）
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("- **丁**")) {
                return Flux.just("## 人物 3：丙\n- 身份：丙的身份");
            }
            if (prompt != null && prompt.contains("- **戊**")) {
                return Flux.just(BATCH3_LLM);
            }
            return Flux.just(BATCH1_LLM);
        });

        List<ServerSentEvent<Object>> events = service
                .generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 三批全部收口成功（draft_ready 尾包）：批 2 覆盖 1/2 = 半数不判失败
        assertThat(events.stream().map(ServerSentEvent::data).toList())
                .contains(Map.of("type", "batch_done", "stage", "S3", "index", 2, "seq", 2, "status", "done"));
        assertThat(events.get(events.size() - 2).data()).isEqualTo(
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 4));
        verify(runService, never()).markStageFailed(anyString(), any());
        verify(workspaceStore, times(3)).appendStageCandidates(eq(RUN_ID), eq(S3), anyList());
    }

    // ===== 跨轮自编序号不判重名（6.3 E2E 后真实使用发现，run 5ffd6d5b） =====

    @Test
    void 跨轮自编序号前缀不判重名() {
        // 返工轮单流产出「人物 1：甲」「人物 2：乙」（成稿模板序号）后分批：
        // 剩余 [丙,丁,戊] 补一批 [丙,丁]，LLM 漂移自编序号 1/2（未锚定名册
        // 原名）——序号前缀与返工轮候选的「人物 1」「人物 2」撞车。身份比对键
        // 限定名册人名域（命中名册的丙/丁才比对）后不误杀；修复前第二候选
        // 「人物 1/2」互撞致整批全丢判「全部为重名或无效」失败
        seedCandidates(new PipelineManifest.CandidateState(
                "candidate-01", "人物 1：甲", DraftItemStatus.PENDING.name()));
        seedCandidates(new PipelineManifest.CandidateState(
                "candidate-02", "人物 2：乙", DraftItemStatus.PENDING.name()));
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("- **丙**")) {
                return Flux.just("## 人物 1：丙\n- 身份：丙的身份\n\n## 人物 2：丁\n- 身份：丁的身份");
            }
            return Flux.just(BATCH1_LLM);
        });

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        // 批 1 正常收口（序号撞车不判重名）：2 张全追加，无失败冻结
        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 1, "count", 2),
                Map.of("content", "## 人物 1：丙\n- 身份：丙的身份\n\n## 人物 2：丁\n- 身份：丁的身份"),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 4),
                "[DONE]");
        verify(runService, never()).markStageFailed(anyString(), any());
        verify(runService).setBatchStatus(RUN_ID, S3, 1, "DONE");
        // 既有 2 行不重置 + 追加 2 行（标题原样：LLM 自编序号保留）
        assertThat(manifestRef.stage(S3.code()).getCandidates()).extracting(
                        PipelineManifest.CandidateState::getId,
                        PipelineManifest.CandidateState::getTitle,
                        PipelineManifest.CandidateState::getStatus)
                .containsExactly(
                        tuple("candidate-01", "人物 1：甲", "PENDING"),
                        tuple("candidate-02", "人物 2：乙", "PENDING"),
                        tuple("candidate-03", "人物 1：丙", "PENDING"),
                        tuple("candidate-04", "人物 2：丁", "PENDING"));
        // 批内 prompt 已产出索引：身份键（甲乙）不含序号前缀行（buildBatchContext
        // 同过滤口径，防「- 人物 1」误导 LLM 编号）
        assertThat(prompts.get(0))
                .contains("- 甲\n")
                .contains("- 乙\n")
                .doesNotContain("- 人物");
    }

    // ===== 名描述形候选身份识别（断点续跑不重复生成，run 5ffd6d5b 重试发现） =====

    @Test
    void 断点续跑名描述形候选不重复生成() {
        // 上一轮成功批锚定句产出「名：描述」形（标题以名册原名开头）已落
        // manifest：重试编排时该人物不回剩余清单——修复前 titleNameCandidates
        // 对无「人物 N：」前缀标题只取整标题，甲乙不命中名册名全部回剩余
        // 被重复生成（重名检测同源漏检不拦）
        seedCandidates(new PipelineManifest.CandidateState(
                "candidate-01", "甲：甲的身份描述", DraftItemStatus.PENDING.name()));
        seedCandidates(new PipelineManifest.CandidateState(
                "candidate-02", "乙：乙的身份描述", DraftItemStatus.PENDING.name()));
        when(streamSpec.content()).thenAnswer(inv -> {
            String prompt = lastPrompt.get();
            if (prompt != null && prompt.contains("- **戊**")) {
                return Flux.just(BATCH3_LLM);
            }
            return Flux.just(BATCH2_LLM);
        });

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .collectList().block(TIMEOUT);

        // 剩余 [丙,丁,戊] 两批：甲乙不重复生成，产出 3 张全收口
        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 2, "count", 2),
                Map.of("content", BATCH2_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "batch_start", "stage", "S3", "index", 2, "seq", 2, "total", 2, "count", 1),
                Map.of("content", BATCH3_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 2, "seq", 2, "status", "done"),
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 5),
                "[DONE]");
        // 批内名册块只含剩余人（名册条目不含甲乙）；已产出索引含甲乙（告诉 LLM 已产出）
        assertThat(prompts.get(0))
                .contains("- **丙**")
                .contains("- **丁**")
                .contains("- 甲\n")
                .doesNotContain("- **甲**");
        assertThat(manifestRef.stage(S3.code()).getCandidates()).extracting(
                        PipelineManifest.CandidateState::getTitle)
                .containsExactly("甲：甲的身份描述", "乙：乙的身份描述",
                        "人物 3：丙", "人物 4：丁", "人物 5：戊");
    }

    // ===== 断点续跑批事件序号本轮语义（run 5ffd6d5b「第 5/4 批」显示错位） =====

    @Test
    void 断点续跑批事件序号本轮起全局批号作seq() {
        // manifest 已有历史批记账（上轮 FAILED 冻结）：本轮编排 recordBatchPlan
        // 全局批号顺延（4→5），SSE 进度事件 index 用本轮序号（1 起）、seq 带
        // 全局批号——修复前 index 直接用全局批号致「第 5/4 批」错位
        manifestRef.stage(S3.code()).getBatches()
                .add(new PipelineManifest.BatchState(4, List.of("历史人物"), "FAILED"));
        when(streamSpec.content()).thenReturn(Flux.just(BATCH1_LLM));

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 5, "total", 1, "count", 2),
                Map.of("content", BATCH1_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 5, "status", "done"),
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 2),
                "[DONE]");
        // 批记账仍全局序号（编排轨迹真源）：历史批保留 FAILED，新批 index 5 DONE
        assertThat(manifestRef.stage(S3.code()).getBatches()).extracting(
                        PipelineManifest.BatchState::getIndex, PipelineManifest.BatchState::getStatus)
                .containsExactly(tuple(4, "FAILED"), tuple(5, "DONE"));
    }

    // ===== 瞬时网络故障自动重试（run 5ffd6d5b 批 10 okhttp timeout 杀流） =====

    @Test
    void 瞬时网络故障自动重试原批重发收口() {
        // 批 1 LLM 流中途 IOException（okhttp 读空闲超时 / StreamReset 族）：
        // defer 重订阅原批重发（完整重调），重试成功即正常收口——无失败冻结
        AtomicInteger calls = new AtomicInteger();
        when(streamSpec.content()).thenAnswer(inv -> calls.incrementAndGet() == 1
                ? Flux.error(new IOException("simulated read timeout"))
                : Flux.just(BATCH1_LLM));

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        assertThat(events).extracting(ServerSentEvent::data).containsExactly(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"),
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 1, "count", 2),
                Map.of("content", BATCH1_LLM),
                Map.of("type", "batch_done", "stage", "S3", "index", 1, "seq", 1, "status", "done"),
                Map.of("type", "draft_ready", "stage", "S3", "version", 2, "candidates", 2),
                "[DONE]");
        // 重试重发 = 2 次 LLM 调用（advisors 每次调用 1 次归账）；无失败冻结
        verify(requestSpec, times(2)).advisors(any(Consumer.class));
        verify(runService, never()).markStageFailed(anyString(), any());
        assertThat(manifestRef.stage(S3.code()).getCandidates()).hasSize(2);
    }

    @Test
    void 网络故障重试耗尽批失败冻结() {
        // 持续 IOException：重试 1 次仍失败 → 失败冻结（阶段 FAILED + 批 FAILED +
        // error 尾包，已成功批次保留语义同旧行为——本例零落盘）
        when(streamSpec.content()).thenReturn(Flux.error(new IOException("simulated read timeout")));

        List<ServerSentEvent<Object>> events = service.generateRemainingBatches(RUN_ID, 1)
                .collectList().block(TIMEOUT);

        assertThat(events).hasSize(4);
        assertThat(events.get(0).data()).isEqualTo(
                Map.of("type", "stage_start", "stage", "S3", "label", "人物设定"));
        assertThat(events.get(1).data()).isEqualTo(
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 1, "count", 2));
        assertThat((String) ((Map<?, ?>) events.get(2).data()).get("error"))
                .startsWith("本轮第 1/1 批生成失败（已成功批次保留，可重试续跑）");
        assertThat(events.get(3).data()).isEqualTo("[DONE]");
        // 1 次原始 + 1 次重试 = 2 次 LLM 调用；批 FAILED 记账 + 阶段 FAILED
        verify(requestSpec, times(2)).advisors(any(Consumer.class));
        verify(runService).markStageFailed(RUN_ID, S3);
        verify(runService).setBatchStatus(RUN_ID, S3, 1, "FAILED");
        verify(workspaceStore, never()).appendStageCandidates(anyString(), any(), anyList());
    }

    // ===== 取消（前端断流/中止 → FAILED 可断点续跑） =====

    @Test
    void 流取消置失败可断点续跑() {
        List<ServerSentEvent<Object>> events = service
                .generateRemainingBatches(RUN_ID, Integer.MAX_VALUE)
                .take(2)
                .collectList().block(TIMEOUT);

        // 取 stage_start + 批 1 batch_start 即取消（批 1 未收口）
        assertThat(events).hasSize(2);
        assertThat(events.get(1).data()).isEqualTo(
                Map.of("type", "batch_start", "stage", "S3", "index", 1, "seq", 1, "total", 3, "count", 2));
        // CANCEL → 显式置 FAILED（重试走断点续跑，已落盘批保留——本例零落盘）
        verify(runService).markStageFailed(RUN_ID, S3);
        verify(workspaceStore, never()).appendStageCandidates(anyString(), any(), anyList());
        // 守卫释放（可重试占位）
        assertThat(generationGuard.tryAcquire(RUN_ID, S3)).isTrue();
        generationGuard.release(RUN_ID, S3);
    }

    // ===== 工具 =====

    /** 标准可生成 manifest 夹具（currentStage S3、S3 AWAITING_CONFIRM v1）。 */
    private static PipelineManifest manifest() {
        PipelineManifest manifest = new PipelineManifest();
        manifest.setRunId(RUN_ID);
        manifest.setStatus("AWAITING");
        manifest.setCurrentStage("S3");
        manifest.setStyleMode("faithful");
        manifest.stage("S3").setStatus("AWAITING_CONFIRM");
        manifest.stage("S3").setVersion(1);
        return manifest;
    }

    private static PipelineRun run(String status, String currentStage) {
        PipelineRun run = new PipelineRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        run.setCurrentStage(currentStage);
        return run;
    }

    /** 植入既有候选行（断点续跑 / 毙掉出局 / 全产出拒绝夹具）。 */
    private void seedCandidates(PipelineManifest.CandidateState... candidates) {
        for (PipelineManifest.CandidateState candidate : candidates) {
            manifestRef.stage(S3.code()).getCandidates().add(candidate);
        }
    }
}
