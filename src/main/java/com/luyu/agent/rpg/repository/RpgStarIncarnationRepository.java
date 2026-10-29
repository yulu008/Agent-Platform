package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 冥星武将录 Repository（对应 rpg_star_incarnation 表，世界级静态名录）。
 * <p>
 * 真源为 md，物化时按 {@code world_id} 全量重写（delete + insert，id 换新）；
 * 跨物化的稳定关联键是 {@code star_name}（见 runtime 表冗余列）。
 */
@Repository
public class RpgStarIncarnationRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgStarIncarnationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(StarIncarnation incarnation) {
        jdbcTemplate.update(
                "INSERT INTO rpg_star_incarnation (id, tenant_id, world_id, star_name, warlord_name, lore, aliases, rollable, sort_order, set_id, source_ref, category) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                incarnation.getId(), tid(), incarnation.getWorldId(), incarnation.getStarName(),
                incarnation.getWarlordName(), incarnation.getLore(), incarnation.getAliases(),
                incarnation.getRollable(), incarnation.getSortOrder(),
                incarnation.getSetId(), incarnation.getSourceRef(), incarnation.getCategory());
    }

    /** 更新单行（set 行编辑用；模板行仍走 rewriteAll 全量重写） */
    public void update(StarIncarnation incarnation) {
        jdbcTemplate.update(
                "UPDATE rpg_star_incarnation SET star_name = ?, warlord_name = ?, lore = ?, aliases = ?, rollable = ?, sort_order = ?, category = ? " +
                        "WHERE id = ? AND set_id = ? AND tenant_id = ?",
                incarnation.getStarName(), incarnation.getWarlordName(), incarnation.getLore(),
                incarnation.getAliases(), incarnation.getRollable(), incarnation.getSortOrder(), incarnation.getCategory(),
                incarnation.getId(), incarnation.getSetId(), tid());
    }

    /**
     * 按世界全量重写名录（物化幂等路径）：先删后插。
     * <p>
     * 物化本身幂等可重跑，中途失败无需补偿——再次物化即完整重写。
     * 重写范围仅限模板行（set_id IS NULL，add-rpg-lorebook-sets D3）：set 行不受物化影响。
     */
    public void rewriteAll(String worldId, List<StarIncarnation> incarnations) {
        jdbcTemplate.update(
                "DELETE FROM rpg_star_incarnation WHERE world_id = ? AND set_id IS NULL AND tenant_id = ?", worldId, tid());
        for (StarIncarnation incarnation : incarnations) {
            if (incarnation.getId() == null || incarnation.getId().isBlank()) {
                incarnation.setId(UUID.randomUUID().toString());
            }
            save(incarnation);
        }
    }

    /** 按世界查模板名录（set_id IS NULL 且未软删，按 md 内出现顺序排序）；名录未物化时为空列表 */
    public List<StarIncarnation> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_star_incarnation WHERE world_id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL ORDER BY sort_order, id",
                BeanPropertyRowMapper.newInstance(StarIncarnation.class), worldId, tid());
    }

    /** 按 set 查名录行（存档副本视图，按 sort_order） */
    public List<StarIncarnation> findBySetId(String setId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_star_incarnation WHERE set_id = ? AND tenant_id = ? ORDER BY sort_order, id",
                BeanPropertyRowMapper.newInstance(StarIncarnation.class), setId, tid());
    }

    public StarIncarnation findById(String id) {
        List<StarIncarnation> list = jdbcTemplate.query(
                "SELECT * FROM rpg_star_incarnation WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                BeanPropertyRowMapper.newInstance(StarIncarnation.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按模板行定位（workshop-soft-delete-cascade D8：工坊冥星轨 CRUD 锚点，
     * set 行与软删行均不可见，防误改存档副本）。
     */
    public StarIncarnation findTemplateById(String id) {
        List<StarIncarnation> list = jdbcTemplate.query(
                "SELECT * FROM rpg_star_incarnation WHERE id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL",
                BeanPropertyRowMapper.newInstance(StarIncarnation.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 更新单条模板行（工坊冥星轨编辑用；set 行仍走 {@link #update}） */
    public void updateTemplate(StarIncarnation incarnation) {
        jdbcTemplate.update(
                "UPDATE rpg_star_incarnation SET star_name = ?, warlord_name = ?, lore = ?, aliases = ?, rollable = ?, sort_order = ?, category = ? "
                        + "WHERE id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL",
                incarnation.getStarName(), incarnation.getWarlordName(), incarnation.getLore(),
                incarnation.getAliases(), incarnation.getRollable(), incarnation.getSortOrder(), incarnation.getCategory(),
                incarnation.getId(), tid());
    }

    /**
     * 软删除单条模板行（workshop-soft-delete-cascade D8；存档 set 副本快照哲学不受影响）。
     */
    public int softDeleteTemplateById(String id) {
        return jdbcTemplate.update(
                "UPDATE rpg_star_incarnation SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL",
                id, tid());
    }

    /** 软删除某世界全部模板行（workshop-soft-delete-cascade D3：世界级联，set 行不动） */
    public int softDeleteTemplatesByWorldId(String worldId) {
        return jdbcTemplate.update(
                "UPDATE rpg_star_incarnation SET deleted_at = CURRENT_TIMESTAMP WHERE world_id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL",
                worldId, tid());
    }

    /** 删除 set 内单行（限定 set 归属，防跨副本误删），返回受影响行数 */
    public int deleteByIdAndSet(String id, String setId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_star_incarnation WHERE id = ? AND set_id = ? AND tenant_id = ?", id, setId, tid());
    }

    /** 删除某 set 的全部行（set 删除用） */
    public int deleteBySetId(String setId) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_star_incarnation WHERE set_id = ? AND tenant_id = ?", setId, tid());
    }
}
