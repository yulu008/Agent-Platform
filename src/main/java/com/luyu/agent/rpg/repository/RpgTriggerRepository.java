package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.tenancy.TenantContext;
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

    /** 当前租户（所有 SQL 的强制过滤参数；缺失即快速失败，绝不静默跨租户） */
    private static String tid() {
        return TenantContext.requireTenantId();
    }

    @Autowired
    public RpgTriggerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Trigger trigger) {
        jdbcTemplate.update(
                "INSERT INTO rpg_trigger (id, tenant_id, world_id, type, npc_id, hard_conditions, soft_condition, action, cooldown, probability) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                trigger.getId(), tid(), trigger.getWorldId(), trigger.getType(),
                trigger.getNpcId(), trigger.getHardConditions(),
                trigger.getSoftCondition(), trigger.getAction(),
                trigger.getCooldown(), trigger.getProbability());
    }

    public Trigger findById(String id) {
        List<Trigger> list = jdbcTemplate.query(
                "SELECT * FROM rpg_trigger WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                BeanPropertyRowMapper.newInstance(Trigger.class), id, tid());
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Trigger> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger WHERE world_id = ? AND tenant_id = ? AND deleted_at IS NULL ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(Trigger.class), worldId, tid());
    }

    public List<Trigger> findByNpcId(String npcId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_trigger WHERE npc_id = ? AND tenant_id = ? AND deleted_at IS NULL ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(Trigger.class), npcId, tid());
    }

    /**
     * 仅重写关联 NPC 引用（一键中文化改名时将旧名修正为角色卡 ID）。
     */
    public void updateNpcId(String id, String npcId) {
        jdbcTemplate.update(
                "UPDATE rpg_trigger SET npc_id = ? WHERE id = ? AND tenant_id = ?",
                npcId, id, tid());
    }

    /**
     * 编辑已有触发器（workshop-soft-delete-cascade：按 id 全量重写可编辑列，仅未软删行）。
     */
    public void update(Trigger trigger) {
        jdbcTemplate.update(
                "UPDATE rpg_trigger SET world_id = ?, type = ?, npc_id = ?, hard_conditions = ?, soft_condition = ?, "
                        + "action = ?, cooldown = ?, probability = ? WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                trigger.getWorldId(), trigger.getType(), trigger.getNpcId(), trigger.getHardConditions(),
                trigger.getSoftCondition(), trigger.getAction(), trigger.getCooldown(), trigger.getProbability(),
                trigger.getId(), tid());
    }

    /**
     * 关联该角色的触发器数（未软删；角色级联软删后即归零）。
     */
    public int countByNpcId(String npcId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_trigger WHERE npc_id = ? AND tenant_id = ? AND deleted_at IS NULL",
                Integer.class, npcId, tid());
        return count == null ? 0 : count;
    }

    /**
     * 该世界下的触发器数（未软删；工坊级联软删后即归零）。
     */
    public int countByWorldId(String worldId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_trigger WHERE world_id = ? AND tenant_id = ? AND deleted_at IS NULL",
                Integer.class, worldId, tid());
        return count == null ? 0 : count;
    }

    /** 软删除单个触发器（workshop-soft-delete-cascade D5：引擎查询过滤后对进行中存档立即停止触发） */
    public int softDeleteById(String id) {
        return jdbcTemplate.update(
                "UPDATE rpg_trigger SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL",
                id, tid());
    }

    /** 软删除关联某角色的全部触发器（workshop-soft-delete-cascade D4：角色级联） */
    public int softDeleteByNpcId(String npcId) {
        return jdbcTemplate.update(
                "UPDATE rpg_trigger SET deleted_at = CURRENT_TIMESTAMP WHERE npc_id = ? AND tenant_id = ? AND deleted_at IS NULL",
                npcId, tid());
    }

    /** 软删除某世界全部触发器（workshop-soft-delete-cascade D3：世界级联） */
    public int softDeleteByWorldId(String worldId) {
        return jdbcTemplate.update(
                "UPDATE rpg_trigger SET deleted_at = CURRENT_TIMESTAMP WHERE world_id = ? AND tenant_id = ? AND deleted_at IS NULL",
                worldId, tid());
    }
}
