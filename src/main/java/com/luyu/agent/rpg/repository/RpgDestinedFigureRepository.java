package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 命定人物 Repository（对应 rpg_destined_figure 表，世界级静态档案）。
 * <p>
 * 真源为 md，物化时按 {@code world_id} 全量重写（delete + insert，id 换新）；
 * 跨物化的稳定关联键是 {@code figure_key}。命定人物无存档副本与软删轨
 * （档案是只读静态数据，扮演状态在 playerStates 与抢星 runtime 中），故仅提供
 * 物化写入与按世界查询两类方法。
 */
@Repository
public class RpgDestinedFigureRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgDestinedFigureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(DestinedFigure figure) {
        jdbcTemplate.update(
                "INSERT INTO rpg_destined_figure (id, tenant_id, world_id, figure_key, display_name, " +
                        "faction, constellation, star_rank, era_note, identity, personality, background, aliases, sort_order) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                figure.getId(), tid(), figure.getWorldId(), figure.getFigureKey(), figure.getDisplayName(),
                figure.getFaction(), figure.getConstellation(), figure.getStarRank(),
                figure.getEraNote(), figure.getIdentity(), figure.getPersonality(),
                figure.getBackground(), figure.getAliases(), figure.getSortOrder());
    }

    /**
     * 按世界全量重写人物档案（物化幂等路径）：先删后插。
     * <p>
     * 物化本身幂等可重跑，中途失败无需补偿——再次物化即完整重写。
     */
    public void rewriteAll(String worldId, List<DestinedFigure> figures) {
        jdbcTemplate.update(
                "DELETE FROM rpg_destined_figure WHERE world_id = ? AND tenant_id = ?", worldId, tid());
        for (DestinedFigure figure : figures) {
            if (figure.getId() == null || figure.getId().isBlank()) {
                figure.setId(UUID.randomUUID().toString());
            }
            save(figure);
        }
    }

    /** 按世界查人物档案（按 md 内出现顺序排序）；档案未物化时为空列表 */
    public List<DestinedFigure> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_destined_figure WHERE world_id = ? AND tenant_id = ? ORDER BY sort_order, id",
                BeanPropertyRowMapper.newInstance(DestinedFigure.class), worldId, tid());
    }
}
