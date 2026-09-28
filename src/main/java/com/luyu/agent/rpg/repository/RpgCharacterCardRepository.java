package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 角色卡 Repository（对应 rpg_character_card 表）
 */
@Repository
public class RpgCharacterCardRepository {

    private final JdbcTemplate jdbcTemplate;

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgCharacterCardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(CharacterCard card) {
        jdbcTemplate.update(
                "INSERT INTO rpg_character_card (id, tenant_id, world_id, name, type, identity, personality, background, motivation, speech_style, knowledge) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                card.getId(), tid(), card.getWorldId(), card.getName(), card.getType(),
                card.getIdentity(), card.getPersonality(), card.getBackground(),
                card.getMotivation(), card.getSpeechStyle(), card.getKnowledge());
    }

    public CharacterCard findById(String id) {
        List<CharacterCard> list = jdbcTemplate.query(
                "SELECT * FROM rpg_character_card WHERE id = ? AND tenant_id = ?",
                BeanPropertyRowMapper.newInstance(CharacterCard.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    public List<CharacterCard> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_character_card WHERE world_id = ? AND tenant_id = ? ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(CharacterCard.class), worldId, tid());
    }

    public List<CharacterCard> findByWorldIdAndType(String worldId, String type) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_character_card WHERE world_id = ? AND type = ? AND tenant_id = ? ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(CharacterCard.class), worldId, type, tid());
    }

    /**
     * 仅重写名字一列（一键中文化改名用）。
     * <p>
     * ID 不变，故 npcStates key / trigger.npcId / relationship 引用一旦 ID 化，
     * 后续改名永远只动本列。
     */
    public void updateName(String id, String name) {
        jdbcTemplate.update(
                "UPDATE rpg_character_card SET name = ? WHERE id = ? AND tenant_id = ?",
                name, id, tid());
    }

    /**
     * 编辑已有角色卡（按 id 全量重写可编辑列）。
     */
    public void update(CharacterCard card) {
        jdbcTemplate.update(
                "UPDATE rpg_character_card SET world_id = ?, name = ?, type = ?, identity = ?, personality = ?, " +
                        "background = ?, motivation = ?, speech_style = ?, knowledge = ? WHERE id = ? AND tenant_id = ?",
                card.getWorldId(), card.getName(), card.getType(), card.getIdentity(),
                card.getPersonality(), card.getBackground(), card.getMotivation(),
                card.getSpeechStyle(), card.getKnowledge(), card.getId(), tid());
    }

    /**
     * 仅重写类型列（开始冒险时将被选角色升级为 player）。
     */
    public void updateType(String id, String type) {
        jdbcTemplate.update(
                "UPDATE rpg_character_card SET type = ? WHERE id = ? AND tenant_id = ?",
                type, id, tid());
    }

    /**
     * 该世界下的角色数（删除世界观前的阻塞校验）。
     */
    public int countByWorldId(String worldId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_character_card WHERE world_id = ? AND tenant_id = ?",
                Integer.class, worldId, tid());
        return count == null ? 0 : count;
    }

    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM rpg_character_card WHERE id = ? AND tenant_id = ?", id, tid());
    }
}
