package com.luyu.agent.tenancy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.Location;
import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.WorldPublishRepository;
import com.luyu.agent.rpg.service.PresetWorldService;
import com.luyu.agent.rpg.service.WorldCoverStorageService;
import com.luyu.agent.rpg.service.WorldGalleryService;
import com.luyu.agent.rpg.service.WorldPublishService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 共享世界发布守卫兼容集成测试（works-view-and-world-publish tasks 6.2，
 * @JdbcTest + 真实内存 H2 + 守卫版主 bean JdbcTemplate + 真实 Repository/Service）。
 * <p>
 * 钉住 spec「守卫兼容性」：发布经济全链路在运行时守卫下零误杀——
 * <ul>
 *   <li>橱窗读（{@code SELECT ... FROM shared_world} 无租户过滤 SQL）正常执行且跨租户可见；</li>
 *   <li>发布写（INSERT 列含 {@code publisher_tenant_id} 字样，但 shared_world 表名不在
 *       GUARDED_TABLES——守卫只在 SQL 命中守卫表名时才检查 tenant_id，shared_ 中缀天然隔断）；</li>
 *   <li>导入混合读写（shared_world 读 + rpg_* 六表带 tenant_id 写）正常执行，行归属接收方；</li>
 *   <li>跨租户撤销受 publisher 锚点约束（0 行 → false），不抛隔离违例。</li>
 * </ul>
 * 源世界 cover 列留 null：copyToShared/copyFromShared 直接降级返回 null，
 * 纯守卫测试零文件系统操作（封面文件闭环由 WorldCoverStorageServiceTest 6.5 钉）。
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        WorldPublishRepository.class, RpgWorldSettingRepository.class,
        RpgCharacterCardRepository.class, RpgLocationRepository.class,
        RpgRelationshipRepository.class, RpgTriggerRepository.class,
        RpgWorldbookEntryRepository.class, RpgStarIncarnationRepository.class,
        RpgDestinedFigureRepository.class,
        WorldCoverStorageService.class, PresetWorldService.class,
        WorldGalleryService.class, WorldPublishService.class})
class WorldPublishGuardCompatibilityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @Autowired
    private RpgCharacterCardRepository charRepo;

    @Autowired
    private RpgLocationRepository locationRepo;

    @Autowired
    private RpgRelationshipRepository relRepo;

    @Autowired
    private RpgTriggerRepository triggerRepo;

    @Autowired
    private RpgWorldbookEntryRepository worldbookRepo;

    @Autowired
    private RpgStarIncarnationRepository starRepo;

    @Autowired
    private WorldPublishService service;

    @Autowired
    private WorldGalleryService gallery;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 辅助 ====================

    /**
     * 租户 A 建全家桶世界（2 角色 + 1 地点 + 1 关系 + 1 触发器 + 1 世界书 + 1 冥星
     * = entryCount 7）并发布。子数据 id 均带 -a 后缀，导入断言据此区分新旧行。
     */
    private WorldPublishService.PublishResult tenantAPublishes() {
        TenantContext.set("u-a", "t-a");
        worldRepo.save(world("w-a", "租户A的世界"));
        charRepo.save(card("c-a1", "w-a", "甲"));
        charRepo.save(card("c-a2", "w-a", "乙"));
        locationRepo.save(loc("loc-a1", "w-a", "钟楼"));
        relRepo.save(rel("rel-a1", "c-a1", "c-a2"));
        triggerRepo.save(trigger("trig-a1", "w-a", "c-a1"));
        worldbookRepo.save(wbEntry("wb-a1", "w-a", "钟表城"));
        starRepo.save(star("inc-a1", "w-a", "天魁星", "织田信长"));
        return service.publish("w-a");
    }

    private static WorldSetting world(String id, String name) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName(name);
        w.setSettingDesc("desc");
        return w;
    }

    private static CharacterCard card(String id, String worldId, String name) {
        CharacterCard c = new CharacterCard();
        c.setId(id);
        c.setWorldId(worldId);
        c.setName(name);
        c.setType("npc");
        return c;
    }

    private static Location loc(String id, String worldId, String name) {
        Location l = new Location();
        l.setId(id);
        l.setWorldId(worldId);
        l.setName(name);
        l.setNpcIds("[]");
        l.setTriggerIds("[]");
        return l;
    }

    private static Relationship rel(String id, String charA, String charB) {
        Relationship r = new Relationship();
        r.setId(id);
        r.setCharAId(charA);
        r.setCharBId(charB);
        r.setAttitude(0);
        r.setTrust(50);
        r.setNotes("");
        return r;
    }

    private static Trigger trigger(String id, String worldId, String npcId) {
        Trigger t = new Trigger();
        t.setId(id);
        t.setWorldId(worldId);
        t.setType("event");
        t.setNpcId(npcId);
        t.setHardConditions("[]");
        t.setSoftCondition("");
        t.setAction("rob");
        t.setCooldown(3);
        t.setProbability(0.6);
        return t;
    }

    private static WorldbookEntry wbEntry(String id, String worldId, String title) {
        WorldbookEntry wb = new WorldbookEntry();
        wb.setId(id);
        wb.setWorldId(worldId);
        wb.setTitle(title);
        wb.setKeywords(title);
        wb.setContent("设定正文");
        wb.setEnabled(true);
        wb.setSortOrder(0);
        return wb;
    }

    private static StarIncarnation star(String id, String worldId, String starName, String warlordName) {
        StarIncarnation s = new StarIncarnation();
        s.setId(id);
        s.setWorldId(worldId);
        s.setStarName(starName);
        s.setWarlordName(warlordName);
        s.setLore("命定叙事");
        s.setRollable(true);
        s.setSortOrder(0);
        return s;
    }

    // ==================== 守卫兼容性 ====================

    @Test
    void 注入的JdbcTemplate为守卫版主bean() {
        assertThat(jdbcTemplate).isInstanceOf(TenantGuardedJdbcTemplate.class);
    }

    @Test
    void 橱窗查询过守卫不误杀且跨租户可见() {
        WorldPublishService.PublishResult result = tenantAPublishes();

        // 守卫版 JdbcTemplate 上执行无租户过滤的 shared_world 橱窗查询：不抛 TenantIsolationViolationException
        TenantContext.set("u-b", "t-b");
        List<WorldGalleryService.ShowcaseCard> cards = gallery.showcase();
        List<WorldGalleryService.ShowcaseCard> published = cards.stream()
                .filter(c -> "published".equals(c.source())).toList();
        assertThat(published).extracting(WorldGalleryService.ShowcaseCard::id)
                .containsExactly(result.packId());
        assertThat(published.get(0).publisherTenantId()).isEqualTo("t-a");
        assertThat(published.get(0).publisherSelf()).isFalse();
        assertThat(published.get(0).originWorldId()).isEqualTo("w-a");
    }

    @Test
    void 发布写入过守卫不误杀_insert含租户列名仍放行() {
        // INSERT INTO shared_world 列清单含 publisher_tenant_id（tenant_id 字样）：
        // 守卫按「SQL 命中 GUARDED_TABLES 表名」才检查 tenant_id，shared_world 表名
        // 与 rpg_* 守卫表无子串关系 → 放行（含列名字样不构成拦截条件）
        WorldPublishService.PublishResult result = tenantAPublishes();

        assertThat(result.overwrite()).isFalse();
        assertThat(result.entryCount()).isEqualTo(7);

        // 读侧验证包已落库（shared_world 读不误杀）
        TenantContext.set("u-a", "t-a");
        assertThat(service.findVisibleById(result.packId())).isNotNull();
    }

    @Test
    void 导入混合读写过守卫不误杀_行归属接收方() {
        WorldPublishService.PublishResult result = tenantAPublishes();

        // 同一调用内：读 shared_world（无租户过滤）+ 写 rpg_* 六表（带 tenant_id）
        TenantContext.set("u-b", "t-b");
        WorldPublishService.ImportResult imported = service.importWorld(result.packId());
        String newWorldId = imported.newWorldId();

        assertThat(imported.entryCount()).isEqualTo(7);
        assertThat(newWorldId).isNotEqualTo("w-a");

        // 原生 SQL 断言归属：世界行 tenant_id=t-b、id 全新
        List<Map<String, Object>> worldRows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, name FROM rpg_world_setting WHERE tenant_id = ? AND id = ?",
                "t-b", newWorldId);
        assertThat(worldRows).hasSize(1);
        assertThat(worldRows.get(0).get("name")).isEqualTo("租户A的世界");

        // 角色行：归属接收方、world_id 重绑、内容保留
        List<Map<String, Object>> charRows = jdbcTemplate.queryForList(
                "SELECT id, world_id, name FROM rpg_character_card WHERE tenant_id = ? AND world_id = ?",
                "t-b", newWorldId);
        assertThat(charRows).hasSize(2);
        assertThat(charRows).extracting(r -> r.get("name")).containsExactlyInAnyOrder("甲", "乙");
        Set<String> newCharIds = charRows.stream().map(r -> (String) r.get("id")).collect(Collectors.toSet());
        assertThat(newCharIds).doesNotContain("c-a1", "c-a2");

        // 守卫名单上的关系表（INSERT 带 tenant_id 列）：归属接收方、两端重绑角色新 id
        List<Map<String, Object>> relRows = jdbcTemplate.queryForList(
                "SELECT char_a_id, char_b_id, tenant_id FROM rpg_relationship WHERE tenant_id = ?", "t-b");
        assertThat(relRows).hasSize(1);
        String relCharA = (String) relRows.get(0).get("char_a_id");
        String relCharB = (String) relRows.get(0).get("char_b_id");
        assertThat(newCharIds).contains(relCharA, relCharB);

        // 通用世界书模板行：title 保留、set_id/source_ref 均空（模板层归位）
        List<Map<String, Object>> wbRows = jdbcTemplate.queryForList(
                "SELECT title, world_id, set_id, source_ref FROM rpg_worldbook_entry "
                        + "WHERE tenant_id = ? AND world_id = ?", "t-b", newWorldId);
        assertThat(wbRows).hasSize(1);
        assertThat(wbRows.get(0).get("title")).isEqualTo("钟表城");
        assertThat(wbRows.get(0).get("set_id")).isNull();
        assertThat(wbRows.get(0).get("source_ref")).isNull();

        // 冥星模板行：star_name 保留（揭示/抢星 runtime 稳定关联键）
        List<Map<String, Object>> starRows = jdbcTemplate.queryForList(
                "SELECT star_name, warlord_name, world_id FROM rpg_star_incarnation "
                        + "WHERE tenant_id = ? AND world_id = ?", "t-b", newWorldId);
        assertThat(starRows).hasSize(1);
        assertThat(starRows.get(0).get("star_name")).isEqualTo("天魁星");
        assertThat(starRows.get(0).get("warlord_name")).isEqualTo("织田信长");

        // 发布源零变化：t-a 视角下原行原封不动
        TenantContext.set("u-a", "t-a");
        assertThat(worldRepo.findById("w-a")).isNotNull();
        assertThat(charRepo.findByWorldId("w-a")).hasSize(2);
        assertThat(worldbookRepo.findByWorldId("w-a")).hasSize(1);
    }

    @Test
    void 跨租户撤销受发布者锚点约束行数为零() {
        WorldPublishService.PublishResult result = tenantAPublishes();

        // B 撤 A 的包：UPDATE ... WHERE publisher_tenant_id = 't-b' 命中 0 行 → false
        TenantContext.set("u-b", "t-b");
        assertThat(service.revoke(result.packId())).isFalse();

        // 发布者本人撤销成功，橱窗不再列出已发布卡
        TenantContext.set("u-a", "t-a");
        assertThat(service.revoke(result.packId())).isTrue();
        TenantContext.set("u-b", "t-b");
        assertThat(gallery.showcase().stream()
                .filter(c -> "published".equals(c.source()))).isEmpty();
    }

    @Test
    void 守卫红灯仍对rpg表生效_共享库不弱化隔离() {
        // 反向钉：守卫在 shared_world 语境外照常工作，发布经济不是隔离旁路
        assertThatCode(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shared_world", Integer.class))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rpg_world_setting", Integer.class))
                .isInstanceOf(TenantIsolationViolationException.class);
    }
}
