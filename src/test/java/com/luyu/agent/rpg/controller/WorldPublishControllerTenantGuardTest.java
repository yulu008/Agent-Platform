package com.luyu.agent.rpg.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.MimeTypeUtils;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.rpg.model.SharedWorld;
import com.luyu.agent.rpg.service.WorldCoverStorageService;
import com.luyu.agent.rpg.service.WorldPublishService;
import com.luyu.agent.service.JwtService;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 共享世界发布 Web 层测试（works-view-and-world-publish tasks 6.3，MockMvc + JWT cookie）。
 * <p>
 * 钉住读写不对称的 HTTP 表现（design D8）：
 * <ul>
 *   <li>橱窗读全平台：租户 B 的 cookie 可见租户 A 发布的包（列表/详情/封面 200）；</li>
 *   <li>跨租户「覆盖」在发布校验即被拒（B 读不到 A 的世界 → 400，不泄露包状态）；
 *       跨租户撤销 → 404（revoke WHERE 带 publisher 锚点，0 行 → false）；</li>
 *   <li>发布者本人导入 → 409（API 防线，前端经 publisherSelf 走深链开局）；</li>
 *   <li>revoked / 不存在的包：详情与导入 → 404。</li>
 * </ul>
 * 端到端归属断言（tenant_id 落接收方、副本独立）见 WorldPublishGuardCompatibilityTest 与 6.4。
 */
@WebMvcTest(WorldPublishController.class)
@Import(JwtService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class WorldPublishControllerTenantGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private WorldPublishService publishService;

    @MockitoBean
    private WorldCoverStorageService coverStorage;

    /** AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard 不在切片中，mock 之 */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    private Path tempCover;

    @AfterEach
    void cleanUp() throws Exception {
        if (tempCover != null) {
            Files.deleteIfExists(tempCover);
            tempCover = null;
        }
    }

    /** 租户 B 的有效 JWT cookie（A 发布的包对 B 橱窗可见、写操作被锚点拒）。 */
    private jakarta.servlet.http.Cookie tenantBCookie() {
        String token = jwtService.issue("u-b", "t-b");
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token);
    }

    private static SharedWorld packOfTenantA() {
        SharedWorld pack = new SharedWorld();
        pack.setId("pack-a");
        pack.setName("A的世界");
        pack.setBlurb("租户A的共享世界包");
        pack.setPublisherTenantId("t-a");
        pack.setOriginWorldId("w-a");
        pack.setOriginWorldName("A的世界");
        pack.setEntryCount(7);
        return pack;
    }

    // ==================== 橱窗读：全平台可见 ====================

    @Test
    void 橱窗列表跨租户可见() throws Exception {
        when(publishService.listVisible()).thenReturn(List.of(packOfTenantA()));

        mockMvc.perform(get("/rpg/shared-worlds").cookie(tenantBCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("pack-a"))
                .andExpect(jsonPath("$[0].name").value("A的世界"))
                .andExpect(jsonPath("$[0].publisherTenantId").value("t-a"))
                .andExpect(jsonPath("$[0].originWorldId").value("w-a"))
                .andExpect(jsonPath("$[0].entryCount").value(7));
    }

    @Test
    void 包详情跨租户可见() throws Exception {
        when(publishService.findVisibleById("pack-a")).thenReturn(packOfTenantA());

        mockMvc.perform(get("/rpg/shared-worlds/pack-a").cookie(tenantBCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pack-a"))
                .andExpect(jsonPath("$.publisherTenantId").value("t-a"))
                .andExpect(jsonPath("$.entryCount").value(7));
    }

    @Test
    void 包详情revoked或不存在返回404() throws Exception {
        when(publishService.findVisibleById("pack-gone")).thenReturn(null);

        mockMvc.perform(get("/rpg/shared-worlds/pack-gone").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    // ==================== 写：发布者锚点判权 ====================

    @Test
    void 跨租户撤销非发布者返回404() throws Exception {
        // 租户 B 撤租户 A 的包：revoke WHERE 带 publisher 锚点，受影响行 0 → false → 404
        when(publishService.revoke("pack-a")).thenReturn(false);

        mockMvc.perform(delete("/rpg/shared-worlds/pack-a").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 撤销发布者成功返回204() throws Exception {
        when(publishService.revoke("pack-a")).thenReturn(true);

        mockMvc.perform(delete("/rpg/shared-worlds/pack-a").cookie(tenantBCookie()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 发布校验拒绝跨租户世界返回400() throws Exception {
        // 跨租户「覆盖」在发布校验即被拒：B 读不到 A 的世界（repo 租户过滤）→ 400，
        // 不泄露 A 侧包的存在性；A 既有包零触碰
        doThrow(new IllegalArgumentException("世界不存在或不属于当前租户: w-of-a"))
                .when(publishService).publish("w-of-a");

        mockMvc.perform(post("/rpg/shared-worlds").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-of-a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 发布预设世界被拒返回400() throws Exception {
        doThrow(new IllegalArgumentException("预设世界无需发布（橱窗已展示预设卡）"))
                .when(publishService).publish("preset-adventure:t-b");

        mockMvc.perform(post("/rpg/shared-worlds").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"preset-adventure:t-b\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("预设世界")));
    }

    @Test
    void 发布成功返回包id与覆盖标记() throws Exception {
        when(publishService.publish("w-b"))
                .thenReturn(new WorldPublishService.PublishResult("pack-new", false, 7));

        mockMvc.perform(post("/rpg/shared-worlds").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId").value("pack-new"))
                .andExpect(jsonPath("$.overwrite").value(false))
                .andExpect(jsonPath("$.entryCount").value(7));
    }

    @Test
    void 覆盖重发返回overwrite为true() throws Exception {
        // 同发布者同源世界二次发布：覆盖刷新既有包（entryCount/updated_at 重写）
        when(publishService.publish("w-b"))
                .thenReturn(new WorldPublishService.PublishResult("pack-old", true, 9));

        mockMvc.perform(post("/rpg/shared-worlds").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId").value("pack-old"))
                .andExpect(jsonPath("$.overwrite").value(true))
                .andExpect(jsonPath("$.entryCount").value(9));
    }

    // ==================== 导入：前置校验（API 防线） ====================

    @Test
    void 导入发布者本人返回409() throws Exception {
        // 正常流程前端经 showcase 的 publisherSelf 标志直接深链开局本地原世界，
        // 不调导入；此端点是防线（直连 API 也拒绝）
        doThrow(new IllegalStateException("这是你自己发布的世界，直接开局本地原世界即可（origin: w-a）"))
                .when(publishService).importWorld("pack-a");

        mockMvc.perform(post("/rpg/shared-worlds/pack-a/import").cookie(tenantBCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("你自己发布")));
    }

    @Test
    void 导入revoked或不存在包返回404() throws Exception {
        doThrow(new NoSuchElementException("共享世界不存在或已下架: pack-gone"))
                .when(publishService).importWorld("pack-gone");

        mockMvc.perform(post("/rpg/shared-worlds/pack-gone/import").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 导入成功返回新世界id与条目数() throws Exception {
        when(publishService.importWorld("pack-a"))
                .thenReturn(new WorldPublishService.ImportResult("w-new-of-b", 7));

        mockMvc.perform(post("/rpg/shared-worlds/pack-a/import").cookie(tenantBCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newWorldId").value("w-new-of-b"))
                .andExpect(jsonPath("$.entryCount").value(7));
    }

    // ==================== 共享封面伺服 ====================

    @Test
    void 共享封面跨租户可伺服() throws Exception {
        SharedWorld pack = packOfTenantA();
        pack.setCover("pack-a.png");
        when(publishService.findVisibleById("pack-a")).thenReturn(pack);
        tempCover = Files.createTempFile("shared-cover-test", ".png");
        when(coverStorage.readShared("pack-a.png")).thenReturn(Optional.of(tempCover));
        when(coverStorage.resolveMimeType("pack-a.png")).thenReturn(MimeTypeUtils.IMAGE_PNG);

        mockMvc.perform(get("/rpg/shared-worlds/pack-a/cover").cookie(tenantBCookie()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void 无封面包封面端点返回404() throws Exception {
        // pack cover 列 null：包级判断短路 404（前端渐变兜底）
        when(publishService.findVisibleById("pack-a")).thenReturn(packOfTenantA());

        mockMvc.perform(get("/rpg/shared-worlds/pack-a/cover").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void revoked包封面端点返回404() throws Exception {
        when(publishService.findVisibleById("pack-gone")).thenReturn(null);

        mockMvc.perform(get("/rpg/shared-worlds/pack-gone/cover").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }
}
