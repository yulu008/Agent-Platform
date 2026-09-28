package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.EventLog;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 事件日志 Repository（对应 rpg_event_log 表）
 * <p>
 * append-only，支持按 game_state_id 和轮次范围查询。
 * 供软条件评估和 GM {@code get_recent_events} 工具使用。
 */
@Repository
public class RpgEventLogRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgEventLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入事件日志（id 自增）
     */
    public void insert(EventLog log) {
        jdbcTemplate.update(
                "INSERT INTO rpg_event_log (tenant_id, game_state_id, turn, event_type, content, state_delta) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                tid(), log.getGameStateId(), log.getTurn(), log.getEventType(),
                log.getContent(), log.getStateDelta());
    }

    /**
     * 查询最近 N 条事件（ORDER BY id DESC LIMIT n）
     */
    public List<EventLog> findRecentByGameStateId(String gameStateId, int count) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_event_log WHERE game_state_id = ? AND tenant_id = ? ORDER BY id DESC LIMIT ?",
                BeanPropertyRowMapper.newInstance(EventLog.class), gameStateId, tid(), count);
    }

    /**
     * 按轮次范围查询
     */
    public List<EventLog> findByGameStateIdAndTurnRange(String gameStateId, int fromTurn, int toTurn) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_event_log WHERE game_state_id = ? AND turn >= ? AND turn <= ? AND tenant_id = ? ORDER BY id ASC",
                BeanPropertyRowMapper.newInstance(EventLog.class), gameStateId, fromTurn, toTurn, tid());
    }

    /**
     * 查询某轮次的所有事件
     */
    public List<EventLog> findByGameStateIdAndTurn(String gameStateId, int turn) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_event_log WHERE game_state_id = ? AND turn = ? AND tenant_id = ? ORDER BY id ASC",
                BeanPropertyRowMapper.newInstance(EventLog.class), gameStateId, turn, tid());
    }

    /**
     * 删除某轮及之后的全部事件日志（回溯用）。
     *
     * @return 删除的行数
     */
    public int deleteFromTurn(String gameStateId, int turn) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_event_log WHERE game_state_id = ? AND turn >= ? AND tenant_id = ?",
                gameStateId, turn, tid());
    }

    /**
     * 玩家行动事件的最大轮次（回溯锚点轮次推导用：最后一条 player_action 即最后发起的回合）。
     * 含中止/失败但已 prepareTurn 的轮次，比 rpg_game_state.turn_count 更贴近会话事件末尾。
     */
    public int findMaxPlayerActionTurn(String gameStateId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT MAX(turn) FROM rpg_event_log WHERE game_state_id = ? AND event_type = 'player_action' AND tenant_id = ?",
                Integer.class, gameStateId, tid());
        return max == null ? 0 : max;
    }

    /**
     * 删除该存档的全部事件日志（删档用）。
     *
     * @return 删除的行数
     */
    public int deleteByGameStateId(String gameStateId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_event_log WHERE game_state_id = ? AND tenant_id = ?", gameStateId, tid());
    }
}
