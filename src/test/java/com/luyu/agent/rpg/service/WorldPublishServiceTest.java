package com.luyu.agent.rpg.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.Location;
import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.rpg.model.SharedWorld;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.WorldPublishRepository;
import com.luyu.agent.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorldPublishService} 单元测试（works-view-and-world-publish tasks 6.1，
 * 纯 Mockito + 真实 {@link TenantContext} ThreadLocal，参照 {@link SharedPackServiceTest}）。
 * <p>
 * 钉住六组语义（spec world-publish）：
 * <ol>
 *   <li><b>发布快照</b>：snapshot_json 单表文档 v1 含全家桶（世界核心列 + 六类子数据），
 *       发布时点冻结——发布后改源零渗入；关系仅收两端均属快照角色集的行
 *       （关系表无 world_id，跨世界关系剔除）；封面复制失败降级 null 不阻断发布；</li>
 *   <li><b>模板层口径</b>：世界书 / 冥星只收模板行（set_id IS NULL 过滤在 Repository SQL，
 *       本测试 mock 的返回即已过滤结果；set/runtime 行不进包的 SQL 级断言见 6.2/6.4 集成测试）；</li>
 *   <li><b>覆盖更新</b>：同发布者同源世界重复发布 → refreshPack 重写整包（含撤销后重发布
 *       复活：revoked_at 置 NULL 在 SQL 层），不插新包行；覆盖时源世界无封面保留旧包封面；</li>
 *   <li><b>导入行字段</b>：新世界 id、全子数据新行 id、跨行引用重绑
 *       （world.locations / location.npcIds / triggerIds / relationship 两端 / trigger.npcId）、
 *       冥星保留 star_name、世界书 set_id / source_ref 置 NULL
 *       （tenant_id 归属由 Repository 强制注入，SQL 层归属断言见 6.2/6.4）；</li>
 *   <li><b>导入失败语义</b>：包不存在/已下架 → 404；发布者本人 → 409 零写入；
 *       快照损坏 → 500 语义上抛；子表失败异常上抛不静默（整体单一事务，
 *       真库零残留回滚断言见 6.4 端到端流转测试）；</li>
 *   <li><b>撤销</b>：软下架由 Repository 行数分流 true/false（SQL 带发布者锚点，
 *       非发布者撤销行数为 0）。</li>
 * </ol>
 */
class WorldPublishServiceTest {

    private static final String WORLD_ID = "w-src";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 内容块配置原文（发布/导入同一份，rpg-hud-custom-blocks D6 快照携带断言用） */
    private static final String CUSTOM_BLOCKS_JSON = "[{\"title\":\"本幕目标\",\"field\":\"objectives\"}]";

    private WorldPublishRepository sharedRepo;
    private RpgWorldSettingRepository worldRepo;
    private RpgCharacterCardRepository charRepo;
    private RpgLocationRepository locationRepo;
    private RpgRelationshipRepository relRepo;
    private RpgTriggerRepository triggerRepo;
    private RpgWorldbookEntryRepository worldbookRepo;
    private RpgStarIncarnationRepository starRepo;
    private WorldCoverStorageService coverStorage;
    private WorldPublishService service;

    @BeforeEach
    void setUp() {
        sharedRepo = mock(WorldPublishRepository.class);
        worldRepo = mock(RpgWorldSettingRepository.class);
        charRepo = mock(RpgCharacterCardRepository.class);
        locationRepo = mock(RpgLocationRepository.class);
        relRepo = mock(RpgRelationshipRepository.class);
        triggerRepo = mock(RpgTriggerRepository.class);
        worldbookRepo = mock(RpgWorldbookEntryRepository.class);
        starRepo = mock(RpgStarIncarnationRepository.class);
        coverStorage = mock(WorldCoverStorageService.class);
        service = new WorldPublishService(sharedRepo, worldRepo, charRepo, locationRepo,
                relRepo, triggerRepo, worldbookRepo, starRepo, coverStorage);
        TenantContext.set("u-a", "t-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 1. 发布快照 ====================

    @Test
    void publish_快照文档含全家桶与发布锚点() throws Exception {
        mockFullWorldFamily();
        when(coverStorage.copyToShared(eq("t-a"), eq("cover.png"), anyString())).thenReturn("pack.png");

        WorldPublishService.PublishResult result = service.publish(WORLD_ID);

        assertThat(result.overwrite()).isFalse();
        assertThat(result.entryCount()).isEqualTo(6);   // 1角色+1地点+1关系+1触发器+1世界书+1冥星
        assertThat(result.packId()).isNotBlank();

        ArgumentCaptor<SharedWorld> cap = ArgumentCaptor.forClass(SharedWorld.class);
        verify(sharedRepo).insertPack(cap.capture());
        SharedWorld pack = cap.getValue();
        assertThat(pack.getId()).isEqualTo(result.packId());
        assertThat(pack.getName()).isEqualTo("世界-" + WORLD_ID);
        assertThat(pack.getBlurb()).isEqualTo("desc");
        assertThat(pack.getCover()).isEqualTo("pack.png");
        assertThat(pack.getPublisherTenantId()).isEqualTo("t-a");
        assertThat(pack.getPublisherUserId()).isEqualTo("u-a");
        assertThat(pack.getOriginWorldId()).isEqualTo(WORLD_ID);
        assertThat(pack.getOriginWorldName()).isEqualTo("世界-" + WORLD_ID);
        assertThat(pack.getSnapshotVersion()).isEqualTo(1);
        assertThat(pack.getEntryCount()).isEqualTo(6);

        WorldPublishService.Snapshot snapshot =
                MAPPER.readValue(pack.getSnapshotJson(), WorldPublishService.Snapshot.class);
        assertThat(snapshot.world().name()).isEqualTo("世界-" + WORLD_ID);
        assertThat(snapshot.world().settingDesc()).isEqualTo("desc");
        assertThat(snapshot.world().era()).isEqualTo("era");
        assertThat(snapshot.world().locations()).isEqualTo("[\"loc-1\"]");
        // 内容块配置随包传播（rpg-hud-custom-blocks D6：照 themeTokens 先例）
        assertThat(snapshot.world().customBlocks()).isEqualTo(CUSTOM_BLOCKS_JSON);
        assertThat(snapshot.characters()).hasSize(1);
        assertThat(snapshot.characters().get(0).name()).isEqualTo("信长");
        assertThat(snapshot.locations()).hasSize(1);
        assertThat(snapshot.locations().get(0).name()).isEqualTo("安土城");
        assertThat(snapshot.relationships()).hasSize(1);
        assertThat(snapshot.triggers()).hasSize(1);
        // 模板层口径：repo 返回即模板行（SQL set_id IS NULL 已滤），进包的是模板内容本体
        assertThat(snapshot.worldbookEntries()).hasSize(1);
        assertThat(snapshot.worldbookEntries().get(0).title()).isEqualTo("钟表城");
        assertThat(snapshot.starIncarnations()).hasSize(1);
        assertThat(snapshot.starIncarnations().get(0).starName()).isEqualTo("天魁星");
    }

    @Test
    void publish_发布后改源零渗入快照() throws Exception {
        WorldSetting world = world(WORLD_ID);
        CharacterCard src = character("c-1", "信长");
        when(worldRepo.findById(WORLD_ID)).thenReturn(world);
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(src));
        mockEmptyFamilyRest();

        service.publish(WORLD_ID);

        ArgumentCaptor<SharedWorld> cap = ArgumentCaptor.forClass(SharedWorld.class);
        verify(sharedRepo).insertPack(cap.capture());
        String frozenJson = cap.getValue().getSnapshotJson();

        // 发布后源世界与角色再改：snapshot_json 是发布时点冻结副本（快照哲学）
        world.setName("改名了");
        src.setName("改名角色");
        WorldPublishService.Snapshot frozen =
                MAPPER.readValue(frozenJson, WorldPublishService.Snapshot.class);
        assertThat(frozen.world().name()).isEqualTo("世界-" + WORLD_ID);
        assertThat(frozen.characters().get(0).name()).isEqualTo("信长");
    }

    @Test
    void publish_关系仅收两端均属快照角色集的行() throws Exception {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(
                List.of(character("c-1", "甲"), character("c-2", "乙")));
        mockEmptyFamilyRest();   // 先空底（含关系空集）
        // 再覆盖关系：两端都在 → 进包；一端跨世界 → 剔除（后 stub 者胜）
        when(relRepo.findAll()).thenReturn(List.of(
                relationship("rel-in", "c-1", "c-2"),
                relationship("rel-out", "c-1", "c-x")));

        WorldPublishService.PublishResult result = service.publish(WORLD_ID);

        ArgumentCaptor<SharedWorld> cap = ArgumentCaptor.forClass(SharedWorld.class);
        verify(sharedRepo).insertPack(cap.capture());
        WorldPublishService.Snapshot snapshot =
                MAPPER.readValue(cap.getValue().getSnapshotJson(), WorldPublishService.Snapshot.class);
        assertThat(snapshot.relationships())
                .extracting(WorldPublishService.RelInfo::id)
                .containsExactly("rel-in");
        assertThat(result.entryCount()).isEqualTo(3);   // 2角色 + 1关系
    }

    @Test
    void publish_封面复制失败降级null不阻断发布() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        mockEmptyFamilyRest();
        // coverStorage 默认返回 null = 复制失败降级路径（观感资产，不阻断发布）

        WorldPublishService.PublishResult result = service.publish(WORLD_ID);

        ArgumentCaptor<SharedWorld> cap = ArgumentCaptor.forClass(SharedWorld.class);
        verify(sharedRepo).insertPack(cap.capture());
        assertThat(cap.getValue().getCover()).isNull();
        assertThat(result.packId()).isNotBlank();
        assertThat(result.entryCount()).isZero();
    }

    @Test
    void publish_世界id空拒绝() {
        assertThatThrownBy(() -> service.publish(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("世界 id");
    }

    @Test
    void publish_世界不存在或不属本租户拒绝() {
        when(worldRepo.findById("w-of-b")).thenReturn(null);

        assertThatThrownBy(() -> service.publish("w-of-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("世界不存在");
    }

    @Test
    void publish_预设世界无需发布() {
        when(worldRepo.findById("preset-adventure:t-a")).thenReturn(world("preset-adventure:t-a"));

        assertThatThrownBy(() -> service.publish("preset-adventure:t-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预设世界");
    }

    // ==================== 2. 覆盖更新 ====================

    @Test
    void publish_重复发布覆盖刷新既有包不插新行() {
        mockFullWorldFamily();
        SharedWorld existing = new SharedWorld();
        existing.setId("pack-old");
        existing.setPublisherTenantId("t-a");
        existing.setOriginWorldId(WORLD_ID);
        existing.setCover("old.png");
        when(sharedRepo.findByPublisherAndWorld("t-a", WORLD_ID)).thenReturn(existing);
        when(coverStorage.copyToShared(eq("t-a"), any(), eq("pack-old"))).thenReturn("new.png");

        WorldPublishService.PublishResult result = service.publish(WORLD_ID);

        assertThat(result.overwrite()).isTrue();
        assertThat(result.packId()).isEqualTo("pack-old");
        assertThat(result.entryCount()).isEqualTo(6);
        // 覆盖 = refreshPack 整包重写（SQL 层 updated_at 刷新 + revoked_at 置 NULL
        // → 撤销后重发布复活）；旧包 id 复用，不插新行
        verify(sharedRepo).refreshPack(eq("pack-old"), eq("世界-" + WORLD_ID), eq("desc"),
                eq("new.png"), anyString(), eq(1), eq(6), eq("t-a"));
        verify(sharedRepo, never()).insertPack(any());
    }

    @Test
    void publish_覆盖时源世界无封面保留旧包封面() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        mockEmptyFamilyRest();
        SharedWorld existing = new SharedWorld();
        existing.setId("pack-old");
        existing.setPublisherTenantId("t-a");
        existing.setOriginWorldId(WORLD_ID);
        existing.setCover("old.png");
        when(sharedRepo.findByPublisherAndWorld("t-a", WORLD_ID)).thenReturn(existing);
        // coverStorage 默认 null：源封面缺失/复制失败 → 覆盖保留旧包封面

        service.publish(WORLD_ID);

        verify(sharedRepo).refreshPack(eq("pack-old"), anyString(), anyString(),
                eq("old.png"), anyString(), eq(1), eq(0), eq("t-a"));
    }

    // ==================== 3. 导入行字段 ====================

    @Test
    void importWorld_逐表复制新行重绑跨行引用且冥星保留星名() throws Exception {
        SharedWorld pack = visiblePack("pack-1", "t-other");
        pack.setSnapshotJson(fullSnapshotJson());
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(pack);
        when(coverStorage.copyFromShared("cover.png", "t-a")).thenReturn("recv.png");

        WorldPublishService.ImportResult result = service.importWorld("pack-1");

        assertThat(result.newWorldId()).isNotBlank();
        assertThat(result.entryCount()).isEqualTo(6);

        // ---- 世界行：新 id、封面落接收方私有区、locations JSON 重绑地点新 id ----
        ArgumentCaptor<WorldSetting> worldCap = ArgumentCaptor.forClass(WorldSetting.class);
        verify(worldRepo).save(worldCap.capture());
        WorldSetting world = worldCap.getValue();
        assertThat(world.getId()).isEqualTo(result.newWorldId());
        assertThat(world.getName()).isEqualTo("共享世界");
        assertThat(world.getEra()).isEqualTo("战国");
        assertThat(world.getCover()).isEqualTo("recv.png");
        // 主题 token 随包传播：共享世界导入后换肤保持（rpg-hud-bubble-components）
        assertThat(world.getThemeTokens()).isEqualTo("{\"--hud-gold\":\"#e8c15a\"}");
        // 内容块配置随包传播：导入后开局照常渲染（rpg-hud-custom-blocks D6）
        assertThat(world.getCustomBlocks()).isEqualTo(CUSTOM_BLOCKS_JSON);

        // ---- 角色行：新 id、world_id 重绑 ----
        ArgumentCaptor<CharacterCard> charCap = ArgumentCaptor.forClass(CharacterCard.class);
        verify(charRepo).save(charCap.capture());
        CharacterCard importedChar = charCap.getValue();
        assertThat(importedChar.getId()).isNotBlank().isNotEqualTo("c-1");
        assertThat(importedChar.getWorldId()).isEqualTo(result.newWorldId());
        assertThat(importedChar.getName()).isEqualTo("信长");
        assertThat(importedChar.getType()).isEqualTo("npc");
        String newCharId = importedChar.getId();

        // ---- 地点行：新 id；npcIds / triggerIds JSON 数组重绑新 id ----
        ArgumentCaptor<Location> locCap = ArgumentCaptor.forClass(Location.class);
        verify(locationRepo).save(locCap.capture());
        Location importedLoc = locCap.getValue();
        assertThat(importedLoc.getId()).isNotBlank().isNotEqualTo("loc-1");
        assertThat(importedLoc.getWorldId()).isEqualTo(result.newWorldId());
        assertThat(importedLoc.getNpcIds()).isEqualTo("[\"" + newCharId + "\"]");
        assertThat(world.getLocations()).isEqualTo("[\"" + importedLoc.getId() + "\"]");

        // ---- 触发器行：新 id；npcId 重绑角色新 id ----
        ArgumentCaptor<Trigger> trigCap = ArgumentCaptor.forClass(Trigger.class);
        verify(triggerRepo).save(trigCap.capture());
        Trigger importedTrig = trigCap.getValue();
        assertThat(importedTrig.getId()).isNotBlank().isNotEqualTo("trig-1");
        assertThat(importedTrig.getWorldId()).isEqualTo(result.newWorldId());
        assertThat(importedTrig.getNpcId()).isEqualTo(newCharId);
        assertThat(importedLoc.getTriggerIds()).isEqualTo("[\"" + importedTrig.getId() + "\"]");

        // ---- 关系行：新 id；两端引用重绑角色新 id ----
        ArgumentCaptor<Relationship> relCap = ArgumentCaptor.forClass(Relationship.class);
        verify(relRepo).save(relCap.capture());
        assertThat(relCap.getValue().getId()).isNotBlank().isNotEqualTo("rel-1");
        assertThat(relCap.getValue().getCharAId()).isEqualTo(newCharId);
        assertThat(relCap.getValue().getCharBId()).isEqualTo(newCharId);

        // ---- 通用世界书：新 id、模板层（set_id NULL）、接收方原生行（source_ref NULL）----
        ArgumentCaptor<WorldbookEntry> wbCap = ArgumentCaptor.forClass(WorldbookEntry.class);
        verify(worldbookRepo).save(wbCap.capture());
        WorldbookEntry importedWb = wbCap.getValue();
        assertThat(importedWb.getId()).isNotBlank().isNotEqualTo("wb-1");
        assertThat(importedWb.getWorldId()).isEqualTo(result.newWorldId());
        assertThat(importedWb.getTitle()).isEqualTo("钟表城");
        assertThat(importedWb.getSetId()).isNull();
        assertThat(importedWb.getSourceRef()).isNull();

        // ---- 冥星：新 id；star_name 保留（揭示/抢星 runtime 稳定关联键）----
        ArgumentCaptor<StarIncarnation> starCap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).save(starCap.capture());
        StarIncarnation importedStar = starCap.getValue();
        assertThat(importedStar.getId()).isNotBlank().isNotEqualTo("inc-1");
        assertThat(importedStar.getWorldId()).isEqualTo(result.newWorldId());
        assertThat(importedStar.getStarName()).isEqualTo("天魁星");
        assertThat(importedStar.getSetId()).isNull();
        assertThat(importedStar.getSourceRef()).isNull();
    }

    @Test
    void importWorld_旧快照缺customBlocks键兼容为null() {
        SharedWorld pack = visiblePack("pack-1", "t-other");
        // 手写上线前旧格式快照：WorldInfo 无 customBlocks（与 themeTokens）键，
        // record 反序列化缺键填 null——导入成功且新世界无自定义块（spec 旧快照兼容）
        pack.setSnapshotJson("""
                {"world":{"name":"旧世界","settingDesc":"描述","era":"战国","rules":"规则",
                "locations":null,"atmosphere":"气氛","openingTemplate":"开场白"},
                "characters":[],"locations":[],"relationships":[],"triggers":[],
                "worldbookEntries":[],"starIncarnations":[]}
                """);
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(pack);
        when(coverStorage.copyFromShared("cover.png", "t-a")).thenReturn("recv.png");

        WorldPublishService.ImportResult result = service.importWorld("pack-1");   // 缺键不报错

        assertThat(result.entryCount()).isZero();
        ArgumentCaptor<WorldSetting> worldCap = ArgumentCaptor.forClass(WorldSetting.class);
        verify(worldRepo).save(worldCap.capture());
        assertThat(worldCap.getValue().getThemeTokens()).isNull();
        assertThat(worldCap.getValue().getCustomBlocks()).isNull();   // null = 无自定义块
    }

    // ==================== 4. 导入失败语义 ====================

    @Test
    void importWorld_包不存在或已下架按404语义拒绝() {
        when(sharedRepo.findVisibleById("pack-x")).thenReturn(null);

        assertThatThrownBy(() -> service.importWorld("pack-x"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void importWorld_发布者本人导入被拒零写入() throws Exception {
        SharedWorld pack = visiblePack("pack-1", "t-a");   // 发布者 = 当前租户
        pack.setSnapshotJson(fullSnapshotJson());
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(pack);

        assertThatThrownBy(() -> service.importWorld("pack-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("你自己发布的世界");
        verify(worldRepo, never()).save(any());
        verify(charRepo, never()).save(any());
        verify(locationRepo, never()).save(any());
        verify(relRepo, never()).save(any());
        verify(triggerRepo, never()).save(any());
        verify(worldbookRepo, never()).save(any());
        verify(starRepo, never()).save(any());
    }

    @Test
    void importWorld_快照损坏按500语义上抛且零写入() {
        SharedWorld pack = visiblePack("pack-1", "t-other");
        pack.setSnapshotJson("{broken");
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(pack);

        assertThatThrownBy(() -> service.importWorld("pack-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照解析失败");
        verify(worldRepo, never()).save(any());
    }

    @Test
    void importWorld_子表失败异常上抛不静默() throws Exception {
        SharedWorld pack = visiblePack("pack-1", "t-other");
        pack.setSnapshotJson(fullSnapshotJson());
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(pack);
        doThrow(new RuntimeException("worldbook 插入失败")).when(worldbookRepo).save(any());

        // 部分失败异常必须上抛（不静默吞掉）；整体单一事务零残留回滚
        // 由 @Transactional 保证，真库断言见 6.4 端到端流转测试
        assertThatThrownBy(() -> service.importWorld("pack-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("worldbook 插入失败");
        verify(worldbookRepo).save(any());
    }

    // ==================== 5. 撤销 / 橱窗透传 ====================

    @Test
    void revoke_发布者撤回成功() {
        when(sharedRepo.revoke(eq("pack-1"), eq("t-a"), any(Instant.class))).thenReturn(1);

        assertThat(service.revoke("pack-1")).isTrue();
    }

    @Test
    void revoke_非发布者或已下架返回false供控制层判404() {
        when(sharedRepo.revoke(eq("pack-1"), eq("t-a"), any(Instant.class))).thenReturn(0);

        assertThat(service.revoke("pack-1")).isFalse();
    }

    @Test
    void listVisible_透传仓库在架列表() {
        List<SharedWorld> list = List.of(visiblePack("pack-1", "t-other"));
        when(sharedRepo.listVisible()).thenReturn(list);

        assertThat(service.listVisible()).isSameAs(list);
    }

    @Test
    void findVisibleById_透传详情() {
        SharedWorld pack = visiblePack("pack-1", "t-other");
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(pack);

        assertThat(service.findVisibleById("pack-1")).isSameAs(pack);
    }

    // ==================== 构造辅助 ====================

    /** 全家桶数据源 mock（1 角色 + 1 地点 + 1 关系 + 1 触发器 + 1 世界书模板 + 1 冥星模板） */
    private void mockFullWorldFamily() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(character("c-1", "信长")));
        when(locationRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(location("loc-1", "安土城", "[\"c-1\"]", "[\"trig-1\"]")));
        when(triggerRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(trigger("trig-1", "c-1")));
        when(worldbookRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(worldbookEntry("wb-1", "钟表城", "正文")));
        when(starRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(star("inc-1", "天魁星", "织田信长")));
        when(relRepo.findAll()).thenReturn(List.of(relationship("rel-1", "c-1", "c-1")));
    }

    /** 除角色外的空子数据（角色 mock 由调用方自定） */
    private void mockEmptyFamilyRest() {
        when(locationRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(triggerRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(worldbookRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(relRepo.findAll()).thenReturn(List.of());
    }

    private static WorldSetting world(String id) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName("世界-" + id);
        w.setSettingDesc("desc");
        w.setEra("era");
        w.setRules("rules");
        w.setLocations("[\"loc-1\"]");
        w.setAtmosphere("atmos");
        w.setOpeningTemplate("opening");
        w.setCover("cover.png");
        w.setCustomBlocks(CUSTOM_BLOCKS_JSON);
        return w;
    }

    private static CharacterCard character(String id, String name) {
        CharacterCard c = new CharacterCard();
        c.setId(id);
        c.setWorldId(WORLD_ID);
        c.setName(name);
        c.setType("npc");
        c.setIdentity("身份");
        c.setPersonality("性格");
        c.setBackground("背景");
        c.setMotivation("动机");
        c.setSpeechStyle("语风");
        c.setKnowledge("知识");
        return c;
    }

    private static Location location(String id, String name, String npcIds, String triggerIds) {
        Location l = new Location();
        l.setId(id);
        l.setWorldId(WORLD_ID);
        l.setName(name);
        l.setDescription("描述");
        l.setNpcIds(npcIds);
        l.setTriggerIds(triggerIds);
        return l;
    }

    private static Relationship relationship(String id, String charAId, String charBId) {
        Relationship r = new Relationship();
        r.setId(id);
        r.setCharAId(charAId);
        r.setCharBId(charBId);
        r.setAttitude(5);
        r.setTrust(80);
        r.setNotes("注");
        return r;
    }

    private static Trigger trigger(String id, String npcId) {
        Trigger t = new Trigger();
        t.setId(id);
        t.setWorldId(WORLD_ID);
        t.setType("type");
        t.setNpcId(npcId);
        t.setHardConditions("硬条件");
        t.setSoftCondition("软条件");
        t.setAction("动作");
        t.setCooldown(3);
        t.setProbability(0.5);
        return t;
    }

    private static WorldbookEntry worldbookEntry(String id, String title, String content) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId(id);
        e.setWorldId(WORLD_ID);
        e.setTitle(title);
        e.setKeywords(title);
        e.setContent(content);
        e.setEnabled(true);
        e.setSortOrder(0);
        return e;
    }

    private static StarIncarnation star(String id, String starName, String warlord) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId(id);
        inc.setWorldId(WORLD_ID);
        inc.setStarName(starName);
        inc.setWarlordName(warlord);
        inc.setLore("命定叙事");
        inc.setAliases("别名");
        inc.setRollable(true);
        inc.setSortOrder(0);
        return inc;
    }

    /** 在架共享世界包（publisherTenantId 自定：t-other = 他人、t-a = 本人） */
    private static SharedWorld visiblePack(String id, String publisherTenantId) {
        SharedWorld pack = new SharedWorld();
        pack.setId(id);
        pack.setName("共享世界");
        pack.setPublisherTenantId(publisherTenantId);
        pack.setPublisherUserId("u-" + publisherTenantId);
        pack.setOriginWorldId("w-of-" + publisherTenantId);
        pack.setCover("cover.png");
        pack.setSnapshotVersion(1);
        pack.setEntryCount(6);
        return pack;
    }

    /** 与 mockFullWorldFamily 对偶的快照 JSON（导入端数据源，跨行引用齐全）；themeTokens/customBlocks 验证换肤与内容块随包传播（rpg-hud-bubble-components 4.1 / rpg-hud-custom-blocks D6） */
    private static String fullSnapshotJson() throws Exception {
        return MAPPER.writeValueAsString(new WorldPublishService.Snapshot(
                new WorldPublishService.WorldInfo("共享世界", "描述", "战国", "规则",
                        "[\"loc-1\"]", "气氛", "开场白", "{\"--hud-gold\":\"#e8c15a\"}", CUSTOM_BLOCKS_JSON),
                List.of(new WorldPublishService.CharInfo("c-1", "信长", "npc", "身份", "性格",
                        "背景", "动机", "语风", "知识")),
                List.of(new WorldPublishService.LocInfo("loc-1", "安土城", "描述",
                        "[\"c-1\"]", "[\"trig-1\"]")),
                List.of(new WorldPublishService.RelInfo("rel-1", "c-1", "c-1", 5, 80, "注")),
                List.of(new WorldPublishService.TrigInfo("trig-1", "type", "c-1",
                        "硬条件", "软条件", "动作", 3, 0.5)),
                List.of(new WorldPublishService.WbInfo("wb-1", "钟表城", "钟表城", "正文", true, 0)),
                List.of(new WorldPublishService.StarInfo("inc-1", "天魁星", "织田信长",
                        "命定叙事", "别名", true, 0))));
    }
}
