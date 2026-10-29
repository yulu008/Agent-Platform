package com.luyu.agent.rpg.controller;

import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.service.PresetWorldService;
import com.luyu.agent.rpg.service.WorldCoverStorageService;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 世界封面端点（world-card-gallery，design D5/D6）。
 * <p>
 * 项目首个 MultipartFile 端点：上传落 {@code data/covers/} 持久区并更新 cover 列；
 * 伺服按解析序「cover 列文件 → 预设随包 classpath 兜底（租户后缀闸门）→ 404」。
 * <p>
 * 两路径均在 {@code JwtTenantFilter} 之后（白名单零改动），行查询走租户过滤
 * Repository，跨租户 id 查不到行即 404，不存在越权面。
 */
@RestController
public class WorldCoverController {

    private static final Logger log = LoggerFactory.getLogger(WorldCoverController.class);

    /** 文件名 UUID 即版本（替换上传 = 新文件名），可安全长缓存 */
    private static final CacheControl COVER_CACHE =
            CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic();

    private final RpgWorldSettingRepository worldRepo;
    private final WorldCoverStorageService coverStorage;
    private final PresetWorldService presetWorldService;

    public WorldCoverController(RpgWorldSettingRepository worldRepo,
                                WorldCoverStorageService coverStorage,
                                PresetWorldService presetWorldService) {
        this.worldRepo = worldRepo;
        this.coverStorage = coverStorage;
        this.presetWorldService = presetWorldService;
    }

    /**
     * 上传/替换世界封面。
     * <p>
     * 请求: POST /rpg/workshop/worlds/{id}/cover，multipart 字段 {@code file}<br>
     * 响应: 200 {@code {cover, coverUrl}}；行不存在 404；空文件/非法 MIME 400；超限 413
     */
    @PostMapping("/rpg/workshop/worlds/{id}/cover")
    public ResponseEntity<?> uploadCover(@PathVariable String id,
                                         @RequestParam("file") MultipartFile file) {
        WorldSetting world = worldRepo.findById(id);
        if (world == null) {
            return ResponseEntity.notFound().build();
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "封面文件不能为空"));
        }
        String filename;
        try {
            // 落盘到租户私有区（works-view-and-world-publish D6）；行已由 findById 确认属当前租户
            filename = coverStorage.store(TenantContext.requireTenantId(), file.getBytes(), file.getContentType());
        } catch (IllegalArgumentException e) {
            // MIME 白名单外（png/jpeg/webp 之外一律拒）
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("封面落盘失败: worldId={}, reason={}", id, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "封面保存失败"));
        }
        // 先写库再删旧文件：删旧是 best-effort（双轨：租户子目录 + 根目录存量裸文件），不阻断成功响应
        String oldCover = world.getCover();
        worldRepo.updateCover(id, filename);
        coverStorage.delete(TenantContext.requireTenantId(), oldCover);
        log.info("世界封面已更新: worldId={}, cover={}", id, filename);
        return ResponseEntity.ok(Map.of("cover", filename, "coverUrl", coverUrl(id)));
    }

    /**
     * 伺服世界封面（design D6 解析序）。
     * <p>
     * cover 列文件命中 → 流式返回；否则若 id 是本租户的白名单预设 → 随包 classpath
     * 图兜底（未物化预设也能出图）；再否则 404（前端渐变 + 首字兜底，不破图）。
     */
    @GetMapping("/rpg/worlds/{id}/cover")
    public ResponseEntity<Resource> getCover(@PathVariable String id) {
        WorldSetting world = worldRepo.findById(id);
        if (world != null && world.getCover() != null && !world.getCover().isBlank()) {
            // 行已由 findById 确认属当前租户（works-view-and-world-publish 1.4：按世界行租户解析路径，
            // 双轨读：租户子目录优先，miss 回落根目录存量裸文件）
            Optional<Path> path = coverStorage.read(TenantContext.requireTenantId(), world.getCover());
            if (path.isPresent()) {
                return imageResponse(new FileSystemResource(path.get()),
                        MediaType.parseMediaType(
                                coverStorage.resolveMimeType(world.getCover()).toString()));
            }
            log.warn("cover 列指向的文件缺失，尝试兜底: worldId={}, cover={}", id, world.getCover());
        }
        // 预设随包兜底：presetKeyForTenant 双闸（租户后缀 + 白名单），跨租户必 null
        String presetKey = presetWorldService.presetKeyForTenant(id, TenantContext.requireTenantId());
        if (presetKey != null) {
            byte[] bundled = coverStorage.readBundled(presetKey);
            if (bundled != null) {
                return imageResponse(new org.springframework.core.io.ByteArrayResource(bundled),
                        MediaType.IMAGE_PNG);
            }
        }
        return ResponseEntity.notFound().build();
    }

    /** 超尺寸闸（spring.servlet.multipart 4MB/8MB）→ 413 + 友好 error */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("封面上传超限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "封面文件过大，请上传 4MB 以内的图片"));
    }

    private static ResponseEntity<Resource> imageResponse(Resource resource, MediaType mediaType) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(COVER_CACHE)
                .body(resource);
    }

    private static String coverUrl(String worldId) {
        return "/rpg/worlds/" + worldId + "/cover";
    }
}
