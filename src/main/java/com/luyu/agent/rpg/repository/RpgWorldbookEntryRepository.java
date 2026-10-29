package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通用世界书条目 Repository（对应 rpg_worldbook_entry 表，世界级可编辑）。
 * <p>
 * 全量 CRUD 由工坊维护（区别于冥星武将录的 md 物化全量重写）；所有 SQL 强制带 {@code tenant_id}
 * 过滤——缺失即快速失败，绝不静默跨租户（对齐 {@link RpgWorldSettingRepository}）。
 */
@Repository
public class RpgWorldbookEntryRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgWorldbookEntryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(WorldbookEntry entry) {
        jdbcTemplate.update(
                "INSERT INTO rpg_worldbook_entry (id, tenant_id, world_id, title, keywords, content, enabled, sort_order, set_id, source_ref) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entry.getId(), tid(), entry.getWorldId(), entry.getTitle(),
                entry.getKeywords(), entry.getContent(), entry.getEnabled(), entry.getSortOrder(),
                entry.getSetId(), entry.getSourceRef());
    }

    public void update(WorldbookEntry entry) {
        jdbcTemplate.update(
                "UPDATE rpg_worldbook_entry SET world_id = ?, title = ?, keywords = ?, content = ?, enabled = ?, sort_order = ? " +
                        "WHERE id = ? AND tenant_id = ?",
                entry.getWorldId(), entry.getTitle(), entry.getKeywords(), entry.getContent(),
                entry.getEnabled(), entry.getSortOrder(), entry.getId(), tid());
    }

    public WorldbookEntry findById(String id) {
        List<WorldbookEntry> list = jdbcTemplate.query(
                "SELECT * FROM rpg_worldbook_entry WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                BeanPropertyRowMapper.newInstance(WorldbookEntry.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按世界查全部模板条目（set_id IS NULL 且未软删，含停用），按 sort_order/创建时间排序；无条目时为空列表 */
    public List<WorldbookEntry> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_worldbook_entry WHERE world_id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL ORDER BY sort_order, created_at, id",
                BeanPropertyRowMapper.newInstance(WorldbookEntry.class), worldId, tid());
    }

    /** 按 set 查全部条目（存档副本视图，含停用） */
    public List<WorldbookEntry> findBySetId(String setId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_worldbook_entry WHERE set_id = ? AND tenant_id = ? ORDER BY sort_order, created_at, id",
                BeanPropertyRowMapper.newInstance(WorldbookEntry.class), setId, tid());
    }

    /**
     * 软删除单条模板行（workshop-soft-delete-cascade D1/D10：工坊删除统一软删，
     * 限模板层 set_id IS NULL，set 行硬删路径不受影响），返回受影响行数
     * （0 = 不存在/不属于当前租户/set 行/已软删，供控制层判 404）。
     */
    public int softDeleteTemplateById(String id) {
        return jdbcTemplate.update(
                "UPDATE rpg_worldbook_entry SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL",
                id, tid());
    }

    /** 删除 set 内单条（限定 set 归属，防跨副本误删），返回受影响行数 */
    public int deleteByIdAndSet(String id, String setId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_worldbook_entry WHERE id = ? AND set_id = ? AND tenant_id = ?", id, setId, tid());
    }

    /** 删除某 set 的全部条目（set 删除用） */
    public int deleteBySetId(String setId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_worldbook_entry WHERE set_id = ? AND tenant_id = ?", setId, tid());
    }
}
