package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.SharedWorld;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.WorldPublishRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 世界画廊服务（world-card-gallery，design D2）。
 * <p>
 * 合并两个数据源产出统一卡片 DTO：
 * <ol>
 *   <li>当前租户 DB 世界行（含已物化预设，{@code presetKeyForTenant} 判 source）；</li>
 *   <li>白名单内<b>未物化</b>预设（{@code describePresets()} 只读解析 md，不写库）——
 *       已物化的按 worldId 去重，以 DB 行为准。</li>
 * </ol>
 * md 约 8KB 逐请求解析不缓存，{@code process-resources} 热更新即时生效。
 * <p>
 * works-view-and-world-publish 数据源分层：作品墙走 {@code gallery(scope=works)}
 * （纯沙盒行）；根首页橱窗走 {@link #showcase()}（全平台已发布包 + 预设）。
 */
@Service
public class WorldGalleryService {

    private static final Logger log = LoggerFactory.getLogger(WorldGalleryService.class);

    /** scope 参数值：仅用户创建的世界（沙盒行；一人一租户口径 = source != 'preset'） */
    public static final String SCOPE_WORKS = "works";

    private final RpgWorldSettingRepository worldRepo;
    private final PresetWorldService presetWorldService;
    private final WorldPublishRepository sharedRepo;

    public WorldGalleryService(RpgWorldSettingRepository worldRepo,
                               PresetWorldService presetWorldService,
                               WorldPublishRepository sharedRepo) {
        this.worldRepo = worldRepo;
        this.presetWorldService = presetWorldService;
        this.sharedRepo = sharedRepo;
    }

    /**
     * 画廊卡片 DTO。
     *
     * @param id        世界 ID（预设 = {@code preset-{key}:{tenantId}} 确定性 ID）
     * @param name      世界名
     * @param blurb     两行简介（settingDesc 剥 md 记号压平）
     * @param coverUrl  统一封面端点 {@code /rpg/worlds/{id}/cover}（404 由前端渐变兜底）
     * @param source    {@code sandbox} | {@code preset}
     * @param presetKey 预设键（source=preset 时非 null，前端据此走向导深链）
     * @param published 本租户发布态（origin_world_id 命中在架包即已发布；作品墙发布/撤销钮族分流依据）
     */
    public record WorldCard(String id, String name, String blurb,
                            String coverUrl, String source, String presetKey,
                            boolean published) {
    }

    /**
     * 画廊卡片列表（兼容口径，DB 行在前按 created_at DESC，未物化预设卡殿后）。
     * scope 参数缺省的旧调用方过渡期使用。
     */
    public List<WorldCard> gallery() {
        String tenantId = TenantContext.requireTenantId();
        Set<String> publishedIds = Set.copyOf(sharedRepo.listVisibleOriginWorldIdsByPublisher(tenantId));
        List<WorldCard> cards = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (WorldSetting world : worldRepo.findAll()) {
            String presetKey = presetWorldService.presetKeyForTenant(world.getId(), tenantId);
            boolean preset = presetKey != null;
            cards.add(new WorldCard(world.getId(),
                    nullSafe(world.getName()),
                    toBlurb(world.getSettingDesc()),
                    coverUrl(world.getId()),
                    preset ? "preset" : "sandbox",
                    presetKey,
                    !preset && publishedIds.contains(world.getId())));
            seenIds.add(world.getId());
        }

        // 未物化预设直渲染上墙（点卡才 materialize）；已物化的 worldId 命中即跳过
        for (PresetWorldService.PresetDescriptor descriptor : presetWorldService.describePresets()) {
            if (seenIds.contains(descriptor.worldId())) {
                continue;
            }
            cards.add(new WorldCard(descriptor.worldId(),
                    nullSafe(descriptor.name()),
                    toBlurb(descriptor.blurb()),
                    coverUrl(descriptor.worldId()),
                    "preset",
                    descriptor.presetKey(),
                    false));
        }

        log.debug("画廊卡片已产出: tenant={}, cards={}", tenantId, cards.size());
        return cards;
    }

    /**
     * 画廊卡片列表（scope 分流，works-view-and-world-publish 2.4）：{@code scope=works}
     * 只含沙盒世界行（一人一租户口径的"用户创建"——已物化预设行与未物化预设殿后段
     * 均剔除，含导入来的副本）；其余值保持兼容口径。
     */
    public List<WorldCard> gallery(String scope) {
        if (!SCOPE_WORKS.equals(scope)) {
            return gallery();
        }
        String tenantId = TenantContext.requireTenantId();
        Set<String> publishedIds = Set.copyOf(sharedRepo.listVisibleOriginWorldIdsByPublisher(tenantId));
        List<WorldCard> cards = new ArrayList<>();
        for (WorldSetting world : worldRepo.findAll()) {
            if (presetWorldService.presetKeyForTenant(world.getId(), tenantId) != null) {
                continue; // 已物化预设不上作品墙（作品墙 = 纯用户创建）
            }
            cards.add(new WorldCard(world.getId(),
                    nullSafe(world.getName()),
                    toBlurb(world.getSettingDesc()),
                    coverUrl(world.getId()),
                    "sandbox",
                    null,
                    publishedIds.contains(world.getId())));
        }
        log.debug("作品墙卡片已产出: tenant={}, cards={}", tenantId, cards.size());
        return cards;
    }

    // ==================== 橱窗（根首页数据源，works-view-and-world-publish 2.5） ====================

    /**
     * 橱窗卡片 DTO：已发布世界卡（source=published，跨租户）+ 预设卡（source=preset）。
     * publisherSelf 标记当前租户为发布者（前端本人特判：直接深链开局本地原世界，不进导入流程）；
     * originWorldId 供该深链取目标；entryCount 供详情确认弹窗展示子数据计数。
     */
    public record ShowcaseCard(String id, String name, String blurb, String coverUrl,
                               String source, String presetKey, String originWorldId,
                               String publisherTenantId, boolean publisherSelf, int entryCount) {
    }

    /**
     * 根首页橱窗：全平台在架已发布世界卡（shared 读侧无租户过滤，updated_at 倒序）+ 预设世界。
     * 本地未发布沙盒卡不上橱窗；已物化预设按 worldId 去重只出一次（与未物化殿后段共用 seenIds）。
     */
    public List<ShowcaseCard> showcase() {
        String tenantId = TenantContext.requireTenantId();
        List<ShowcaseCard> cards = new ArrayList<>();

        for (SharedWorld pack : sharedRepo.listVisible()) {
            cards.add(new ShowcaseCard(pack.getId(),
                    nullSafe(pack.getName()),
                    nullSafe(pack.getBlurb()),
                    sharedCoverUrl(pack.getId()),
                    "published",
                    null,
                    pack.getOriginWorldId(),
                    pack.getPublisherTenantId(),
                    tenantId.equals(pack.getPublisherTenantId()),
                    pack.getEntryCount() == null ? 0 : pack.getEntryCount()));
        }

        // 预设世界：已物化 DB 行 + 未物化殿后，按 worldId 去重（不因本地物化状态重复）
        Set<String> seenIds = new HashSet<>();
        for (WorldSetting world : worldRepo.findAll()) {
            String presetKey = presetWorldService.presetKeyForTenant(world.getId(), tenantId);
            if (presetKey == null) {
                continue; // 本地沙盒卡不上橱窗（未发布内容不外显）
            }
            cards.add(new ShowcaseCard(world.getId(),
                    nullSafe(world.getName()),
                    toBlurb(world.getSettingDesc()),
                    coverUrl(world.getId()),
                    "preset",
                    presetKey,
                    null,
                    null,
                    false,
                    0));
            seenIds.add(world.getId());
        }
        for (PresetWorldService.PresetDescriptor descriptor : presetWorldService.describePresets()) {
            if (seenIds.contains(descriptor.worldId())) {
                continue;
            }
            cards.add(new ShowcaseCard(descriptor.worldId(),
                    nullSafe(descriptor.name()),
                    toBlurb(descriptor.blurb()),
                    coverUrl(descriptor.worldId()),
                    "preset",
                    descriptor.presetKey(),
                    null,
                    null,
                    false,
                    0));
        }

        log.debug("橱窗卡片已产出: viewer={}, cards={}", tenantId, cards.size());
        return cards;
    }

    /** 共享包封面端点（WorldPublishController 伺服 shared 区文件） */
    private static String sharedCoverUrl(String packId) {
        return "/rpg/shared-worlds/" + packId + "/cover";
    }

    /** 统一封面端点（与 WorldCoverController 的 GET 路径同源） */
    private static String coverUrl(String worldId) {
        return "/rpg/worlds/" + worldId + "/cover";
    }

    private static String nullSafe(String text) {
        return text == null ? "" : text;
    }

    /**
     * settingDesc / md 世界描述 → 卡片两行简介：逐行剥掉 md 记号（标题、列表、引用、
     * 粗斜体、行内代码）再压平成单行。卡片 CSS line-clamp 只留两行，这里只求
     * 「不出现生 markdown 符号」，不做截断。
     */
    static String toBlurb(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder flat = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String stripped = line.stripLeading();
            stripped = stripped.replaceFirst("^#{1,6}\\s+", "");
            stripped = stripped.replaceFirst("^[-*+]\\s+", "");
            stripped = stripped.replaceFirst("^>\\s+", "");
            stripped = stripped.replaceAll("\\*\\*|__|~~|`", "").trim();
            if (!stripped.isEmpty()) {
                if (flat.length() > 0) {
                    flat.append(' ');
                }
                flat.append(stripped);
            }
        }
        return flat.toString();
    }
}
