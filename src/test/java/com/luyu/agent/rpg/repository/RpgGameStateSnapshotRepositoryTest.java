package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RpgGameStateSnapshotRepository} 的单元测试（mock JdbcTemplate）。
 * <p>
 * 钉住 upsert 的 DELETE+INSERT 覆盖语义（连续重新生成同 turn 只留一份）
 * 与 prune 的保留窗口行为（仅删窗口外的最旧份数）。
 */
class RpgGameStateSnapshotRepositoryTest {

    private static final String GS_ID = "gs-1";
    private static final String TID = "t-test";

    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private RpgGameStateSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TID);
        jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        repository = new RpgGameStateSnapshotRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private GameState gameState() {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setCurrentLocation("清州城");
        gs.setFlags("{}");
        gs.setNpcStates("{}");
        gs.setPlayerStates("{\"money\":70}");
        return gs;
    }

    @Test
    void upsert先删后插保证同turn覆盖语义() {
        repository.upsert(gameState(), 3);

        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).update(
                eq("DELETE FROM rpg_game_state_snapshot WHERE game_state_id = ? AND turn = ? AND tenant_id = ?"),
                eq(GS_ID), eq(3), eq(TID));
        inOrder.verify(jdbcTemplate).update(
                eq("INSERT INTO rpg_game_state_snapshot (tenant_id, game_state_id, turn, current_location, flags, npc_states, player_states) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)"),
                eq(TID), eq(GS_ID), eq(3), eq("清州城"), eq("{}"), eq("{}"), eq("{\"money\":70}"));
    }

    @Test
    void prune仅删除保留窗口外的最旧快照() {
        // 现有快照轮次（降序）：8,7,6,5,4；保留 4 份 → 只删 turn=4
        when(jdbcTemplate.queryForList(
                eq("SELECT turn FROM rpg_game_state_snapshot WHERE game_state_id = ? AND tenant_id = ? ORDER BY turn DESC"),
                eq(Integer.class), eq(GS_ID), eq(TID)))
                .thenReturn(List.of(8, 7, 6, 5, 4));

        int removed = repository.prune(GS_ID, 4);

        org.mockito.Mockito.verify(jdbcTemplate, times(1)).update(
                eq("DELETE FROM rpg_game_state_snapshot WHERE game_state_id = ? AND turn = ? AND tenant_id = ?"),
                eq(GS_ID), eq(4), eq(TID));
        org.mockito.Mockito.verify(jdbcTemplate, times(1)).update(
                any(String.class), any(Object[].class));
        org.assertj.core.api.Assertions.assertThat(removed).isEqualTo(1);
    }

    @Test
    void prune窗口内不删任何快照() {
        when(jdbcTemplate.queryForList(
                eq("SELECT turn FROM rpg_game_state_snapshot WHERE game_state_id = ? AND tenant_id = ? ORDER BY turn DESC"),
                eq(Integer.class), eq(GS_ID), eq(TID)))
                .thenReturn(List.of(3, 2, 1, 0));

        int removed = repository.prune(GS_ID, 4);

        org.assertj.core.api.Assertions.assertThat(removed).isZero();
        verify(jdbcTemplate, times(0)).update(any(String.class), any(Object[].class));
    }
}
