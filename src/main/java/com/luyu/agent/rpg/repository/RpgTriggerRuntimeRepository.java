package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.TriggerRuntime;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 触发器运行时状态 Repository（对应 rpg_trigger_runtime 表）
 * <p>
 * 每轮触发器扫描时使用，判断冷却和启用状态。
 */
@Repository
public class RpgTriggerRuntimeRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgTriggerRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TriggerRuntime runtime) {
        jdbcTemplate.update(
                "INSERT INTO rpg_trigger_runtime (id, tenant_id, game_state_id, trigger_id, last_triggered_turn, is_active) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                runtime.getId(), tid(), runtime.getGameStateId(), runtime.getTriggerId(),
                runtime.getLastTriggeredTurn(), runtime.getIsActive());
    }

    public List<TriggerRuntime> findByGameStateId(String gameStateId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger_runtime WHERE game_state_id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(TriggerRuntime.class), gameStateId, tid());
    }

    /**
     * 查询活跃的触发器运行时记录
     */
    public List<TriggerRuntime> findActiveByGameStateId(String gameStateId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger_runtime WHERE game_state_id = ? AND is_active = true AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(TriggerRuntime.class), gameStateId, tid());
    }

    public void updateLastTriggeredTurn(String id, Integer turn) {
        jdbcTemplate.update(
                "UPDATE rpg_trigger_runtime SET last_triggered_turn = ? WHERE id = ? AND tenant_id = ?",
                turn, id, tid());
    }

    public void updateActive(String id, Boolean isActive) {
        jdbcTemplate.update(
                "UPDATE rpg_trigger_runtime SET is_active = ? WHERE id = ? AND tenant_id = ?",
                isActive, id, tid());
    }

    /**
     * 删除某轮及之后触发的运行时记录（回溯用：冷却记录随回滚重置，首轮扫描会按需重建）。
     *
     * @return 删除的行数
     */
    public int deleteFromTurn(String gameStateId, int lastTriggeredTurn) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_trigger_runtime WHERE game_state_id = ? AND last_triggered_turn >= ? AND tenant_id = ?",
                gameStateId, lastTriggeredTurn, tid());
    }

    /**
     * 删除该存档的全部触发器运行时记录（删档用）。
     *
     * @return 删除的行数
     */
    public int deleteByGameStateId(String gameStateId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_trigger_runtime WHERE game_state_id = ? AND tenant_id = ?", gameStateId, tid());
    }
}
