package com.luyu.agent.rpg.repository;

import com.luyu.agent.rpg.model.StarIncarnation;
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
 * {@link RpgStarIncarnationRepository} 单元测试（mock JdbcTemplate，对齐
 * {@link RpgWorldbookEntryRepositoryTest} 的 SQL/参数钉住风格）。
 * <p>
 * 重点钉住模板隔离（add-rpg-lorebook-sets 1.4）：
 * <ol>
 *   <li>{@code findByWorldId} 带 {@code set_id IS NULL}——fork 进副本层的 set 行绝不回流模板名录；</li>
 *   <li>{@code rewriteAll} 的 DELETE 范围同样限定 {@code set_id IS NULL}——md 重新物化只重写模板行，
 *       各存档的 set 副本（含用户编辑）不受物化波及（三层模型 L1 与 L2 的写手隔离）；</li>
 *   <li>{@code findBySetId} 反向只按 set 归属取行；{@code save} 把 set_id/source_ref 绑进 INSERT；</li>
 *   <li>租户隔离——每条 SQL 都把 {@code tid()} 绑进 {@code tenant_id} 过滤位。</li>
 * </ol>
 */
class RpgStarIncarnationRepositoryTest {

    private static final String TID_A = "tenant-a";
    private static final String WORLD_ID = "preset-adventure:tenant-a";
    private static final String SET_ID = "set-1";

    private JdbcTemplate jdbcTemplate;
    private RpgStarIncarnationRepository repository;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TID_A);
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RpgStarIncarnationRepository(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 模板行（set_id/source_ref 皆 null） */
    private StarIncarnation template(String id, String starName, String warlord) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId(id);
        inc.setWorldId(WORLD_ID);
        inc.setStarName(starName);
        inc.setWarlordName(warlord);
        inc.setLore("命定叙事");
        inc.setAliases(warlord);
        inc.setRollable(true);
        inc.setSortOrder(0);
        return inc;
    }

    @Test
    void findByWorldId带set_id_IS_NULL过滤_set副本行不回流模板() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A)))
                .thenReturn(List.of(template("inc-1", "天魁星", "织田信长")));

        List<StarIncarnation> result = repository.findByWorldId(WORLD_ID);

        assertThat(result).hasSize(1);
        // 1.4 模板隔离核心：SQL 必含 set_id IS NULL，副本行对模板名录不可见
        verify(jdbcTemplate).query(
                contains("set_id IS NULL"),
                any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A));
    }

    @Test
    void rewriteAll的DELETE范围限定set_id_IS_NULL_物化不波及副本行() {
        repository.rewriteAll(WORLD_ID, List.of(template("inc-1", "天魁星", "织田信长")));

        // 先删后插：DELETE 只清模板行（set_id IS NULL），各存档 set 副本安然存活
        verify(jdbcTemplate).update(
                eq("DELETE FROM rpg_star_incarnation WHERE world_id = ? AND set_id IS NULL AND tenant_id = ?"),
                eq(WORLD_ID), eq(TID_A));
        // 随后插入新模板行（id 已给定，不再重新生成；category 随行携带，物化路径为 null）
        verify(jdbcTemplate).update(
                contains("INSERT INTO rpg_star_incarnation"),
                eq("inc-1"), eq(TID_A), eq(WORLD_ID), eq("天魁星"), eq("织田信长"),
                eq("命定叙事"), eq("织田信长"), eq(Boolean.TRUE), eq(0), isNull(), isNull(), isNull());
    }

    @Test
    void findBySetId只按set归属查询并绑当前租户() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(SET_ID), eq(TID_A)))
                .thenReturn(List.of(template("inc-1", "天魁星", "织田信长")));

        List<StarIncarnation> result = repository.findBySetId(SET_ID);

        assertThat(result).hasSize(1);
        verify(jdbcTemplate).query(
                eq("SELECT * FROM rpg_star_incarnation WHERE set_id = ? AND tenant_id = ? ORDER BY sort_order, id"),
                any(BeanPropertyRowMapper.class), eq(SET_ID), eq(TID_A));
    }

    @Test
    void save把set归属列绑进INSERT() {
        StarIncarnation copy = template("inc-1", "天魁星", "织田信长");
        copy.setSetId(SET_ID);
        copy.setSourceRef("天魁星");

        repository.save(copy);

        verify(jdbcTemplate).update(
                eq("INSERT INTO rpg_star_incarnation (id, tenant_id, world_id, star_name, warlord_name, lore, aliases, rollable, sort_order, set_id, source_ref, category) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
                eq("inc-1"), eq(TID_A), eq(WORLD_ID), eq("天魁星"), eq("织田信长"),
                eq("命定叙事"), eq("织田信长"), eq(Boolean.TRUE), eq(0), eq(SET_ID), eq("天魁星"), isNull());
    }

    @Test
    void deleteByIdAndSet限定set归属防跨副本误删() {
        when(jdbcTemplate.update(any(String.class), eq("inc-1"), eq(SET_ID), eq(TID_A))).thenReturn(1);

        int affected = repository.deleteByIdAndSet("inc-1", SET_ID);

        assertThat(affected).isEqualTo(1);
        verify(jdbcTemplate).update(
                eq("DELETE FROM rpg_star_incarnation WHERE id = ? AND set_id = ? AND tenant_id = ?"),
                eq("inc-1"), eq(SET_ID), eq(TID_A));
    }

    @Test
    void 切换租户后模板查询换绑tenant_id实现A不可见B() {
        when(jdbcTemplate.query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), any(String.class)))
                .thenReturn(List.of());

        repository.findByWorldId(WORLD_ID);
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq(TID_A));

        TenantContext.set("u-other", "tenant-b");
        repository.findByWorldId(WORLD_ID);
        verify(jdbcTemplate).query(any(String.class), any(BeanPropertyRowMapper.class), eq(WORLD_ID), eq("tenant-b"));
    }
}
