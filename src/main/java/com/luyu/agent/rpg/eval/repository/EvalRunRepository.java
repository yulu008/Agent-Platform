package com.luyu.agent.rpg.eval.repository;

import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 评估 run Repository（对应 rpg_eval_run 表，content-eval tasks 1.3）。
 * <p>
 * 状态机写入：{@link #save} 落 running 初始态；批跑完成/失败由
 * {@link #markDone} / {@link #markFailed} 收口（finished_at 一并写入）。
 * 全部读写携带租户过滤（多租户列隔离惯例）。
 */
@Repository
public class EvalRunRepository {

    private final JdbcTemplate jdbcTemplate;

    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public EvalRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 落 running 初始态（触发批跑时调用；record 由 EvalRecordRepository 逐条写） */
    public void save(EvalRun run) {
        jdbcTemplate.update(
                "INSERT INTO rpg_eval_run (id, tenant_id, layer, scope_json, judge_model, " +
                        "rubric_version, status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                run.getId(), tid(), run.getLayer(), run.getScopeJson(), run.getJudgeModel(),
                run.getRubricVersion(), run.getStatus());
    }

    /** 批跑完成收口：写汇总与成本，状态转 done */
    public void markDone(String runId, String summaryJson, long costTokens, long costAmountMicro) {
        jdbcTemplate.update(
                "UPDATE rpg_eval_run SET status = ?, summary_json = ?, cost_tokens = ?, " +
                        "cost_amount_micro = ?, finished_at = CURRENT_TIMESTAMP " +
                        "WHERE id = ? AND tenant_id = ?",
                EvalRun.STATUS_DONE, summaryJson, costTokens, costAmountMicro, runId, tid());
    }

    /** 批跑失败收口：状态转 failed（已落 record 保留，spec「批跑中断保留部分结果」） */
    public void markFailed(String runId) {
        jdbcTemplate.update(
                "UPDATE rpg_eval_run SET status = ?, finished_at = CURRENT_TIMESTAMP " +
                        "WHERE id = ? AND tenant_id = ?",
                EvalRun.STATUS_FAILED, runId, tid());
    }

    /** 按 id 查 run（run 详情页入口）；不存在返回 null */
    public EvalRun findById(String runId) {
        List<EvalRun> list = jdbcTemplate.query(
                "SELECT * FROM rpg_eval_run WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(EvalRun.class), runId, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 租户 run 列表（倒序取最近 limit 条，列表页入口） */
    public List<EvalRun> listRecent(int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_eval_run WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                BeanPropertyRowMapper.newInstance(EvalRun.class), tid(), limit);
    }

    /**
     * 某层最近一次已完成的 run（content-eval tasks 8.1 返工门展示）：无 → null。
     * running/failed 不取（未收口或中断的批跑无稳定结论可展示）。
     */
    public EvalRun findLatestDoneByLayer(String layer) {
        List<EvalRun> list = jdbcTemplate.query(
                "SELECT * FROM rpg_eval_run WHERE tenant_id = ? AND layer = ? AND status = ? "
                        + "ORDER BY created_at DESC LIMIT 1",
                BeanPropertyRowMapper.newInstance(EvalRun.class), tid(), layer,
                EvalRun.STATUS_DONE);
        return list.isEmpty() ? null : list.get(0);
    }
}
