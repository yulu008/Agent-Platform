package com.luyu.agent.tenancy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.service.JwtService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JwtTenantFilter} 端到端行为集成测试（tenancy/tenant-context spec 对账）。
 * <p>
 * 钉住：无 cookie 访问受保护接口 401（JSON）/ 页面导航 302 登录页分流；
 * 篡改 token 401；query 参数 tid 与 JWT 不一致时以 JWT（验签后声明）为准；
 * 请求结束后 ThreadLocal 必被清理（线程池复用防线）；白名单路径放行。
 */
class JwtTenantFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private JwtService jwtService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setSecret(SECRET);
        props.setTtl(Duration.ofHours(1));
        jwtService = new JwtService(props);
        mockMvc = MockMvcBuilders.standaloneSetup(new TenantProbeController())
                .addFilters(new JwtTenantFilter(jwtService))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void 无cookie访问受保护接口返回401() throws Exception {
        mockMvc.perform(get("/api/tenant/probe").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 无cookie页面导航302到登录页() throws Exception {
        mockMvc.perform(get("/rpg").accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void 篡改token返回401() throws Exception {
        // 用错误密钥签发的 token：签名必不匹配，等价于签名段被篡改
        AuthProperties forged = new AuthProperties();
        forged.setSecret("ffffffffffffffffffffffffffffffff");
        forged.setTtl(Duration.ofHours(1));
        String tampered = new JwtService(forged).issue("u-1", "t-1");

        mockMvc.perform(get("/api/tenant/probe")
                        .cookie(new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, tampered))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 有效token建立租户上下文() throws Exception {
        String token = jwtService.issue("u-1", "t-1");

        mockMvc.perform(get("/api/tenant/probe")
                        .cookie(new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("t-1"))
                .andExpect(jsonPath("$.userId").value("u-1"));

        // 线程池复用防线：请求结束后 ThreadLocal 必被清理
        assertThat(TenantContext.isPresent()).isFalse();
    }

    @Test
    void query参数tid与JWT不一致时以JWT为准() throws Exception {
        // query 传他租户 tid 试图越权：租户身份只来自验签后的 JWT（设计铁律）
        String token = jwtService.issue("u-1", "t-real");

        mockMvc.perform(get("/api/tenant/probe")
                        .queryParam("tid", "t-other")
                        .cookie(new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("t-real"));
    }

    @Test
    void 过期token返回401() throws Exception {
        AuthProperties props = new AuthProperties();
        props.setSecret(SECRET);
        props.setTtl(Duration.ofMillis(1));
        JwtService expiring = new JwtService(props);
        Thread.sleep(50);
        String expired = expiring.issue("u-1", "t-1");

        mockMvc.perform(get("/api/tenant/probe")
                        .cookie(new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, expired))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 白名单路径无需认证直接放行() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isNotFound()); // 探针上下文无该路由：404 证明已穿透过滤器
    }

    /** 探针端点：回显 TenantContext（受保护路径的可见视角） */
    @RestController
    static class TenantProbeController {

        @GetMapping(value = "/api/tenant/probe", produces = MediaType.APPLICATION_JSON_VALUE)
        public java.util.Map<String, String> probe(@RequestParam(value = "tid", required = false) String ignoredTid) {
            // 刻意不消费 query tid：租户身份只来自 JWT，参数不参与决策
            return java.util.Map.of(
                    "tenantId", TenantContext.requireTenantId(),
                    "userId", TenantContext.requireUserId());
        }
    }
}
