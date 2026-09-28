package com.luyu.agent.rpg.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.service.JwtService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工坊 Web 层租户校验测试（tasks 3.7）：跨租户资源统一 404。
 * <p>
 * Repository 层租户过滤使跨租户 findById 返回 null / 删除抛 IllegalArgumentException，
 * 本测试钉住 Controller 层将其表现为 404（不区分"不存在"与"无权"，不泄露资源存在性）。
 * HTTP 层未认证 401 / 上下文建立由 {@code JwtTenantFilterTest} 覆盖。
 */
@WebMvcTest(WorkshopController.class)
@Import(JwtService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class WorkshopControllerTenantGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private WorkshopService workshopService;

    @MockitoBean
    private WorkshopLlmGenerator llmGenerator;

    @MockitoBean
    private ContentModerationGate moderationGate;

    @MockitoBean
    private QuotaGuard quotaGuard;

    /** AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard 不在切片中，mock 之 */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    /** 租户 B 的有效 JWT cookie（租户隔离语义由 Repository 过滤 mock 模拟）。 */
    private jakarta.servlet.http.Cookie tenantBCookie() {
        String token = jwtService.issue("u-b", "t-b");
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token);
    }

    @Test
    void 跨租户读取世界观返回404() throws Exception {
        // 模拟租户 B 以租户 A 的 worldId 读取：Repository 过滤后返回 null
        when(workshopService.getWorld(anyString())).thenReturn(null);

        mockMvc.perform(get("/rpg/workshop/world/w-of-tenant-a").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 跨租户删除世界观返回404() throws Exception {
        doThrow(new IllegalArgumentException("not found"))
                .when(workshopService).deleteWorld(anyString());

        mockMvc.perform(delete("/rpg/workshop/world/w-of-tenant-a").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 跨租户删除角色返回404() throws Exception {
        doThrow(new IllegalArgumentException("not found"))
                .when(workshopService).deleteCharacter(anyString());

        mockMvc.perform(delete("/rpg/workshop/character/c-of-tenant-a").cookie(tenantBCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 引用冲突仍返回409不与404混淆() throws Exception {
        doThrow(new IllegalStateException("存在子数据"))
                .when(workshopService).deleteWorld(anyString());

        mockMvc.perform(delete("/rpg/workshop/world/w-of-tenant-a").cookie(tenantBCookie()))
                .andExpect(status().isConflict());
    }
}
