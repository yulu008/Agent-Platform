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
     * 双向查询：匹配 char_a_id 或 char_b_id
     */
    public List<Relationship> findByCharId(String charId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE (char_a_id = ? OR char_b_id = ?) AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(Relationship.class), charId, charId, tid());
    }

    public Relationship findById(String id) {
        List<Relationship> list = jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(Relationship.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateAttitude(String id, Integer attitude, Integer trust) {
        jdbcTemplate.update(
                "UPDATE rpg_relationship SET attitude = ?, trust = ? WHERE id = ? AND tenant_id = ?",
                attitude, trust, id, tid());
    }

    /**
     * 本租户的全量关系（本表无 world_id 列，一键中文化改名时需遍历判定两端引用是否命中旧名）。
     */
    public List<Relationship> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE tenant_id = ? ORDER BY created_at",
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
     * 引用该角色的关系数（双向；删除角色前的阻塞校验）。
     */
    public int countByCharId(String charId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_relationship WHERE (char_a_id = ? OR char_b_id = ?) AND tenant_id = ?",
                Integer.class, charId, charId, tid());
        return count == null ? 0 : count;
    }
}
