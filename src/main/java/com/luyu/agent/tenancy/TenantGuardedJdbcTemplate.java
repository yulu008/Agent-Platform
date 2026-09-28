package com.luyu.agent.tenancy;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.lang.Nullable;

/**
 * 运行时租户隔离守卫（design D5）：包装 {@link JdbcTemplate}，在 SQL 入口做字符串级检查——
 * 涉及 rpg_* 业务表的语句必须显式含 {@code tenant_id}，且执行线程必须已建立租户上下文；
 * 否则抛 {@link TenantIsolationViolationException}。
 * <p>
 * 覆盖范围：全部带参 query / queryForObject / queryForList / update / batchUpdate 入口
 * （现有代码路径全量命中；未来新路径由静态扫描守卫在构建期先拦截）。
 * 天然豁免：DDL 走 {@code RpgSchemaInitializer} 的 ResourceDatabasePopulator 直连 Connection，
 * 不经过 JdbcTemplate；非 rpg_* 表（AI_SESSION / app_user / tenant）不在守卫名单。
 * <p>
 * 作为主 bean 装配（见 {@code TenantGuardConfiguration}）：Boot 的 JdbcTemplate 自动配置
 * 因 JdbcOperations bean 已存在而 back off，全部既有消费者注入到的即守卫版。
 */
public class TenantGuardedJdbcTemplate extends JdbcTemplate {

    /** 守卫名单：rpg_* 业务表（含快照/运行时表）。 */
    private static final String[] GUARDED_TABLES = {
            "rpg_world_setting", "rpg_location", "rpg_character_card", "rpg_relationship",
            "rpg_trigger_runtime", "rpg_trigger", "rpg_game_state_snapshot",
            "rpg_game_state", "rpg_event_log"
    };

    public TenantGuardedJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    // ---- query 家族 ----

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, @Nullable Object... args) {
        guard(sql);
        return super.query(sql, rowMapper, args);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        guard(sql);
        return super.query(sql, rowMapper);
    }

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> rse, @Nullable Object... args) {
        guard(sql);
        return super.query(sql, rse, args);
    }

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> rse) {
        guard(sql);
        return super.query(sql, rse);
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, @Nullable Object... args) {
        guard(sql);
        return super.queryForObject(sql, requiredType, args);
    }

    /** 无参重载：Java 重载解析优先于 varargs 版本，不覆盖即成绕过口（集成测试抓出）。 */
    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType) {
        guard(sql);
        return super.queryForObject(sql, requiredType);
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, @Nullable Object... args) {
        guard(sql);
        return super.queryForList(sql, elementType, args);
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType) {
        guard(sql);
        return super.queryForList(sql, elementType);
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql, @Nullable Object... args) {
        guard(sql);
        return super.queryForList(sql, args);
    }

    @Override
    public List<Map<String, Object>> queryForList(String sql) {
        guard(sql);
        return super.queryForList(sql);
    }

    // ---- update / batchUpdate / execute 家族 ----

    @Override
    public int update(String sql, @Nullable Object... args) {
        guard(sql);
        return super.update(sql, args);
    }

    /** 无参重载：同上，重载解析优先级导致的绕过口。 */
    @Override
    public int update(String sql) {
        guard(sql);
        return super.update(sql);
    }

    @Override
    public int[] batchUpdate(String... sqls) {
        for (String sql : sqls) {
            guard(sql);
        }
        return super.batchUpdate(sqls);
    }

    @Override
    public void execute(String sql) {
        guard(sql);
        super.execute(sql);
    }

    // ---- 守卫本体 ----

    /**
     * 字符串级启发式检查：涉及 rpg_* 表的语句必须有 tenant_id 字样，且租户上下文已建立。
     * INSERT 补租户列、SELECT/UPDATE/DELETE 加过滤均满足字样要求。
     */
    private static void guard(String sql) {
        if (sql == null) {
            return;
        }
        String lower = sql.toLowerCase();
        for (String table : GUARDED_TABLES) {
            if (lower.contains(table)) {
                if (!lower.contains("tenant_id")) {
                    throw new TenantIsolationViolationException(
                            "租户隔离违例：rpg_* 表 SQL 缺少 tenant_id 过滤（design D5 运行时守卫）: " + abbreviate(sql));
                }
                if (!TenantContext.isPresent()) {
                    throw new TenantIsolationViolationException(
                            "租户隔离违例：租户表 SQL 在无租户上下文的线程上执行（应经 JWT 认证建立，"
                                    + "或从 ToolContext 等显式载体恢复）: " + abbreviate(sql));
                }
                return;
            }
        }
    }

    private static String abbreviate(String sql) {
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }
}
