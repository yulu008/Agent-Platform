package com.luyu.agent.tenancy;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 列级租户隔离集成测试（@JdbcTest + 真实内存 H2，RPG 表经
 * {@link RpgSchemaInitializer} 幂等建表；JdbcTemplate 为守卫版主 bean）。
 * <p>
 * 钉住的 spec 场景（column-isolation）：
 * <ul>
 *   <li>列表只含本租户</li>
 *   <li>跨租户主键访问表现为不存在（空结果，Controller 层上抛为 404）</li>
 *   <li>运行时守卫：无 tenant_id 的 rpg_* SQL 红灯、上下文缺失红灯、合法语句放行</li>
 *   <li>存量库升级幂等：schema 脚本重复执行不破坏已归租数据</li>
 * </ul>
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        RpgWorldSettingRepository.class})
class TenantIsolationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private WorldSetting world(String id, String name) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName(name);
        w.setSettingDesc("desc");
        return w;
    }

    @Test
    void 注入的JdbcTemplate为守卫版主bean() {
        assertThat(jdbcTemplate).isInstanceOf(TenantGuardedJdbcTemplate.class);
    }

    @Test
    void 列表只含本租户() {
        TenantContext.set("u-a", "t-a");
        worldRepo.save(world("w-a", "租户A的世界"));
        TenantContext.set("u-b", "t-b");
        worldRepo.save(world("w-b", "租户B的世界"));

        TenantContext.set("u-a", "t-a");
        List<WorldSetting> forA = worldRepo.findAll();
        TenantContext.set("u-b", "t-b");
        List<WorldSetting> forB = worldRepo.findAll();

        assertThat(forA).extracting(WorldSetting::getId).containsExactly("w-a");
        assertThat(forB).extracting(WorldSetting::getId).containsExactly("w-b");
    }

    @Test
    void 跨租户主键访问返回空而非数据() {
        TenantContext.set("u-a", "t-a");
        worldRepo.save(world("w-a", "租户A的世界"));

        TenantContext.set("u-b", "t-b");
        assertThat(worldRepo.findById("w-a")).isNull();
        // Controller 层 getWorld 对 null 上抛 404：表现为不存在，不区分"不存在"与"无权"
    }

    @Test
    void 守卫红灯_SQL涉及rpg表但缺tenant_id() {
        TenantContext.set("u-a", "t-a");   // 上下文存在，但语句无 tenant_id 字样
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_world_setting", Integer.class))
                .isInstanceOf(TenantIsolationViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM rpg_world_setting WHERE id = ?", "w-x"))
                .isInstanceOf(TenantIsolationViolationException.class);
    }

    @Test
    void 守卫红灯_租户上下文缺失() {
        TenantContext.clear();             // SQL 含 tenant_id 字样，但线程无租户上下文
        assertThatThrownBy(() -> jdbcTemplate.queryForList(
                "SELECT * FROM rpg_world_setting WHERE tenant_id = ?", "t-a"))
                .isInstanceOf(TenantIsolationViolationException.class);
    }

    @Test
    void 守卫放行_合法语句与非守卫表() {
        // rpg_* 表 + tenant_id + 上下文存在：放行
        assertThatCode(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_world_setting WHERE tenant_id = ?", Integer.class, "t-test"))
                .doesNotThrowAnyException();
        // 非 rpg_* 表（app_user 控制表）：不在守卫名单，即使无 tenant_id 也放行
        assertThatCode(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user", Integer.class))
                .doesNotThrowAnyException();
    }

    @Test
    void 存量库升级幂等重跑不破坏已归租数据() throws Exception {
        // 租户 A 写入两行并归租
        TenantContext.set("u-a", "t-a");
        worldRepo.save(world("w-a1", "世界一"));
        worldRepo.save(world("w-a2", "世界二"));

        // 模拟升级部署：schema 脚本（含 ALTER ADD COLUMN / 回填 / 控制表 DDL 全部幂等段）重复执行
        Resource schema = new DefaultResourceLoader().getResource("classpath:rpg/schema-h2.sql");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setContinueOnError(true);
        populator.addScript(schema);
        try (java.sql.Connection conn = dataSource.getConnection()) {
            populator.populate(conn);
            // 连续第二次重跑，验证幂等
            populator.populate(conn);
        }

        // 数据仍在、归属未被回填冲掉、跨租户语义不变
        List<WorldSetting> forA = worldRepo.findAll();
        assertThat(forA).extracting(WorldSetting::getId).containsExactlyInAnyOrder("w-a1", "w-a2");
        String rawTenant = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM rpg_world_setting WHERE id = ? AND tenant_id = ?",
                String.class, "w-a1", "t-a");
        assertThat(rawTenant).isEqualTo("t-a");
        TenantContext.set("u-b", "t-b");
        assertThat(worldRepo.findById("w-a1")).isNull();
    }
}
