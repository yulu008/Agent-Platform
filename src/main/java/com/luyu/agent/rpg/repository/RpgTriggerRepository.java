package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.Trigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 触发器定义 Repository（对应 rpg_trigger 表）
 */
@Repository
public class RpgTriggerRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public RpgTriggerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Trigger trigger) {
        jdbcTemplate.update(
                "INSERT INTO rpg_trigger (id, world_id, type, npc_id, hard_conditions, soft_condition, action, cooldown, probability) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                trigger.getId(), trigger.getWorldId(), trigger.getType(),
                trigger.getNpcId(), trigger.getHardConditions(),
                trigger.getSoftCondition(), trigger.getAction(),
                trigger.getCooldown(), trigger.getProbability());
    }

    public Trigger findById(String id) {
        List<Trigger> list = jdbcTemplate.query(
                "SELECT * FROM rpg_trigger WHERE id = ?",
                BeanPropertyRowMapper.newInstance(Trigger.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Trigger> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger WHERE world_id = ? ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(Trigger.class), worldId);
    }

    public List<Trigger> findByNpcId(String npcId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger WHERE npc_id = ? ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(Trigger.class), npcId);
    }

    /**
     * 仅重写关联 NPC 引用（一键中文化改名时将旧名修正为角色卡 ID）。
     */
    public void updateNpcId(String id, String npcId) {
        jdbcTemplate.update(
                "UPDATE rpg_trigger SET npc_id = ? WHERE id = ?",
                npcId, id);
    }
}
