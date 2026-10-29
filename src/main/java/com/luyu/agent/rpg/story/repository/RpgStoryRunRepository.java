package com.luyu.agent.rpg.story.repository;

import com.luyu.agent.rpg.story.model.StoryRun;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 故事图运行副本 Repository（对应 rpg_story_run 表，存档级）。
 * <p>
 * 玩家游走只写本表（当前节点/已走边/物化缓存/即兴深度），模板层不感知。
 * 一存档一运行副本（game_state_id 唯一语义由服务层保证：重开局换新行）。
 */
@Repository
public class RpgStoryRunRepository {

    private final JdbcTemplate jdbcTemplate;

    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgStoryRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(StoryRun run) {
        jdbcTemplate.update(
                "INSERT INTO rpg_story_run (id, tenant_id, game_state_id, graph_id, current_node, " +
                        "walked_edges, materialized_bodies, improvise_depth, rematerialize, pending_gate, " +
                        "initial_player_states) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                run.getId(), tid(), run.getGameStateId(), run.getGraphId(), run.getCurrentNode(),
                run.getWalkedEdges(), run.getMaterializedBodies(),
                run.getImproviseDepth() == null ? 0 : run.getImproviseDepth(),
                Boolean.TRUE.equals(run.getRematerialize()), run.getPendingGate(),
                run.getInitialPlayerStates());
    }

    /**
     * UPDATE 不携带 initial_player_states（4.8 语义）：开局基线不可变——回退重放
     * 只重算 current_node/walked_edges/playerStates，基线保持开局写入值。
     */
    public void update(StoryRun run) {
        jdbcTemplate.update(
                "UPDATE rpg_story_run SET current_node = ?, walked_edges = ?, materialized_bodies = ?, " +
                        "improvise_depth = ?, rematerialize = ?, pending_gate = ?, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE id = ? AND tenant_id = ?",
                run.getCurrentNode(), run.getWalkedEdges(), run.getMaterializedBodies(),
                run.getImproviseDepth() == null ? 0 : run.getImproviseDepth(),
                Boolean.TRUE.equals(run.getRematerialize()), run.getPendingGate(), run.getId(), tid());
    }

    /** 按存档查运行副本（游戏中每轮读写入口）；无副本返回 null */
    public StoryRun findByGameStateId(String gameStateId) {
        List<StoryRun> list = jdbcTemplate.query(
                "SELECT * FROM rpg_story_run WHERE game_state_id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(StoryRun.class), gameStateId, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 删除存档的运行副本（存档删除级联），返回受影响行数 */
    public int deleteByGameStateId(String gameStateId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_story_run WHERE game_state_id = ? AND tenant_id = ?", gameStateId, tid());
    }

    /** 模板图下是否存在运行副本（工坊删图守卫：有副本则拒删，对齐世界书有存档 409 惯例） */
    public boolean existsByGraphId(String graphId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_story_run WHERE graph_id = ? AND tenant_id = ?",
                Integer.class, graphId, tid());
        return count != null && count > 0;
    }
}
