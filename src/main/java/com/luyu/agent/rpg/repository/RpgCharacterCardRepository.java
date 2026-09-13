package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.CharacterCard;
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

    @Autowired
    public RpgCharacterCardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(CharacterCard card) {
        jdbcTemplate.update(
                "INSERT INTO rpg_character_card (id, world_id, name, type, identity, personality, background, motivation, speech_style, knowledge) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                card.getId(), card.getWorldId(), card.getName(), card.getType(),
                card.getIdentity(), card.getPersonality(), card.getBackground(),
                card.getMotivation(), card.getSpeechStyle(), card.getKnowledge());
    }

    public CharacterCard findById(String id) {
        List<CharacterCard> list = jdbcTemplate.query(
                "SELECT * FROM rpg_character_card WHERE id = ?",
                BeanPropertyRowMapper.newInstance(CharacterCard.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<CharacterCard> findByWorldId(String worldId) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_character_card WHERE world_id = ? ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(CharacterCard.class), worldId);
    }

    public List<CharacterCard> findByWorldIdAndType(String worldId, String type) {
        return jdbcTemplate.query(
                "SELECT * FROM rpg_character_card WHERE world_id = ? AND type = ? ORDER BY created_at",
                BeanPropertyRowMapper.newInstance(CharacterCard.class), worldId, type);
    }

    /**
     * 仅重写名字一列（一键中文化改名用）。
     * <p>
     * ID 不变，故 npcStates key / trigger.npcId / relationship 引用一旦 ID 化，
     * 后续改名永远只动本列。
     */
    public void updateName(String id, String name) {
        jdbcTemplate.update(
                "UPDATE rpg_character_card SET name = ? WHERE id = ?",
                name, id);
    }
}
