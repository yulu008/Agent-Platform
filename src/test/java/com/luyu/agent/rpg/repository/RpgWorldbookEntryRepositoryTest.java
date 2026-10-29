package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RpgWorldbookEntryRepository} 单元测试（mock JdbcTemplate，对齐
 * {@link RpgGameStateSnapshotRepositoryTest} 的 SQL/参数钉住风格）。
 * <p>
 * 钉住三件事：
 * <ol>
 *   <li>CRUD 的 SQL 形态与列顺序（save/update/findById/findByWorldId/softDeleteTemplateById）；</li>
 *   <li>租户隔离——每条 SQL 都把 {@code tid()} 绑进 {@code tenant_id} 过滤位，切换租户即换绑参，
 *       A 租户的查询不可能命中 B 租户的行；</li>
 *   <li>{@code findByWorldId} <b>不过滤 enabled</b>（含停用条目一并返回，供工坊列出以重新启用）——
 *       enabled=false 的跳过是扫描期职责（{@code WorldbookScanner}），不在仓储层；</li>
 *   <li>模板隔离（add-rpg-lorebook-sets 1.4）——{@code findByWorldId} 带 {@code set_id IS NULL}，
 *       fork 进副本层的 set 行绝不回流模板列表；{@code findBySetId} 反向只按 set 归属取行。</li>
 * </ol>
 */
class RpgWorldbookEntryRepositoryTest {

    private static final String TID_A = "tenant-a";
    private static final String TID_B = "tenant-b";
    private static final String WORLD_ID = "world-1";

    private JdbcTemplate jdbcTemplate;
    private RpgWorldbookEntryRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TID_A);
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RpgWorldbookEntryRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private WorldbookEntry entry(String id, String title, boolean enabled) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId(id);
        e.setWorldId(WORLD_ID);
        e.setTitle(title);
        e.setKeywords(title + ",别名");
        e.setContent("设定正文");
        e.setEnabled(enabled);
        e.setSortOrder(0);
        return e;
    }

    @Test
    void save把租户绑进INSERT的tenant_id列() {
        repository.save(entry("wb-1", "钟表城", true));

        // 模板行 set_id/source_ref 皆 null（副本行才带 set 归属，add-rpg-lorebook-sets 1.2）
        verify(jdbcTemplate).update(
                eq("INSERT INTO rpg_worldbook_entry (id, tenant_id, world_id, title, keywords, content, enabled, sort_order, set_id, source_ref) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
                eq("wb-1"), eq(TID_A), eq(WORLD_ID), eq("钟表城"),
                eq("钟表城,别名"), eq("设定正文"), eq(Boolean.TRUE), eq(0), isNull(), isNull());
    }

    @Test
    void update的WHERE同时约束id与租户() {
        repository.update(entry("wb-1", "雾都", false));

        verify(jdbcTemplate).update(
                eq("UPDATE rpg_worldbook_entry SET world_id = ?, title = ?, keywords = ?, content = ?, enabled = ?, sort_order = ? " +
                        "WHERE id = ? AND tenant_id = ?"),
                eq(WORLD_ID), eq("雾都"), eq("雾都,别名"), eq("设定正文"),
                eq(Boolean.FALSE), eq(0), eq("wb-1"), eq(TID_A));
    }

    @Test
    void softDeleteTemplateById写软标并约束模板层与租户() {
        when(jdbcTemplate.update(any(String.class), eq("wb-1"), eq(TID_A))).thenReturn(1);

        int affected = repository.softDeleteTemplateById("wb-1");

        assertThat(affected).isEqualTo(1);
        // 物理删除退役（workshop-soft-delete-cascade D2）：模板行写软标，
        // set 行与已软删行不命中（0 = 供控制层判 404）
        verify(jdbcTemplate).update(
                eq("UPDATE rpg_worldbook_entry SET deleted_at = CURRENT_TIMESTAMP WHERE id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL"),
                eq("wb-1"), eq(TID_A));
    }

    @Test
    void findByWorldId含停用条目一并返回且绑当前租户() {
        List<WorldbookEntry> rows = List.of(entry("wb-1", "钟表城", true), entry("wb-2", "雾都", false));
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A)))
                .thenReturn(rows);

        List<WorldbookEntry> result = repository.findByWorldId(WORLD_ID);

        // 仓储不按 enabled 过滤：停用条目也返回（工坊需列出以重新启用；跳过是扫描期职责）
        assertThat(result).hasSize(2);
        // 软删过滤（workshop-soft-delete-cascade 2.4）：模板列表不返回已软删行
        verify(jdbcTemplate).query(
                eq("SELECT * FROM rpg_worldbook_entry WHERE world_id = ? AND set_id IS NULL AND tenant_id = ? AND deleted_at IS NULL ORDER BY sort_order, created_at, id"),
                any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A));
    }

    @Test
    void findByWorldId带set_id_IS_NULL过滤_set副本行不回流模板() {
        // 1.4 模板隔离核心：SQL 必含 set_id IS NULL，fork 进副本层的行对模板列表不可见
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A)))
                .thenReturn(List.of(entry("wb-tpl", "钟表城", true)));

        List<WorldbookEntry> result = repository.findByWorldId(WORLD_ID);

        assertThat(result).hasSize(1);
        verify(jdbcTemplate).query(
                contains("set_id IS NULL"),
                any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A));
    }

    @Test
    void findBySetId只按set归属查询并绑当前租户() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq("set-1"), eq(TID_A)))
                .thenReturn(List.of(entry("wb-1", "钟表城", true)));

        List<WorldbookEntry> result = repository.findBySetId("set-1");

        assertThat(result).hasSize(1);
        verify(jdbcTemplate).query(
                eq("SELECT * FROM rpg_worldbook_entry WHERE set_id = ? AND tenant_id = ? ORDER BY sort_order, created_at, id"),
                any(BeanPropertyRowMapper.class), eq("set-1"), eq(TID_A));
    }

    @Test
    void 切换租户后查询换绑tenant_id实现A不可见B() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), any(String.class)))
                .thenReturn(List.of());

        repository.findByWorldId(WORLD_ID);
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A));

        // 切到租户 B：同一 worldId 的查询把 tenant_id 绑成 B，A 的行不会进入结果集
        TenantContext.set("u-other", TID_B);
        repository.findByWorldId(WORLD_ID);
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_B));
    }

    @Test
    void findById空结果返回null() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq("wb-x"), eq(TID_A)))
                .thenReturn(List.of());

        assertThat(repository.findById("wb-x")).isNull();
    }
}
