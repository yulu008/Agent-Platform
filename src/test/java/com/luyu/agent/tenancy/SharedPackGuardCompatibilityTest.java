package com.luyu.agent.tenancy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.SharedLorebookPack;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.SharedPackRepository;
import com.luyu.agent.rpg.service.SharedPackService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 共享世界书包守卫兼容集成测试（add-shared-worldbook-publish tasks 5.3，
 * @JdbcTest + 真实内存 H2 + 守卫版主 bean JdbcTemplate + 真实 Repository/Service）。
 * <p>
 * 钉住 spec「守卫兼容性」：shared_ 三张表（表名含 {@code shared_} 中缀，与 GUARDED_TABLES
 * 的 rpg_* 表名天然子串隔断）在运行时守卫下零误杀——
 * <ul>
 *   <li>画廊读（无租户过滤 SQL）正常执行且跨租户可见；</li>
 *   <li>发布写（INSERT 无 tenant_id 字样）正常执行；</li>
 *   <li>导入混合读写（shared_ 读 + rpg_* 带 tenant_id 写）正常执行，行归属接收方；</li>
 *   <li>跨租户撤回受 publisher 锚点约束（0 行 → false），不抛隔离违例。</li>
 * </ul>
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        SharedPackRepository.class, RpgWorldSettingRepository.class,
        RpgWorldbookEntryRepository.class, RpgStarIncarnationRepository.class,
        SharedPackService.class})
class SharedPackGuardCompatibilityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @Autowired
    private RpgWorldbookEntryRepository worldbookRepo;

    @Autowired
    private RpgStarIncarnationRepository starRepo;

    @Autowired
    private SharedPackService service;

    @Autowired
    private SharedPackRepository sharedRepo;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 辅助 ====================

    /** 租户 A 建世界 + 双轨模板行并发布共享包（返回 packId） */
    private String tenantAPublishesPack() {
        TenantContext.set("u-a", "t-a");
        worldRepo.save(world("w-a", "租户A的世界"));
        WorldbookEntry wb = new WorldbookEntry();
        wb.setId("wb-a1");
        wb.setWorldId("w-a");
        wb.setTitle("钟表城");
        wb.setKeywords("钟表城,齿轮塔");
        wb.setContent("设定正文");
        wb.setEnabled(true);
        wb.setSortOrder(0);
        worldbookRepo.save(wb);
        StarIncarnation star = new StarIncarnation();
        star.setId("inc-a1");
        star.setWorldId("w-a");
        star.setStarName("天魁星");
        star.setWarlordName("织田信长");
        star.setLore("命定叙事");
        star.setRollable(true);
        star.setSortOrder(0);
        starRepo.save(star);

        SharedPackService.PublishResult result = service.publish("w-a", "A的设定集", "简介",
                List.of("wb-a1"), List.of("inc-a1"));
        return result.packId();
    }

    private static WorldSetting world(String id, String name) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName(name);
        w.setSettingDesc("desc");
        return w;
    }

    // ==================== 守卫兼容性 ====================

    @Test
    void 注入的JdbcTemplate为守卫版主bean() {
        assertThat(jdbcTemplate).isInstanceOf(TenantGuardedJdbcTemplate.class);
    }

    @Test
    void 画廊查询过守卫不误杀且跨租户可见() {
        String packId = tenantAPublishesPack();

        // 守卫版 JdbcTemplate 上执行无租户过滤的 shared_ 画廊查询：不抛 TenantIsolationViolationException
        TenantContext.set("u-b", "t-b");
        List<SharedLorebookPack> visible = sharedRepo.listVisible();
        assertThat(visible).extracting(SharedLorebookPack::getId).containsExactly(packId);
        assertThat(visible.get(0).getPublisherTenantId()).isEqualTo("t-a");
    }

    @Test
    void 发布写入过守卫不误杀_不含tenant_id字样的INSERT正常执行() {
        // INSERT INTO shared_lorebook_pack / shared_worldbook_entry / shared_star_entry
        // 均无 tenant_id 字样：shared_ 中缀与 GUARDED_TABLES 的 rpg_* 表名隔断，守卫放行
        String packId = tenantAPublishesPack();
        TenantContext.set("u-a", "t-a");
        SharedPackService.PackDetail detail = service.loadPack(packId);
        assertThat(detail).isNotNull();
        assertThat(detail.worldbookEntries()).hasSize(1);
        assertThat(detail.starEntries()).hasSize(1);
    }

    @Test
    void 导入混合读写过守卫不误杀_行归属接收方() {
        String packId = tenantAPublishesPack();

        TenantContext.set("u-b", "t-b");
        worldRepo.save(world("w-b", "租户B的世界"));
        // 同一调用内：读 shared_ 表（无 tenant_id）+ 写 rpg_worldbook_entry / rpg_star_incarnation（带 tenant_id）
        SharedPackService.ImportResult result = service.importPack(packId, "w-b");

        assertThat(result.worldbookCount()).isEqualTo(1);
        assertThat(result.starCount()).isEqualTo(1);

        // 原生 SQL 断言归属：通用轨行 tenant_id=t-b、world_id 重绑、source_ref/set_id 均空
        List<Map<String, Object>> wbRows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, world_id, title, source_ref, set_id FROM rpg_worldbook_entry "
                        + "WHERE tenant_id = ? AND world_id = ?", "t-b", "w-b");
        assertThat(wbRows).hasSize(1);
        assertThat(wbRows.get(0).get("id")).isNotEqualTo("wb-a1");   // 新行 id
        assertThat(wbRows.get(0).get("title")).isEqualTo("钟表城");
        assertThat(wbRows.get(0).get("source_ref")).isNull();
        assertThat(wbRows.get(0).get("set_id")).isNull();

        // 冥星轨行：star_name 保留（runtime 稳定关联键）
        List<Map<String, Object>> starRows = jdbcTemplate.queryForList(
                "SELECT star_name, tenant_id, world_id FROM rpg_star_incarnation "
                        + "WHERE tenant_id = ? AND world_id = ?", "t-b", "w-b");
        assertThat(starRows).hasSize(1);
        assertThat(starRows.get(0).get("star_name")).isEqualTo("天魁星");

        // 发布源零变化：t-a 的模板行原封不动（t-a 视角下 worldbook 仍 1 条）
        TenantContext.set("u-a", "t-a");
        assertThat(worldbookRepo.findByWorldId("w-a")).hasSize(1);
    }

    @Test
    void 跨租户撤回受发布者锚点约束行数为零() {
        String packId = tenantAPublishesPack();

        // B 撤 A 的包：UPDATE ... WHERE publisher_tenant_id = 't-b' 命中 0 行 → false
        TenantContext.set("u-b", "t-b");
        assertThat(service.revoke(packId)).isFalse();

        // 发布者本人撤回成功，画廊不再列出
        TenantContext.set("u-a", "t-a");
        assertThat(service.revoke(packId)).isTrue();
        TenantContext.set("u-b", "t-b");
        assertThat(sharedRepo.listVisible()).isEmpty();
        assertThat(service.loadPack(packId)).isNull();
    }

    @Test
    void 守卫红灯仍对rpg表生效_共享库不弱化隔离() {
        // 反向钉：守卫在 shared_ 语境外照常工作，共享库不是隔离旁路
        assertThatCode(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shared_lorebook_pack", Integer.class))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_world_setting", Integer.class))
                .isInstanceOf(TenantIsolationViolationException.class);
    }
}
