package com.luyu.agent.rpg.story.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.luyu.agent.rpg.story.model.SharedStoryGraph;

/**
 * 共享故事图包 Repository（对应 shared_story_graph 表，跨租户共享内容）。
 * <p>
 * <b>读写不对称（对齐 {@code SharedPackRepository}，刻意区别于 rpg_* 租户隔离域）：</b>
 * <ul>
 *   <li>读侧（画廊列表/详情）全平台可见——SQL <b>无租户过滤</b>；
 *       shared_ 表名不含 GUARDED_TABLES 任何表名的子串，运行时守卫零拦截；</li>
 *   <li>写侧（覆盖定位/快照刷新/撤回）SQL 显式带 {@code publisher_tenant_id} 列——
 *       发布者归属即权限锚点，非本人包更新/撤回受影响行数为 0（供控制层判 404）。</li>
 * </ul>
 * 导入写入 rpg_story_graph 由 {@link RpgStoryGraphRepository} 承担（既有
 * tenant_id 强制过滤，本类不越界）。快照不可变：覆盖 = 整行重写 snapshot_json
 * （发布方重新发布时点内容），不存在局部更新。
 */
@Repository
public class SharedStoryGraphRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SharedStoryGraphRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 读侧（全平台，无租户过滤） ====================

    /** 画廊列表：仅在架包（revoked_at IS NULL），按更新时间倒序；空库为空列表 */
    public List<SharedStoryGraph> listVisible() {
        return jdbcTemplate.query(
                "SELECT * FROM shared_story_graph WHERE revoked_at IS NULL ORDER BY updated_at DESC, id",
                BeanPropertyRowMapper.newInstance(SharedStoryGraph.class));
    }

    /** 包详情：仅在架包可见（revoked → null，供控制层判 404） */
    public SharedStoryGraph findVisibleById(String id) {
        List<SharedStoryGraph> list = jdbcTemplate.query(
                "SELECT * FROM shared_story_graph WHERE id = ? AND revoked_at IS NULL",
                BeanPropertyRowMapper.newInstance(SharedStoryGraph.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 写侧（发布者归属，publisher_tenant_id 为权限锚点） ====================

    /**
     * 覆盖定位：发布者租户 + 源图的既有包（含已撤回——撤回后重发布即复活）。
     * 同租户同图一包（一 world 多图，覆盖定位粒度是图；design D3 三层对齐）。
     */
    public SharedStoryGraph findPackByPublisherAndGraph(String publisherTenantId, String originGraphId) {
        List<SharedStoryGraph> list = jdbcTemplate.query(
                "SELECT * FROM shared_story_graph WHERE publisher_tenant_id = ? AND origin_graph_id = ?",
                BeanPropertyRowMapper.newInstance(SharedStoryGraph.class), publisherTenantId, originGraphId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新建包行（id/publisher/origin/snapshot 由服务层校验后生成传入） */
    public void insertPack(SharedStoryGraph pack) {
        jdbcTemplate.update(
                "INSERT INTO shared_story_graph (id, name, blurb, publisher_tenant_id, publisher_user_id, "
                        + "origin_world_id, origin_graph_id, origin_world_name, snapshot_json, "
                        + "snapshot_version, node_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                pack.getId(), pack.getName(), pack.getBlurb(), pack.getPublisherTenantId(),
                pack.getPublisherUserId(), pack.getOriginWorldId(), pack.getOriginGraphId(),
                pack.getOriginWorldName(), pack.getSnapshotJson(), pack.getSnapshotVersion(),
                pack.getNodeCount());
    }

    /**
     * 覆盖刷新包：名称/简介/快照/节点数整体重写（快照不可变 → 覆盖即发布时点重置），
     * updated_at 刷新，revoked_at 置 NULL（撤回后重发布复活）。
     * WHERE 带发布者锚点——非本人包受影响行数为 0（供服务层判 404）。
     */
    public int refreshPack(String packId, String name, String blurb, String snapshotJson,
                           int nodeCount, String publisherTenantId) {
        return jdbcTemplate.update(
                "UPDATE shared_story_graph SET name = ?, blurb = ?, snapshot_json = ?, node_count = ?, "
                        + "snapshot_version = 1, updated_at = CURRENT_TIMESTAMP, revoked_at = NULL "
                        + "WHERE id = ? AND publisher_tenant_id = ?",
                name, blurb, snapshotJson, nodeCount, packId, publisherTenantId);
    }

    /**
     * 撤回（软下架）：置 revoked_at；已导入副本零清理（快照哲学，无回链）。
     * WHERE 带发布者锚点——非发布者撤回受影响行数为 0（供控制层判 404）。
     */
    public int revoke(String packId, String publisherTenantId, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE shared_story_graph SET revoked_at = ? WHERE id = ? AND publisher_tenant_id = ? "
                        + "AND revoked_at IS NULL",
                java.sql.Timestamp.from(revokedAt), packId, publisherTenantId);
    }
}
