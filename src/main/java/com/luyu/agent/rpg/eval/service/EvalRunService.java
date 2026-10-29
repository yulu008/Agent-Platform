package com.luyu.agent.rpg.eval.service;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 评估批跑编排服务（content-eval tasks 6.1，design D12 / D11）。
 * <p>
 * run 状态机 {@code running → done / failed}：触发线程同步落 running run 并返回
 * id（对齐 planner 长任务惯例——后台虚拟线程执行）；record 逐条落库（非整批
 * 事务），批跑整体异常时已落 record 保留且 run 标 failed（spec「批跑中断保留
 * 部分结果」——再次触发创建新 run，不覆盖旧 run）。
 * <p>
 * 编排链（单 case）：L1 确定性预警（零 LLM）→ judge 裁决（三层防御，业务
 * 异常全部降级 outcome）→ record 逐条落库；单 case 失败跳过不挂批。judge
 * 并发度按 {@code rpg.eval.judge-concurrency}（默认 1 = 串行，D12 预算可控
 * 起步）——池线程无租户 ThreadLocal，逐 case 以 withTenant 恢复（planner-async
 * 同模式）。批跑仅由端点手动触发，不接入发布链路或生成时流程（spec）。
 * <p>
 * 复核域编排（tasks 7.x，design D10）：收口前跨 run 分歧检测（同对齐键
 * 不同 judge 指纹 verdict 分档不一致 → 两端标 divergence_group 入队）；
 * 回归集消费（is_regression subject 的 case 在批跑中标记，后续批跑必含）。
 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);

    private final EvalRunRepository runRepository;
    private final EvalRecordRepository recordRepository;
    private final StaticEvalCaseAssembler staticAssembler;
    private final DynamicEvalCaseAssembler dynamicAssembler;
    private final L1CheckRunner l1CheckRunner;
    private final JudgeClient judgeClient;
    private final RubricLoader rubricLoader;
    private final EvalProperties evalProperties;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public EvalRunService(EvalRunRepository runRepository,
                          EvalRecordRepository recordRepository,
                          StaticEvalCaseAssembler staticAssembler,
                          DynamicEvalCaseAssembler dynamicAssembler,
                          L1CheckRunner l1CheckRunner,
                          JudgeClient judgeClient,
                          RubricLoader rubricLoader,
                          EvalProperties evalProperties,
                          JdbcTemplate jdbcTemplate) {
        this.runRepository = runRepository;
        this.recordRepository = recordRepository;
        this.staticAssembler = staticAssembler;
        this.dynamicAssembler = dynamicAssembler;
        this.l1CheckRunner = l1CheckRunner;
        this.judgeClient = judgeClient;
        this.rubricLoader = rubricLoader;
        this.evalProperties = evalProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 触发批跑：启用预检 → 同步落 running run → 后台虚拟线程执行 → 返回 run id。
     * 未配置 judge（D1）抛 {@link EvalNotEnabledException}——不落 run、不发起
     * 任何 LLM 调用（spec「未配置 judge 时端点明确拒绝」）。
     */
    public String startRun(String layer, String worldId, String gameStateId, Integer windowTurns) {
        if (!judgeClient.isJudgeConfigured()) {
            throw new EvalNotEnabledException();
        }
        String runId = UUID.randomUUID().toString();
        EvalRun run = new EvalRun();
        run.setId(runId);
        run.setLayer(layer);
        run.setScopeJson(scopeJson(layer, worldId, gameStateId, windowTurns));
        run.setJudgeModel(judgeClient.judgeModelName());
        run.setRubricVersion(rubricLoader.version()); // null → DB NULL（rubric 资源缺失降级）
        run.setStatus(EvalRun.STATUS_RUNNING);
        runRepository.save(run);

        // 请求线程捕获租户 → 后台虚拟线程恢复（planner-async 同模式）
        String tenantId = TenantContext.requireTenantId();
        Thread.ofVirtual().name("eval-run-" + runId).start(() -> {
            TenantContext.set("eval-async", tenantId);
            try {
                executeRun(runId, layer, worldId, gameStateId, windowTurns);
            } finally {
                TenantContext.clear();
            }
        });
        return runId;
    }

    // ==================== 查询透传（run 列表 / 详情入口） ====================

    /** 租户 run 列表（倒序取最近 limit 条） */
    public List<EvalRun> listRecent(int limit) {
        return runRepository.listRecent(limit);
    }

    /** 按 id 查 run；不存在返回 null（controller 404 语义） */
    public EvalRun findRun(String runId) {
        return runRepository.findById(runId);
    }

    /** run 明细（verdict/subject 可选过滤；verdict=invalid 单列作废行） */
    public List<EvalRecord> listRecords(String runId, String verdict, String subject) {
        return recordRepository.listByRunId(runId, verdict, subject);
    }

    // ==================== 批跑执行 ====================

    /**
     * 执行批跑（后台虚拟线程；包内可见供测试同步驱动状态机）：
     * 装配 → 逐 case L1 + judge + 落库 → 汇总 + 成本 → done；
     * 整体异常 → failed（已落 record 保留）。
     */
    void executeRun(String runId, String layer, String worldId, String gameStateId, Integer windowTurns) {
        String tenantId = TenantContext.getTenantId();
        try {
            List<EvalCase> cases = EvalRun.LAYER_STATIC.equals(layer)
                    ? staticAssembler.assemble(worldId)
                    : dynamicAssembler.assemble(gameStateId, windowTurns);
            log.info("eval 批跑装配完成: runId={}, layer={}, cases={}", runId, layer, cases.size());
            String judgeFingerprint = judgeClient.judgeFingerprint();
            // 回归集消费（tasks 7.3）：固定用例 subject 清单，批跑必含（静态层
            // 全量装配天然含；动态层出场不足的回归人物无证据不评——不强行造评）
            Set<String> regressionSubjects = loadRegressionSubjects();

            int concurrency = Math.max(1, evalProperties.getJudgeConcurrency());
            if (concurrency == 1 || cases.size() <= 1) {
                for (EvalCase c : cases) {
                    processCase(runId, tenantId, judgeFingerprint, regressionSubjects, c);
                }
            } else {
                runConcurrent(runId, tenantId, judgeFingerprint, regressionSubjects, cases, concurrency);
            }

            // 分歧检测（tasks 7.1）：跨 run 对齐后两端标记，先于汇总（队列可见性）
            detectDivergence(runId);

            String summaryJson = summarize(runId);
            Cost cost = summarizeCost(runId, tenantId);
            runRepository.markDone(runId, summaryJson, cost.tokens(), cost.amountMicro());
            log.info("eval 批跑完成: runId={}, recordsSummary={}, costTokens={}, costAmountMicro={}",
                    runId, summaryJson, cost.tokens(), cost.amountMicro());
        } catch (Exception e) {
            log.error("eval 批跑失败（已落 record 保留）: runId={}", runId, e);
            runRepository.markFailed(runId);
        }
    }

    /** 并发执行（judge 并发度 &gt; 1）：固定池逐 case 提交，池线程租户由 withTenant 恢复 */
    private void runConcurrent(String runId, String tenantId, String judgeFingerprint,
                               Set<String> regressionSubjects, List<EvalCase> cases, int concurrency) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (EvalCase c : cases) {
                futures.add(pool.submit(() ->
                        processCase(runId, tenantId, judgeFingerprint, regressionSubjects, c)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    // processCase 内已全 catch——此路径为防御兜底，单 case 失败不挂批
                    log.warn("eval 单元执行异常（防御兜底）: runId={}", runId, e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("eval 并发等待被中断（停止收拢，整体走 failed 收口）: runId={}", runId, e);
                    break;
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    /**
     * 处理单个评估单元：L1 预警 → judge 裁决 → record 逐条落库（回归集
     * subject 标记）。全程 catch（单 case 失败跳过不挂批）；池线程无租户
     * ThreadLocal → withTenant 恢复（恢复者负责 finally 清理）。
     */
    private void processCase(String runId, String tenantId, String judgeFingerprint,
                             Set<String> regressionSubjects, EvalCase c) {
        withTenant(tenantId, () -> {
            try {
                List<L1Warning> warnings = l1CheckRunner.run(c);
                JudgeOutcome outcome = judgeClient.judge(c, warnings);
                persistRecords(runId, judgeFingerprint, regressionSubjects, c, warnings, outcome);
            } catch (Exception e) {
                log.warn("eval 单元处理失败（跳过该单元）: runId={}, subject={}", runId, c.subject(), e);
            }
        });
    }

    /**
     * 逐条落库（非整批事务，中断保留）：case 级作废 → 1 条 subDimension/verdict
     * 均 null 的 invalid record；判分条目 → 每条一 record（证据核验失败条目
     * verdict 不落、invalidReason 挂核验原因——verdict 三档约束只落合法值）。
     */
    private void persistRecords(String runId, String judgeFingerprint, Set<String> regressionSubjects,
                                EvalCase c, List<L1Warning> warnings, JudgeOutcome outcome) {
        String l1Json = warnings.isEmpty() ? null : StaticEvalCaseAssembler.toJson(warnings);
        boolean regression = regressionSubjects.contains(c.subject());
        if (outcome.caseInvalidReason() != null) {
            EvalRecord record = baseRecord(runId, judgeFingerprint, c);
            record.setL1WarningsJson(l1Json);
            record.setInvalidReason(outcome.caseInvalidReason());
            record.setIsRegression(regression);
            recordRepository.insert(record);
            return;
        }
        for (JudgeJudgment j : outcome.judgments()) {
            EvalRecord record = baseRecord(runId, judgeFingerprint, c);
            record.setL1WarningsJson(l1Json);
            record.setSubDimension(j.subDimension());
            record.setVerdict(j.invalidReason() == null ? j.verdict() : null);
            record.setRationale(j.rationale());
            record.setInvalidReason(j.invalidReason());
            record.setEvidenceJson(toJsonEvidence(j.evidence()));
            record.setIsRegression(regression);
            recordRepository.insert(record);
        }
    }

    private static EvalRecord baseRecord(String runId, String judgeFingerprint, EvalCase c) {
        EvalRecord record = new EvalRecord();
        record.setId(UUID.randomUUID().toString());
        record.setRunId(runId);
        record.setDimension(EvalRecord.DIMENSION_CHARACTER_CONSISTENCY);
        record.setSubject(c.subject());
        record.setSubjectLabel(c.subjectLabel());
        record.setUnitJson(c.unitJson());
        record.setTargetFingerprint(c.targetFingerprint());
        record.setJudgeFingerprint(judgeFingerprint);
        return record;
    }

    private static String toJsonEvidence(List<JudgeJudgment.Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        return StaticEvalCaseAssembler.toJson(evidence);
    }

    // ==================== 汇总与成本 ====================

    /**
     * 分歧检测（tasks 7.1，design D10）：本次 run 与历史 run（不同 judge
     * 指纹）同对齐键（subject+dimension+unit）verdict 分档不一致 → 两端标
     * divergence_group 进复核队列。失败跳过不阻断收口（复核是增强，非主流程）。
     */
    private void detectDivergence(String runId) {
        try {
            List<EvalRecordRepository.DivergencePair> pairs = recordRepository.findDivergences(runId);
            Map<String, List<String>> groupIds = new LinkedHashMap<>();
            for (EvalRecordRepository.DivergencePair p : pairs) {
                groupIds.computeIfAbsent(divergenceGroupKey(p), k -> new ArrayList<>())
                        .addAll(List.of(p.currentId(), p.otherId()));
            }
            for (Map.Entry<String, List<String>> e : groupIds.entrySet()) {
                recordRepository.markDivergence(e.getValue(), e.getKey());
            }
            if (!groupIds.isEmpty()) {
                log.info("eval 分歧检测标记完成: runId={}, 分歧组={}", runId, groupIds.size());
            }
        } catch (Exception e) {
            log.warn("eval 分歧检测失败（跳过）: runId={}", runId, e);
        }
    }

    /** 分歧组键：对齐键（subject+dimension+unit）SHA-256 前 16 位（列宽 120 内，对齐 prompt_hash 惯例） */
    private static String divergenceGroupKey(EvalRecordRepository.DivergencePair p) {
        String raw = p.subject() + "|" + p.dimension() + "|" + p.unitJson();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    /** 回归集 subject 清单（is_regression=true 去重）；读取失败降级空集不阻断 */
    private Set<String> loadRegressionSubjects() {
        try {
            return new HashSet<>(recordRepository.listRegressionSubjects());
        } catch (Exception e) {
            log.warn("回归集清单读取失败（按无回归用例继续）", e);
            return Set.of();
        }
    }

    /**
     * run 汇总（design D11：分 sub-dimension 的 pass/borderline/fail/invalid 计数）。
     * 统计源为已落库 record（落库事实即汇总口径）；invalid 不计入三档统计单列
     * 计数与作废率（spec「无证据判分作废不计入任何汇总统计」），作废率超阈值
     * 标 {@code invalidRateWarn}（judge 或 rubric 需校准，design 风险节）。
     */
    private String summarize(String runId) {
        List<EvalRecord> records = recordRepository.listByRunId(runId, null, null);
        // [pass, borderline, fail, invalid]；case 级作废行（subDimension null）归 "case" 键
        Map<String, int[]> bySub = new LinkedHashMap<>();
        for (EvalRecord r : records) {
            String key = r.getSubDimension() == null ? "case" : r.getSubDimension();
            int[] counts = bySub.computeIfAbsent(key, k -> new int[4]);
            counts[idxOf(r)]++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        int invalid = 0;
        int total = 0;
        for (Map.Entry<String, int[]> e : bySub.entrySet()) {
            int[] counts = e.getValue();
            Map<String, Integer> cell = new LinkedHashMap<>();
            cell.put(EvalRecord.VERDICT_PASS, counts[0]);
            cell.put(EvalRecord.VERDICT_BORDERLINE, counts[1]);
            cell.put(EvalRecord.VERDICT_FAIL, counts[2]);
            cell.put("invalid", counts[3]);
            summary.put(e.getKey(), cell);
            invalid += counts[3];
            total += counts[0] + counts[1] + counts[2] + counts[3];
        }
        summary.put("total", total);
        summary.put("invalid", invalid);
        summary.put("invalidRatePct", total == 0 ? 0 : Math.round(invalid * 1000.0 / total) / 10.0);
        summary.put("invalidRateWarn", total > 0
                && invalid * 100 >= (long) total * evalProperties.getInvalidRateWarnThreshold());
        return StaticEvalCaseAssembler.toJson(summary);
    }

    private static int idxOf(EvalRecord r) {
        if (r.getInvalidReason() != null) {
            return 3;
        }
        return switch (r.getVerdict() == null ? "" : r.getVerdict()) {
            case EvalRecord.VERDICT_PASS -> 0;
            case EvalRecord.VERDICT_BORDERLINE -> 1;
            case EvalRecord.VERDICT_FAIL -> 2;
            default -> 3; // 防御：异常 verdict 按作废口径单列（不应出现——落库前已三档约束）
        };
    }

    /**
     * run 成本汇总：按 metering 明细（{@code token_usage_log}）的
     * {@code call_type='judge'} 行求和，时段下限为本 run 落库时刻（两表
     * created_at 同用 DB CURRENT_TIMESTAMP 时钟，save 后查回）。同租户并发
     * run 有串账可能——管理域手动触发，按串行使用假设（D12 串行起步）。
     */
    private Cost summarizeCost(String runId, String tenantId) {
        try {
            // save 不写 created_at（DB DEFAULT）→ 查回真实起点
            EvalRun fresh = runRepository.findById(runId);
            LocalDateTime since = fresh != null && fresh.getCreatedAt() != null
                    ? fresh.getCreatedAt()
                    : LocalDateTime.now().minusMinutes(5);
            Cost cost = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0), "
                            + "COALESCE(SUM(amount_micro), 0) FROM token_usage_log "
                            + "WHERE tenant_id = ? AND call_type = ? AND created_at >= ?",
                    (rs, i) -> new Cost(rs.getLong(1), rs.getLong(2)),
                    tenantId, JudgeClient.ROLE_JUDGE, since);
            return cost == null ? new Cost(0, 0) : cost;
        } catch (Exception e) {
            log.warn("eval 成本汇总失败（记 0，不阻断收口）: runId={}", runId, e);
            return new Cost(0, 0);
        }
    }

    private record Cost(long tokens, long amountMicro) {
    }

    // ==================== 私有辅助 ====================

    private static String scopeJson(String layer, String worldId, String gameStateId, Integer windowTurns) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("layer", layer);
        if (EvalRun.LAYER_STATIC.equals(layer)) {
            scope.put("worldId", worldId);
        } else {
            scope.put("gameStateId", gameStateId);
            if (windowTurns != null) {
                scope.put("windowTurns", windowTurns);
            }
        }
        return StaticEvalCaseAssembler.toJson(scope);
    }

    /** 租户传播（planner-async 同模式）：当前线程已有上下文时不覆盖不清理。 */
    private void withTenant(String tenantId, Runnable action) {
        boolean restored = false;
        if (!TenantContext.isPresent() && tenantId != null && !tenantId.isBlank()) {
            TenantContext.set("eval-async", tenantId);
            restored = true;
        }
        try {
            action.run();
        } finally {
            if (restored) {
                TenantContext.clear();
            }
        }
    }
}
