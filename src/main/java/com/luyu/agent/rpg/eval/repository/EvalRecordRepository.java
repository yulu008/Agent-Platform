package com.luyu.agent.rpg.eval.repository;

import java.util.ArrayList;
import java.util.List;

import com.luyu.agent.rpg.eval.model.EvalRecord;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 评估 record Repository（对应 rpg_eval_record 表，content-eval tasks 1.3 / 7.x）。
 * <p>
 * record 逐条落库（非整批事务，spec「批跑中断保留部分结果」）。列表查询
 * 支持 verdict / subject 可选过滤（run 详情页接口，参数化绑定防注入）；
 * 全部读写携带租户过滤（多租户列隔离惯例）。
 * <p>
 * 人工复核域（tasks 7.x，design D10）：复核队列 = pending 且（borderline 或
 * 分歧标记）的行；分歧检测 = 本次 run 与历史 run（不同 judge 指纹）同对齐键
 * （subject+dimension+unit）有效 verdict 分档不一致；复核收口 = 终裁写
 * review_final_verdict（override 时机器原判保留于 verdict 列不覆盖）。
 */
@Repository
public class EvalRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    private static String tid() {
        return TenantContext.requireTenantId();
    }

    /** 一对分歧 record（本次端 + 历史端，同对齐键跨 run 聚合） */
    public record DivergencePair(String currentId, String otherId,
                                 String subject, String dimension, String unitJson) {
    }

    @Autowired
    public EvalRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 逐条落库（isRegression 缺省 false；review 缺省 pending） */
    public void insert(EvalRecord record) {
        jdbcTemplate.update(
                "INSERT INTO rpg_eval_record (id, run_id, tenant_id, dimension, sub_dimension, " +
                        "subject, subject_label, unit_json, verdict, evidence_json, rationale, " +
                        "l1_warnings_json, invalid_reason, target_fingerprint, judge_fingerprint, " +
                        "is_regression, review_status, review_final_verdict, divergence_group) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.getId(), record.getRunId(), tid(), record.getDimension(),
                record.getSubDimension(), record.getSubject(), record.getSubjectLabel(),
                record.getUnitJson(), record.getVerdict(), record.getEvidenceJson(),
                record.getRationale(), record.getL1WarningsJson(), record.getInvalidReason(),
                record.getTargetFingerprint(), record.getJudgeFingerprint(),
                Boolean.TRUE.equals(record.getIsRegression()),
                record.getReviewStatus() == null ? EvalRecord.REVIEW_PENDING : record.getReviewStatus(),
                record.getReviewFinalVerdict(), record.getDivergenceGroup());
    }

    /**
     * run 明细（按落库序）：verdict / subject 均可空（空 = 不过滤）。
     * verdict 过滤支持特殊值 {@code invalid}（invalid_reason IS NULL 为假——
     * 即作废行）供报告页单列作废计数与人工翻查。
     */
    public List<EvalRecord> listByRunId(String runId, String verdict, String subject) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM rpg_eval_record WHERE run_id = ? AND tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(runId);
        args.add(tid());
        if (verdict != null && !verdict.isBlank()) {
            if ("invalid".equals(verdict)) {
                sql.append(" AND invalid_reason IS NOT NULL");
            } else {
                sql.append(" AND verdict = ?");
                args.add(verdict);
            }
        }
        if (subject != null && !subject.isBlank()) {
            sql.append(" AND subject = ?");
            args.add(subject);
        }
        sql.append(" ORDER BY created_at ASC, id ASC");
        return jdbcTemplate.query(sql.toString(),
                BeanPropertyRowMapper.newInstance(EvalRecord.class), args.toArray());
    }

    /** 按 id 查单条（复核详情入口）；不存在返回 null */
    public EvalRecord findById(String recordId) {
        List<EvalRecord> list = jdbcTemplate.query(
                "SELECT * FROM rpg_eval_record WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(EvalRecord.class), recordId, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 人工复核域（tasks 7.x，design D10） ====================

    /**
     * 复核队列（borderline 自动入队 + 分歧标记行）：pending 且（borderline 或
     * divergence_group 非空），创建序倒序取最近 limit 条。borderline 不计
     * 失败率（汇总单列），队列是唯一人工复核入口。
     */
    public List<EvalRecord> listReviewQueue(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_eval_record WHERE tenant_id = ? AND review_status = ? "
                        + "AND (verdict = ? OR divergence_group IS NOT NULL) "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                BeanPropertyRowMapper.newInstance(EvalRecord.class), tid(),
                EvalRecord.REVIEW_PENDING, EvalRecord.VERDICT_BORDERLINE, limit);
    }

    /**
     * 分歧对齐查询（tasks 7.1，对齐粒度修正自 9.3 端到端发现）：本次 run 的有效
     * record 与历史 run（同租户、不同 run、不同 judge 指纹）同对齐键
     * （subject+dimension+unit+<b>sub_dimension</b>）的有效 record 的 verdict
     * 分档不一致 → 两端入分歧对。join 必须比对 sub_dimension：同一评估单元
     * （unit）落多行子维度判分，若只在 case 级对齐，judge A 判 relations=borderline
     * 会与 judge B 判 hard-facts=pass 交叉配对——非同命题的过捕（复核队列噪音）。
     * 分歧组键仍为 case 级（subject+dimension+unit）：同单元多子维度分歧归一组。
     */
    public List<DivergencePair> findDivergences(String runId) {
        return jdbcTemplate.query(
                "SELECT r1.id AS current_id, r2.id AS other_id, r1.subject, "
                        + "r1.dimension, r1.unit_json FROM rpg_eval_record r1 "
                        + "JOIN rpg_eval_record r2 ON r2.tenant_id = r1.tenant_id "
                        + "AND r2.subject = r1.subject AND r2.dimension = r1.dimension "
                        + "AND r2.unit_json = r1.unit_json AND r2.sub_dimension = r1.sub_dimension "
                        + "AND r2.run_id <> r1.run_id "
                        + "AND r2.judge_fingerprint <> r1.judge_fingerprint "
                        + "AND r2.verdict IS NOT NULL AND r2.invalid_reason IS NULL "
                        + "WHERE r1.tenant_id = ? AND r1.run_id = ? "
                        + "AND r1.verdict IS NOT NULL AND r1.invalid_reason IS NULL "
                        + "AND r1.verdict <> r2.verdict",
                (rs, i) -> new DivergencePair(rs.getString("current_id"), rs.getString("other_id"),
                        rs.getString("subject"), rs.getString("dimension"), rs.getString("unit_json")),
                tid(), runId);
    }

    /** 标记分歧组（两端 record 同 divergence_group；重复标记同值幂等） */
    public void markDivergence(List<String> recordIds, String group) {
        if (recordIds == null || recordIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(recordIds.size(), "?"));
        List<Object> args = new ArrayList<>(recordIds.size() + 2);
        args.add(group);
        args.add(tid());
        args.addAll(recordIds);
        jdbcTemplate.update(
                "UPDATE rpg_eval_record SET divergence_group = ? "
                        + "WHERE tenant_id = ? AND id IN (" + placeholders + ")",
                args.toArray());
    }

    /**
     * 复核收口（tasks 7.2）：终裁写 review_final_verdict；reviewStatus 由调用方
     * 按「终裁 vs 机器 verdict」判定（一致 confirmed / 不一致 override）——机器
     * 原判保留于 verdict 列不覆盖（spec「override 保留机器原判」）。
     * 更新 0 行（不存在/跨租户）返回 false。
     */
    public boolean applyReview(String recordId, String reviewStatus, String finalVerdict,
                               boolean regression) {
        return jdbcTemplate.update(
                "UPDATE rpg_eval_record SET review_status = ?, review_final_verdict = ?, "
                        + "is_regression = ? WHERE id = ? AND tenant_id = ?",
                reviewStatus, finalVerdict, regression, recordId, tid()) > 0;
    }

    /** 回归集 subject 清单（is_regression=true 行去重；批跑自动纳入固定用例） */
    public List<String> listRegressionSubjects() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT subject FROM rpg_eval_record WHERE tenant_id = ? "
                        + "AND is_regression = TRUE ORDER BY subject",
                String.class, tid());
    }
}
