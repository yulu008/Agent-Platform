package com.luyu.agent.rpg.story.repository;

import com.luyu.agent.rpg.story.model.StoryRun;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RpgStoryRunRepository} 单元测试（story-graph-model 任务 1.7）。
 * <p>
 * 钉住：SQL 形态与列顺序（save/update/findByGameStateId/deleteByGameStateId）、
 * 租户隔离（每条 SQL 绑 {@code tid()}）、一存档一运行副本的空结果 null 语义、
 * 存档删除级联删除返回行数。
 */
class RpgStoryRunRepositoryTest {

    private static final String TID_A = "tenant-a";
    private static final String TID_B = "tenant-b";
    private static final String GAME_STATE_ID = "gs-1";
    private static final String GRAPH_ID = "sg-1";

    private JdbcTemplate jdbcTemplate;
    private RpgStoryRunRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TID_A);
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RpgStoryRunRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StoryRun run() {
        StoryRun run = new StoryRun();
        run.setId("sr-1");
        run.setGameStateId(GAME_STATE_ID);
        run.setGraphId(GRAPH_ID);
        run.setCurrentNode("n1_01");
        run.setWalkedEdges("[\"e1_01\"]");
        run.setMaterializedBodies("{\"n1_01\":{\"pathHash\":\"h1\",\"narration\":\"开篇\"}}");
        run.setImproviseDepth(2);
        run.setRematerialize(false);
        return run;
    }

    @Test
    void save把租户绑进INSERT的tenant_id列() {
        repository.save(run());

        verify(jdbcTemplate).update(
                eq("INSERT INTO rpg_story_run (id, tenant_id, game_state_id, graph_id, current_node, " +
                        "walked_edges, materialized_bodies, improvise_depth, rematerialize, pending_gate, " +
                        "initial_player_states) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
                eq("sr-1"), eq(TID_A), eq(GAME_STATE_ID), eq(GRAPH_ID), eq("n1_01"),
                eq("[\"e1_01\"]"), eq("{\"n1_01\":{\"pathHash\":\"h1\",\"narration\":\"开篇\"}}"),
                eq(2), eq(false), isNull(), isNull());
    }

    @Test
    void save的空即兴深度与重物化标志落默认值() {
        StoryRun run = run();
        run.setImproviseDepth(null);
        run.setRematerialize(null);

        repository.save(run);

        verify(jdbcTemplate).update(any(String.class),
                eq("sr-1"), eq(TID_A), eq(GAME_STATE_ID), eq(GRAPH_ID), eq("n1_01"),
                eq("[\"e1_01\"]"), eq("{\"n1_01\":{\"pathHash\":\"h1\",\"narration\":\"开篇\"}}"),
                eq(0), eq(false), isNull(), isNull());
    }

    @Test
    void update的WHERE同时约束id与租户() {
        StoryRun run = run();
        run.setCurrentNode("n1_02");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");

        repository.update(run);

        verify(jdbcTemplate).update(
                eq("UPDATE rpg_story_run SET current_node = ?, walked_edges = ?, materialized_bodies = ?, " +
                        "improvise_depth = ?, rematerialize = ?, pending_gate = ?, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE id = ? AND tenant_id = ?"),
                eq("n1_02"), eq("[\"e1_01\",\"e1_02\"]"),
                eq("{\"n1_01\":{\"pathHash\":\"h1\",\"narration\":\"开篇\"}}"),
                eq(2), eq(false), isNull(), eq("sr-1"), eq(TID_A));
    }

    @Test
    void save透传开局基线() {
        StoryRun run = run();
        run.setInitialPlayerStates("{\"trust_信长\": 50}");

        repository.save(run);

        verify(jdbcTemplate).update(any(String.class),
                eq("sr-1"), eq(TID_A), eq(GAME_STATE_ID), eq(GRAPH_ID), eq("n1_01"),
                eq("[\"e1_01\"]"), eq("{\"n1_01\":{\"pathHash\":\"h1\",\"narration\":\"开篇\"}}"),
                eq(2), eq(false), isNull(), eq("{\"trust_信长\": 50}"));
    }

    @Test
    void save与update透传轨道闸指令() {
        StoryRun run = run();
        run.setPendingGate("【轨道收敛】拉回信任线");

        repository.save(run);
        repository.update(run);

        verify(jdbcTemplate).update(any(String.class),
                eq("sr-1"), eq(TID_A), eq(GAME_STATE_ID), eq(GRAPH_ID), eq("n1_01"),
                eq("[\"e1_01\"]"), eq("{\"n1_01\":{\"pathHash\":\"h1\",\"narration\":\"开篇\"}}"),
                eq(2), eq(false), eq("【轨道收敛】拉回信任线"), isNull());
        verify(jdbcTemplate).update(any(String.class),
                eq("n1_01"), eq("[\"e1_01\"]"),
                eq("{\"n1_01\":{\"pathHash\":\"h1\",\"narration\":\"开篇\"}}"),
                eq(2), eq(false), eq("【轨道收敛】拉回信任线"), eq("sr-1"), eq(TID_A));
        // UPDATE 不携带 initial_player_states：开局基线不可变（update 部分 8 参，无基线列）
    }

    @Test
    void findByGameStateId按存档与租户查询() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(GAME_STATE_ID), eq(TID_A)))
                .thenReturn(List.of(run()));

        StoryRun result = repository.findByGameStateId(GAME_STATE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getGraphId()).isEqualTo(GRAPH_ID);
        verify(jdbcTemplate).query(
                eq("SELECT * FROM rpg_story_run WHERE game_state_id = ? AND tenant_id = ?"),
                any(BeanPropertyRowMapper.class), eq(GAME_STATE_ID), eq(TID_A));
    }

    @Test
    void 无运行副本时返回null() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq("gs-x"), eq(TID_A)))
                .thenReturn(List.of());

        assertThat(repository.findByGameStateId("gs-x")).isNull();
    }

    @Test
    void 切换租户后查询换绑tenant_id实现A不可见B() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(GAME_STATE_ID), any(String.class)))
                .thenReturn(List.of());

        repository.findByGameStateId(GAME_STATE_ID);
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq(GAME_STATE_ID), eq(TID_A));

        TenantContext.set("u-other", TID_B);
        repository.findByGameStateId(GAME_STATE_ID);
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq(GAME_STATE_ID), eq(TID_B));
    }

    @Test
    void deleteByGameStateId级联删除存档运行副本() {
        when(jdbcTemplate.update(any(String.class), eq(GAME_STATE_ID), eq(TID_A))).thenReturn(1);

        int affected = repository.deleteByGameStateId(GAME_STATE_ID);

        assertThat(affected).isEqualTo(1);
        verify(jdbcTemplate).update(
                eq("DELETE FROM rpg_story_run WHERE game_state_id = ? AND tenant_id = ?"),
                eq(GAME_STATE_ID), eq(TID_A));
    }
}
