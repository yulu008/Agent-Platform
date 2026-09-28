package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.tenancy.TenantContext;
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

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgGameStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(GameState state) {
        jdbcTemplate.update(
                "INSERT INTO rpg_game_state (id, tenant_id, session_id, world_id, player_char_id, current_location, turn_count, flags, npc_states, player_states) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                state.getId(), tid(), state.getSessionId(), state.getWorldId(),
                state.getPlayerCharId(), state.getCurrentLocation(),
                state.getTurnCount(), state.getFlags(), state.getNpcStates(), state.getPlayerStates());
    }

    public GameState findById(String id) {
        List<GameState> list = jdbcTemplate.query(
                "SELECT * FROM rpg_game_state WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(GameState.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按会话 ID查游戏状态（回溯前端近似置灰所需的 currentTurn 来源）。
     * 读档后 sessionId 会指向新存档，最新写入的行优先。
     */
    public GameState findLatestBySessionId(String sessionId) {
        List<GameState> list = jdbcTemplate.query(
                "SELECT * FROM rpg_game_state WHERE session_id = ? AND tenant_id = ? ORDER BY updated_at DESC",
                BeanPropertyRowMapper.newInstance(GameState.class), sessionId, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateSessionId(String id, String sessionId) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET session_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                sessionId, id, tid());
    }

    public void updateState(String id, String npcStates, Integer turnCount, String currentLocation) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET npc_states = ?, turn_count = ?, current_location = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                npcStates, turnCount, currentLocation, id, tid());
    }

    public void updateFlags(String id, String flags) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET flags = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                flags, id, tid());
    }

    /**
     * 仅更新 PC 结构化状态（与 {@link #updateFlags} 同模式）。
     * <p>
     * 回溯还原与 finalizeTurn 的 player 节点合并共用；快照列值为 NULL（变更前旧快照）时传 null 即置空。
     */
    public void updatePlayerStates(String id, String playerStates) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET player_states = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                playerStates, id, tid());
    }

    public List<GameState> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_game_state WHERE tenant_id = ? ORDER BY updated_at DESC",
                BeanPropertyRowMapper.newInstance(GameState.class), tid());
    }

    /**
     * 同一世界的全部存档（一键中文化改名时需迁移各存档的 npcStates key）。
     */
    public List<GameState> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_game_state WHERE world_id = ? AND tenant_id = ? ORDER BY updated_at DESC",
                BeanPropertyRowMapper.newInstance(GameState.class), worldId, tid());
    }

    /**
     * 仅重写 npc_states（key 从旧名迁移为角色卡 ID），不动轮次与位置。
     */
    public void updateNpcStates(String id, String npcStates) {
        jdbcTemplate.update(
                "UPDATE rpg_game_state SET npc_states = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                npcStates, id, tid());
    }

    /**
     * 以该角色为玩家的存档数（删除角色前的阻塞校验）。
     */
    public int countByPlayerCharId(String playerCharId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_game_state WHERE player_char_id = ? AND tenant_id = ?",
                Integer.class, playerCharId, tid());
        return count == null ? 0 : count;
    }

    /**
     * 该世界下的存档数（删除世界观前的阻塞校验）。
     */
    public int countByWorldId(String worldId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_game_state WHERE world_id = ? AND tenant_id = ?",
                Integer.class, worldId, tid());
        return count == null ? 0 : count;
    }

    /**
     * 删除存档主行（删档事务内最后一步：附属表全部清理完才删主行）。
     */
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM rpg_game_state WHERE id = ? AND tenant_id = ?", id, tid());
    }
}
