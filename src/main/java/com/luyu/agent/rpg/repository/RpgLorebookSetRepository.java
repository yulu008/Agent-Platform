package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 世界书副本元数据 Repository（对应 rpg_lorebook_set 表，add-rpg-lorebook-sets D2）。
 * <p>
 * 所有 SQL 强制带 {@code tenant_id} 过滤——缺失即快速失败，绝不静默跨租户
 * （对齐 {@link RpgWorldSettingRepository}）。孤儿判定 = {@code game_state_id IS NULL}。
 */
@Repository
public class RpgLorebookSetRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgLorebookSetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(LorebookSet set) {
        jdbcTemplate.update(
                "INSERT INTO rpg_lorebook_set (id, tenant_id, world_id, name, origin, game_state_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                set.getId(), tid(), set.getWorldId(), set.getName(), set.getOrigin(), set.getGameStateId());
    }

    public LorebookSet findById(String id) {
        List<LorebookSet> list = jdbcTemplate.query(
                "SELECT * FROM rpg_lorebook_set WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(LorebookSet.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 存档引用的 set（1:1）；存档无 set（老存档/未开局物化）时返回 null */
    public LorebookSet findByGameStateId(String gameStateId) {
        List<LorebookSet> list = jdbcTemplate.query(
                "SELECT * FROM rpg_lorebook_set WHERE game_state_id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(LorebookSet.class), gameStateId, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 本世界全部 set（含孤儿，按创建时间序，供开局来源步列表） */
    public List<LorebookSet> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_lorebook_set WHERE world_id = ? AND tenant_id = ? ORDER BY created_at, id",
                BeanPropertyRowMapper.newInstance(LorebookSet.class), worldId, tid());
    }

    /** 存档删除孤儿化（D7）：引用置空，set 与条目行保留 */
    public int orphanizeByGameStateId(String gameStateId) {
        return jdbcTemplate.update(
                "UPDATE rpg_lorebook_set SET game_state_id = NULL WHERE game_state_id = ? AND tenant_id = ?",
                gameStateId, tid());
    }

    /** 删除孤儿 set（仅 game_state_id NULL 者命中，被引用者返回 0 供控制层判 409） */
    public int deleteOrphan(String id) {
        return jdbcTemplate.update(
                "DELETE FROM rpg_lorebook_set WHERE id = ? AND game_state_id IS NULL AND tenant_id = ?",
                id, tid());
    }
}
