package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.GameStateSnapshot;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 游戏状态快照 Repository（对应 rpg_game_state_snapshot 表）。
 * <p>
 * 快照在每轮 {@code prepareTurn} 开始时写入（turn=N-1），回溯时按 turn 取出覆盖
 * {@code rpg_game_state}。保留窗口为最近 {@value #KEEP_COUNT} 份（当前轮 + 可回溯的 3 轮），
 * 超出的最旧快照由 {@link #prune} 清理。
 */
@Repository
public class RpgGameStateSnapshotRepository {

    /** 快照保留份数：当前轮 + 可回溯的 3 轮（回溯深度上限见 RollbackService） */
    public static final int KEEP_COUNT = 4;

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgGameStateSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * UPSERT 语义写入快照：同 (gameStateId, turn) 已存在时覆盖。
     * <p>
     * 连续重新生成会对同一 turn 重复 prepareTurn，DELETE+INSERT 保证不产生重复份数。
     */
    public void upsert(GameState state, int turn) {
        jdbcTemplate.update(
                "DELETE FROM rpg_game_state_snapshot WHERE game_state_id = ? AND turn = ? AND tenant_id = ?",
                state.getId(), turn, tid());
        jdbcTemplate.update(
                "INSERT INTO rpg_game_state_snapshot (tenant_id, game_state_id, turn, current_location, flags, npc_states, player_states) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                tid(), state.getId(), turn, state.getCurrentLocation(),
                state.getFlags(), state.getNpcStates(), state.getPlayerStates());
    }

    /**
     * 按 turn 查询快照，不存在返回 null。
     */
    public GameStateSnapshot findByGameStateIdAndTurn(String gameStateId, int turn) {
        List<GameStateSnapshot> list = jdbcTemplate.query(
                "SELECT * FROM rpg_game_state_snapshot WHERE game_state_id = ? AND turn = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(GameStateSnapshot.class), gameStateId, turn, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 列出该存档现有的全部快照轮次（降序），供保留窗口清理与快照存在性判断。
     */
    public List<Integer> findTurnsDesc(String gameStateId) {
        return jdbcTemplate.queryForList(
                "SELECT turn FROM rpg_game_state_snapshot WHERE game_state_id = ? AND tenant_id = ? ORDER BY turn DESC",
                Integer.class, gameStateId, tid());
    }

    /**
     * 清理保留窗口外的最旧快照，仅保留最近 keepCount 份。
     *
     * @return 实际删除的份数
     */
    public int prune(String gameStateId, int keepCount) {
        List<Integer> turns = findTurnsDesc(gameStateId);
        int removed = 0;
        for (int i = keepCount; i < turns.size(); i++) {
            jdbcTemplate.update(
                    "DELETE FROM rpg_game_state_snapshot WHERE game_state_id = ? AND turn = ? AND tenant_id = ?",
                    gameStateId, turns.get(i), tid());
            removed++;
        }
        return removed;
    }

    /**
     * 删除某轮及之后的全部快照（回溯用：被回溯轮次之后的时间线作废，对应快照一并清理，
     * 避免重演后回溯到旧时间线状态）。
     *
     * @return 删除的份数
     */
    public int deleteFromTurn(String gameStateId, int turn) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_game_state_snapshot WHERE game_state_id = ? AND turn >= ? AND tenant_id = ?",
                gameStateId, turn, tid());
    }

    /**
     * 删除该存档的全部快照（删档用）。
     *
     * @return 删除的份数
     */
    public int deleteByGameStateId(String gameStateId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_game_state_snapshot WHERE game_state_id = ? AND tenant_id = ?", gameStateId, tid());
    }
}
