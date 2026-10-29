package com.luyu.agent.rpg.eval.service;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.config.EvalProperties;
import com.luyu.agent.rpg.eval.engine.EvalNotEnabledException;
import com.luyu.agent.rpg.eval.engine.JudgeClient;
import com.luyu.agent.rpg.eval.engine.L1CheckRunner;
import com.luyu.agent.rpg.eval.engine.RubricLoader;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.EvalRecord;
import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.rpg.eval.model.JudgeJudgment;
import com.luyu.agent.rpg.eval.model.JudgeOutcome;
import com.luyu.agent.rpg.eval.model.L1Warning;
import com.luyu.agent.rpg.eval.repository.EvalRecordRepository;
import com.luyu.agent.rpg.eval.repository.EvalRunRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EvalRunService} 单测（content-eval tasks 6.3 / 7.4）。
 * <p>
 * 覆盖：startRun 启用预检（未配置 judge 不落 run 零 LLM）与异步状态流转
 * （timeout verify 收口）；executeRun 同步驱动（汇总结构 / record 逐条落库
 * 字段 / case 级作废 / 核验失败条目 verdict 不落）；中断 markFailed 后重触发
 * 创建新 run 不覆盖；成本汇总按 metering call_type=judge 入账与查询失败降级；
 * 复核域编排（tasks 7.1 / 7.3）：分歧检测两端标记同组且失败跳过不阻断、
 * 回归集 subject 在批跑落库时标记 is_regression。
 */
@ExtendWith(MockitoExtension.class)
class EvalRunServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private EvalRunRepository runRepository;
    @Mock
    private EvalRecordRepository recordRepository;
    @Mock
    private StaticEvalCaseAssembler staticAssembler;
    @Mock
    private DynamicEvalCaseAssembler dynamicAssembler;
    @Mock
    private L1CheckRunner l1CheckRunner;
    @Mock
    private JudgeClient judgeClient;
    @Mock
    private RubricLoader rubricLoader;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private final EvalProperties evalProperties = new EvalProperties();
    private EvalRunService service;

    /** 分歧检测 markDivergence 调用的两端 id 清单（List 泛型 captor） */
    @Captor
    private ArgumentCaptor<List<String>> divergenceIdsCaptor;

    @BeforeEach
    void setUp() {
        service = new EvalRunService(runRepository, recordRepository, staticAssembler,
                dynamicAssembler, l1CheckRunner, judgeClient, rubricLoader,
                evalProperties, jdbcTemplate);
        TenantContext.set("u-1", "t-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== startRun：启用预检与异步状态流转 ====================

    @Test
    void startRun_未配置judge拒绝_不落run零LLM调用() {
        when(judgeClient.isJudgeConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.startRun(EvalRun.LAYER_STATIC, "w-1", null, null))
                .isInstanceOf(EvalNotEnabledException.class);

        verify(runRepository, never()).save(any());
        verify(judgeClient, never()).judge(any(), any());
        verify(judgeClient, never()).judgeFingerprint();
    }

    @Test
    void startRun_落running_run_后台异步完成转done() {
        when(judgeClient.isJudgeConfigured()).thenReturn(true);
        when(judgeClient.judgeModelName()).thenReturn("glm-5");
        when(rubricLoader.version()).thenReturn("rub123");
        when(staticAssembler.assemble("w-1")).thenReturn(List.of());
        when(recordRepository.listByRunId(anyString(), isNull(), isNull())).thenReturn(List.of());

        String runId = service.startRun(EvalRun.LAYER_STATIC, "w-1", null, null);

        assertThat(runId).isNotBlank();
        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runRepository).save(runCaptor.capture());
        EvalRun saved = runCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(runId);
        assertThat(saved.getStatus()).isEqualTo(EvalRun.STATUS_RUNNING);
        assertThat(saved.getLayer()).isEqualTo(EvalRun.LAYER_STATIC);
        assertThat(saved.getJudgeModel()).isEqualTo("glm-5");
        assertThat(saved.getRubricVersion()).isEqualTo("rub123");
        assertThat(saved.getScopeJson()).contains("\"worldId\":\"w-1\"");

        // 后台虚拟线程完成收口：空 cases → done + 空 summary（total=0）
        verify(runRepository, timeout(3000)).markDone(eq(runId), anyString(), eq(0L), eq(0L));
        verify(runRepository, never()).markFailed(anyString());
    }

    @Test
    void startRun_中断failed后重触发_新runId不覆盖旧run() {
        when(judgeClient.isJudgeConfigured()).thenReturn(true);
        when(judgeClient.judgeModelName()).thenReturn("glm-5");
        when(rubricLoader.version()).thenReturn("rub123");
        // 首次批跑执行期失败（防御兜底路径：judgeFingerprint 抛 → markFailed）
        when(judgeClient.judgeFingerprint()).thenThrow(new IllegalStateException("执行期配置失效"));

        String first = service.startRun(EvalRun.LAYER_STATIC, "w-1", null, null);
        verify(runRepository, timeout(3000)).markFailed(first);
        verify(runRepository, never()).markDone(anyString(), anyString(), anyLong(), anyLong());

        // 恢复后重触发：新 run（新 id，旧 run 行不动——repository 无 update-by-retrigger 路径）。
        // re-stub 用 doReturn：旧 stub 为 thenThrow，when(mock.x()) 会真实触发旧 stub 抛异常
        org.mockito.Mockito.doReturn("glm-5/rub123").when(judgeClient).judgeFingerprint();
        when(staticAssembler.assemble("w-1")).thenReturn(List.of());
        when(recordRepository.listByRunId(anyString(), isNull(), isNull())).thenReturn(List.of());
        String second = service.startRun(EvalRun.LAYER_STATIC, "w-1", null, null);

        assertThat(second).isNotEqualTo(first);
        ArgumentCaptor<EvalRun> runCaptor = ArgumentCaptor.forClass(EvalRun.class);
        verify(runRepository, times(2)).save(runCaptor.capture());
        assertThat(runCaptor.getAllValues()).extracting(EvalRun::getId)
                .containsExactly(first, second);
        verify(runRepository, timeout(3000)).markDone(eq(second), anyString(), eq(0L), eq(0L));
    }

    // ==================== executeRun：同步驱动的批跑链路 ====================

    @Test
    void executeRun_静态层全链路_落库字段与汇总结构() throws Exception {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of(staticCase()));
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(l1CheckRunner.run(any())).thenReturn(List.of(
                new L1Warning(L1Warning.CHECKER_HARD_FACTS, L1Warning.TURN_NONE, "疑似矛盾", "证据")));
        when(judgeClient.judge(any(), any())).thenReturn(new JudgeOutcome(null, List.of(
                new JudgeJudgment(EvalRecord.SUB_HARD_FACTS, EvalRecord.VERDICT_PASS,
                        "硬事实齐备", List.of(new JudgeJudgment.Evidence(-1, "身份")), null),
                new JudgeJudgment(EvalRecord.SUB_MOTIVATION, EvalRecord.VERDICT_FAIL,
                        "无证据", List.of(), "证据核验失败：无证据判分作废"))));
        // summarize 统计源 = 落库查回（模拟 repository 逐条 insert 后的回读）
        when(recordRepository.listByRunId(eq("run-1"), isNull(), isNull())).thenReturn(List.of(
                record(EvalRecord.SUB_HARD_FACTS, EvalRecord.VERDICT_PASS, null),
                record(EvalRecord.SUB_MOTIVATION, null, "证据核验失败：无证据判分作废")));

        service.executeRun("run-1", EvalRun.LAYER_STATIC, "w-1", null, null);

        // record 逐条落库：判分条目 2 条；核验失败条目 verdict 不落、invalidReason 挂核验原因
        ArgumentCaptor<EvalRecord> recCaptor = ArgumentCaptor.forClass(EvalRecord.class);
        verify(recordRepository, times(2)).insert(recCaptor.capture());
        EvalRecord pass = recCaptor.getAllValues().get(0);
        assertThat(pass.getSubDimension()).isEqualTo(EvalRecord.SUB_HARD_FACTS);
        assertThat(pass.getVerdict()).isEqualTo(EvalRecord.VERDICT_PASS);
        assertThat(pass.getInvalidReason()).isNull();
        assertThat(pass.getDimension()).isEqualTo(EvalRecord.DIMENSION_CHARACTER_CONSISTENCY);
        assertThat(pass.getSubject()).isEqualTo("kagetoro");
        assertThat(pass.getSubjectLabel()).isEqualTo("景虎");
        assertThat(pass.getJudgeFingerprint()).isEqualTo("glm-5/rub123");
        assertThat(pass.getL1WarningsJson()).contains("疑似矛盾");
        assertThat(pass.getEvidenceJson()).contains("身份");
        assertThat(pass.getId()).isNotBlank();
        EvalRecord invalid = recCaptor.getAllValues().get(1);
        assertThat(invalid.getVerdict()).isNull();
        assertThat(invalid.getInvalidReason()).contains("证据核验失败");

        // 汇总：分 sub-dimension 计数 + invalid 单列 + 作废率超阈值警示（1/2 = 50% ≥ 20%）
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(runRepository).markDone(eq("run-1"), summaryCaptor.capture(), eq(0L), eq(0L));
        JsonNode summary = JSON.readTree(summaryCaptor.getValue());
        assertThat(summary.path(EvalRecord.SUB_HARD_FACTS).path("pass").asInt()).isEqualTo(1);
        assertThat(summary.path(EvalRecord.SUB_MOTIVATION).path("invalid").asInt()).isEqualTo(1);
        assertThat(summary.path("total").asInt()).isEqualTo(2);
        assertThat(summary.path("invalid").asInt()).isEqualTo(1);
        assertThat(summary.path("invalidRatePct").asDouble()).isEqualTo(50.0);
        assertThat(summary.path("invalidRateWarn").asBoolean()).isTrue();
        verify(runRepository, never()).markFailed(anyString());
    }

    @Test
    void executeRun_case级作废_单条invalid_record进case键() throws Exception {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of(staticCase()));
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(l1CheckRunner.run(any())).thenReturn(List.of());
        when(judgeClient.judge(any(), any()))
                .thenReturn(JudgeOutcome.invalid("judge 调用/解析失败（重试一次仍失败）"));
        when(recordRepository.listByRunId(eq("run-2"), isNull(), isNull())).thenReturn(List.of(
                record(null, null, "judge 调用/解析失败（重试一次仍失败）")));

        service.executeRun("run-2", EvalRun.LAYER_STATIC, "w-1", null, null);

        // case 级作废 → 1 条 subDimension/verdict 均 null 的 record
        ArgumentCaptor<EvalRecord> recCaptor = ArgumentCaptor.forClass(EvalRecord.class);
        verify(recordRepository, times(1)).insert(recCaptor.capture());
        assertThat(recCaptor.getValue().getSubDimension()).isNull();
        assertThat(recCaptor.getValue().getVerdict()).isNull();
        assertThat(recCaptor.getValue().getInvalidReason()).contains("judge");
        assertThat(recCaptor.getValue().getL1WarningsJson()).isNull();

        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        verify(runRepository).markDone(eq("run-2"), summaryCaptor.capture(), eq(0L), eq(0L));
        JsonNode summary = JSON.readTree(summaryCaptor.getValue());
        assertThat(summary.path("case").path("invalid").asInt()).isEqualTo(1);
        assertThat(summary.path("total").asInt()).isEqualTo(1);
        assertThat(summary.path("invalidRatePct").asDouble()).isEqualTo(100.0);
    }

    @Test
    void executeRun_整体异常中断_markFailed保留已落record() {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of(staticCase()));
        when(judgeClient.judgeFingerprint()).thenThrow(new IllegalStateException("执行期配置失效"));

        service.executeRun("run-4", EvalRun.LAYER_STATIC, "w-1", null, null);

        verify(runRepository).markFailed("run-4");
        verify(runRepository, never()).markDone(anyString(), anyString(), anyLong(), anyLong());
        // 已落 record 保留：repository 无删除 API（schema 设计保证），此处钉住无清理调用
        verify(recordRepository, never()).findById(anyString());
    }

    @Test
    void executeRun_动态层窗覆盖透传装配器() {
        when(dynamicAssembler.assemble("gs-1", 7)).thenReturn(List.of());
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(recordRepository.listByRunId(eq("run-5"), isNull(), isNull())).thenReturn(List.of());

        service.executeRun("run-5", EvalRun.LAYER_DYNAMIC, null, "gs-1", 7);
        verify(dynamicAssembler).assemble("gs-1", 7);

        // 窗覆盖缺省（null）→ 装配器回落配置默认
        when(recordRepository.listByRunId(eq("run-6"), isNull(), isNull())).thenReturn(List.of());
        service.executeRun("run-6", EvalRun.LAYER_DYNAMIC, null, "gs-1", null);
        verify(dynamicAssembler).assemble("gs-1", null);
    }

    @Test
    void executeRun_成本汇总按metering_judge明细入账() {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of());
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(recordRepository.listByRunId(eq("run-cost"), isNull(), isNull())).thenReturn(List.of());
        EvalRun fresh = new EvalRun();
        fresh.setId("run-cost");
        fresh.setCreatedAt(LocalDateTime.of(2026, 10, 22, 10, 0));
        when(runRepository.findById("run-cost")).thenReturn(fresh);

        AtomicReference<Object[]> sqlArgs = new AtomicReference<>();
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    sqlArgs.set(inv.getArguments());
                    RowMapper<?> mapper = inv.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong(1)).thenReturn(4000L);
                    when(rs.getLong(2)).thenReturn(50000L);
                    return mapper.mapRow(rs, 0);
                });

        service.executeRun("run-cost", EvalRun.LAYER_STATIC, "w-1", null, null);

        // run 汇总收到 metering 查回的 tokens/金额；查询按 call_type=judge + run 起点时段
        verify(runRepository).markDone(eq("run-cost"), anyString(), eq(4000L), eq(50000L));
        Object[] args = sqlArgs.get();
        assertThat(args[2]).isEqualTo("t-1");
        assertThat(args[3]).isEqualTo(JudgeClient.ROLE_JUDGE);
        assertThat(args[4]).isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 0));
    }

    @Test
    void executeRun_成本查询失败_降级记零不阻断收口() {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of());
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(recordRepository.listByRunId(eq("run-7"), isNull(), isNull())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new RuntimeException("表不存在"));

        service.executeRun("run-7", EvalRun.LAYER_STATIC, "w-1", null, null);

        verify(runRepository).markDone(eq("run-7"), anyString(), eq(0L), eq(0L));
        verify(runRepository, never()).markFailed(anyString());
    }

    @Test
    void executeRun_judge并发2_两单元并行都处理() {
        evalProperties.setJudgeConcurrency(2);
        when(staticAssembler.assemble("w-1")).thenReturn(List.of(
                staticCase(), new EvalCase(EvalRun.LAYER_STATIC, EvalCase.SOURCE_DESTINED,
                        "harunobu", "晴信", "{}", "{}", List.of("晴信"),
                        null, null, null, null, null, null)));
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(l1CheckRunner.run(any())).thenReturn(List.of());
        when(judgeClient.judge(any(), any())).thenReturn(new JudgeOutcome(null, List.of(
                new JudgeJudgment(EvalRecord.SUB_SPEECH_STYLE, EvalRecord.VERDICT_PASS,
                        "风格稳定", List.of(), null))));
        when(recordRepository.listByRunId(eq("run-c"), isNull(), isNull())).thenReturn(List.of());

        service.executeRun("run-c", EvalRun.LAYER_STATIC, "w-1", null, null);

        verify(judgeClient, times(2)).judge(any(), any());
        verify(recordRepository, times(2)).insert(any(EvalRecord.class));
        verify(runRepository).markDone(eq("run-c"), anyString(), eq(0L), eq(0L));
    }

    // ==================== 复核域编排：分歧检测与回归集消费（tasks 7.1 / 7.3 / 7.4） ====================

    @Test
    void executeRun_分歧检测_跨run不一致两端标记同组() {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of());
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(recordRepository.listByRunId(eq("run-d"), isNull(), isNull())).thenReturn(List.of());
        // 本次 run 与历史 run（不同 judge 指纹）同对齐键 verdict 不一致 → 一对分歧
        when(recordRepository.findDivergences("run-d")).thenReturn(List.of(
                new EvalRecordRepository.DivergencePair("rec-cur", "rec-old",
                        "kagetoro", "character-consistency",
                        "{\"figureKey\":\"kagetoro\"}")));

        service.executeRun("run-d", EvalRun.LAYER_STATIC, "w-1", null, null);

        // 两端进同一分歧组（组键 = 对齐键 SHA-256 前 16 位，由 repository 幂等落组）
        verify(recordRepository).markDivergence(divergenceIdsCaptor.capture(), anyString());
        assertThat(divergenceIdsCaptor.getValue())
                .containsExactlyInAnyOrder("rec-cur", "rec-old");
        // 分歧检测先于收口（队列可见性）且不阻断批跑完成
        verify(runRepository).markDone(eq("run-d"), anyString(), eq(0L), eq(0L));
        verify(runRepository, never()).markFailed(anyString());
    }

    @Test
    void executeRun_分歧检测失败跳过不阻断收口() {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of());
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(recordRepository.listByRunId(eq("run-df"), isNull(), isNull())).thenReturn(List.of());
        when(recordRepository.findDivergences("run-df")).thenThrow(new RuntimeException("查询失败"));

        service.executeRun("run-df", EvalRun.LAYER_STATIC, "w-1", null, null);

        // 复核是增强非主流程：检测异常跳过，批跑照常 done
        verify(recordRepository, never()).markDivergence(any(), anyString());
        verify(runRepository).markDone(eq("run-df"), anyString(), eq(0L), eq(0L));
        verify(runRepository, never()).markFailed(anyString());
    }

    @Test
    void executeRun_回归集subject标记_批跑必含() {
        when(staticAssembler.assemble("w-1")).thenReturn(List.of(
                staticCase(),
                new EvalCase(EvalRun.LAYER_STATIC, EvalCase.SOURCE_DESTINED,
                        "harunobu", "晴信", "{}", "{}", List.of("晴信"),
                        null, null, null, null, null, null)));
        when(judgeClient.judgeFingerprint()).thenReturn("glm-5/rub123");
        when(l1CheckRunner.run(any())).thenReturn(List.of());
        when(judgeClient.judge(any(), any())).thenReturn(new JudgeOutcome(null, List.of(
                new JudgeJudgment(EvalRecord.SUB_HARD_FACTS, EvalRecord.VERDICT_PASS,
                        "硬事实齐备", List.of(), null))));
        // 回归集清单（人工复核沉淀）：kagetoro 在集，harunobu 不在
        when(recordRepository.listRegressionSubjects()).thenReturn(List.of("kagetoro"));
        when(recordRepository.listByRunId(eq("run-r"), isNull(), isNull())).thenReturn(List.of());

        service.executeRun("run-r", EvalRun.LAYER_STATIC, "w-1", null, null);

        ArgumentCaptor<EvalRecord> recCaptor = ArgumentCaptor.forClass(EvalRecord.class);
        verify(recordRepository, times(2)).insert(recCaptor.capture());
        // 回归用例标记 true（后续批跑必含）；非回归 subject 保持 false
        assertThat(recCaptor.getAllValues().get(0).getSubject()).isEqualTo("kagetoro");
        assertThat(recCaptor.getAllValues().get(0).getIsRegression()).isTrue();
        assertThat(recCaptor.getAllValues().get(1).getSubject()).isEqualTo("harunobu");
        assertThat(recCaptor.getAllValues().get(1).getIsRegression()).isFalse();
    }

    // ==================== 辅助构造 ====================

    private static EvalCase staticCase() {
        return new EvalCase(EvalRun.LAYER_STATIC, EvalCase.SOURCE_DESTINED, "kagetoro", "景虎",
                "{\"source\":\"destined\",\"subject\":\"kagetoro\",\"worldId\":\"w-1\"}",
                "{}", List.of("景虎"), null, null, null, null, null, null);
    }

    private static EvalRecord record(String sub, String verdict, String invalidReason) {
        EvalRecord r = new EvalRecord();
        r.setSubDimension(sub);
        r.setVerdict(verdict);
        r.setInvalidReason(invalidReason);
        return r;
    }
}
