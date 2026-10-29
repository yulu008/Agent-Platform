package com.luyu.agent.rpg.eval.repository;

import java.util.List;

import com.luyu.agent.rpg.eval.config.EvalSchemaInitializer;
import com.luyu.agent.rpg.eval.model.EvalRecord;
import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.rpg.eval.service.EvalReviewService;
import com.luyu.agent.rpg.eval.service.PromptHashService;
import com.luyu.agent.rpg.eval.engine.PromptHashCalculator;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luyu.agent.tenancy.TenantGuardConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评估表建表幂等 + run/record 仓储读写 + 租户隔离 + 人工复核域测试
 * （content-eval tasks 1.5 / 7.4，参照 {@code SharedStoryGraphEndToEndTest}：
 * {@code @JdbcTest} + 真实 H2 + @Import 装配 schema 初始化器与仓储）。
 * <p>
 * 钉住四组语义：
 * <ol>
 *   <li><b>建表幂等</b>：容器期 {@code @Bean} 钩子已建表一次，测试内再执行
 *       schema 脚本与 AI_SESSION_EVENT 加列各一次——重复执行不抛不报错
 *       （spec「启动重跑不破坏数据」）。</li>
 *   <li><b>run 状态机与 record 过滤</b>：running→done/failed 状态流转、
 *       汇总与成本落列、verdict/subject/invalid 过滤。</li>
 *   <li><b>租户隔离</b>：每条读写 SQL 绑 {@code tid()}，A 租户不可见 B 租户。</li>
 *   <li><b>人工复核域（tasks 7.4，design D10）</b>：borderline 自动入队且
 *       收口后出队；跨 run 不同 judge 指纹 verdict 不一致两端标记同分歧组；
 *       复核终裁 override 保留机器原判、fail 沉淀回归集 subject 清单。</li>
 * </ol>
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, EvalSchemaInitializer.class,
        EvalRunRepository.class, EvalRecordRepository.class,
        PromptHashCalculator.class, PromptHashService.class,
        EvalReviewService.class})
class EvalSchemaAndRepositoryTest {

    private static final String TID_A = "tenant-a";
    private static final String TID_B = "tenant-b";

    @Autowired
    private EvalSchemaInitializer schemaInitializer;

    @Autowired
    private EvalRunRepository runRepo;

    @Autowired
    private EvalRecordRepository recordRepo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PromptHashService promptHashService;

    @Autowired
    private EvalReviewService reviewService;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TID_A);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private EvalRun run(String id) {
        EvalRun run = new EvalRun();
        run.setId(id);
        run.setLayer(EvalRun.LAYER_STATIC);
        run.setScopeJson("{\"worldId\":\"preset-adventure\"}");
        run.setJudgeModel("glm-5.1");
        run.setRubricVersion("rubric-v1");
        run.setStatus(EvalRun.STATUS_RUNNING);
        return run;
    }

    private EvalRecord record(String id, String runId, String subject, String verdict, String invalidReason) {
        return record(id, runId, subject, verdict, invalidReason, "glm-5.1|rubric-v1");
    }

    /** 带 judge 指纹重载（分歧检测需同对齐键不同指纹构造双 run 数据） */
    private EvalRecord record(String id, String runId, String subject, String verdict,
                              String invalidReason, String judgeFingerprint) {
        return record(id, runId, subject, verdict, invalidReason, judgeFingerprint, EvalRecord.SUB_HARD_FACTS);
    }

    /** 带子维度重载（同 unit 多子维度行，分歧对齐的 join 粒度验证） */
    private EvalRecord record(String id, String runId, String subject, String verdict,
                              String invalidReason, String judgeFingerprint, String subDimension) {
        EvalRecord r = new EvalRecord();
        r.setId(id);
        r.setRunId(runId);
        r.setDimension(EvalRecord.DIMENSION_CHARACTER_CONSISTENCY);
        r.setSubDimension(subDimension);
        r.setSubject(subject);
        r.setSubjectLabel(subject);
        r.setUnitJson("{\"figureKey\":\"" + subject + "\"}");
        r.setVerdict(verdict);
        r.setEvidenceJson("[{\"turn\":3,\"span\":\"圣衣箱纹丝不动\"}]");
        r.setRationale("硬事实与档案一致");
        r.setInvalidReason(invalidReason);
        r.setTargetFingerprint("prompt:ab12cd34ef56ab12");
        r.setJudgeFingerprint(judgeFingerprint);
        return r;
    }

    @Test
    void 建表脚本重复执行幂等不抛() {
        // 容器期 @Bean 钩子已建表一次（@Import 装配即触发）；再跑一次脚本
        assertThatCode(() -> schemaInitializer.evalSchemaInitializationHook())
                .doesNotThrowAnyException();
        // 表仍可用（INSERT 成功即结构完好，无重建破坏）
        runRepo.save(run("run-idem"));
        assertThat(runRepo.findById("run-idem")).isNotNull();
    }

    @Test
    void AI_SESSION_EVENT加列重复执行幂等且不阻断() {
        // @JdbcTest 的 H2 无 spring-ai 建表，先手工造简表模拟就绪状态（主键列名对齐真实表 id）
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS AI_SESSION_EVENT " +
                "(id VARCHAR(64) PRIMARY KEY)");
        // 两次加列（ApplicationReady 钩子逻辑直调）：第二次列已存在，幂等不抛
        assertThatCode(() -> {
            schemaInitializer.addEventPromptHashColumn();
            schemaInitializer.addEventPromptHashColumn();
        }).doesNotThrowAnyException();
        // 列已就绪：带 prompt_hash 的 INSERT 可写入（存量 NULL 语义由可空列保证）
        jdbcTemplate.update("INSERT INTO AI_SESSION_EVENT (id, prompt_hash) VALUES (?, ?)",
                "ev-1", "ab12cd34ef56ab12");
        String hash = jdbcTemplate.queryForObject(
                "SELECT prompt_hash FROM AI_SESSION_EVENT WHERE id = ?", String.class, "ev-1");
        assertThat(hash).isEqualTo("ab12cd34ef56ab12");
    }

    @Test
    void run状态机running到done落汇总与成本() {
        runRepo.save(run("run-1"));

        EvalRun before = runRepo.findById("run-1");
        assertThat(before.getStatus()).isEqualTo(EvalRun.STATUS_RUNNING);
        assertThat(before.getFinishedAt()).isNull();

        runRepo.markDone("run-1",
                "{\"hard-facts\":{\"pass\":3,\"borderline\":1,\"fail\":0,\"invalid\":1}}",
                12345L, 6789L);

        EvalRun after = runRepo.findById("run-1");
        assertThat(after.getStatus()).isEqualTo(EvalRun.STATUS_DONE);
        assertThat(after.getSummaryJson()).contains("hard-facts");
        assertThat(after.getCostTokens()).isEqualTo(12345L);
        assertThat(after.getCostAmountMicro()).isEqualTo(6789L);
        assertThat(after.getFinishedAt()).isNotNull();
    }

    @Test
    void run失败收口保留已落record() {
        runRepo.save(run("run-2"));
        recordRepo.insert(record("rec-2a", "run-2", "kagetora", EvalRecord.VERDICT_PASS, null));

        runRepo.markFailed("run-2");

        assertThat(runRepo.findById("run-2").getStatus()).isEqualTo(EvalRun.STATUS_FAILED);
        // 中断不回滚：已落 record 保留（spec「批跑中断保留部分结果」）
        assertThat(recordRepo.listByRunId("run-2", null, null)).hasSize(1);
    }

    @Test
    void record支持verdict与invalid与subject过滤() {
        runRepo.save(run("run-3"));
        recordRepo.insert(record("rec-3a", "run-3", "kagetora", EvalRecord.VERDICT_PASS, null));
        recordRepo.insert(record("rec-3b", "run-3", "kagetora", EvalRecord.VERDICT_BORDERLINE, null));
        recordRepo.insert(record("rec-3c", "run-3", "harunobu", null, "judge 幻觉：证据 span 无法定位"));
        recordRepo.insert(record("rec-3d", "run-3", "harunobu", EvalRecord.VERDICT_PASS, null));

        assertThat(recordRepo.listByRunId("run-3", null, null)).hasSize(4);
        assertThat(recordRepo.listByRunId("run-3", EvalRecord.VERDICT_BORDERLINE, null))
                .extracting(EvalRecord::getId).containsExactly("rec-3b");
        // invalid 特殊过滤：invalid_reason 非空即作废行（报告单列作废计数）
        assertThat(recordRepo.listByRunId("run-3", "invalid", null))
                .extracting(EvalRecord::getId).containsExactly("rec-3c");
        assertThat(recordRepo.listByRunId("run-3", null, "kagetora")).hasSize(2);

        EvalRecord byId = recordRepo.findById("rec-3b");
        assertThat(byId.getVerdict()).isEqualTo(EvalRecord.VERDICT_BORDERLINE);
        // insert 缺省：review_status 落 pending、is_regression 落 false
        assertThat(byId.getReviewStatus()).isEqualTo(EvalRecord.REVIEW_PENDING);
        assertThat(byId.getIsRegression()).isFalse();
        assertThat(recordRepo.findById("rec-x")).isNull();
    }

    @Test
    void prompt_hash附着真实资源指纹且存量NULL行读取不抛() {
        // 简表主键列名对齐真实 AI_SESSION_EVENT（id）；两用例共享同一 H2，
        // 列名一致保证执行顺序无关
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS AI_SESSION_EVENT " +
                "(id VARCHAR(64) PRIMARY KEY)");
        // 用例自足：加列 DDL 幂等（不依赖加列用例先执行）
        jdbcTemplate.execute("ALTER TABLE AI_SESSION_EVENT ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(16)");
        jdbcTemplate.update("INSERT INTO AI_SESSION_EVENT (id) VALUES (?)", "ev-new");
        jdbcTemplate.update("INSERT INTO AI_SESSION_EVENT (id) VALUES (?)", "ev-legacy");

        promptHashService.attachToEvent("ev-new");

        // 新事件：真实 classpath GM 资源指纹（16 位 hex）已落列
        String attached = jdbcTemplate.queryForObject(
                "SELECT prompt_hash FROM AI_SESSION_EVENT WHERE id = ?", String.class, "ev-new");
        assertThat(attached).isNotNull().hasSize(16).matches("[0-9a-f]{16}");
        // 存量事件：未附着保持 NULL，读取不抛（spec「存量事件 NULL 容忍」）
        String legacy = jdbcTemplate.queryForObject(
                "SELECT prompt_hash FROM AI_SESSION_EVENT WHERE id = ?", String.class, "ev-legacy");
        assertThat(legacy).isNull();
    }

    @Test
    void 切换租户后A的数据对B不可见() {
        runRepo.save(run("run-4"));
        recordRepo.insert(record("rec-4a", "run-4", "kagetora", EvalRecord.VERDICT_PASS, null));
        // 复核队列行（borderline）：复核域读写的租户隔离一并钉住
        recordRepo.insert(record("rec-4b", "run-4", "kagetora", EvalRecord.VERDICT_BORDERLINE, null));

        TenantContext.set("u-other", TID_B);
        assertThat(runRepo.findById("run-4")).isNull();
        assertThat(runRepo.listRecent(10)).isEmpty();
        assertThat(recordRepo.listByRunId("run-4", null, null)).isEmpty();
        assertThat(recordRepo.findById("rec-4a")).isNull();
        // 复核域查询与写路径同样绑 tid()：B 租户队空、回归集空、分歧空、跨租户收口 0 行
        assertThat(recordRepo.listReviewQueue(10)).isEmpty();
        assertThat(recordRepo.listRegressionSubjects()).isEmpty();
        assertThat(recordRepo.findDivergences("run-4")).isEmpty();
        assertThat(recordRepo.applyReview("rec-4b", EvalRecord.REVIEW_OVERRIDE,
                EvalRecord.VERDICT_FAIL, false)).isFalse();

        TenantContext.set("u-test", TID_A);
        assertThat(runRepo.findById("run-4")).isNotNull();
        assertThat(runRepo.listRecent(10)).extracting(EvalRun::getId).containsExactly("run-4");
        // B 租户的跨租户收口未写入 A 租户数据：仍是 pending 且在队列
        assertThat(recordRepo.listReviewQueue(10))
                .extracting(EvalRecord::getId).containsExactly("rec-4b");
        assertThat(reviewService.review("rec-x", EvalRecord.VERDICT_PASS, false)).isNull();
    }

    // ==================== 人工复核域（tasks 7.4，design D10） ====================

    @Test
    void borderline自动入队_其余verdict与收口后行不入队() {
        runRepo.save(run("run-q"));
        recordRepo.insert(record("rec-q1", "run-q", "kagetora", EvalRecord.VERDICT_BORDERLINE, null));
        recordRepo.insert(record("rec-q2", "run-q", "kagetora", EvalRecord.VERDICT_PASS, null));
        recordRepo.insert(record("rec-q3", "run-q", "kagetora", EvalRecord.VERDICT_FAIL, null));
        recordRepo.insert(record("rec-q4", "run-q", "kagetora", null, "judge 幻觉：证据 span 无法定位"));

        // 队列 = pending 且（borderline 或 divergence_group 非空）：仅 borderline 自动入队
        //（pass/fail 无分歧标记不入队；作废行 verdict NULL 不入队）
        assertThat(recordRepo.listReviewQueue(10))
                .extracting(EvalRecord::getId).containsExactly("rec-q1");

        // 复核收口后出队（review_status 离开 pending）
        recordRepo.applyReview("rec-q1", EvalRecord.REVIEW_CONFIRMED,
                EvalRecord.VERDICT_BORDERLINE, false);
        assertThat(recordRepo.listReviewQueue(10)).isEmpty();
    }

    @Test
    void 跨run不同judge指纹verdict不一致_两端标记同分歧组入队() {
        runRepo.save(run("run-h1"));
        runRepo.save(run("run-h2"));
        // 历史 run：judge A 判 pass
        recordRepo.insert(record("rec-h1", "run-h1", "kagetora", EvalRecord.VERDICT_PASS,
                null, "glm-5.1|rubric-v1"));
        // 本次 run：judge B 同对齐键判 fail；同 run 同指纹另一 subject 干扰行
        recordRepo.insert(record("rec-h2", "run-h2", "kagetora", EvalRecord.VERDICT_FAIL,
                null, "glm-5.2|rubric-v1"));
        recordRepo.insert(record("rec-h3", "run-h2", "harunobu", EvalRecord.VERDICT_PASS,
                null, "glm-5.2|rubric-v1"));

        // 本次 run（run-h2）视角：与历史 run（不同 judge 指纹）verdict 不一致 → 一对
        List<EvalRecordRepository.DivergencePair> pairs = recordRepo.findDivergences("run-h2");
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).currentId()).isEqualTo("rec-h2");
        assertThat(pairs.get(0).otherId()).isEqualTo("rec-h1");
        // 反向视角（run-h1）同样拉出该对（对称检测，两端都可见）
        assertThat(recordRepo.findDivergences("run-h1")).hasSize(1);

        // 两端标记同组 → 双双入队（fail/pass 行原不在队列，分歧标记后入队——两通道合一）
        recordRepo.markDivergence(List.of(pairs.get(0).currentId(), pairs.get(0).otherId()), "div-key");
        assertThat(recordRepo.findById("rec-h1").getDivergenceGroup()).isEqualTo("div-key");
        assertThat(recordRepo.findById("rec-h2").getDivergenceGroup()).isEqualTo("div-key");
        assertThat(recordRepo.findById("rec-h3").getDivergenceGroup()).isNull();
        assertThat(recordRepo.listReviewQueue(10))
                .extracting(EvalRecord::getId).containsExactlyInAnyOrder("rec-h1", "rec-h2");
        // 标记后重查仍返回该对（幂等：检测不因已标记而跳过，markDivergence 落同值幂等）
        assertThat(recordRepo.findDivergences("run-h2")).hasSize(1);
    }

    @Test
    void 同judge指纹或verdict一致不触发分歧() {
        runRepo.save(run("run-s1"));
        runRepo.save(run("run-s2"));
        // 不同 run 同 judge 指纹同对齐键 verdict 不一致——同模型重跑漂移非 judge 间分歧
        recordRepo.insert(record("rec-s1", "run-s1", "kagetora", EvalRecord.VERDICT_PASS,
                null, "glm-5.1|rubric-v1"));
        recordRepo.insert(record("rec-s2", "run-s2", "kagetora", EvalRecord.VERDICT_FAIL,
                null, "glm-5.1|rubric-v1"));
        // 不同 judge 指纹同对齐键但 verdict 一致——判分一致无需复核
        recordRepo.insert(record("rec-s3", "run-s1", "harunobu", EvalRecord.VERDICT_PASS,
                null, "glm-5.1|rubric-v1"));
        recordRepo.insert(record("rec-s4", "run-s2", "harunobu", EvalRecord.VERDICT_PASS,
                null, "glm-5.2|rubric-v1"));

        assertThat(recordRepo.findDivergences("run-s1")).isEmpty();
        assertThat(recordRepo.findDivergences("run-s2")).isEmpty();
    }

    @Test
    void 同unit不同子维度不交叉配对_仅同命题分歧入对() {
        // 9.3 端到端发现的过捕修复：同一评估单元（unit）落多行子维度判分，
        // judge A 判 relations=borderline 与 judge B 判 hard-facts/motivation=pass
        // 非同一命题——不构成分歧；只有同子维度（relations）两端 verdict 不一致才入对
        runRepo.save(run("run-c1"));
        runRepo.save(run("run-c2"));
        // 历史 run（judge A）：hard-facts=pass + relations=borderline（判分分歧端）
        recordRepo.insert(record("rec-c1", "run-c1", "kagetora", EvalRecord.VERDICT_PASS,
                null, "glm-5.1|rubric-v1", EvalRecord.SUB_HARD_FACTS));
        recordRepo.insert(record("rec-c2", "run-c1", "kagetora", EvalRecord.VERDICT_BORDERLINE,
                null, "glm-5.1|rubric-v1", EvalRecord.SUB_RELATIONS));
        // 本次 run（judge B）同 unit 三个子维度全部 pass
        recordRepo.insert(record("rec-c3", "run-c2", "kagetora", EvalRecord.VERDICT_PASS,
                null, "glm-5.2|rubric-v1", EvalRecord.SUB_HARD_FACTS));
        recordRepo.insert(record("rec-c4", "run-c2", "kagetora", EvalRecord.VERDICT_PASS,
                null, "glm-5.2|rubric-v1", EvalRecord.SUB_RELATIONS));
        recordRepo.insert(record("rec-c5", "run-c2", "kagetora", EvalRecord.VERDICT_PASS,
                null, "glm-5.2|rubric-v1", EvalRecord.SUB_MOTIVATION));

        // 仅 relations 一对（rec-c4 vs rec-c2）：hard-facts 两端同 pass 不入对；
        // motivation 只在本次 run 有，与历史 relations=borderline 不同命题不交叉
        List<EvalRecordRepository.DivergencePair> pairs = recordRepo.findDivergences("run-c2");
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).currentId()).isEqualTo("rec-c4");
        assertThat(pairs.get(0).otherId()).isEqualTo("rec-c2");
        // 反向视角同样只有一对
        assertThat(recordRepo.findDivergences("run-c1")).hasSize(1);
    }

    @Test
    void 复核终裁override保留机器原判_fail沉淀回归集() {
        runRepo.save(run("run-o"));
        recordRepo.insert(record("rec-o1", "run-o", "kagetora", EvalRecord.VERDICT_PASS, null));
        recordRepo.insert(record("rec-o2", "run-o", "nobukatsu", EvalRecord.VERDICT_FAIL, null));

        // 人工推翻（机器 pass → 终裁 fail）+ 沉淀回归集
        EvalRecord overridden = reviewService.review("rec-o1", EvalRecord.VERDICT_FAIL, true);
        assertThat(overridden.getReviewStatus()).isEqualTo(EvalRecord.REVIEW_OVERRIDE);
        assertThat(overridden.getReviewFinalVerdict()).isEqualTo(EvalRecord.VERDICT_FAIL);
        // 机器原判保留于 verdict 列不覆盖（spec「override 保留机器原判」）
        assertThat(overridden.getVerdict()).isEqualTo(EvalRecord.VERDICT_PASS);
        assertThat(overridden.getIsRegression()).isTrue();

        // 人工确认（机器 fail → 终裁 fail）不沉淀
        EvalRecord confirmed = reviewService.review("rec-o2", EvalRecord.VERDICT_FAIL, false);
        assertThat(confirmed.getReviewStatus()).isEqualTo(EvalRecord.REVIEW_CONFIRMED);
        assertThat(confirmed.getReviewFinalVerdict()).isEqualTo(EvalRecord.VERDICT_FAIL);
        assertThat(confirmed.getVerdict()).isEqualTo(EvalRecord.VERDICT_FAIL);
        assertThat(confirmed.getIsRegression()).isFalse();

        // 回归集 subject 清单：仅 override 且沉淀的 kagetora（后续批跑必含）
        assertThat(recordRepo.listRegressionSubjects()).containsExactly("kagetora");
    }

    @Test
    void 复核终裁校验拒绝不落库_记录不存在返回null() {
        runRepo.save(run("run-v"));
        recordRepo.insert(record("rec-v1", "run-v", "kagetora", EvalRecord.VERDICT_PASS, null));

        assertThatThrownBy(() -> reviewService.review("rec-v1", "excellent", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalVerdict");
        assertThatThrownBy(() -> reviewService.review("rec-v1", EvalRecord.VERDICT_PASS, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail");
        // 校验拒绝不落库：review_status 仍 pending
        assertThat(recordRepo.findById("rec-v1").getReviewStatus())
                .isEqualTo(EvalRecord.REVIEW_PENDING);
        // 记录不存在（或跨租户）→ null（controller 404 语义）
        assertThat(reviewService.review("rec-x", EvalRecord.VERDICT_PASS, false)).isNull();
    }

    @Test
    void case级作废行subDimension为NULL可落库() {
        runRepo.save(run("run-n"));
        EvalRecord r = record("rec-n1", "run-n", "kagetora", null,
                "judge 调用/解析失败（重试一次仍失败）");
        r.setSubDimension(null); // case 级作废：子维度不适用（schema 7.x 前置修正钉住）
        recordRepo.insert(r);

        EvalRecord saved = recordRepo.findById("rec-n1");
        assertThat(saved.getSubDimension()).isNull();
        assertThat(saved.getInvalidReason()).contains("judge");
        // 作废行 verdict NULL 且无分歧标记 → 不入复核队列
        assertThat(recordRepo.listReviewQueue(10)).isEmpty();
    }
}
