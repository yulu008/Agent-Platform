package com.luyu.agent.rpg.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.luyu.agent.rpg.model.SharedWorld;

/**
 * 共享世界发布 Repository（对应 shared_world 单表，works-view-and-world-publish）。
 * <p>
 * <b>读写不对称（design D3/D4，与 {@link SharedPackRepository} 同约，刻意区别于 rpg_* 租户隔离域）：</b>
 * <ul>
 *   <li>读侧（橱窗列表/详情）全平台可见——SQL <b>无租户过滤</b>；
 *       shared_world 表名不含 GUARDED_TABLES 任何表名的子串（{@code shared_} 中缀隔断），
 *       运行时守卫零拦截；</li>
 *   <li>写侧（覆盖定位/整包刷新/撤销）SQL 显式带 {@code publisher_tenant_id} 列——
 *       发布者归属即权限锚点，非本人包更新/撤销受影响行数为 0（供控制层判 404）。</li>
 * </ul>
 * 导入写入 rpg_* 业务表由既有各 Repository 承担（tenant_id 强制过滤，本类不越界）。
 */
@Repository
public class WorldPublishRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public WorldPublishRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 读侧（全平台，无租户过滤） ====================

    /**
     * 橱窗列表：仅在架包（revoked_at IS NULL），按更新时间倒序。
     * 刻意不查 snapshot_json 整包快照列——列表渲染只取包级列，避免每行反序列化大文档。
     */
    public List<SharedWorld> listVisible() {
        return jdbcTemplate.query(
                "SELECT id, name, blurb, cover, publisher_tenant_id, publisher_user_id, origin_world_id, "
                        + "origin_world_name, snapshot_version, entry_count, created_at, updated_at "
                        + "FROM shared_world WHERE revoked_at IS NULL ORDER BY updated_at DESC, id",
                BeanPropertyRowMapper.newInstance(SharedWorld.class));
    }

    /** 包详情（含整包快照，导入用）；revoked/不存在返回 null（供控制层判 404） */
    public SharedWorld findVisibleById(String id) {
        List<SharedWorld> list = jdbcTemplate.query(
                "SELECT * FROM shared_world WHERE id = ? AND revoked_at IS NULL",
                BeanPropertyRowMapper.newInstance(SharedWorld.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 写侧（发布者归属，publisher_tenant_id 为权限锚点） ====================

    /**
     * 覆盖定位：发布者租户 + 源世界的既有包（含已撤销——撤销后重发布即复活）。
     * 同发布者同源世界一包（design D4：一世界一包，重复发布覆盖）。
     */
    public SharedWorld findByPublisherAndWorld(String publisherTenantId, String originWorldId) {
        List<SharedWorld> list = jdbcTemplate.query(
                "SELECT * FROM shared_world WHERE publisher_tenant_id = ? AND origin_world_id = ?",
                BeanPropertyRowMapper.newInstance(SharedWorld.class), publisherTenantId, originWorldId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新建包行（id/publisher/origin/snapshot 由服务层生成与校验后传入） */
    public void insertPack(SharedWorld pack) {
        jdbcTemplate.update(
                "INSERT INTO shared_world (id, name, blurb, cover, publisher_tenant_id, publisher_user_id, "
                        + "origin_world_id, origin_world_name, snapshot_json, snapshot_version, entry_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                pack.getId(), pack.getName(), pack.getBlurb(), pack.getCover(),
                pack.getPublisherTenantId(), pack.getPublisherUserId(),
                pack.getOriginWorldId(), pack.getOriginWorldName(),
                pack.getSnapshotJson(), pack.getSnapshotVersion(), pack.getEntryCount());
    }

    /**
     * 覆盖刷新整包：元数据（name/blurb/cover/entry_count）与快照文档重写、
     * updated_at 刷新、revoked_at 置 NULL（撤销后重发布复活）。
     * WHERE 带发布者锚点——非本人包受影响行数为 0（供服务层判 404）。
     */
    public int refreshPack(String packId, String name, String blurb, String cover,
                           String snapshotJson, int snapshotVersion, int entryCount,
                           String publisherTenantId) {
        return jdbcTemplate.update(
                "UPDATE shared_world SET name = ?, blurb = ?, cover = ?, snapshot_json = ?, "
                        + "snapshot_version = ?, entry_count = ?, updated_at = CURRENT_TIMESTAMP, revoked_at = NULL "
                        + "WHERE id = ? AND publisher_tenant_id = ?",
                name, blurb, cover, snapshotJson, snapshotVersion, entryCount,
                packId, publisherTenantId);
    }

    /**
     * 撤销（软下架）：置 revoked_at；已导入副本零清理（快照哲学，无回链）。
     * WHERE 带发布者锚点——非发布者撤销受影响行数为 0（供控制层判 404）。
     */
    public int revoke(String packId, String publisherTenantId, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE shared_world SET revoked_at = ? WHERE id = ? AND publisher_tenant_id = ? "
                        + "AND revoked_at IS NULL",
                Timestamp.from(revokedAt), packId, publisherTenantId);
    }

    // ==================== 发布者自查（作品墙已发布标识） ====================

    /**
     * 发布者本租户在架包的源世界 id 集：作品墙回查已发布标识（origin_world_id 命中即已发布）。
     * 按发布者过滤是业务语义（与覆盖定位同型），非隔离过滤列——守卫不拦 shared_world 表。
     */
    public List<String> listVisibleOriginWorldIdsByPublisher(String publisherTenantId) {
        return jdbcTemplate.queryForList(
                "SELECT origin_world_id FROM shared_world WHERE publisher_tenant_id = ? AND revoked_at IS NULL",
                String.class, publisherTenantId);
    }
}
