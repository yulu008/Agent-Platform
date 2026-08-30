package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.TriggerRuntime;
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

    @Autowired
    public RpgTriggerRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(TriggerRuntime runtime) {
        jdbcTemplate.update(
                "INSERT INTO rpg_trigger_runtime (id, game_state_id, trigger_id, last_triggered_turn, is_active) " +
                        "VALUES (?, ?, ?, ?, ?)",
                runtime.getId(), runtime.getGameStateId(), runtime.getTriggerId(),
                runtime.getLastTriggeredTurn(), runtime.getIsActive());
    }

    public List<TriggerRuntime> findByGameStateId(String gameStateId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger_runtime WHERE game_state_id = ?",
                BeanPropertyRowMapper.newInstance(TriggerRuntime.class), gameStateId);
    }

    /**
     * 查询活跃的触发器运行时记录
     */
    public List<TriggerRuntime> findActiveByGameStateId(String gameStateId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger_runtime WHERE game_state_id = ? AND is_active = true",
                BeanPropertyRowMapper.newInstance(TriggerRuntime.class), gameStateId);
    }

    public void updateLastTriggeredTurn(String id, Integer turn) {
        jdbcTemplate.update(
                "UPDATE rpg_trigger_runtime SET last_triggered_turn = ? WHERE id = ?",
                turn, id);
    }

    public void updateActive(String id, Boolean isActive) {
        jdbcTemplate.update(
                "UPDATE rpg_trigger_runtime SET is_active = ? WHERE id = ?",
                isActive, id);
    }
}
