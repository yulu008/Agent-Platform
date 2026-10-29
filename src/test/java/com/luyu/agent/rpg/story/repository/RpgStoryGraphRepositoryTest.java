package com.luyu.agent.rpg.story.repository;

import com.luyu.agent.rpg.story.model.StoryGraph;
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
 * {@link RpgStoryGraphRepository} 单元测试（story-graph-model 任务 1.7）。
 * <p>
 * 钉住三件事（对齐 {@code RpgWorldbookEntryRepositoryTest} 的 SQL/参数钉住风格）：
 * <ol>
 *   <li>CRUD 的 SQL 形态与列顺序（save/update/findById/findByWorldId/existsByWorldId/deleteById）；</li>
 *   <li>租户隔离——每条 SQL 绑 {@code tid()} 进 {@code tenant_id} 过滤位，切换租户即换绑参
 *       （spec Scenario：跨租户图不可达，控制层映射 404 不泄露存在性）；</li>
 *   <li>{@code findByWorldId} 返回 world 下全部图按创建时间排序（spec Scenario：一 world
 *       多图共存，两图并存互不干扰，工坊按 world 维度列出）。</li>
 * </ol>
 */
class RpgStoryGraphRepositoryTest {

    private static final String TID_A = "tenant-a";
    private static final String TID_B = "tenant-b";
    private static final String WORLD_ID = "world-1";

    private JdbcTemplate jdbcTemplate;
    private RpgStoryGraphRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TID_A);
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RpgStoryGraphRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private StoryGraph graph(String id) {
        StoryGraph graph = new StoryGraph(id, TID_A, WORLD_ID, "图 " + id);
        graph.setImproviseMode(StoryGraph.IMPROVISE_BOUNDED);
        graph.setStateSchema("{\"trust_信长\": \"number\"}");
        graph.setGraphJson("[{\"id\":\"act1\"}]");
        return graph;
    }

    @Test
    void save把租户绑进INSERT的tenant_id列() {
        repository.save(graph("sg-1"));

        verify(jdbcTemplate).update(
                eq("INSERT INTO rpg_story_graph (id, tenant_id, world_id, name, improvise_mode, improvise_depth_limit, state_schema, graph_json) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)"),
                eq("sg-1"), eq(TID_A), eq(WORLD_ID), eq("图 sg-1"),
                eq(StoryGraph.IMPROVISE_BOUNDED), isNull(),
                eq("{\"trust_信长\": \"number\"}"), eq("[{\"id\":\"act1\"}]"));
    }

    @Test
    void update的WHERE同时约束id与租户() {
        StoryGraph graph = graph("sg-1");
        graph.setName("改名后的图");

        repository.update(graph);

        verify(jdbcTemplate).update(
                eq("UPDATE rpg_story_graph SET world_id = ?, name = ?, improvise_mode = ?, improvise_depth_limit = ?, state_schema = ?, graph_json = ?, " +
                        "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?"),
                eq(WORLD_ID), eq("改名后的图"), eq(StoryGraph.IMPROVISE_BOUNDED), isNull(),
                eq("{\"trust_信长\": \"number\"}"), eq("[{\"id\":\"act1\"}]"), eq("sg-1"), eq(TID_A));
    }

    @Test
    void 一world多图共存_findByWorldId返回全部图按创建时间排序() {
        // spec Scenario：同一 world 下第 2 张故事图 → 两图并存互不干扰
        List<StoryGraph> rows = List.of(graph("sg-1"), graph("sg-2"));
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A)))
                .thenReturn(rows);

        List<StoryGraph> result = repository.findByWorldId(WORLD_ID);

        assertThat(result).hasSize(2);
        verify(jdbcTemplate).query(
                eq("SELECT * FROM rpg_story_graph WHERE world_id = ? AND tenant_id = ? ORDER BY created_at, id"),
                any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A));
    }

    @Test
    void 跨租户不可达_切换租户后查询换绑tenant_id() {
        // spec Scenario：租户 A 请求租户 B 的图 → 404 不泄露存在性（仓储层 null 语义）
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq("sg-b"), any(String.class)))
                .thenReturn(List.of());

        assertThat(repository.findById("sg-b")).isNull();
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq("sg-b"), eq(TID_A));

        // 切到租户 B：同一 id 的查询把 tenant_id 绑成 B，A 的行不会进入结果集
        TenantContext.set("u-other", TID_B);
        repository.findById("sg-b");
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq("sg-b"), eq(TID_B));
    }

    @Test
    void findById跨租户空结果统一返回null() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq("sg-x"), eq(TID_A)))
                .thenReturn(List.of());

        assertThat(repository.findById("sg-x")).isNull();
    }

    @Test
    void existsByWorldId按租户过滤计数() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq(WORLD_ID), eq(TID_A)))
                .thenReturn(1);

        assertThat(repository.existsByWorldId(WORLD_ID)).isTrue();
        verify(jdbcTemplate).queryForObject(
                eq("SELECT COUNT(*) FROM rpg_story_graph WHERE world_id = ? AND tenant_id = ?"),
                eq(Integer.class), eq(WORLD_ID), eq(TID_A));

        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class), eq("world-empty"), eq(TID_A)))
                .thenReturn(0);
        assertThat(repository.existsByWorldId("world-empty")).isFalse();
    }

    @Test
    void deleteById约束租户返回受影响行数() {
        when(jdbcTemplate.update(any(String.class), eq("sg-1"), eq(TID_A))).thenReturn(1);

        int affected = repository.deleteById("sg-1");

        assertThat(affected).isEqualTo(1);
        verify(jdbcTemplate).update(
                eq("DELETE FROM rpg_story_graph WHERE id = ? AND tenant_id = ?"), eq("sg-1"), eq(TID_A));
    }
}
