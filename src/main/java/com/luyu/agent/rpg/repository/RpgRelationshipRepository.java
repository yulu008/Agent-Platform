package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.Relationship;
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

    @Autowired
    public RpgRelationshipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Relationship rel) {
        jdbcTemplate.update(
                "INSERT INTO rpg_relationship (id, char_a_id, char_b_id, attitude, trust, notes) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                rel.getId(), rel.getCharAId(), rel.getCharBId(),
                rel.getAttitude(), rel.getTrust(), rel.getNotes());
    }

    /**
     * 双向查询：匹配 char_a_id 或 char_b_id
     */
    public List<Relationship> findByCharId(String charId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE char_a_id = ? OR char_b_id = ?",
                BeanPropertyRowMapper.newInstance(Relationship.class), charId, charId);
    }

    public Relationship findById(String id) {
        List<Relationship> list = jdbcTemplate.query(
                "SELECT * FROM rpg_relationship WHERE id = ?",
                BeanPropertyRowMapper.newInstance(Relationship.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateAttitude(String id, Integer attitude, Integer trust) {
        jdbcTemplate.update(
                "UPDATE rpg_relationship SET attitude = ?, trust = ? WHERE id = ?",
                attitude, trust, id);
    }

    /**
     * 全量关系（本表无 world_id 列，一键中文化改名时需遍历判定两端引用是否命中旧名）。
     */
    public List<Relationship> findAll() {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_relationship ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(Relationship.class));
    }

    /**
     * 重写两端角色引用（旧名 → 角色卡 ID）。
     */
    public void updateCharRefs(String id, String charAId, String charBId) {
        jdbcTemplate.update(
                "UPDATE rpg_relationship SET char_a_id = ?, char_b_id = ? WHERE id = ?",
                charAId, charBId, id);
    }
}
