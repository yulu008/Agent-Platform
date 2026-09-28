package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.Location;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 地点 Repository（对应 rpg_location 表）
 */
@Repository
public class RpgLocationRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgLocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Location location) {
        jdbcTemplate.update(
                "INSERT INTO rpg_location (id, tenant_id, world_id, name, description, npc_ids, trigger_ids) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                location.getId(), tid(), location.getWorldId(), location.getName(),
                location.getDescription(), location.getNpcIds(), location.getTriggerIds());
    }

    public Location findById(String id) {
        List<Location> list = jdbcTemplate.query(
                "SELECT * FROM rpg_location WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(Location.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Location> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_location WHERE world_id = ? AND tenant_id = ? ORDER BY name",
                BeanPropertyRowMapper.newInstance(Location.class), worldId, tid());
    }

    public void update(String id, String name, String description, String npcIds, String triggerIds) {
        jdbcTemplate.update(
                "UPDATE rpg_location SET name = ?, description = ?, npc_ids = ?, trigger_ids = ? WHERE id = ? AND tenant_id = ?",
                name, description, npcIds, triggerIds, id, tid());
    }

    /**
     * 该世界下的地点数（删除世界观前的阻塞校验）。
     */
    public int countByWorldId(String worldId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_location WHERE world_id = ? AND tenant_id = ?",
                Integer.class, worldId, tid());
        return count == null ? 0 : count;
    }
}
