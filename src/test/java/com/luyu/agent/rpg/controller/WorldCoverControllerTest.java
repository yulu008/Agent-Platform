package com.luyu.agent.rpg.controller;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.service.PresetWorldService;
import com.luyu.agent.rpg.service.WorldCoverStorageService;
import com.luyu.agent.service.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WorldCoverController} Web 层测试（world-card-gallery tasks 10.2 / design D5/D6）。
 * <p>
 * 上传五场景：成功、非法 MIME 400、空文件 400、超尺寸 413、跨租户行不存在 404（并连带删旧）；
 * 伺服四场景：沙盒 cover 列文件命中、预设随包 classpath 兜底、跨租户闸门 404、cover 为 NULL 404。
 * <p>
 * 租户上下文由 {@code JwtTenantFilter}（随 {@code @WebMvcTest} 切片加载）从 cookie 建立；
 * Repository/Storage 层租户过滤与磁盘 IO 由 mock 体现（跨租户查询返回 null）。
 */
@WebMvcTest(WorldCoverController.class)
@Import(JwtService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class WorldCoverControllerTest {

    private static final String WORLD_ID = "w-a";
    private static final String PRESET_WORLD_ID = "preset-adventure:t-a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private RpgWorldSettingRepository worldRepo;

    @MockitoBean
    private WorldCoverStorageService coverStorage;

    @MockitoBean
    private PresetWorldService presetWorldService;

    /** AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard 不在切片中，mock 之 */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    /** 伺服沙盒封面时的真实文件（FileSystemResource 需可读） */
    @TempDir
    Path tempDir;

    private jakarta.servlet.http.Cookie tenantACookie() {
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, jwtService.issue("u-a", "t-a"));
    }

    private WorldSetting world(String id, String cover) {
        WorldSetting w = new WorldSetting();
        w.setId(id);
        w.setName("测试世界");
        w.setCover(cover);
        return w;
    }

    // ==================== 上传：POST /rpg/workshop/worlds/{id}/cover ====================

    @Test
    void 上传成功更新cover列并删除旧文件() throws Exception {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, "old.png"));
        when(coverStorage.store(eq("t-a"), any(byte[].class), eq("image/png"))).thenReturn("new.png");

        mockMvc.perform(multipart("/rpg/workshop/worlds/{id}/cover", WORLD_ID)
                        .file(new MockMultipartFile("file", "cover.png", "image/png", new byte[]{1, 2, 3}))
                        .cookie(tenantACookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cover").value("new.png"))
                .andExpect(jsonPath("$.coverUrl").value("/rpg/worlds/" + WORLD_ID + "/cover"));

        // 先写库再删旧：新文件名落 cover 列，旧文件 best-effort 清理
        verify(worldRepo).updateCover(WORLD_ID, "new.png");
        verify(coverStorage).delete("t-a", "old.png");
    }

    @Test
    void 上传非法MIME返回400() throws Exception {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, null));
        when(coverStorage.store(eq("t-a"), any(byte[].class), eq("image/bmp")))
                .thenThrow(new IllegalArgumentException("不支持的封面类型: image/bmp"));

        mockMvc.perform(multipart("/rpg/workshop/worlds/{id}/cover", WORLD_ID)
                        .file(new MockMultipartFile("file", "a.bmp", "image/bmp", new byte[]{1}))
                        .cookie(tenantACookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // MIME 白名单外（bmp；gif 已放行 world-cover-gif-support）：拒绝后不得写库
        verify(worldRepo, never()).updateCover(anyString(), anyString());
    }

    @Test
    void 上传gif成功更新cover列() throws Exception {
        // world-cover-gif-support：image/gif 走同一上传链路，响应与静态格式一致
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, null));
        when(coverStorage.store(eq("t-a"), any(byte[].class), eq("image/gif"))).thenReturn("new.gif");

        mockMvc.perform(multipart("/rpg/workshop/worlds/{id}/cover", WORLD_ID)
                        .file(new MockMultipartFile("file", "a.gif", "image/gif",
                                new byte[]{'G', 'I', 'F', '8', '9', 'a'}))
                        .cookie(tenantACookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cover").value("new.gif"))
                .andExpect(jsonPath("$.coverUrl").value("/rpg/worlds/" + WORLD_ID + "/cover"));

        verify(worldRepo).updateCover(WORLD_ID, "new.gif");
    }

    @Test
    void 上传空文件返回400() throws Exception {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, null));

        mockMvc.perform(multipart("/rpg/workshop/worlds/{id}/cover", WORLD_ID)
                        .file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0]))
                        .cookie(tenantACookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(worldRepo, never()).updateCover(anyString(), anyString());
    }

    @Test
    void 上传超尺寸返回413() throws Exception {
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, null));
        // 超尺寸闸：MaxUploadSizeExceededException 逃逸方法体，由 @ExceptionHandler 映射 413
        when(coverStorage.store(eq("t-a"), any(byte[].class), anyString()))
                .thenThrow(new MaxUploadSizeExceededException(4 * 1024 * 1024L));

        mockMvc.perform(multipart("/rpg/workshop/worlds/{id}/cover", WORLD_ID)
                        .file(new MockMultipartFile("file", "big.png", "image/png", new byte[]{1}))
                        .cookie(tenantACookie()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").exists());

        verify(worldRepo, never()).updateCover(anyString(), anyString());
    }

    @Test
    void 上传跨租户行不存在返回404() throws Exception {
        // 租户 B 以租户 A 的 worldId 上传：Repository 租户过滤后 findById 返回 null
        when(worldRepo.findById("w-of-other-tenant")).thenReturn(null);

        mockMvc.perform(multipart("/rpg/workshop/worlds/{id}/cover", "w-of-other-tenant")
                        .file(new MockMultipartFile("file", "c.png", "image/png", new byte[]{1}))
                        .cookie(tenantACookie()))
                .andExpect(status().isNotFound());

        verify(worldRepo, never()).updateCover(anyString(), anyString());
    }

    // ==================== 伺服：GET /rpg/worlds/{id}/cover ====================

    @Test
    void 伺服沙盒cover列文件命中返回图片与长缓存头() throws Exception {
        Path coverFile = tempDir.resolve("sandbox.png");
        Files.write(coverFile, new byte[]{9, 8, 7});
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, "sandbox.png"));
        when(coverStorage.read("t-a", "sandbox.png")).thenReturn(Optional.of(coverFile));
        when(coverStorage.resolveMimeType("sandbox.png")).thenReturn(MimeTypeUtils.IMAGE_PNG);

        mockMvc.perform(get("/rpg/worlds/{id}/cover", WORLD_ID).cookie(tenantACookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE))
                // UUID 文件名即版本，可安全长缓存（30 天 public）
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("max-age")));
    }

    @Test
    void 伺服gif封面返回imageGif与长缓存头() throws Exception {
        // world-cover-gif-support：.gif 精确 Content-Type，缓存策略与静态格式一致
        Path coverFile = tempDir.resolve("sandbox.gif");
        Files.write(coverFile, new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, "sandbox.gif"));
        when(coverStorage.read("t-a", "sandbox.gif")).thenReturn(Optional.of(coverFile));
        when(coverStorage.resolveMimeType("sandbox.gif")).thenReturn(MimeTypeUtils.IMAGE_GIF);

        mockMvc.perform(get("/rpg/worlds/{id}/cover", WORLD_ID).cookie(tenantACookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_GIF_VALUE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("max-age")));
    }

    @Test
    void 伺服未物化预设走随包classpath兜底() throws Exception {
        // 未物化预设：DB 无行，靠 presetKeyForTenant 双闸 + readBundled 出图
        when(worldRepo.findById(PRESET_WORLD_ID)).thenReturn(null);
        when(presetWorldService.presetKeyForTenant(PRESET_WORLD_ID, "t-a")).thenReturn("adventure");
        when(coverStorage.readBundled("adventure")).thenReturn(new byte[]{5, 4, 3});

        mockMvc.perform(get("/rpg/worlds/{id}/cover", PRESET_WORLD_ID).cookie(tenantACookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE));
    }

    @Test
    void 伺服跨租户预设闸门拒绝返回404() throws Exception {
        // 租户 A 请求租户 B 的预设世界 id：presetKeyForTenant 租户后缀不匹配返回 null
        String foreignPreset = "preset-adventure:t-b";
        when(worldRepo.findById(foreignPreset)).thenReturn(null);
        when(presetWorldService.presetKeyForTenant(foreignPreset, "t-a")).thenReturn(null);

        mockMvc.perform(get("/rpg/worlds/{id}/cover", foreignPreset).cookie(tenantACookie()))
                .andExpect(status().isNotFound());

        verify(coverStorage, never()).readBundled(anyString());
    }

    @Test
    void 伺服cover为NULL且非预设返回404() throws Exception {
        // 沙盒世界未上传封面（cover 列 NULL）且非预设：无兜底 → 404（前端渐变兜底不破图）
        when(worldRepo.findById(WORLD_ID)).thenReturn(world(WORLD_ID, null));
        when(presetWorldService.presetKeyForTenant(WORLD_ID, "t-a")).thenReturn(null);

        mockMvc.perform(get("/rpg/worlds/{id}/cover", WORLD_ID).cookie(tenantACookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 伺服cover列指向文件缺失时回落预设兜底() throws Exception {
        // cover 列有值但磁盘文件缺失（read 返回 empty）→ 若同时是预设则回落随包图
        when(worldRepo.findById(PRESET_WORLD_ID)).thenReturn(world(PRESET_WORLD_ID, "gone.png"));
        when(coverStorage.read("t-a", "gone.png")).thenReturn(Optional.empty());
        when(presetWorldService.presetKeyForTenant(PRESET_WORLD_ID, "t-a")).thenReturn("adventure");
        when(coverStorage.readBundled("adventure")).thenReturn(new byte[]{2, 2, 2});

        mockMvc.perform(get("/rpg/worlds/{id}/cover", PRESET_WORLD_ID).cookie(tenantACookie()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE));
    }
}
