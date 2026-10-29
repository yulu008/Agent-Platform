package com.luyu.agent.rpg.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.luyu.agent.rpg.model.SharedLorebookPack;
import com.luyu.agent.rpg.model.SharedStarEntry;
import com.luyu.agent.rpg.model.SharedWorldbookEntry;

/**
 * 共享世界书包 Repository（对应 shared_lorebook_pack 三张表，跨租户共享内容）。
 * <p>
 * <b>读写不对称（add-shared-worldbook-publish design D2/D3，刻意区别于 rpg_* 租户隔离域）：</b>
 * <ul>
 *   <li>读侧（画廊列表/详情/包内条目）全平台可见——SQL <b>无租户过滤</b>；
 *       shared_ 表名不含 GUARDED_TABLES 任何表名的子串，运行时守卫零拦截；</li>
 *   <li>写侧（覆盖定位/元数据刷新/撤回）SQL 显式带 {@code publisher_tenant_id} 列——
 *       发布者归属即权限锚点，非本人包更新/撤回受影响行数为 0（供控制层判 404）。</li>
 * </ul>
 * 发布/导入写入 rpg_* 业务表由 {@link RpgWorldbookEntryRepository} /
 * {@link RpgStarIncarnationRepository} 承担（既有 tenant_id 强制过滤，本类不越界）。
 */
@Repository
public class SharedPackRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SharedPackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ==================== 读侧（全平台，无租户过滤） ====================

    /** 画廊列表：仅在架包（revoked_at IS NULL），按更新时间倒序；空库为空列表 */
    public List<SharedLorebookPack> listVisible() {
        return jdbcTemplate.query(
                "SELECT * FROM shared_lorebook_pack WHERE revoked_at IS NULL ORDER BY updated_at DESC, id",
                BeanPropertyRowMapper.newInstance(SharedLorebookPack.class));
    }

    /** 包详情：仅在架包可见（revoked → null，供控制层判 404） */
    public SharedLorebookPack findVisibleById(String id) {
        List<SharedLorebookPack> list = jdbcTemplate.query(
                "SELECT * FROM shared_lorebook_pack WHERE id = ? AND revoked_at IS NULL",
                BeanPropertyRowMapper.newInstance(SharedLorebookPack.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 包内通用世界书条目（按发布时排序） */
    public List<SharedWorldbookEntry> readWorldbookEntries(String packId) {
        return jdbcTemplate.query(
                "SELECT * FROM shared_worldbook_entry WHERE pack_id = ? ORDER BY sort_order, id",
                BeanPropertyRowMapper.newInstance(SharedWorldbookEntry.class), packId);
    }

    /** 包内冥星武将录条目（按发布时排序） */
    public List<SharedStarEntry> readStarEntries(String packId) {
        return jdbcTemplate.query(
                "SELECT * FROM shared_star_entry WHERE pack_id = ? ORDER BY sort_order, id",
                BeanPropertyRowMapper.newInstance(SharedStarEntry.class), packId);
    }

    // ==================== 写侧（发布者归属，publisher_tenant_id 为权限锚点） ====================

    /**
     * 覆盖定位：发布者租户 + 源世界的既有包（含已撤回——撤回后重发布即复活）。
     * 同租户同世界一包（design D4：一世界一包，重复发布覆盖）。
     */
    public SharedLorebookPack findPackByPublisherAndWorld(String publisherTenantId, String originWorldId) {
        List<SharedLorebookPack> list = jdbcTemplate.query(
                "SELECT * FROM shared_lorebook_pack WHERE publisher_tenant_id = ? AND origin_world_id = ?",
                BeanPropertyRowMapper.newInstance(SharedLorebookPack.class), publisherTenantId, originWorldId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 新建包行（id/publisher/origin 由服务层生成与校验后传入） */
    public void insertPack(SharedLorebookPack pack) {
        jdbcTemplate.update(
                "INSERT INTO shared_lorebook_pack (id, name, blurb, publisher_tenant_id, publisher_user_id, "
                        + "origin_world_id, origin_world_name, entry_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                pack.getId(), pack.getName(), pack.getBlurb(), pack.getPublisherTenantId(),
                pack.getPublisherUserId(), pack.getOriginWorldId(), pack.getOriginWorldName(),
                pack.getEntryCount());
    }

    /**
     * 覆盖刷新包元数据：名称/简介/条目数重写，updated_at 刷新，revoked_at 置 NULL（撤回后重发布复活）。
     * WHERE 带发布者锚点——非本人包受影响行数为 0（供服务层判 404）。
     */
    public int refreshPack(String packId, String name, String blurb, int entryCount, String publisherTenantId) {
        return jdbcTemplate.update(
                "UPDATE shared_lorebook_pack SET name = ?, blurb = ?, entry_count = ?, "
                        + "updated_at = CURRENT_TIMESTAMP, revoked_at = NULL "
                        + "WHERE id = ? AND publisher_tenant_id = ?",
                name, blurb, entryCount, packId, publisherTenantId);
    }

    /** 删除包内两轨条目行（覆盖前清场；导入永不调此方法——导入方不动共享包） */
    public void deleteEntriesOfPack(String packId) {
        jdbcTemplate.update("DELETE FROM shared_worldbook_entry WHERE pack_id = ?", packId);
        jdbcTemplate.update("DELETE FROM shared_star_entry WHERE pack_id = ?", packId);
    }

    /** 写入包内通用条目（发布/覆盖快照） */
    public void insertWorldbookEntry(SharedWorldbookEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO shared_worldbook_entry (id, pack_id, title, keywords, content, enabled, sort_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                entry.getId(), entry.getPackId(), entry.getTitle(), entry.getKeywords(),
                entry.getContent(), entry.getEnabled(), entry.getSortOrder());
    }

    /** 写入包内冥星条目（发布/覆盖快照；category 随行携带，add-star-lorebook-category） */
    public void insertStarEntry(SharedStarEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO shared_star_entry (id, pack_id, star_name, warlord_name, lore, aliases, rollable, sort_order, category) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entry.getId(), entry.getPackId(), entry.getStarName(), entry.getWarlordName(),
                entry.getLore(), entry.getAliases(), entry.getRollable(), entry.getSortOrder(),
                entry.getCategory());
    }

    /**
     * 撤回（软下架）：置 revoked_at；已导入副本零清理（快照哲学，无回链）。
     * WHERE 带发布者锚点——非发布者撤回受影响行数为 0（供控制层判 404）。
     */
    public int revoke(String packId, String publisherTenantId, Instant revokedAt) {
        return jdbcTemplate.update(
                "UPDATE shared_lorebook_pack SET revoked_at = ? WHERE id = ? AND publisher_tenant_id = ? "
                        + "AND revoked_at IS NULL",
                java.sql.Timestamp.from(revokedAt), packId, publisherTenantId);
    }
}
