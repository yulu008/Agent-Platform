package com.luyu.agent.rpg.service;

import java.util.List;

import com.luyu.agent.rpg.config.RpgSchemaInitializer;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.Location;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgLorebookSetRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.tenancy.TenantContext;
import com.luyu.agent.tenancy.TenantGuardConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 工坊软删除端到端测试（workshop-soft-delete-cascade 8.3 / 4.2 / 5.3 set 副本面，
 * 参照 {@code SharedPackEndToEndTest}：@JdbcTest + 真实 H2 + 真实仓储与服务，
 * 以 TenantContext 直设模拟 JwtTenantFilter 生命周期）。
 * <p>
 * 钉住完整数据链：世界/角色条件级联软删（读路径全消失、通用世界书豁免、封面保留）、
 * 触发器软删后引擎扫描查询即时排除、关系复活绕开唯一约束、
 * fork 复制排除软删行、冥星模板删除不影响 set 副本、
 * 软删预设世界重新物化复活（固定 preset- 主键不冲突）。
 */
@JdbcTest
@Import({TenantGuardConfiguration.class, RpgSchemaInitializer.class,
        RpgWorldSettingRepository.class, RpgCharacterCardRepository.class,
        RpgLocationRepository.class, RpgRelationshipRepository.class,
        RpgTriggerRepository.class, RpgTriggerRuntimeRepository.class,
        RpgGameStateRepository.class, RpgStarIncarnationRepository.class,
        RpgStarIncarnationRuntimeRepository.class, RpgWorldbookEntryRepository.class,
        RpgLorebookSetRepository.class, RpgDestinedFigureRepository.class,
        WorkshopService.class, LorebookSetService.class, PresetWorldService.class})
class WorkshopSoftDeleteEndToEndTest {

    /** LLM/起源/封面属外部边界，mock 隔离；档案物化同 SharedPackEndToEndTest 另测 */
    @MockitoBean
    private WorkshopLlmGenerator llmGenerator;

    @MockitoBean
    private CharacterOriginService originService;

    @MockitoBean
    private WorldCoverStorageService coverStorage;

    @MockitoBean
    private LoreArchiveService loreArchiveService;

    // spring-ai-session 自动配置不在 @JdbcTest 切片内，开局打标属外部边界 mock 隔离（降级不阻断）
    @MockitoBean
    private SessionService sessionService;

    @MockitoBean
    private SessionRepository sessionRepository;

    @Autowired
    private WorkshopService workshopService;

    @Autowired
    private PresetWorldService presetWorldService;

    @Autowired
    private LorebookSetService lorebookSetService;

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
    private RpgGameStateRepository stateRepo;

    @Autowired
    private RpgStarIncarnationRepository starRepo;

    @Autowired
    private RpgWorldbookEntryRepository worldbookRepo;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 辅助构造 ====================

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
        l.setDescription("desc");
        l.setNpcIds("[]");
        l.setTriggerIds("[]");
        return l;
    }

    private static Relationship rel(String id, String a, String b) {
        Relationship r = new Relationship();
        r.setId(id);
        r.setCharAId(a);
        r.setCharBId(b);
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

    private static WorldbookEntry wbEntry(String id, String worldId, String title) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId(id);
        e.setWorldId(worldId);
        e.setTitle(title);
        e.setKeywords(title);
        e.setContent("设定正文");
        e.setEnabled(true);
        e.setSortOrder(0);
        return e;
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

    // ==================== 世界级联软删（D3） ====================

    @Test
    void 世界级联软删后全部读路径消失且通用世界书与封面豁免() {
        worldRepo.save(world("w-1", "钟表城"));
        charRepo.save(card("c-a", "w-1", "甲"));
        charRepo.save(card("c-b", "w-1", "乙"));
        locationRepo.save(loc("loc-1", "w-1", "客栈"));
        triggerRepo.save(trigger("trig-1", "w-1", "c-a"));
        relRepo.save(rel("rel-1", "c-a", "c-b"));
        starRepo.save(star("star-1", "w-1", "天罡星", "卢灭朱"));
        worldbookRepo.save(wbEntry("wb-1", "w-1", "钟表城"));

        workshopService.deleteWorld("w-1");

        // 全部读路径消失（物理行保留在库，过滤后不可见）
        assertThat(worldRepo.findById("w-1")).isNull();
        assertThat(workshopService.listWorlds()).extracting(WorldSetting::getId).doesNotContain("w-1");
        assertThat(charRepo.findByWorldId("w-1")).isEmpty();
        assertThat(locationRepo.findByWorldId("w-1")).isEmpty();
        assertThat(triggerRepo.findByWorldId("w-1")).isEmpty();
        assertThat(relRepo.findByCharId("c-a")).isEmpty();
        assertThat(starRepo.findByWorldId("w-1")).isEmpty();
        // 通用世界书模板行豁免：内容原样保留
        assertThat(worldbookRepo.findByWorldId("w-1"))
                .extracting(WorldbookEntry::getTitle).containsExactly("钟表城");
        // 封面文件不动（design D3）
        verify(coverStorage, never()).delete(any(), any());
    }

    @Test
    void 有存档的世界删除被拒绝且零写入() {
        worldRepo.save(world("w-1", "钟表城"));
        charRepo.save(card("c-a", "w-1", "甲"));
        stateRepo.save(gameState("gs-1", "s-1", "w-1", "c-a"));

        assertThatThrownBy(() -> workshopService.deleteWorld("w-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存档");

        assertThat(worldRepo.findById("w-1")).isNotNull();
        assertThat(charRepo.findByWorldId("w-1")).hasSize(1);
    }

    // ==================== 角色级联软删（D4） ====================

    @Test
    void 角色级联软删两端关系与关联触发器() {
        worldRepo.save(world("w-1", "钟表城"));
        charRepo.save(card("c-a", "w-1", "甲"));
        charRepo.save(card("c-b", "w-1", "乙"));
        triggerRepo.save(trigger("trig-1", "w-1", "c-a"));
        relRepo.save(rel("rel-1", "c-a", "c-b"));

        workshopService.deleteCharacter("c-a");

        assertThat(charRepo.findById("c-a")).isNull();
        assertThat(charRepo.findByWorldId("w-1")).extracting(CharacterCard::getId).containsExactly("c-b");
        assertThat(relRepo.findByCharId("c-a")).isEmpty();
        assertThat(triggerRepo.findByWorldId("w-1")).isEmpty();
    }

    @Test
    void 被存档引用为玩家的角色删除被拒绝() {
        worldRepo.save(world("w-1", "钟表城"));
        charRepo.save(card("c-a", "w-1", "甲"));
        stateRepo.save(gameState("gs-1", "s-1", "w-1", "c-a"));

        assertThatThrownBy(() -> workshopService.deleteCharacter("c-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存档");

        assertThat(charRepo.findById("c-a")).isNotNull();
    }

    // ==================== 触发器软删后引擎扫描排除（D5 / 3.6） ====================

    @Test
    void 软删触发器从引擎扫描查询即时排除() {
        worldRepo.save(world("w-1", "钟表城"));
        triggerRepo.save(trigger("trig-1", "w-1", null));
        triggerRepo.save(trigger("trig-2", "w-1", null));

        assertThat(workshopService.deleteTrigger("trig-1")).isTrue();

        // TriggerEngine.scanHardConditions 消费同一 findByWorldId：软删行即时退出扫描，
        // 进行中存档本局不再触发
        assertThat(triggerRepo.findByWorldId("w-1")).extracting(Trigger::getId).containsExactly("trig-2");
    }

    // ==================== 关系复活（D6，真实唯一约束） ====================

    @Test
    void 软删关系同对重建复活原行不产生新行() {
        worldRepo.save(world("w-1", "钟表城"));
        charRepo.save(card("c-a", "w-1", "甲"));
        charRepo.save(card("c-b", "w-1", "乙"));
        relRepo.save(rel("rel-1", "c-a", "c-b"));

        assertThat(workshopService.deleteRelationship("rel-1")).isTrue();

        Relationship rebuilt = rel(null, "c-a", "c-b");
        rebuilt.setAttitude(77);
        Relationship saved = workshopService.saveRelationship(rebuilt);

        // 复活原行：复用 id、覆写字段；uk_relationship 不炸、无重复行
        assertThat(saved.getId()).isEqualTo("rel-1");
        assertThat(relRepo.findByCharId("c-a")).hasSize(1);
        assertThat(relRepo.findById("rel-1").getAttitude()).isEqualTo(77);
    }

    @Test
    void 软删关系反向方向重建同样复活原行() {
        worldRepo.save(world("w-1", "钟表城"));
        charRepo.save(card("c-a", "w-1", "甲"));
        charRepo.save(card("c-b", "w-1", "乙"));
        relRepo.save(rel("rel-1", "c-a", "c-b"));
        workshopService.deleteRelationship("rel-1");

        // 用户按乙→甲顺序重建（与软删行方向相反）
        Relationship saved = workshopService.saveRelationship(rel(null, "c-b", "c-a"));

        assertThat(saved.getId()).isEqualTo("rel-1");
        assertThat(relRepo.findByCharId("c-a")).hasSize(1);
    }

    // ==================== fork 复制排除软删行（8.3） ====================

    @Test
    void 开局fork复制排除软删模板行() {
        worldRepo.save(world("w-1", "钟表城"));
        starRepo.save(star("star-1", "w-1", "天罡星", "卢灭朱"));
        starRepo.save(star("star-2", "w-1", "天机星", "军师"));
        worldbookRepo.save(wbEntry("wb-1", "w-1", "钟表城"));
        worldbookRepo.save(wbEntry("wb-2", "w-1", "雾都"));
        workshopService.deleteStarIncarnation("star-1");
        workshopService.deleteWorldbookEntry("wb-1");

        LorebookSet set = lorebookSetService.copyForNewGame("w-1", null, "gs-x");

        assertThat(starRepo.findBySetId(set.getId()))
                .extracting(StarIncarnation::getStarName).containsExactly("天机星");
        assertThat(worldbookRepo.findBySetId(set.getId()))
                .extracting(WorldbookEntry::getTitle).containsExactly("雾都");
    }

    // ==================== 冥星模板删除不影响 set 副本（5.3） ====================

    @Test
    void 冥星模板删除不影响已开局存档set副本() {
        worldRepo.save(world("w-1", "钟表城"));
        starRepo.save(star("star-1", "w-1", "天罡星", "卢灭朱"));
        LorebookSet set = lorebookSetService.copyForNewGame("w-1", null, "gs-x");
        assertThat(starRepo.findBySetId(set.getId())).hasSize(1);

        assertThat(workshopService.deleteStarIncarnation("star-1")).isTrue();

        // 模板行消失，set 副本快照不变（游戏内弹窗照常展示）
        assertThat(starRepo.findByWorldId("w-1")).isEmpty();
        assertThat(starRepo.findBySetId(set.getId()))
                .extracting(StarIncarnation::getStarName).containsExactly("天罡星");
    }

    // ==================== 预设物化复活（D7 / 4.2） ====================

    @Test
    void 软删预设世界重新物化复活原行且set副本不受影响() {
        PresetWorldService.PresetWorld first = presetWorldService.materialize("adventure");
        String worldId = first.worldId();
        assertThat(worldId).startsWith("preset-adventure:");
        List<StarIncarnation> templateStars = starRepo.findByWorldId(worldId);
        assertThat(templateStars).isNotEmpty();
        LorebookSet set = lorebookSetService.copyForNewGame(worldId, null, "gs-x");
        List<StarIncarnation> setStars = starRepo.findBySetId(set.getId());
        assertThat(setStars).hasSize(templateStars.size());

        // 无 game_state 存档 → deleteWorld 放行：级联软删世界 + 冥星模板行
        workshopService.deleteWorld(worldId);
        assertThat(worldRepo.findById(worldId)).isNull();
        assertThat(starRepo.findByWorldId(worldId)).isEmpty();

        // 重新物化：复活原行（固定 preset- 主键不冲突）+ rewriteAll 全量重写
        PresetWorldService.PresetWorld second = presetWorldService.materialize("adventure");
        assertThat(second.worldId()).isEqualTo(worldId);
        assertThat(worldRepo.findById(worldId)).isNotNull();
        List<StarIncarnation> rewritten = starRepo.findByWorldId(worldId);
        assertThat(rewritten).extracting(StarIncarnation::getStarName)
                .containsExactlyElementsOf(templateStars.stream().map(StarIncarnation::getStarName).toList());

        // set 副本照旧（重新物化不触碰 set 行，md 增删条目也不回写）
        assertThat(starRepo.findBySetId(set.getId()))
                .extracting(StarIncarnation::getStarName)
                .containsExactlyElementsOf(setStars.stream().map(StarIncarnation::getStarName).toList());
    }

    // ==================== 三件套与关系软删读过滤（8.3） ====================

    @Test
    void 三件套软删后各列表读路径消失() {
        worldRepo.save(world("w-1", "钟表城"));
        locationRepo.save(loc("loc-1", "w-1", "客栈"));
        triggerRepo.save(trigger("trig-1", "w-1", null));
        charRepo.save(card("c-a", "w-1", "甲"));
        charRepo.save(card("c-b", "w-1", "乙"));
        relRepo.save(rel("rel-1", "c-a", "c-b"));

        assertThat(workshopService.deleteLocation("loc-1")).isTrue();
        assertThat(workshopService.deleteTrigger("trig-1")).isTrue();
        assertThat(workshopService.deleteRelationship("rel-1")).isTrue();

        assertThat(workshopService.listLocations("w-1")).isEmpty();
        assertThat(workshopService.listTriggers("w-1")).isEmpty();
        assertThat(workshopService.getRelationships("c-a")).isEmpty();
        assertThat(workshopService.listRelationshipsByWorld("w-1")).isEmpty();
    }
}
