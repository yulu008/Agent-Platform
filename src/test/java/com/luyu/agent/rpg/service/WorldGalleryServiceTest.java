package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.SharedWorld;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.WorldPublishRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorldGalleryService} 单元测试（world-card-gallery tasks 10.3 / design D2）。
 * <p>
 * 钉住画廊合并三件事：
 * <ol>
 *   <li><b>新租户未物化预设直上墙且不写库</b>：DB 空行时，白名单预设经
 *       {@code describePresets()} 只读解析出卡片，全程零 {@code save/update}；</li>
 *   <li><b>已物化预设按 worldId 去重</b>：DB 命中同 worldId 时以 DB 行为准，
 *       不产生第二张卡；</li>
 *   <li><b>沙盒 N 卡齐全</b>：多行沙盒世界逐一成卡，source=sandbox、presetKey=null。</li>
 * </ol>
 * 附带钉死 {@code toBlurb} 的 markdown 记号剥离（曾因换行压平后 {@code (?m)^} 失效而回归）。
 * <p>
 * works-view-and-world-publish tasks 6.6 扩展：scope=works 数据源分层（剔除预设）/
 * 沙盒行发布回查标识 / showcase 橱窗（已发布 + 预设，本地未发布沙盒不上橱窗、
 * 本人包 publisherSelf 分流、已物化预设去重）。
 */
class WorldGalleryServiceTest {

    private static final String TENANT_ID = "t-test";
    private static final String PRESET_WORLD_ID = "preset-adventure:" + TENANT_ID;

    private RpgWorldSettingRepository worldRepo;
    private PresetWorldService presetWorldService;
    private WorldPublishRepository sharedRepo;
    private WorldGalleryService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", TENANT_ID);
        worldRepo = mock(RpgWorldSettingRepository.class);
        presetWorldService = mock(PresetWorldService.class);
        sharedRepo = mock(WorldPublishRepository.class);
        service = new WorldGalleryService(worldRepo, presetWorldService, sharedRepo);
        // 发布回查默认空集（既有 gallery 兼容口径测试不关心发布态）
        when(sharedRepo.listVisibleOriginWorldIdsByPublisher(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private WorldSetting world(String id, String name, String desc) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName(name);
        w.setSettingDesc(desc);
        return w;
    }

    // ==================== 场景一：新租户未物化预设 ====================

    @Test
    void 新租户画廊含未物化预设卡且不写库() {
        when(worldRepo.findAll()).thenReturn(List.of());
        when(presetWorldService.presetKeyForTenant(anyString(), anyString())).thenReturn(null);
        when(presetWorldService.describePresets()).thenReturn(List.of(
                new PresetWorldService.PresetDescriptor(
                        "adventure", PRESET_WORLD_ID, "圣战之始", "# 概述\n雅典娜的圣斗士集结。")));

        List<WorldGalleryService.WorldCard> cards = service.gallery();

        assertThat(cards).hasSize(1);
        WorldGalleryService.WorldCard card = cards.get(0);
        assertThat(card.id()).isEqualTo(PRESET_WORLD_ID);
        assertThat(card.name()).isEqualTo("圣战之始");
        assertThat(card.source()).isEqualTo("preset");
        assertThat(card.presetKey()).isEqualTo("adventure");
        assertThat(card.coverUrl()).isEqualTo("/rpg/worlds/" + PRESET_WORLD_ID + "/cover");
        // blurb 已剥掉 markdown 标题记号并压平
        assertThat(card.blurb()).isEqualTo("概述 雅典娜的圣斗士集结。");
        // 只读上墙：未物化预设绝不触发写库（点卡才 materialize）
        verify(worldRepo, never()).save(any());
        verify(worldRepo, never()).update(any());
        verify(worldRepo, never()).updateCover(anyString(), anyString());
    }

    // ==================== 场景二：已物化预设去重 ====================

    @Test
    void 已物化预设按worldId去重以DB行为准() {
        WorldSetting materialized = world(PRESET_WORLD_ID, "圣战之始", "已物化的世界描述。");
        when(worldRepo.findAll()).thenReturn(List.of(materialized));
        // DB 行 id 命中白名单预设 → source=preset
        when(presetWorldService.presetKeyForTenant(PRESET_WORLD_ID, TENANT_ID)).thenReturn("adventure");
        // describePresets 仍返回同 worldId 的描述项（未去重前会是两张卡）
        when(presetWorldService.describePresets()).thenReturn(List.of(
                new PresetWorldService.PresetDescriptor(
                        "adventure", PRESET_WORLD_ID, "圣战之始", "md 里的描述。")));

        List<WorldGalleryService.WorldCard> cards = service.gallery();

        // 同 worldId 只保留 DB 行那一张，未物化描述项被跳过
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).id()).isEqualTo(PRESET_WORLD_ID);
        assertThat(cards.get(0).source()).isEqualTo("preset");
        // 以 DB 行为准：blurb 来自 settingDesc 而非 md 描述
        assertThat(cards.get(0).blurb()).isEqualTo("已物化的世界描述。");
    }

    // ==================== 场景三：沙盒 N 卡齐全 ====================

    @Test
    void 沙盒世界逐一成卡source为sandbox() {
        when(worldRepo.findAll()).thenReturn(List.of(
                world("w-1", "江户怪谈", "阴雨连绵的驿站小镇。"),
                world("w-2", "钟表城", "齿轮与蒸汽之都。"),
                world("w-3", "星海", "无垠的宇宙航路。")));
        // 沙盒世界 id 不匹配预设前缀 → presetKeyForTenant 恒 null
        when(presetWorldService.presetKeyForTenant(anyString(), anyString())).thenReturn(null);
        when(presetWorldService.describePresets()).thenReturn(List.of());

        List<WorldGalleryService.WorldCard> cards = service.gallery();

        assertThat(cards).hasSize(3);
        assertThat(cards).extracting(WorldGalleryService.WorldCard::id)
                .containsExactly("w-1", "w-2", "w-3");
        assertThat(cards).allSatisfy(c -> {
            assertThat(c.source()).isEqualTo("sandbox");
            assertThat(c.presetKey()).isNull();
        });
    }

    @Test
    void 沙盒与已物化预设混排时DB行在前预设描述项殿后() {
        when(worldRepo.findAll()).thenReturn(List.of(
                world("w-1", "江户怪谈", "驿站小镇。"),
                world(PRESET_WORLD_ID, "圣战之始", "已物化预设。")));
        when(presetWorldService.presetKeyForTenant("w-1", TENANT_ID)).thenReturn(null);
        when(presetWorldService.presetKeyForTenant(PRESET_WORLD_ID, TENANT_ID)).thenReturn("adventure");
        // 未物化描述项与已物化行同 worldId → 去重后不再追加
        when(presetWorldService.describePresets()).thenReturn(List.of(
                new PresetWorldService.PresetDescriptor(
                        "adventure", PRESET_WORLD_ID, "圣战之始", "md 描述。")));

        List<WorldGalleryService.WorldCard> cards = service.gallery();

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).id()).isEqualTo("w-1");
        assertThat(cards.get(0).source()).isEqualTo("sandbox");
        assertThat(cards.get(1).id()).isEqualTo(PRESET_WORLD_ID);
        assertThat(cards.get(1).source()).isEqualTo("preset");
    }

    // ==================== scope=works 数据源分层（tasks 6.6） ====================

    @Test
    void scopeWorks剔除已物化预设行与未物化殿后段() {
        when(worldRepo.findAll()).thenReturn(List.of(
                world("w-1", "江户怪谈", "驿站小镇。"),
                world(PRESET_WORLD_ID, "圣战之始", "已物化预设。")));
        when(presetWorldService.presetKeyForTenant("w-1", TENANT_ID)).thenReturn(null);
        when(presetWorldService.presetKeyForTenant(PRESET_WORLD_ID, TENANT_ID)).thenReturn("adventure");
        // 未物化殿后段（scope=works 同样剔除）
        when(presetWorldService.describePresets()).thenReturn(List.of(
                new PresetWorldService.PresetDescriptor(
                        "adventure", PRESET_WORLD_ID, "圣战之始", "md 描述。")));

        List<WorldGalleryService.WorldCard> cards = service.gallery("works");

        // 作品墙 = 纯沙盒行：已物化预设行与未物化殿后段均不上墙
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).id()).isEqualTo("w-1");
        assertThat(cards.get(0).source()).isEqualTo("sandbox");
        assertThat(cards.get(0).presetKey()).isNull();
    }

    @Test
    void scopeWorks沙盒行命中发布回查置published() {
        when(worldRepo.findAll()).thenReturn(List.of(
                world("w-1", "江户怪谈", "驿站小镇。"),
                world("w-2", "钟表城", "齿轮之都。")));
        when(presetWorldService.presetKeyForTenant(anyString(), anyString())).thenReturn(null);
        when(presetWorldService.describePresets()).thenReturn(List.of());
        // 本租户在架包的 origin_world_id 集：w-1 命中即已发布（发布/撤销钮族分流依据）
        when(sharedRepo.listVisibleOriginWorldIdsByPublisher(TENANT_ID)).thenReturn(List.of("w-1"));

        List<WorldGalleryService.WorldCard> cards = service.gallery("works");

        assertThat(cards).extracting(WorldGalleryService.WorldCard::id)
                .containsExactly("w-1", "w-2");
        assertThat(cards.get(0).published()).isTrue();
        assertThat(cards.get(1).published()).isFalse();
    }

    @Test
    void 未知scope值回落兼容口径() {
        // scope 缺省/非 works 保持旧行为：沙盒 + 预设全量（兼容过渡期，2.4）
        when(worldRepo.findAll()).thenReturn(List.of(world("w-1", "江户怪谈", "驿站小镇。")));
        when(presetWorldService.presetKeyForTenant(anyString(), anyString())).thenReturn(null);
        when(presetWorldService.describePresets()).thenReturn(List.of(
                new PresetWorldService.PresetDescriptor(
                        "adventure", PRESET_WORLD_ID, "圣战之始", "md 描述。")));

        assertThat(service.gallery("whatever")).hasSize(2);
    }

    // ==================== showcase 橱窗（tasks 6.6） ====================

    @Test
    void showcase含已发布与预设且本地沙盒不上橱窗() {
        when(worldRepo.findAll()).thenReturn(List.of(
                world("w-1", "江户怪谈", "本地未发布沙盒。"),
                world(PRESET_WORLD_ID, "圣战之始", "已物化预设。")));
        when(presetWorldService.presetKeyForTenant("w-1", TENANT_ID)).thenReturn(null);
        when(presetWorldService.presetKeyForTenant(PRESET_WORLD_ID, TENANT_ID)).thenReturn("adventure");
        when(presetWorldService.describePresets()).thenReturn(List.of());
        when(sharedRepo.listVisible()).thenReturn(List.of(
                sharedPack("pack-1", "t-other", "w-of-other", 5)));

        List<WorldGalleryService.ShowcaseCard> cards = service.showcase();

        // 已发布包卡在前（updated_at 倒序由 SQL 保证，此处单包），预设卡殿后；本地沙盒 w-1 不上橱窗
        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(WorldGalleryService.ShowcaseCard::id)
                .containsExactly("pack-1", PRESET_WORLD_ID);
        assertThat(cards).extracting(WorldGalleryService.ShowcaseCard::source)
                .containsExactly("published", "preset");

        WorldGalleryService.ShowcaseCard published = cards.get(0);
        assertThat(published.name()).isEqualTo("共享世界");
        assertThat(published.blurb()).isEqualTo("共享简介");
        assertThat(published.coverUrl()).isEqualTo("/rpg/shared-worlds/pack-1/cover");
        assertThat(published.originWorldId()).isEqualTo("w-of-other");
        assertThat(published.publisherTenantId()).isEqualTo("t-other");
        assertThat(published.publisherSelf()).isFalse();
        assertThat(published.entryCount()).isEqualTo(5);

        WorldGalleryService.ShowcaseCard preset = cards.get(1);
        assertThat(preset.presetKey()).isEqualTo("adventure");
        assertThat(preset.originWorldId()).isNull();
        assertThat(preset.blurb()).isEqualTo("已物化预设。");
    }

    @Test
    void showcase本人包标记publisherSelf() {
        when(worldRepo.findAll()).thenReturn(List.of());
        when(presetWorldService.describePresets()).thenReturn(List.of());
        when(sharedRepo.listVisible()).thenReturn(List.of(
                sharedPack("pack-mine", TENANT_ID, "w-mine", 3),
                sharedPack("pack-other", "t-other", "w-of-other", 7)));

        List<WorldGalleryService.ShowcaseCard> cards = service.showcase();

        assertThat(cards).extracting(WorldGalleryService.ShowcaseCard::id)
                .containsExactly("pack-mine", "pack-other");
        assertThat(cards.get(0).publisherSelf()).isTrue();
        assertThat(cards.get(1).publisherSelf()).isFalse();
    }

    @Test
    void showcase已物化预设去重不因本地状态重复() {
        when(worldRepo.findAll()).thenReturn(List.of(
                world(PRESET_WORLD_ID, "圣战之始", "已物化预设。")));
        when(presetWorldService.presetKeyForTenant(PRESET_WORLD_ID, TENANT_ID)).thenReturn("adventure");
        when(presetWorldService.describePresets()).thenReturn(List.of(
                new PresetWorldService.PresetDescriptor(
                        "adventure", PRESET_WORLD_ID, "圣战之始", "md 描述。")));
        when(sharedRepo.listVisible()).thenReturn(List.of());

        List<WorldGalleryService.ShowcaseCard> cards = service.showcase();

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).id()).isEqualTo(PRESET_WORLD_ID);
        assertThat(cards.get(0).source()).isEqualTo("preset");
    }

    /** 在架共享包（published 卡数据源；publisherTenantId 自定：t-test = 本人） */
    private static SharedWorld sharedPack(String id, String publisherTenantId,
                                          String originWorldId, int entryCount) {
        SharedWorld pack = new SharedWorld();
        pack.setId(id);
        pack.setName("共享世界");
        pack.setBlurb("共享简介");
        pack.setPublisherTenantId(publisherTenantId);
        pack.setPublisherUserId("u-" + publisherTenantId);
        pack.setOriginWorldId(originWorldId);
        pack.setOriginWorldName("出处世界");
        pack.setEntryCount(entryCount);
        return pack;
    }

    // ==================== toBlurb 纯函数（markdown 记号剥离） ====================

    @Test
    void toBlurb逐行剥离标题列表引用与粗斜体后压平() {
        assertThat(WorldGalleryService.toBlurb(
                "## 标题\n- 列表项\n> 引用\n**粗体**与`代码`与~~删除~~")).isEqualTo(
                "标题 列表项 引用 粗体与代码与删除");
    }

    @Test
    void toBlurb空与null安全降级为空串() {
        assertThat(WorldGalleryService.toBlurb(null)).isEmpty();
        assertThat(WorldGalleryService.toBlurb("   ")).isEmpty();
        assertThat(WorldGalleryService.toBlurb("")).isEmpty();
    }

    @Test
    void toBlurb带缩进的记号行也能剥离() {
        // 行首空白 stripLeading 后再匹配记号，缩进列表/标题不漏剥
        assertThat(WorldGalleryService.toBlurb("  - 缩进列表\n   ## 缩进标题")).isEqualTo("缩进列表 缩进标题");
    }
}
