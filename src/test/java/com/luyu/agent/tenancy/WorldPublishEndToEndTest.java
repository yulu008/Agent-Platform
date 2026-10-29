package com.luyu.agent.tenancy;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.Location;
import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLorebookSetRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.WorldPublishRepository;
import com.luyu.agent.rpg.service.LoreArchiveService;
import com.luyu.agent.rpg.service.LorebookSetService;
import com.luyu.agent.rpg.service.PresetWorldService;
import com.luyu.agent.rpg.service.WorldCoverStorageService;
import com.luyu.agent.rpg.service.WorldGalleryService;
import com.luyu.agent.rpg.service.WorldPublishService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 共享世界发布端到端流转测试（works-view-and-world-publish tasks 6.4，参照
 * SharedPackEndToEndTest：@JdbcTest + 真实 H2 + 真实 Repository/Service，
 * 以 runAs 模拟 JwtTenantFilter 的 set → 业务 → finally clear 生命周期）。
 * <p>
 * 钉住完整传播链（design D1 快照复制链）：
 * 租户 A 发布 → 租户 B 橱窗可见 → B 导入落本地新世界（作品墙可开局、chips
 * 含导入角色）→ B 新开局 fork 含导入模板行 → A 覆盖更新 / 撤销后 B 副本零渗入
 * → B 二次导入产生独立副本（多副本互不影响）。
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        WorldPublishRepository.class, RpgWorldSettingRepository.class,
        RpgCharacterCardRepository.class, RpgLocationRepository.class,
        RpgRelationshipRepository.class, RpgTriggerRepository.class,
        RpgWorldbookEntryRepository.class, RpgStarIncarnationRepository.class,
        RpgStarIncarnationRuntimeRepository.class, RpgLorebookSetRepository.class,
        RpgGameStateRepository.class, RpgDestinedFigureRepository.class,
        WorldCoverStorageService.class, PresetWorldService.class,
        WorldGalleryService.class, WorldPublishService.class, LorebookSetService.class})
class WorldPublishEndToEndTest {

    /** 档案物化属 rpg-lore-archival-loading 关注点（另测），此处 mock 隔离 DB 传播链 */
    @MockitoBean
    private LoreArchiveService loreArchiveService;

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
    private RpgGameStateRepository stateRepo;

    @Autowired
    private WorldPublishService publishService;

    @Autowired
    private WorldGalleryService galleryService;

    @Autowired
    private LorebookSetService lorebookSetService;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 辅助 ====================

    /** 模拟 JwtTenantFilter：验签通过后 set → 业务 → finally clear。 */
    private <T> T runAs(String userId, String tenantId, java.util.function.Supplier<T> action) {
        TenantContext.set(userId, tenantId);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void runAs(String userId, String tenantId, Runnable action) {
        TenantContext.set(userId, tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
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

    private static CharacterCard playerCard(String id, String worldId, String name) {
        CharacterCard c = card(id, worldId, name);
        c.setType("player");
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

    private static GameState gameState(String id, String sessionId, String worldId, String playerCharId) {
        GameState gs = new GameState();
        gs.setId(id);
        gs.setSessionId(sessionId);
        gs.setWorldId(worldId);
        gs.setPlayerCharId(playerCharId);
        gs.setCurrentLocation("老地方");
        gs.setTurnCount(0);
        return gs;
    }

    /**
     * 租户 A：建全家桶世界（2 角色 + 1 地点 + 1 关系 + 1 触发器 + 1 世界书 + 1 冥星
     * = entryCount 7）并发布，返回 packId。
     */
    private String tenantAPublish() {
        return runAs("u-a", "t-a", () -> {
            worldRepo.save(world("w-a", "租户A的世界"));
            charRepo.save(card("c-a1", "w-a", "甲"));
            charRepo.save(card("c-a2", "w-a", "乙"));
            locationRepo.save(loc("loc-a1", "w-a", "钟楼"));
            relRepo.save(rel("rel-a1", "c-a1", "c-a2"));
            triggerRepo.save(trigger("trig-a1", "w-a", "c-a1"));
            worldbookRepo.save(wbEntry("wb-a1", "w-a", "钟表城"));
            starRepo.save(star("inc-a1", "w-a", "天魁星", "织田信长"));
            return publishService.publish("w-a").packId();
        });
    }

    /** B 视角橱窗中 source=published 的卡（过滤预设卡） */
    private List<WorldGalleryService.ShowcaseCard> bViewPublished() {
        return runAs("u-b", "t-b", () -> galleryService.showcase().stream()
                .filter(c -> "published".equals(c.source())).toList());
    }

    // ==================== 端到端流转 ====================

    @Test
    void 发布到橱窗到导入到开局fork的完整流转() {
        String packId = tenantAPublish();

        // B 橱窗可见 A 的发布卡（跨租户读全平台）
        List<WorldGalleryService.ShowcaseCard> published = bViewPublished();
        assertThat(published).extracting(WorldGalleryService.ShowcaseCard::id)
                .containsExactly(packId);
        assertThat(published.get(0).originWorldId()).isEqualTo("w-a");
        assertThat(published.get(0).publisherSelf()).isFalse();
        assertThat(published.get(0).entryCount()).isEqualTo(7);

        // B 导入：快照复制为 B 本地新世界
        WorldPublishService.ImportResult importResult = runAs("u-b", "t-b",
                () -> publishService.importWorld(packId));
        String newWorldId = importResult.newWorldId();
        assertThat(newWorldId).isNotEqualTo("w-a");
        assertThat(importResult.entryCount()).isEqualTo(7);

        // B 作品墙可见新世界（可开局；B 非发布者 → published=false）
        runAs("u-b", "t-b", () -> {
            List<WorldGalleryService.WorldCard> works =
                    galleryService.gallery(WorldGalleryService.SCOPE_WORKS);
            assertThat(works).extracting(WorldGalleryService.WorldCard::id)
                    .containsExactly(newWorldId);
            assertThat(works.get(0).published()).isFalse();
        });

        // chips 数据源：导入角色全量落新世界（玩家开局向导照常选角）
        runAs("u-b", "t-b", () -> {
            assertThat(charRepo.findByWorldId(newWorldId))
                    .extracting(CharacterCard::getName).containsExactlyInAnyOrder("甲", "乙");
            assertThat(worldbookRepo.findByWorldId(newWorldId))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(starRepo.findByWorldId(newWorldId))
                    .extracting(StarIncarnation::getStarName).containsExactly("天魁星");
        });

        // B 新开局 fork：世界默认来源复制模板层（含导入行）为本存档 set 副本——
        // 开局向导既有机制自动接管，零新增传播逻辑
        runAs("u-b", "t-b", () -> {
            charRepo.save(playerCard("pc-b", newWorldId, "波波"));
            stateRepo.save(gameState("gs-b1", "sess-b1", newWorldId, "pc-b"));
        });
        runAs("u-b", "t-b", () -> {
            LorebookSet set = lorebookSetService.copyForNewGame(newWorldId, null, "gs-b1");
            assertThat(set).isNotNull();
            assertThat(worldbookRepo.findBySetId(set.getId()))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(starRepo.findBySetId(set.getId()))
                    .extracting(StarIncarnation::getStarName).containsExactly("天魁星");
        });
    }

    @Test
    void 覆盖更新与撤销后已导入副本不变() {
        String packId = tenantAPublish();
        String newWorldId = runAs("u-b", "t-b",
                () -> publishService.importWorld(packId)).newWorldId();
        runAs("u-b", "t-b", () -> {
            charRepo.save(playerCard("pc-b", newWorldId, "波波"));
            stateRepo.save(gameState("gs-b1", "sess-b1", newWorldId, "pc-b"));
        });
        String setId = runAs("u-b", "t-b",
                () -> lorebookSetService.copyForNewGame(newWorldId, null, "gs-b1").getId());

        // A 覆盖更新：追加一条世界书模板行重新发布（同发布者同源世界 → 覆盖既有包）
        runAs("u-a", "t-a", () -> {
            worldbookRepo.save(wbEntry("wb-a2", "w-a", "雾都"));
            WorldPublishService.PublishResult result = publishService.publish("w-a");
            assertThat(result.overwrite()).isTrue();
            assertThat(result.packId()).isEqualTo(packId);
            assertThat(result.entryCount()).isEqualTo(8);
        });

        // 橱窗是活的：覆盖后包元数据（entryCount）即时刷新
        assertThat(bViewPublished().get(0).entryCount()).isEqualTo(8);

        // B 副本是死的：零渗入（导入模板行不变、fork 出的 set 副本不变）
        runAs("u-b", "t-b", () -> {
            assertThat(worldbookRepo.findByWorldId(newWorldId))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(worldbookRepo.findBySetId(setId))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
        });

        // A 撤销下架：橱窗消失、详情/导入 404 语义；B 副本完好照常开局
        runAs("u-a", "t-a", () -> assertThat(publishService.revoke(packId)).isTrue());
        runAs("u-b", "t-b", () -> {
            assertThat(galleryService.showcase().stream()
                    .filter(c -> "published".equals(c.source()))).isEmpty();
            assertThat(publishService.findVisibleById(packId)).isNull();
            assertThatThrownBy(() -> publishService.importWorld(packId))
                    .isInstanceOf(NoSuchElementException.class);
            // 副本完好：世界/角色/世界书/冥星/set 全在，撤销不清理任何已导入内容
            assertThat(worldRepo.findById(newWorldId)).isNotNull();
            // 2 条导入角色 + 1 条 B 自建开局玩家卡，均存活
            assertThat(charRepo.findByWorldId(newWorldId)).hasSize(3);
            assertThat(worldbookRepo.findByWorldId(newWorldId)).hasSize(1);
            assertThat(starRepo.findByWorldId(newWorldId)).hasSize(1);
            assertThat(worldbookRepo.findBySetId(setId)).hasSize(1);
        });
    }

    @Test
    void 二次导入产生独立副本() {
        String packId = tenantAPublish();
        String first = runAs("u-b", "t-b",
                () -> publishService.importWorld(packId)).newWorldId();
        String second = runAs("u-b", "t-b",
                () -> publishService.importWorld(packId)).newWorldId();

        // 两次导入各落一个全新世界（design D1：各导入各的，多副本独立）
        assertThat(first).isNotEqualTo(second);
        runAs("u-b", "t-b", () -> {
            List<CharacterCard> firstChars = charRepo.findByWorldId(first);
            List<CharacterCard> secondChars = charRepo.findByWorldId(second);
            assertThat(firstChars).extracting(CharacterCard::getName)
                    .containsExactlyInAnyOrder("甲", "乙");
            assertThat(secondChars).extracting(CharacterCard::getName)
                    .containsExactlyInAnyOrder("甲", "乙");
            // 副本独立：两世界的角色行 id 互不相同（后续编辑互不影响）
            Set<String> firstIds = firstChars.stream()
                    .map(CharacterCard::getId).collect(Collectors.toSet());
            for (CharacterCard c : secondChars) {
                assertThat(firstIds).doesNotContain(c.getId());
            }
            // 各自的世界书/冥星模板行独立一份
            assertThat(worldbookRepo.findByWorldId(first))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(worldbookRepo.findByWorldId(second))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(starRepo.findByWorldId(first))
                    .extracting(StarIncarnation::getStarName).containsExactly("天魁星");
            assertThat(starRepo.findByWorldId(second))
                    .extracting(StarIncarnation::getStarName).containsExactly("天魁星");
        });
    }
}
