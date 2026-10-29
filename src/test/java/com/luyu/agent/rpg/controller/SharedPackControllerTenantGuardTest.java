package com.luyu.agent.rpg.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.rpg.model.SharedLorebookPack;
import com.luyu.agent.rpg.service.SharedPackService;
import com.luyu.agent.service.JwtService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 共享世界书包 Web 层测试（add-shared-worldbook-publish tasks 5.2/5.4，MockMvc + JWT cookie）。
 * <p>
 * 钉住读写不对称的 HTTP 表现（design D2/D7）：
 * <ul>
 *   <li>画廊读全平台：租户 B 的 cookie 可见租户 A 发布的包（列表/详情 200）；</li>
 *   <li>跨租户撤回 → 404（不区分"不存在"与"非发布者"，不泄露包状态）；</li>
 *   <li>发布/导入校验拒绝 → 400（preset- 目标、无效世界/参数）；同名星冲突 → 409；</li>
 *   <li>未覆盖的端到端归属断言（tenant_id 落接收方、fork 接管）见 SharedPackEndToEndTest。</li>
 * </ul>
 */
@WebMvcTest(SharedPackController.class)
@Import(JwtService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class SharedPackControllerTenantGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SharedPackService sharedPackService;

    /** AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard 不在切片中，mock 之 */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    /** 租户 B 的有效 JWT cookie（发布者 A 的包对 B 画廊可见、写操作被拒）。 */
    private jakarta.servlet.http.Cookie tenantBCookie() {
        String token = jwtService.issue("u-b", "t-b");
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token);
    }

    private static SharedLorebookPack packOfTenantA() {
        SharedLorebookPack pack = new SharedLorebookPack();
        pack.setId("pack-a");
        pack.setName("A的设定集");
        pack.setBlurb("租户A的包");
        pack.setPublisherTenantId("t-a");
        pack.setEntryCount(3);
        return pack;
    }

    // ==================== 画廊读：全平台可见 ====================

    @Test
    void 画廊列表跨租户可见() throws Exception {
        when(sharedPackService.listVisible()).thenReturn(List.of(packOfTenantA()));

        mockMvc.perform(get("/rpg/shared-packs").cookie(tenantBCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("pack-a"))
                .andExpect(jsonPath("$[0].name").value("A的设定集"))
                .andExpect(jsonPath("$[0].publisherTenantId").value("t-a"));
    }

    @Test
    void 包详情含双轨条目跨租户可见() throws Exception {
        when(sharedPackService.loadPack("pack-a")).thenReturn(new SharedPackService.PackDetail(
                packOfTenantA(), List.of(), List.of()));

        mockMvc.perform(get("/rpg/shared-packs/pack-a").cookie(tenantBCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pack.id").value("pack-a"))
                .andExpect(jsonPath("$.worldbookEntries").isArray())
                .andExpect(jsonPath("$.starEntries").isArray());
    }

    @Test
    void 包详情已撤回或不存在返回404() throws Exception {
        when(sharedPackService.loadPack("pack-gone")).thenReturn(null);

        mockMvc.perform(get("/rpg/shared-packs/pack-gone").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    // ==================== 写：发布者锚点判权 ====================

    @Test
    void 撤回非发布者返回404() throws Exception {
        // 租户 B 撤回租户 A 的包：revoke WHERE 带 publisher 锚点，受影响行 0 → false → 404
        when(sharedPackService.revoke("pack-a")).thenReturn(false);

        mockMvc.perform(delete("/rpg/shared-packs/pack-a").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 撤回发布者成功返回204() throws Exception {
        when(sharedPackService.revoke("pack-a")).thenReturn(true);

        mockMvc.perform(delete("/rpg/shared-packs/pack-a").cookie(tenantBCookie()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 发布校验拒绝返回400() throws Exception {
        // 租户 B 用租户 A 的 worldId 发布：Repository 过滤后世界不可见 → 400（不泄露存在性）
        // blurb 可缺省（nullable），请求体未带时 str() 返回 null，anyString() 不匹配 null 故用 nullable
        doThrow(new IllegalArgumentException("世界不存在或不属于当前租户: w-of-a"))
                .when(sharedPackService).publish(nullable(String.class), anyString(),
                        nullable(String.class), anyList(), anyList());

        mockMvc.perform(post("/rpg/shared-packs").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-of-a\",\"name\":\"B的包\",\"worldbookIds\":[\"wb-1\"],\"starIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 发布成功返回包id与覆盖标记() throws Exception {
        when(sharedPackService.publish(nullable(String.class), anyString(),
                nullable(String.class), anyList(), anyList()))
                .thenReturn(new SharedPackService.PublishResult("pack-new", false, 2));

        mockMvc.perform(post("/rpg/shared-packs").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-b\",\"name\":\"B的包\",\"worldbookIds\":[\"wb-1\"],\"starIds\":[\"inc-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId").value("pack-new"))
                .andExpect(jsonPath("$.overwrite").value(false))
                .andExpect(jsonPath("$.entryCount").value(2));
    }

    // ==================== 导入：前置校验（tasks 5.4）====================

    @Test
    void 导入物化预设世界返回400() throws Exception {
        doThrow(new IllegalArgumentException("不支持导入到物化预设世界（重物化会清除导入内容），请选择自建世界"))
                .when(sharedPackService).importPack("pack-a", "preset-adventure:t-a");

        mockMvc.perform(post("/rpg/shared-packs/pack-a/import").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"preset-adventure:t-a\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("物化预设世界")));
    }

    @Test
    void 导入同名星冲突返回409并带冲突星名() throws Exception {
        doThrow(new IllegalStateException("目标世界已有同名冥星，导入会错挂揭示/抢星状态: 天魁星"))
                .when(sharedPackService).importPack("pack-a", "w-b");

        mockMvc.perform(post("/rpg/shared-packs/pack-a/import").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("天魁星")));
    }

    @Test
    void 导入成功返回双轨条数与目标世界() throws Exception {
        when(sharedPackService.importPack("pack-a", "w-b"))
                .thenReturn(new SharedPackService.ImportResult(2, 1, "w-b"));

        mockMvc.perform(post("/rpg/shared-packs/pack-a/import").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldbookCount").value(2))
                .andExpect(jsonPath("$.starCount").value(1))
                .andExpect(jsonPath("$.targetWorldId").value("w-b"));
    }

    @Test
    void 导入包不存在返回404() throws Exception {
        doThrow(new java.util.NoSuchElementException("共享包不存在或已下架: pack-gone"))
                .when(sharedPackService).importPack(anyString(), anyString());

        mockMvc.perform(post("/rpg/shared-packs/pack-gone/import").cookie(tenantBCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"w-b\"}"))
                .andExpect(status().isNotFound());
    }
}
