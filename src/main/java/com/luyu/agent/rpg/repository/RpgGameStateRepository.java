package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.GameState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 游戏状态 Repository（对应 rpg_game_state 表）
 * <p>
 * GameState 独立于 session，支持跨会话加载。
 */
@Repository
public class RpgGameStateRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public RpgGameStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(GameState state) {
        jdbcTemplate.update(
                "INSERT INTO rpg_game_state (id, session_id, world_id, player_char_id, current_location, turn_count, flags, npc_states) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                state.getId(), state.getSessionId(), state.getWorldId(),
                state.getPlayerCharId(), state.getCurrentLocation(),
                state.getTurnCount(), state.getFlags(), state.getNpcStates());
    }

    public GameState findById(String id) {
        List<GameState> list = jdbcTemplate.query(
                "SELECT * FROM rpg_game_state WHERE id = ?",
                BeanPropertyRowMapper.newInstance(GameState.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateSessionId(String id, String sessionId) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET session_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                sessionId, id);
    }

    public void updateState(String id, String npcStates, Integer turnCount, String currentLocation) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET npc_states = ?, turn_count = ?, current_location = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                npcStates, turnCount, currentLocation, id);
    }

    public void updateFlags(String id, String flags) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET flags = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                flags, id);
    }

    public List<GameState> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_game_state ORDER BY updated_at DESC",
                BeanPropertyRowMapper.newInstance(GameState.class));
    }
}
