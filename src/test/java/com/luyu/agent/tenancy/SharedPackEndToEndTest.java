package com.luyu.agent.tenancy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.SharedLorebookPack;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLorebookSetRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.SharedPackRepository;
import com.luyu.agent.rpg.service.LoreArchiveService;
import com.luyu.agent.rpg.service.LorebookSetService;
import com.luyu.agent.rpg.service.SharedPackService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享世界书包端到端流转测试（add-shared-worldbook-publish tasks 5.5，参照
 * MultiTenantEndToEndTest：@JdbcTest + 真实 H2 + 真实 Repository/Service，
 * 以 runAs 模拟 JwtTenantFilter 的 set → 业务 → finally clear 生命周期）。
 * <p>
 * 钉住完整传播链（design D1 快照复制链）：
 * 租户 A 发布 → 租户 B 画廊可见 → B 导入落自己模板层 → B 新开局 fork 含导入条目
 * → A 覆盖更新 / 撤回后 B 副本不变（每个存档/租户有自己的副本，发布侧后续变化零渗入）。
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        SharedPackRepository.class, RpgWorldSettingRepository.class,
        RpgWorldbookEntryRepository.class, RpgStarIncarnationRepository.class,
        RpgStarIncarnationRuntimeRepository.class, RpgLorebookSetRepository.class,
        RpgCharacterCardRepository.class, RpgGameStateRepository.class,
        SharedPackService.class, LorebookSetService.class})
class SharedPackEndToEndTest {

    /** 档案物化属 rpg-lore-archival-loading 关注点（另测），此处 mock 隔离 DB 传播链 */
    @MockitoBean
    private LoreArchiveService loreArchiveService;

    @Autowired
    private RpgWorldSettingRepository worldRepo;

    @Autowired
    private RpgWorldbookEntryRepository worldbookRepo;

    @Autowired
    private RpgStarIncarnationRepository starRepo;

    @Autowired
    private RpgCharacterCardRepository cardRepo;

    @Autowired
    private RpgGameStateRepository stateRepo;

    @Autowired
    private SharedPackService sharedPackService;

    @Autowired
    private LorebookSetService lorebookSetService;

    @Autowired
    private SharedPackRepository sharedRepo;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 模拟 JwtTenantFilter：验签通过后 set → 业务 → finally clear。 */
    private void runAs(String userId, String tenantId, Runnable action) {
        TenantContext.set(userId, tenantId);
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 辅助：双租户业务动作 ====================

    private static WorldSetting world(String id, String name) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName(name);
        w.setSettingDesc("desc");
        return w;
    }

    private static WorldbookEntry wbEntry(String id, String worldId, String title, String content) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId(id);
        e.setWorldId(worldId);
        e.setTitle(title);
        e.setKeywords(title);
        e.setContent(content);
        e.setEnabled(true);
        e.setSortOrder(0);
        return e;
    }

    private static StarIncarnation star(String id, String worldId, String starName, String warlord) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId(id);
        inc.setWorldId(worldId);
        inc.setStarName(starName);
        inc.setWarlordName(warlord);
        inc.setLore("命定叙事");
        inc.setRollable(true);
        inc.setSortOrder(0);
        return inc;
    }

    /** 租户 A：建世界 + 双轨模板行（1 通用 + 1 冥星），返回发布包 id */
    private String tenantAPublish() {
        final String[] packId = new String[1];
        runAs("u-a", "t-a", () -> {
            worldRepo.save(world("w-a", "租户A的世界"));
            worldbookRepo.save(wbEntry("wb-a1", "w-a", "钟表城", "设定正文v1"));
            starRepo.save(star("inc-a1", "w-a", "天魁星", "织田信长"));
            SharedPackService.PublishResult result = sharedPackService.publish(
                    "w-a", "A的设定集", "钟表城与冥星", List.of("wb-a1"), List.of("inc-a1"));
            packId[0] = result.packId();
        });
        return packId[0];
    }

    /** 租户 B：建世界 w-b 并导入指定包（返回导入结果） */
    private SharedPackService.ImportResult tenantBImport(String packId) {
        final SharedPackService.ImportResult[] result = new SharedPackService.ImportResult[1];
        runAs("u-b", "t-b", () -> {
            worldRepo.save(world("w-b", "租户B的世界"));
            result[0] = sharedPackService.importPack(packId, "w-b");
        });
        return result[0];
    }

    /** 租户 B：建玩家卡 + 存档（game_state 的 player_char_id 有 FK，必须先落卡行） */
    private void tenantBStartGame() {
        runAs("u-b", "t-b", () -> {
            cardRepo.save(card("pc-b", "w-b", "波波"));
            stateRepo.save(gameState("gs-b1", "sess-b1", "w-b", "pc-b"));
        });
    }

    // ==================== 端到端流转 ====================

    @Test
    void 发布到导入到开局fork的完整流转() {
        String packId = tenantAPublish();

        // B 画廊可见 A 的包（跨租户读全平台）
        runAs("u-b", "t-b", () -> {
            List<SharedLorebookPack> visible = sharedPackService.listVisible();
            assertThat(visible).extracting(SharedLorebookPack::getId).containsExactly(packId);
            assertThat(visible.get(0).getEntryCount()).isEqualTo(2);
        });

        // B 导入落自己模板层：行数、重绑、字段与 5.3 守卫测试断言一致（此处再钉一次链路完整性）
        SharedPackService.ImportResult importResult = tenantBImport(packId);
        assertThat(importResult.worldbookCount()).isEqualTo(1);
        assertThat(importResult.starCount()).isEqualTo(1);
        runAs("u-b", "t-b", () -> {
            List<WorldbookEntry> wb = worldbookRepo.findByWorldId("w-b");
            assertThat(wb).extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(wb.get(0).getSourceRef()).isNull();
            List<StarIncarnation> stars = starRepo.findByWorldId("w-b");
            assertThat(stars).extracting(StarIncarnation::getStarName).containsExactly("天魁星");
        });

        // B 新开局 fork：世界默认来源复制模板层（含导入行）为本存档 set 副本——
        // 开局向导「世界书来源」步之后的既有机制自动接管，零新增传播逻辑
        tenantBStartGame();
        runAs("u-b", "t-b", () -> {
            LorebookSet set = lorebookSetService.copyForNewGame("w-b", null, "gs-b1");
            assertThat(set).isNotNull();
            assertThat(worldbookRepo.findBySetId(set.getId()))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(starRepo.findBySetId(set.getId()))
                    .extracting(StarIncarnation::getStarName).containsExactly("天魁星");
        });
    }

    @Test
    void 覆盖更新与撤回后已导入副本不变() {
        String packId = tenantAPublish();
        tenantBImport(packId);
        final LorebookSet[] forkedSet = new LorebookSet[1];
        tenantBStartGame();
        runAs("u-b", "t-b", () -> {
            forkedSet[0] = lorebookSetService.copyForNewGame("w-b", null, "gs-b1");
        });

        // A 覆盖更新：追加一条模板行，重新发布同世界（同发布者同世界 → 覆盖）
        runAs("u-a", "t-a", () -> {
            worldbookRepo.save(wbEntry("wb-a2", "w-a", "雾都", "新设定"));
            SharedPackService.PublishResult result = sharedPackService.publish(
                    "w-a", "A的设定集", "钟表城与冥星", List.of("wb-a1", "wb-a2"), List.of("inc-a1"));
            assertThat(result.overwrite()).isTrue();
            assertThat(result.packId()).isEqualTo(packId);
            assertThat(result.entryCount()).isEqualTo(3);
            // 包内条目已替换为新集
            assertThat(sharedRepo.readWorldbookEntries(packId)).hasSize(2);
        });

        // B 已导入的模板行与 fork 出的 set 副本均不变（快照哲学：无回链、零渗入）
        runAs("u-b", "t-b", () -> {
            assertThat(worldbookRepo.findByWorldId("w-b"))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
            assertThat(worldbookRepo.findBySetId(forkedSet[0].getId()))
                    .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
        });

        // A 撤回下架：画廊消失、详情 404 语义；B 副本完好
        runAs("u-a", "t-a", () -> assertThat(sharedPackService.revoke(packId)).isTrue());
        runAs("u-b", "t-b", () -> {
            assertThat(sharedPackService.listVisible()).isEmpty();
            assertThat(sharedPackService.loadPack(packId)).isNull();
            assertThat(worldbookRepo.findByWorldId("w-b")).hasSize(1);
            assertThat(starRepo.findByWorldId("w-b")).hasSize(1);
            assertThat(worldbookRepo.findBySetId(forkedSet[0].getId())).hasSize(1);
        });
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

    private static CharacterCard card(String id, String worldId, String name) {
        CharacterCard c = new CharacterCard();
        c.setId(id);
        c.setWorldId(worldId);
        c.setName(name);
        c.setType("player");
        return c;
    }
}
