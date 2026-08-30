package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.WorldSetting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 世界观设定 Repository（对应 rpg_world_setting 表）
 */
@Repository
public class RpgWorldSettingRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public RpgWorldSettingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(WorldSetting world) {
        jdbcTemplate.update(
                "INSERT INTO rpg_world_setting (id, name, setting_desc, era, rules, locations, atmosphere, opening_template) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                world.getId(), world.getName(), world.getSettingDesc(), world.getEra(),
                world.getRules(), world.getLocations(), world.getAtmosphere(), world.getOpeningTemplate());
    }

    public WorldSetting findById(String id) {
        List<WorldSetting> list = jdbcTemplate.query(
                "SELECT * FROM rpg_world_setting WHERE id = ?",
                BeanPropertyRowMapper.newInstance(WorldSetting.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<WorldSetting> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_world_setting ORDER BY created_at DESC",
                BeanPropertyRowMapper.newInstance(WorldSetting.class));
    }

    public void updateOpeningTemplate(String id, String openingTemplate) {
        jdbcTemplate.update(
                "UPDATE rpg_world_setting SET opening_template = ? WHERE id = ?",
                openingTemplate, id);
    }
}
