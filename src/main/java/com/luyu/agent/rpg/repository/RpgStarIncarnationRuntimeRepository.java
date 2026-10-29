package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 冥星武将录运行时状态 Repository（对应 rpg_star_incarnation_runtime 表，存档级）。
 * <p>
 * 激活（sticky，命中后本局常驻）与抢星判定结果落此表；
 * 对齐 {@link RpgTriggerRuntimeRepository} 的租户隔离与删档/回溯清理惯例。
 */
@Repository
public class RpgStarIncarnationRuntimeRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgStarIncarnationRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(StarIncarnationRuntime runtime) {
        jdbcTemplate.update(
                "INSERT INTO rpg_star_incarnation_runtime (id, tenant_id, game_state_id, incarnation_id, star_name, activated_turn, claimed_by_player) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                runtime.getId(), tid(), runtime.getGameStateId(), runtime.getIncarnationId(),
                runtime.getStarName(), runtime.getActivatedTurn(), runtime.getClaimedByPlayer());
    }

    /** 本存档全部运行时条目（激活 + 抢星）；未激活任何条目时为空列表 */
    public List<StarIncarnationRuntime> findByGameStateId(String gameStateId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_star_incarnation_runtime WHERE game_state_id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(StarIncarnationRuntime.class), gameStateId, tid());
    }

    /**
     * 删除某轮及之后激活的运行时记录（回溯用：被回溯轮次里的关键词激活随回滚撤销；
     * activated_turn=0 的开局抢星记录不受影响——抢星是存档级不变量）。
     *
     * @return 删除的行数
     */
    public int deleteFromTurn(String gameStateId, int fromTurn) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_star_incarnation_runtime WHERE game_state_id = ? AND activated_turn >= ? AND claimed_by_player = false AND tenant_id = ?",
                gameStateId, fromTurn, tid());
    }

    /**
     * 删除该存档的全部运行时记录（删档用）。
     *
     * @return 删除的行数
     */
    public int deleteByGameStateId(String gameStateId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_star_incarnation_runtime WHERE game_state_id = ? AND tenant_id = ?",
                gameStateId, tid());
    }

    /**
     * 本存档揭示/抢星行批量改名（add-rpg-lorebook-sets D8）：set 内武将 starName 改名时
     * 同事务同步，保持 star_name 稳定键不断链。
     *
     * @return 更新的行数
     */
    public int renameStar(String gameStateId, String oldStarName, String newStarName) {
        return jdbcTemplate.update(
                "UPDATE rpg_star_incarnation_runtime SET star_name = ? WHERE game_state_id = ? AND star_name = ? AND tenant_id = ?",
                newStarName, gameStateId, oldStarName, tid());
    }
}
