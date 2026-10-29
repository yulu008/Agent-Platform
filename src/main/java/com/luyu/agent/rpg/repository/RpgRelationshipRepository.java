package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 角色间关系 Repository（对应 rpg_relationship 表）
 * <p>
 * 双向查询：findByCharId 同时匹配 char_a_id 和 char_b_id。
 */
@Repository
public class RpgRelationshipRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgRelationshipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Relationship rel) {
        jdbcTemplate.update(
                "INSERT INTO rpg_relationship (id, tenant_id, char_a_id, char_b_id, attitude, trust, notes) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                rel.getId(), tid(), rel.getCharAId(), rel.getCharBId(),
                rel.getAttitude(), rel.getTrust(), rel.getNotes());
    }

    /**
     * 双向查询：匹配 char_a_id 或 char_b_id（仅未软删）
     */
    public List<Relationship> findByCharId(String charId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE (char_a_id = ? OR char_b_id = ?) AND tenant_id = ? AND deleted_at IS NULL",
                BeanPropertyRowMapper.newInstance(Relationship.class), charId, charId, tid());
    }

    public Relationship findById(String id) {
        List<Relationship> list = jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                BeanPropertyRowMapper.newInstance(Relationship.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 编辑已有关系（workshop-soft-delete-cascade：按 id 全量重写两端引用与内容列，仅未软删行）。
     */
    public void update(Relationship rel) {
        jdbcTemplate.update(
                "UPDATE rpg_relationship SET char_a_id = ?, char_b_id = ?, attitude = ?, trust = ?, notes = ? "
                        + "WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                rel.getCharAId(), rel.getCharBId(), rel.getAttitude(), rel.getTrust(), rel.getNotes(),
                rel.getId(), tid());
    }

    public void updateAttitude(String id, Integer attitude, Integer trust) {
        jdbcTemplate.update(
                "UPDATE rpg_relationship SET attitude = ?, trust = ? WHERE id = ? AND tenant_id = ?",
                attitude, trust, id, tid());
    }

    /**
     * 本租户的全量关系（仅未软删；本表无 world_id 列，一键中文化改名时需遍历判定两端引用是否命中旧名）。
     */
    public List<Relationship> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE tenant_id = ? AND deleted_at IS NULL ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(Relationship.class), tid());
    }

    /**
     * 重写两端角色引用（旧名 → 角色卡 ID）。
     */
    public void updateCharRefs(String id, String charAId, String charBId) {
        jdbcTemplate.update(
                "UPDATE rpg_relationship SET char_a_id = ?, char_b_id = ? WHERE id = ? AND tenant_id = ?",
                charAId, charBId, id, tid());
    }

    /**
     * 引用该角色的关系数（双向，仅未软删；角色级联软删后即归零）。
     */
    public int countByCharId(String charId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_relationship WHERE (char_a_id = ? OR char_b_id = ?) AND tenant_id = ? AND deleted_at IS NULL",
                Integer.class, charId, charId, tid());
        return count == null ? 0 : count;
    }

    /**
     * 按同对角色查软删行（workshop-soft-delete-cascade D6 复活前置；双向匹配，
     * uk_relationship 唯一约束使同对角色至多一行——含软删态）。仅在保存路径调用。
     */
    public Relationship findSoftDeletedByPair(String charAId, String charBId) {
        List<Relationship> list = jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE ((char_a_id = ? AND char_b_id = ?) OR (char_a_id = ? AND char_b_id = ?)) "
                        + "AND tenant_id = ? AND deleted_at IS NOT NULL",
                BeanPropertyRowMapper.newInstance(Relationship.class), charAId, charBId, charBId, charAId, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    /** 复活软删行并覆写内容（workshop-soft-delete-cascade D6：清 deleted_at，对齐共享包 refreshPack 复活惯例） */
    public void revive(Relationship rel) {
        jdbcTemplate.update(
                "UPDATE rpg_relationship SET char_a_id = ?, char_b_id = ?, attitude = ?, trust = ?, notes = ?, deleted_at = NULL "
                        + "WHERE id = ? AND tenant_id = ?",
                rel.getCharAId(), rel.getCharBId(), rel.getAttitude(), rel.getTrust(), rel.getNotes(),
                rel.getId(), tid());
    }

    /** 软删除单个关系（workshop-soft-delete-cascade D5：三件套无条件软删） */
    public int softDeleteById(String id) {
        return jdbcTemplate.update(
                "UPDATE rpg_relationship SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                id, tid());
    }

    /** 软删除引用某角色的全部关系（workshop-soft-delete-cascade D4：角色级联，双向匹配） */
    public int softDeleteByCharId(String charId) {
        return jdbcTemplate.update(
                "UPDATE rpg_relationship SET deleted_at = CURRENT_TIMESTAMP WHERE (char_a_id = ? OR char_b_id = ?) AND tenant_id = ? AND deleted_at IS NULL",
                charId, charId, tid());
    }
}
