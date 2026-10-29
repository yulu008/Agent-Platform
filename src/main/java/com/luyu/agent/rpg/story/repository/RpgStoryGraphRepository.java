package com.luyu.agent.rpg.story.repository;

import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 故事图 Repository（对应 rpg_story_graph 表，world 模板层）。
 * <p>
 * 所有 SQL 强制带 {@code tenant_id} 过滤——缺失即快速失败，绝不静默跨租户
 * （对齐 {@code RpgWorldbookEntryRepository} 惯例）。跨租户访问由调用方
 * 映射为 404（不泄露存在性）。
 */
@Repository
public class RpgStoryGraphRepository {

    private final JdbcTemplate jdbcTemplate;

    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgStoryGraphRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(StoryGraph graph) {
        jdbcTemplate.update(
                "INSERT INTO rpg_story_graph (id, tenant_id, world_id, name, improvise_mode, improvise_depth_limit, state_schema, graph_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                graph.getId(), tid(), graph.getWorldId(), graph.getName(),
                graph.getImproviseMode(), graph.getImproviseDepthLimit(),
                graph.getStateSchema(), graph.getGraphJson());
    }

    public void update(StoryGraph graph) {
        jdbcTemplate.update(
                "UPDATE rpg_story_graph SET world_id = ?, name = ?, improvise_mode = ?, improvise_depth_limit = ?, state_schema = ?, graph_json = ?, " +
                        "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                graph.getWorldId(), graph.getName(), graph.getImproviseMode(),
                graph.getImproviseDepthLimit(), graph.getStateSchema(), graph.getGraphJson(),
                graph.getId(), tid());
    }

    /** 按 ID 查图；不存在/跨租户统一返回 null（控制层映射 404，不泄露存在性） */
    public StoryGraph findById(String id) {
        List<StoryGraph> list = jdbcTemplate.query(
                "SELECT * FROM rpg_story_graph WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(StoryGraph.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 world 查全部故事图（一 world 多图），按创建时间排序 */
    public List<StoryGraph> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_story_graph WHERE world_id = ? AND tenant_id = ? ORDER BY created_at, id",
                BeanPropertyRowMapper.newInstance(StoryGraph.class), worldId, tid());
    }

    /** world 下是否存在至少一张图（开局零增量判断：无图时游戏流程与现状完全一致） */
    public boolean existsByWorldId(String worldId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_story_graph WHERE world_id = ? AND tenant_id = ?",
                Integer.class, worldId, tid());
        return count != null && count > 0;
    }

    /** 删除图（工坊显式删除；运行副本由调用方级联处理），返回受影响行数 */
    public int deleteById(String id) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_story_graph WHERE id = ? AND tenant_id = ?", id, tid());
    }
}
