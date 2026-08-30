package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.EventLog;
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

    @Autowired
    public RpgEventLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入事件日志（id 自增）
     */
    public void insert(EventLog log) {
        jdbcTemplate.update(
                "INSERT INTO rpg_event_log (game_state_id, turn, event_type, content, state_delta) " +
                        "VALUES (?, ?, ?, ?, ?)",
                log.getGameStateId(), log.getTurn(), log.getEventType(),
                log.getContent(), log.getStateDelta());
    }

    /**
     * 查询最近 N 条事件（ORDER BY id DESC LIMIT n）
     */
    public List<EventLog> findRecentByGameStateId(String gameStateId, int count) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_event_log WHERE game_state_id = ? ORDER BY id DESC LIMIT ?",
                BeanPropertyRowMapper.newInstance(EventLog.class), gameStateId, count);
    }

    /**
     * 按轮次范围查询
     */
    public List<EventLog> findByGameStateIdAndTurnRange(String gameStateId, int fromTurn, int toTurn) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_event_log WHERE game_state_id = ? AND turn >= ? AND turn <= ? ORDER BY id ASC",
                BeanPropertyRowMapper.newInstance(EventLog.class), gameStateId, fromTurn, toTurn);
    }

    /**
     * 查询某轮次的所有事件
     */
    public List<EventLog> findByGameStateIdAndTurn(String gameStateId, int turn) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_event_log WHERE game_state_id = ? AND turn = ? ORDER BY id ASC",
                BeanPropertyRowMapper.newInstance(EventLog.class), gameStateId, turn);
    }
}
