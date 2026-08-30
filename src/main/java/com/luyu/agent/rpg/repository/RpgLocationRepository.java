package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.Location;
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

    @Autowired
    public RpgLocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Location location) {
        jdbcTemplate.update(
                "INSERT INTO rpg_location (id, world_id, name, description, npc_ids, trigger_ids) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                location.getId(), location.getWorldId(), location.getName(),
                location.getDescription(), location.getNpcIds(), location.getTriggerIds());
    }

    public Location findById(String id) {
        List<Location> list = jdbcTemplate.query(
                "SELECT * FROM rpg_location WHERE id = ?",
                BeanPropertyRowMapper.newInstance(Location.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Location> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_location WHERE world_id = ? ORDER BY name",
                BeanPropertyRowMapper.newInstance(Location.class), worldId);
    }

    public void update(String id, String name, String description, String npcIds, String triggerIds) {
        jdbcTemplate.update(
                "UPDATE rpg_location SET name = ?, description = ?, npc_ids = ?, trigger_ids = ? WHERE id = ?",
                name, description, npcIds, triggerIds, id);
    }
}
