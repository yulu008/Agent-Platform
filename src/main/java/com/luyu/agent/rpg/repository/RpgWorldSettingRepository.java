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
                "INSERT INTO rpg_world_setting (id, tenant_id, name, setting_desc, era, rules, locations, atmosphere, opening_template, cover, theme_tokens, custom_blocks, narrative_rules) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                world.getId(), tid(), world.getName(), world.getSettingDesc(), world.getEra(),
                world.getRules(), world.getLocations(), world.getAtmosphere(), world.getOpeningTemplate(),
                world.getCover(), world.getThemeTokens(), world.getCustomBlocks(), world.getNarrativeRules());
    }

    public WorldSetting findById(String id) {
        List<WorldSetting> list = jdbcTemplate.query(
                "SELECT * FROM rpg_world_setting WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                BeanPropertyRowMapper.newInstance(WorldSetting.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    public List<WorldSetting> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_world_setting WHERE tenant_id = ? AND deleted_at IS NULL ORDER BY created_at DESC",
                BeanPropertyRowMapper.newInstance(WorldSetting.class), tid());
    }

    public void updateOpeningTemplate(String id, String openingTemplate) {
        jdbcTemplate.update(
                "UPDATE rpg_world_setting SET opening_template = ? WHERE id = ? AND tenant_id = ?",
                openingTemplate, id, tid());
    }

    /**
     * 编辑已有世界观（按 id 全量重写可编辑列，不动 opening_template）。
     * <p>
     * theme_tokens / custom_blocks 随表单全量重写（清空即存 NULL 回默认：主题
     * rpg-hud-bubble-components 4.1，内容块 rpg-hud-custom-blocks D7）。
     * 刻意不含 cover 列（world-card-gallery D7）：封面是用户资产而非表单派生列，
     * 工坊编辑与预设物化的全量重写都不得触碰它，cover 只经 {@link #updateCover} 变更。
     */
    public void update(WorldSetting world) {
        jdbcTemplate.update(
                "UPDATE rpg_world_setting SET name = ?, setting_desc = ?, era = ?, rules = ?, locations = ?, atmosphere = ?, theme_tokens = ?, custom_blocks = ? WHERE id = ? AND tenant_id = ?",
                world.getName(), world.getSettingDesc(), world.getEra(),
                world.getRules(), world.getLocations(), world.getAtmosphere(),
                world.getThemeTokens(), world.getCustomBlocks(), world.getId(), tid());
    }

    /** 封面列专用更新（上传/物化落默认封面走这里；NULL 语义 = 清除封面） */
    public void updateCover(String id, String cover) {
        jdbcTemplate.update(
                "UPDATE rpg_world_setting SET cover = ? WHERE id = ? AND tenant_id = ?",
                cover, id, tid());
    }

    /**
     * 软删除世界观（workshop-soft-delete-cascade D1）：写 deleted_at，物理行保留作数据安全网。
     */
    public int softDeleteById(String id) {
        return jdbcTemplate.update(
                "UPDATE rpg_world_setting SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                id, tid());
    }

    /**
     * 按 id 查软删行（workshop-soft-delete-cascade D7 预设物化复活前置；
     * 普通世界 id 为 UUID 不复用，此查询仅供固定 preset- 主键的复活路径）。
     */
    public WorldSetting findSoftDeletedById(String id) {
        List<WorldSetting> list = jdbcTemplate.query(
                "SELECT * FROM rpg_world_setting WHERE id = ? AND tenant_id = ? AND deleted_at IS NOT NULL",
                BeanPropertyRowMapper.newInstance(WorldSetting.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 复活软删行（清 deleted_at，对齐共享包 refreshPack 复活惯例）；cover 列不受影响 */
    public void reviveById(String id) {
        jdbcTemplate.update(
                "UPDATE rpg_world_setting SET deleted_at = NULL WHERE id = ? AND tenant_id = ?",
                id, tid());
    }
}
