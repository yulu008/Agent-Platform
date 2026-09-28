package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.tenancy.TenantContext;
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

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgWorldSettingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(WorldSetting world) {
        jdbcTemplate.update(
                "INSERT INTO rpg_world_setting (id, tenant_id, name, setting_desc, era, rules, locations, atmosphere, opening_template) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                world.getId(), tid(), world.getName(), world.getSettingDesc(), world.getEra(),
                world.getRules(), world.getLocations(), world.getAtmosphere(), world.getOpeningTemplate());
    }

    public WorldSetting findById(String id) {
        List<WorldSetting> list = jdbcTemplate.query(
                "SELECT * FROM rpg_world_setting WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(WorldSetting.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    public List<WorldSetting> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_world_setting WHERE tenant_id = ? ORDER BY created_at DESC",
                BeanPropertyRowMapper.newInstance(WorldSetting.class), tid());
    }

    public void updateOpeningTemplate(String id, String openingTemplate) {
        jdbcTemplate.update(
                "UPDATE rpg_world_setting SET opening_template = ? WHERE id = ? AND tenant_id = ?",
                openingTemplate, id, tid());
    }

    /**
     * 编辑已有世界观（按 id 全量重写可编辑列，不动 opening_template）。
     */
    public void update(WorldSetting world) {
        jdbcTemplate.update(
                "UPDATE rpg_world_setting SET name = ?, setting_desc = ?, era = ?, rules = ?, locations = ?, atmosphere = ? WHERE id = ? AND tenant_id = ?",
                world.getName(), world.getSettingDesc(), world.getEra(),
                world.getRules(), world.getLocations(), world.getAtmosphere(), world.getId(), tid());
    }

    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM rpg_world_setting WHERE id = ? AND tenant_id = ?", id, tid());
    }
}
