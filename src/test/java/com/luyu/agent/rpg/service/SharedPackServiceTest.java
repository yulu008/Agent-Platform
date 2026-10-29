package com.luyu.agent.rpg.service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.luyu.agent.rpg.model.SharedLorebookPack;
import com.luyu.agent.rpg.model.SharedStarEntry;
import com.luyu.agent.rpg.model.SharedWorldbookEntry;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.SharedPackRepository;
import com.luyu.agent.tenancy.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SharedPackService} 单元测试（add-shared-worldbook-publish tasks 5.1/5.4，纯 Mockito +
 * 真实 {@link TenantContext} ThreadLocal）。
 * <p>
 * 钉住四组语义（spec shared-worldbook-packs）：
 * <ol>
 *   <li><b>发布快照</b>：插入共享库的条目内容 = 发布时点模板内容（独立副本对象，源模板后续
 *       变化不渗入）；仅模板行可选（set 行 / 跨世界行 / 跨租户不可见行 / 无效世界一律拒绝）；</li>
 *   <li><b>覆盖更新</b>：同发布者同源世界重复发布 → refresh + 清旧条目、不插新包行、overwrite=true；</li>
 *   <li><b>导入前置校验</b>：preset- 前缀目标 → IllegalArgumentException 零写入（400 语义）、
 *       目标世界无效拒绝、同名星 → IllegalStateException 列明冲突星名（409 语义）零写入；</li>
 *   <li><b>导入行字段</b>：新 id、world_id 重绑目标、set_id NULL、通用轨 source_ref NULL、
 *       冥星轨保留 star_name（tenant_id 归属由 Repository 强制过滤注入，SQL 层归属断言见
 *       SharedPackGuardCompatibilityTest / SharedPackEndToEndTest）。</li>
 * </ol>
 */
class SharedPackServiceTest {

    private static final String WORLD_ID = "w-src";
    private static final String TARGET_WORLD_ID = "w-dst";

    private SharedPackRepository sharedRepo;
    private RpgWorldSettingRepository worldRepo;
    private RpgWorldbookEntryRepository worldbookRepo;
    private RpgStarIncarnationRepository starRepo;
    private SharedPackService service;

    @BeforeEach
    void setUp() {
        sharedRepo = mock(SharedPackRepository.class);
        worldRepo = mock(RpgWorldSettingRepository.class);
        worldbookRepo = mock(RpgWorldbookEntryRepository.class);
        starRepo = mock(RpgStarIncarnationRepository.class);
        service = new SharedPackService(sharedRepo, worldRepo, worldbookRepo, starRepo);
        TenantContext.set("u-a", "t-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 1. 发布快照 ====================

    @Test
    void publish_快照内容取发布时点且独立于源模板() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        WorldbookEntry template = templateEntry("wb-1", "钟表城", "设定正文v1");
        when(worldbookRepo.findById("wb-1")).thenReturn(template);

        SharedPackService.PublishResult result = service.publish(WORLD_ID, "包一", "简介",
                List.of("wb-1"), List.of());

        assertThat(result.overwrite()).isFalse();
        assertThat(result.entryCount()).isEqualTo(1);
        ArgumentCaptor<SharedWorldbookEntry> cap = ArgumentCaptor.forClass(SharedWorldbookEntry.class);
        verify(sharedRepo).insertWorldbookEntry(cap.capture());
        SharedWorldbookEntry snapshot = cap.getValue();
        assertThat(snapshot.getPackId()).isEqualTo(result.packId());
        assertThat(snapshot.getTitle()).isEqualTo("钟表城");
        assertThat(snapshot.getContent()).isEqualTo("设定正文v1");

        // 发布后源模板再改：快照是独立副本对象，不受影响（快照哲学）
        template.setContent("设定正文v2");
        assertThat(snapshot.getContent()).isEqualTo("设定正文v1");
    }

    @Test
    void publish_冥星条目快照保留星名与NPC专属标记() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        when(starRepo.findById("inc-1")).thenReturn(templateStar("inc-1", "天魁星", "织田信长", false));

        SharedPackService.PublishResult result = service.publish(WORLD_ID, "包一", null,
                List.of(), List.of("inc-1"));

        ArgumentCaptor<SharedStarEntry> cap = ArgumentCaptor.forClass(SharedStarEntry.class);
        verify(sharedRepo).insertStarEntry(cap.capture());
        assertThat(cap.getValue().getStarName()).isEqualTo("天魁星");
        assertThat(cap.getValue().getWarlordName()).isEqualTo("织田信长");
        assertThat(cap.getValue().getRollable()).isFalse();
        assertThat(result.entryCount()).isEqualTo(1);
    }

    @Test
    void publish_新包落发布者锚点与出处世界名() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        when(worldbookRepo.findById("wb-1")).thenReturn(templateEntry("wb-1", "钟表城", "正文"));

        service.publish(WORLD_ID, "包一", "简介", List.of("wb-1"), List.of());

        ArgumentCaptor<SharedLorebookPack> cap = ArgumentCaptor.forClass(SharedLorebookPack.class);
        verify(sharedRepo).insertPack(cap.capture());
        SharedLorebookPack pack = cap.getValue();
        assertThat(pack.getPublisherTenantId()).isEqualTo("t-a");
        assertThat(pack.getPublisherUserId()).isEqualTo("u-a");
        assertThat(pack.getOriginWorldId()).isEqualTo(WORLD_ID);
        assertThat(pack.getOriginWorldName()).isEqualTo("世界-" + WORLD_ID);
        assertThat(pack.getEntryCount()).isEqualTo(1);
    }

    @Test
    void publish_勾选存档副本行拒绝且零写入() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        WorldbookEntry setRow = templateEntry("wb-set", "钟表城", "正文");
        setRow.setSetId("set-1");
        when(worldbookRepo.findById("wb-set")).thenReturn(setRow);

        assertThatThrownBy(() -> service.publish(WORLD_ID, "包一", null, List.of("wb-set"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非模板行");
        verify(sharedRepo, never()).insertPack(any());
        verify(sharedRepo, never()).insertWorldbookEntry(any());
    }

    @Test
    void publish_勾选其他世界行拒绝() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        WorldbookEntry otherWorldRow = templateEntry("wb-other", "雾都", "正文");
        otherWorldRow.setWorldId("w-other");
        when(worldbookRepo.findById("wb-other")).thenReturn(otherWorldRow);

        assertThatThrownBy(() -> service.publish(WORLD_ID, "包一", null, List.of("wb-other"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非模板行");
    }

    @Test
    void publish_勾选跨租户行按不存在拒绝() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        when(worldbookRepo.findById("wb-of-b")).thenReturn(null);   // Repository 租户过滤后不可见

        assertThatThrownBy(() -> service.publish(WORLD_ID, "包一", null, List.of("wb-of-b"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或非模板行");
    }

    @Test
    void publish_世界不存在或不属本租户拒绝() {
        when(worldRepo.findById("w-of-b")).thenReturn(null);

        assertThatThrownBy(() -> service.publish("w-of-b", "包一", null, List.of("wb-1"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("世界不存在");
    }

    @Test
    void publish_包名空或勾选空拒绝() {
        assertThatThrownBy(() -> service.publish(WORLD_ID, " ", null, List.of("wb-1"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("包名");
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        assertThatThrownBy(() -> service.publish(WORLD_ID, "包一", null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("勾选");
    }

    @Test
    void publish_重复勾选同一id按一次快照() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        when(worldbookRepo.findById("wb-1")).thenReturn(templateEntry("wb-1", "钟表城", "正文"));

        SharedPackService.PublishResult result = service.publish(WORLD_ID, "包一", null,
                List.of("wb-1", "wb-1"), List.of());

        assertThat(result.entryCount()).isEqualTo(1);
        verify(sharedRepo, times(1)).insertWorldbookEntry(any());
    }

    // ==================== 2. 覆盖更新 ====================

    @Test
    void publish_同发布者同世界重复发布覆盖旧包() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        SharedLorebookPack existing = new SharedLorebookPack();
        existing.setId("pack-old");
        existing.setPublisherTenantId("t-a");
        existing.setOriginWorldId(WORLD_ID);
        when(sharedRepo.findPackByPublisherAndWorld("t-a", WORLD_ID)).thenReturn(existing);
        when(worldbookRepo.findById("wb-1")).thenReturn(templateEntry("wb-1", "钟表城", "正文"));
        when(worldbookRepo.findById("wb-2")).thenReturn(templateEntry("wb-2", "雾都", "正文"));

        SharedPackService.PublishResult result = service.publish(WORLD_ID, "包一改", "新简介",
                List.of("wb-1", "wb-2"), List.of());

        assertThat(result.overwrite()).isTrue();
        assertThat(result.packId()).isEqualTo("pack-old");
        assertThat(result.entryCount()).isEqualTo(2);
        verify(sharedRepo).refreshPack(eq("pack-old"), eq("包一改"), eq("新简介"), eq(2), eq("t-a"));
        verify(sharedRepo).deleteEntriesOfPack("pack-old");
        verify(sharedRepo, never()).insertPack(any());
        verify(sharedRepo, times(2)).insertWorldbookEntry(any());
    }

    // ==================== 3. 导入前置校验（tasks 5.4）====================

    @Test
    void importPack_物化预设世界拒绝且零写入() {
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(visiblePack("pack-1"));

        assertThatThrownBy(() -> service.importPack("pack-1", "preset-adventure:t-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("物化预设世界");
        verify(worldbookRepo, never()).save(any());
        verify(starRepo, never()).save(any());
    }

    @Test
    void importPack_包不存在或已撤回按404语义拒绝() {
        when(sharedRepo.findVisibleById("pack-x")).thenReturn(null);

        assertThatThrownBy(() -> service.importPack("pack-x", TARGET_WORLD_ID))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void importPack_目标世界无效拒绝且零写入() {
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(visiblePack("pack-1"));
        when(worldRepo.findById("w-of-other")).thenReturn(null);

        assertThatThrownBy(() -> service.importPack("pack-1", "w-of-other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标世界");
        verify(worldbookRepo, never()).save(any());
    }

    @Test
    void importPack_同名星冲突列明星名且零写入() {
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(visiblePack("pack-1"));
        when(worldRepo.findById(TARGET_WORLD_ID)).thenReturn(world(TARGET_WORLD_ID));
        // 目标世界已有天魁星模板行（star_name 是 runtime 稳定关联键，冲突会错挂）
        when(starRepo.findByWorldId(TARGET_WORLD_ID)).thenReturn(
                List.of(templateStar("inc-dup", "天魁星", "本地已有", true)));
        when(sharedRepo.readStarEntries("pack-1")).thenReturn(List.of(
                sharedStar("sst-1", "天魁星", "织田信长"), sharedStar("sst-2", "天罡星", "卢俊义")));

        assertThatThrownBy(() -> service.importPack("pack-1", TARGET_WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("天魁星");   // 冲突清单列明星名（409 error 文案）
        verify(starRepo, never()).save(any());
        verify(worldbookRepo, never()).save(any());
    }

    // ==================== 4. 导入行字段 ====================

    @Test
    void importPack_逐行复制为接收方模板行并重绑世界() {
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(visiblePack("pack-1"));
        when(worldRepo.findById(TARGET_WORLD_ID)).thenReturn(world(TARGET_WORLD_ID));
        when(starRepo.findByWorldId(TARGET_WORLD_ID)).thenReturn(List.of());
        when(sharedRepo.readWorldbookEntries("pack-1")).thenReturn(
                List.of(sharedWb("swb-1", "钟表城", "设定正文")));
        when(sharedRepo.readStarEntries("pack-1")).thenReturn(
                List.of(sharedStar("sst-1", "天魁星", "织田信长")));

        SharedPackService.ImportResult result = service.importPack("pack-1", TARGET_WORLD_ID);

        assertThat(result.worldbookCount()).isEqualTo(1);
        assertThat(result.starCount()).isEqualTo(1);
        assertThat(result.targetWorldId()).isEqualTo(TARGET_WORLD_ID);

        ArgumentCaptor<WorldbookEntry> wbCap = ArgumentCaptor.forClass(WorldbookEntry.class);
        verify(worldbookRepo).save(wbCap.capture());
        WorldbookEntry importedWb = wbCap.getValue();
        assertThat(importedWb.getId()).isNotBlank().isNotEqualTo("swb-1");   // 新行 id
        assertThat(importedWb.getWorldId()).isEqualTo(TARGET_WORLD_ID);       // world_id 重绑
        assertThat(importedWb.getSetId()).isNull();                           // 模板层（set_id NULL）
        assertThat(importedWb.getSourceRef()).isNull();                       // 接收方原生模板行
        assertThat(importedWb.getTitle()).isEqualTo("钟表城");
        assertThat(importedWb.getContent()).isEqualTo("设定正文");

        ArgumentCaptor<StarIncarnation> starCap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).save(starCap.capture());
        StarIncarnation importedStar = starCap.getValue();
        assertThat(importedStar.getId()).isNotBlank().isNotEqualTo("sst-1");
        assertThat(importedStar.getStarName()).isEqualTo("天魁星");            // runtime 稳定键保留
        assertThat(importedStar.getWorldId()).isEqualTo(TARGET_WORLD_ID);
        assertThat(importedStar.getSetId()).isNull();
    }

    // ==================== 分类随共享包携带（add-star-lorebook-category / tasks 6.3） ====================

    @Test
    void publish_冥星条目快照携带分类() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID));
        StarIncarnation template = templateStar("inc-1", "天魁星", "织田信长", false);
        template.setCategory("织田家");
        when(starRepo.findById("inc-1")).thenReturn(template);

        service.publish(WORLD_ID, "包一", null, List.of(), List.of("inc-1"));

        ArgumentCaptor<SharedStarEntry> cap = ArgumentCaptor.forClass(SharedStarEntry.class);
        verify(sharedRepo).insertStarEntry(cap.capture());
        assertThat(cap.getValue().getCategory()).isEqualTo("织田家");
    }

    @Test
    void importPack_导入行携带分类且旧包无分类落NULL() {
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(visiblePack("pack-1"));
        when(worldRepo.findById(TARGET_WORLD_ID)).thenReturn(world(TARGET_WORLD_ID));
        when(starRepo.findByWorldId(TARGET_WORLD_ID)).thenReturn(List.of());
        when(sharedRepo.readWorldbookEntries("pack-1")).thenReturn(List.of());
        SharedStarEntry withCategory = sharedStar("sst-1", "天魁星", "织田信长");
        withCategory.setCategory("织田家");
        SharedStarEntry legacy = sharedStar("sst-2", "天罡星", "卢俊义");   // 旧包快照无分类
        when(sharedRepo.readStarEntries("pack-1")).thenReturn(List.of(withCategory, legacy));

        service.importPack("pack-1", TARGET_WORLD_ID);

        ArgumentCaptor<StarIncarnation> cap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo, times(2)).save(cap.capture());
        // 新包快照携带分类随行落库；旧包（无 category 字段）导入落 NULL（可空列天然兼容）
        assertThat(cap.getAllValues().get(0).getCategory()).isEqualTo("织田家");
        assertThat(cap.getAllValues().get(1).getCategory()).isNull();
    }

    // ==================== 5. 画廊 / 撤回 ====================

    @Test
    void loadPack_撤回或不存在返回null供控制层判404() {
        when(sharedRepo.findVisibleById("pack-x")).thenReturn(null);

        assertThat(service.loadPack("pack-x")).isNull();
    }

    @Test
    void loadPack_详情含双轨条目() {
        SharedLorebookPack pack = visiblePack("pack-1");
        when(sharedRepo.findVisibleById("pack-1")).thenReturn(pack);
        when(sharedRepo.readWorldbookEntries("pack-1")).thenReturn(
                List.of(sharedWb("swb-1", "钟表城", "正文")));
        when(sharedRepo.readStarEntries("pack-1")).thenReturn(
                List.of(sharedStar("sst-1", "天魁星", "织田信长")));

        SharedPackService.PackDetail detail = service.loadPack("pack-1");

        assertThat(detail.pack()).isSameAs(pack);
        assertThat(detail.worldbookEntries()).hasSize(1);
        assertThat(detail.starEntries()).hasSize(1);
    }

    @Test
    void revoke_非发布者或不在架返回false供控制层判404() {
        when(sharedRepo.revoke(eq("pack-1"), eq("t-a"), any(Instant.class))).thenReturn(0);

        assertThat(service.revoke("pack-1")).isFalse();
    }

    @Test
    void revoke_发布者撤回成功() {
        when(sharedRepo.revoke(eq("pack-1"), eq("t-a"), any(Instant.class))).thenReturn(1);

        assertThat(service.revoke("pack-1")).isTrue();
    }

    // ==================== 构造辅助 ====================

    private static WorldSetting world(String id) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName("世界-" + id);
        w.setSettingDesc("desc");
        return w;
    }

    /** 世界模板行：set_id / source_ref 皆 null */
    private static WorldbookEntry templateEntry(String id, String title, String content) {
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

    private static StarIncarnation templateStar(String id, String starName, String warlord, boolean rollable) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId(id);
        inc.setWorldId(WORLD_ID);
        inc.setStarName(starName);
        inc.setWarlordName(warlord);
        inc.setLore("命定叙事");
        inc.setRollable(rollable);
        inc.setSortOrder(0);
        return inc;
    }

    /** 在架共享包（他租户发布，画廊可见） */
    private static SharedLorebookPack visiblePack(String id) {
        SharedLorebookPack pack = new SharedLorebookPack();
        pack.setId(id);
        pack.setName("共享包");
        pack.setPublisherTenantId("t-other");
        pack.setOriginWorldId("w-of-other");
        pack.setEntryCount(2);
        return pack;
    }

    private static SharedWorldbookEntry sharedWb(String id, String title, String content) {
        SharedWorldbookEntry e = new SharedWorldbookEntry();
        e.setId(id);
        e.setPackId("pack-1");
        e.setTitle(title);
        e.setKeywords(title);
        e.setContent(content);
        e.setEnabled(true);
        e.setSortOrder(0);
        return e;
    }

    private static SharedStarEntry sharedStar(String id, String starName, String warlord) {
        SharedStarEntry e = new SharedStarEntry();
        e.setId(id);
        e.setPackId("pack-1");
        e.setStarName(starName);
        e.setWarlordName(warlord);
        e.setLore("命定叙事");
        e.setRollable(true);
        e.setSortOrder(0);
        return e;
    }
}
