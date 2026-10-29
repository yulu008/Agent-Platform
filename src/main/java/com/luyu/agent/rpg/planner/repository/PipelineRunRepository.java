package com.luyu.agent.rpg.planner.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 管线 run Repository（对应 rpg_pipeline_run 表）。
 * <p>
 * 对齐 {@code RpgDestinedFigureRepository} 惯例：当前租户为所有 SQL 的强制过滤参数
 * （{@code tid()} 缺失即快速失败），跨租户访问表现为不存在（空结果）。
 */
@Repository
public class PipelineRunRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public PipelineRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(PipelineRun run) {
        if (run.getId() == null || run.getId().isBlank()) {
            run.setId(UUID.randomUUID().toString());
        }
        jdbcTemplate.update(
                "INSERT INTO rpg_pipeline_run (id, tenant_id, name, source_mode, style_mode, " +
                        "current_stage, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                run.getId(), tid(), run.getName(), run.getSourceMode(), run.getStyleMode(),
                run.getCurrentStage(), run.getStatus());
    }

    /** 按 id 查（租户过滤；跨租户/不存在返回空） */
    public Optional<PipelineRun> findById(String id) {
        List<PipelineRun> runs = jdbcTemplate.query(
                "SELECT * FROM rpg_pipeline_run WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(PipelineRun.class), id, tid());
        return runs.stream().findFirst();
    }

    /** 本租户全部 run（列表页数据源；按创建时间倒序，库表查询而非目录遍历） */
    public List<PipelineRun> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_pipeline_run WHERE tenant_id = ? ORDER BY created_at DESC",
                BeanPropertyRowMapper.newInstance(PipelineRun.class), tid());
    }

    /**
     * 同步推进（阶段码 + run 状态）：写路径唯一入口之一，由 PipelineRunService 在
     * manifest 更新后调用（D3：先写 manifest 后同步库表）。
     */
    public void updateProgress(String id, String currentStage, String status) {
        jdbcTemplate.update(
                "UPDATE rpg_pipeline_run SET current_stage = ?, status = ?, updated_at = NOW() " +
                        "WHERE id = ? AND tenant_id = ?",
                currentStage, status, id, tid());
    }

    /** 放弃（ABANDONED 为终态；工作区文件树不动） */
    public void abandon(String id) {
        jdbcTemplate.update(
                "UPDATE rpg_pipeline_run SET status = 'ABANDONED', updated_at = NOW() " +
                        "WHERE id = ? AND tenant_id = ? AND status <> 'COMPLETED'",
                id, tid());
    }

    /**
     * 硬删除（planner-run-delete tasks 1.2）：删行——与 abandon（置终态保留）
     * 语义互斥互补。租户过滤同 findById：跨租户删除影响 0 行，调用方据
     * requireRun 的 404 幂等语义兜底（重复删除走同一路径）。
     */
    public void deleteById(String id) {
        jdbcTemplate.update(
                "DELETE FROM rpg_pipeline_run WHERE id = ? AND tenant_id = ?",
                id, tid());
    }
}
