package com.luyu.agent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.luyu.agent.tenancy.TenantContext;

/**
 * 管理端访问控制集成测试（tasks 10.8 / design D9）。
 * <p>
 * {@code @JdbcTest} + 真实 H2（{@code app_user} 由测试 schema.sql 建表）：验证
 * {@link PlatformAdminGuard} 按 userId 回查 {@code user_role} 仅认 {@code platform_admin}，
 * 以及 {@link AdminAccessInterceptor} 在 Filter 之后据此拦截——非平台管理员 403、未认证 401、
 * 平台管理员放行；并覆盖页面导航（text/html）与接口（JSON）两种拒绝响应分流。
 * <p>
 * 关键回归点：普通租户的 {@code admin} 角色<b>不</b>等于平台管理员，必须被拒（跨租户越权防护）。
 */
@JdbcTest
@Import(PlatformAdminGuard.class)
class AdminAccessControlTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformAdminGuard guard;

    private AdminAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AdminAccessInterceptor(guard);
        jdbc.update("INSERT INTO tenant (id, display_name) VALUES (?, ?)", "default", "默认租户");
        // 平台管理员（default 租户引导账号）
        insertUser("u-platform", "default", "admin@default.local", "platform_admin");
        // 普通租户管理员（角色 admin，但非 platform_admin）
        insertUser("u-tenant-admin", "default", "boss@tenant.local", "admin");
        // 普通用户
        insertUser("u-normal", "default", "user@tenant.local", "user");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void insertUser(String id, String tenantId, String email, String role) {
        jdbc.update("INSERT INTO app_user (id, tenant_id, email, password_hash, user_role) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, email, "x", role);
    }

    // ==================== PlatformAdminGuard 角色回查 ====================

    @Test
    void 仅platform_admin角色被认定为平台管理员() {
        assertThat(guard.isPlatformAdmin("u-platform")).isTrue();
        // 普通租户 admin 不是平台管理员（越权防护关键回归点）
        assertThat(guard.isPlatformAdmin("u-tenant-admin")).isFalse();
        assertThat(guard.isPlatformAdmin("u-normal")).isFalse();
    }

    @Test
    void 用户不存在或入参空_一律非管理员() {
        assertThat(guard.isPlatformAdmin("u-ghost")).isFalse();
        assertThat(guard.isPlatformAdmin(null)).isFalse();
        assertThat(guard.isPlatformAdmin("")).isFalse();
    }

    // ==================== AdminAccessInterceptor 拦截 ====================

    @Test
    void 平台管理员_放行() throws Exception {
        TenantContext.set("u-platform", "default");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/metering/usage");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isTrue();
    }

    @Test
    void 普通租户管理员_被拒403() throws Exception {
        TenantContext.set("u-tenant-admin", "default");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/metering/usage");
        req.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
        assertThat(resp.getContentAsString()).contains("error");
    }

    @Test
    void 未认证无userId_被拒401() throws Exception {
        TenantContext.clear();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void 页面导航被拒_返回HTML而非JSON() throws Exception {
        TenantContext.set("u-normal", "default");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/admin");
        req.addHeader("Accept", MediaType.TEXT_HTML_VALUE);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, new Object())).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentType()).contains(MediaType.TEXT_HTML_VALUE);
        assertThat(resp.getContentAsString()).contains("403").contains("返回首页");
    }
}
