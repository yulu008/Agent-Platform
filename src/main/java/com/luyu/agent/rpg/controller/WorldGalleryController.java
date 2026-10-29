package com.luyu.agent.rpg.controller;

import com.luyu.agent.rpg.service.WorldGalleryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 世界画廊 REST API（world-card-gallery，design D2）。
 * <p>
 * 只读端点。租户上下文由 {@code JwtTenantFilter} 建立（白名单不含 /rpg），
 * 服务层强制租户过滤。纯本地运算（DB 查询 + md 解析），刻意不套 QuotaGuard 与审查闸门。
 * <p>
 * works-view-and-world-publish 数据源分层：gallery 走作品墙（{@code scope=works}
 * 纯沙盒行）；showcase 走根首页橱窗（全平台已发布包 + 预设，登录即可见）。
 */
@RestController
public class WorldGalleryController {

    private final WorldGalleryService galleryService;

    public WorldGalleryController(WorldGalleryService galleryService) {
        this.galleryService = galleryService;
    }

    /**
     * 当前租户的画廊卡片列表（兼容口径：DB 世界行 + 未物化预设，已物化预设去重）；
     * {@code scope=works} 时只含沙盒世界行（作品墙，含发布态 published 字段）。
     */
    @GetMapping("/rpg/worlds/gallery")
    public List<WorldGalleryService.WorldCard> gallery(
            @RequestParam(name = "scope", required = false) String scope) {
        return galleryService.gallery(scope);
    }

    /**
     * 根首页橱窗卡片列表：全平台在架已发布世界卡（跨租户读）+ 预设世界；
     * 本地未发布沙盒不上橱窗。登录即可见（读侧无租户过滤）。
     */
    @GetMapping("/rpg/worlds/showcase")
    public List<WorldGalleryService.ShowcaseCard> showcase() {
        return galleryService.showcase();
    }
}
