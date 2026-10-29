package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.Location;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三件套软删除、关系复活与冥星模板 CRUD 语义的单元测试
 * （workshop-soft-delete-cascade 8.2 / 5.3 service 面，mock 仓储）。
 * <p>
 * 钉住三块：
 * <ol>
 *   <li><b>三件套删除软标</b>：deleteLocation/deleteTrigger/deleteRelationship
 *       无条件写软标，返回值驱动控制层 404；编辑分流（id 命中走 update）。</li>
 *   <li><b>关系保存复活</b>（D6）：同对角色任一方向存在软删行时复活原行
 *       （复用 id、清 deleted_at、覆写字段）而非 INSERT，绕开 uk_relationship。</li>
 *   <li><b>冥星模板 CRUD</b>（D8）：预设世界写拒绝；非预设世界模板层锚定
 *       （set_id 置 null）+ 缺省补齐；条目定位限模板行。</li>
 * </ol>
 */
class WorkshopServiceSoftDeleteTest {

    private static final String WORLD_ID = "world-1";
    private static final String CHAR_A = "char-a";
    private static final String CHAR_B = "char-b";

    private RpgWorldSettingRepository worldRepo;
    private RpgLocationRepository locationRepo;
    private RpgRelationshipRepository relRepo;
    private RpgTriggerRepository triggerRepo;
    private RpgStarIncarnationRepository starRepo;
    private RpgWorldbookEntryRepository worldbookRepo;
    private WorkshopService service;

    @BeforeEach
    void setUp() {
        worldRepo = mock(RpgWorldSettingRepository.class);
        locationRepo = mock(RpgLocationRepository.class);
        relRepo = mock(RpgRelationshipRepository.class);
        triggerRepo = mock(RpgTriggerRepository.class);
        starRepo = mock(RpgStarIncarnationRepository.class);
        worldbookRepo = mock(RpgWorldbookEntryRepository.class);
        // startGame 的 fork 依赖（本测试不触达，但构造器需要非空返回语义）
        LorebookSetService lorebookSetService = mock(LorebookSetService.class);
        LorebookSet forked = new LorebookSet();
        forked.setId("set-1");
        forked.setWorldId(WORLD_ID);
        when(lorebookSetService.copyForNewGame(any(), any(), any(), any())).thenReturn(forked);

        service = new WorkshopService(worldRepo, mock(RpgCharacterCardRepository.class),
                locationRepo, relRepo, triggerRepo,
                mock(RpgGameStateRepository.class), mock(RpgTriggerRuntimeRepository.class),
                starRepo, mock(RpgStarIncarnationRuntimeRepository.class),
                mock(WorkshopLlmGenerator.class), mock(CharacterOriginService.class),
                mock(WorldCoverStorageService.class),
                mock(PlatformTransactionManager.class),
                worldbookRepo, lorebookSetService, mock(LoreArchiveService.class),
                mock(SessionService.class), mock(SessionRepository.class));
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

    private static WorldSetting world(String id) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName("钟表城");
        return w;
    }

    // ==================== 三件套删除软标（D5） ====================

    @Test
    void 删除地点成功返回true并写软标() {
        when(locationRepo.softDeleteById("loc-1")).thenReturn(1);

        assertThat(service.deleteLocation("loc-1")).isTrue();
    }

    @Test
    void 删除不存在的地点返回false供控制层判404() {
        when(locationRepo.softDeleteById("loc-404")).thenReturn(0);

        assertThat(service.deleteLocation("loc-404")).isFalse();
    }

    @Test
    void 删除触发器成功返回true且runtime行不动() {
        when(triggerRepo.softDeleteById("trig-1")).thenReturn(1);

        assertThat(service.deleteTrigger("trig-1")).isTrue();
        // runtime 行随存档生命周期，三件套删除不触碰（D5）
        verify(triggerRepo, never()).softDeleteByNpcId(any());
        verify(triggerRepo, never()).softDeleteByWorldId(any());
    }

    @Test
    void 删除不存在的触发器返回false() {
        when(triggerRepo.softDeleteById("trig-404")).thenReturn(0);

        assertThat(service.deleteTrigger("trig-404")).isFalse();
    }

    @Test
    void 删除关系成功返回true并写软标() {
        when(relRepo.softDeleteById("rel-1")).thenReturn(1);

        assertThat(service.deleteRelationship("rel-1")).isTrue();
    }

    @Test
    void 删除不存在的关系返回false() {
        when(relRepo.softDeleteById("rel-404")).thenReturn(0);

        assertThat(service.deleteRelationship("rel-404")).isFalse();
    }

    // ==================== 三件套编辑分流（6.2 前置） ====================

    @Test
    void 保存地点已有id命中走UPDATE() {
        Location incoming = loc("loc-1", WORLD_ID, "客栈");
        when(locationRepo.findById("loc-1")).thenReturn(loc("loc-1", WORLD_ID, "旧名"));

        service.saveLocation(incoming);

        verify(locationRepo).update("loc-1", "客栈", "desc", "[]", "[]");
        verify(locationRepo, never()).save(any());
    }

    @Test
    void 保存触发器已有id命中走UPDATE() {
        Trigger incoming = trigger("trig-1", WORLD_ID, CHAR_A);
        when(triggerRepo.findById("trig-1")).thenReturn(trigger("trig-1", WORLD_ID, CHAR_A));

        service.saveTrigger(incoming);

        verify(triggerRepo).update(incoming);
        verify(triggerRepo, never()).save(any());
    }

    @Test
    void 保存触发器把空串npcId归一化为null避免外键违约() {
        // 走查发现：前端留空的 npcId 以空串提交，空串不是合法角色 id（FK 允许 NULL）
        Trigger incoming = trigger("trig-x", WORLD_ID, "");

        service.saveTrigger(incoming);

        assertThat(incoming.getNpcId()).isNull();
        verify(triggerRepo).save(incoming);
    }

    // ==================== 关系保存复活（D6） ====================

    @Test
    void 保存关系命中同对软删行时复活并复用原id() {
        Relationship softDeleted = rel("rel-9", CHAR_A, CHAR_B);
        when(relRepo.findSoftDeletedByPair(CHAR_A, CHAR_B)).thenReturn(softDeleted);

        Relationship incoming = rel(null, CHAR_A, CHAR_B);
        incoming.setAttitude(30);
        Relationship saved = service.saveRelationship(incoming);

        // 复活原行：复用软删行 id（不产生新行）、覆写字段，绕开 uk_relationship
        assertThat(saved.getId()).isEqualTo("rel-9");
        assertThat(saved.getAttitude()).isEqualTo(30);
        verify(relRepo).revive(incoming);
        verify(relRepo, never()).save(any());
    }

    @Test
    void 保存关系反向端点顺序同样复活原行() {
        // 软删行按甲→乙方向落库；用户按乙→甲顺序重建（repo 双向匹配 (a,b)/(b,a)）
        Relationship softDeleted = rel("rel-9", CHAR_A, CHAR_B);
        when(relRepo.findSoftDeletedByPair(CHAR_B, CHAR_A)).thenReturn(softDeleted);

        Relationship saved = service.saveRelationship(rel(null, CHAR_B, CHAR_A));

        assertThat(saved.getId()).isEqualTo("rel-9");
        verify(relRepo).revive(saved);
        verify(relRepo, never()).save(any());
    }

    @Test
    void 保存关系编辑态走UPDATE不走复活() {
        when(relRepo.findById("rel-1")).thenReturn(rel("rel-1", CHAR_A, CHAR_B));

        Relationship incoming = rel("rel-1", CHAR_A, CHAR_B);
        incoming.setTrust(80);
        Relationship saved = service.saveRelationship(incoming);

        assertThat(saved.getId()).isEqualTo("rel-1");
        assertThat(saved.getTrust()).isEqualTo(80);
        verify(relRepo).update(incoming);
        verify(relRepo, never()).revive(any());
        verify(relRepo, never()).save(any());
    }

    @Test
    void 保存关系无软删对时走INSERT() {
        when(relRepo.findSoftDeletedByPair(CHAR_A, CHAR_B)).thenReturn(null);

        Relationship saved = service.saveRelationship(rel(null, CHAR_A, CHAR_B));

        assertThat(saved.getId()).isNotBlank();
        verify(relRepo).save(saved);
        verify(relRepo, never()).revive(any());
    }

    // ==================== 冥星模板 CRUD（D8 / 5.3 service 面） ====================

    @Test
    void 预设世界保存冥星模板被拒绝() {
        WorldSetting preset = world("preset-adventure:t-test");
        when(worldRepo.findById(preset.getId())).thenReturn(preset);
        StarIncarnation inc = new StarIncarnation();
        inc.setWorldId(preset.getId());

        assertThatThrownBy(() -> service.saveStarIncarnation(inc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预设世界");

        verify(starRepo, never()).save(any());
        verify(starRepo, never()).updateTemplate(any());
    }

    @Test
    void 预设世界删除冥星模板被拒绝() {
        StarIncarnation presetRow = new StarIncarnation();
        presetRow.setId("star-1");
        presetRow.setWorldId("preset-adventure:t-test");
        when(starRepo.findTemplateById("star-1")).thenReturn(presetRow);

        assertThatThrownBy(() -> service.deleteStarIncarnation("star-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预设世界");

        verify(starRepo, never()).softDeleteTemplateById(any());
    }

    @Test
    void 非预设世界新建冥星模板锚定模板层并补缺省() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));

        StarIncarnation inc = new StarIncarnation();
        inc.setWorldId(WORLD_ID);
        inc.setStarName("天罡星");
        inc.setWarlordName("卢灭朱");
        // setId/setSetId/rollable/sortOrder 全缺省

        StarIncarnation saved = service.saveStarIncarnation(inc);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getSetId()).isNull();       // 模板层锚定：绝不写 set 行
        assertThat(saved.getRollable()).isTrue();    // 缺省可推演
        assertThat(saved.getSortOrder()).isEqualTo(0);
        verify(starRepo).save(inc);
        verify(starRepo, never()).updateTemplate(any());
    }

    @Test
    void 非预设世界编辑冥星模板走模板层更新() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        StarIncarnation existing = new StarIncarnation();
        existing.setId("star-1");
        existing.setWorldId(WORLD_ID);
        when(starRepo.findTemplateById("star-1")).thenReturn(existing);

        StarIncarnation incoming = new StarIncarnation();
        incoming.setId("star-1");
        incoming.setWorldId(WORLD_ID);
        incoming.setStarName("天罡星");
        incoming.setWarlordName("卢灭朱");
        incoming.setRollable(false);
        incoming.setSortOrder(5);

        StarIncarnation saved = service.saveStarIncarnation(incoming);

        assertThat(saved.getRollable()).isFalse();
        assertThat(saved.getSortOrder()).isEqualTo(5);
        verify(starRepo).updateTemplate(incoming);
        verify(starRepo, never()).save(any());
    }

    @Test
    void 删除不存在的冥星模板返回false() {
        when(starRepo.findTemplateById("star-404")).thenReturn(null);

        assertThat(service.deleteStarIncarnation("star-404")).isFalse();
    }

    @Test
    void 非预设世界删除冥星模板写软标() {
        StarIncarnation row = new StarIncarnation();
        row.setId("star-1");
        row.setWorldId(WORLD_ID);
        when(starRepo.findTemplateById("star-1")).thenReturn(row);
        when(starRepo.softDeleteTemplateById("star-1")).thenReturn(1);

        assertThat(service.deleteStarIncarnation("star-1")).isTrue();
    }
}
