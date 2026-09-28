package com.luyu.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.service.AuthService;
import com.luyu.agent.service.JwtService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} Web 层测试（@WebMvcTest + Mock JWT）。
 * <p>
 * 钉住的 spec 场景：登录成功 Set-Cookie HttpOnly、重复注册 409、
 * 错误凭据 401 不泄露邮箱存在性、登出清 cookie。
 */
@WebMvcTest(AuthController.class)
@Import(JwtService.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    /** AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard 不在切片中，mock 之 */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    @Autowired
    private JwtService jwtService;

    private ResultActions register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private static String cookieHeader(ResultActions result) {
        return result.andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);
    }

    @Test
    void 注册成功返回201与HttpOnlyCookie() throws Exception {
        when(authService.register(anyString(), anyString()))
                .thenReturn(new AuthService.AuthUser("u-1", "t-1", "alice@example.com"));

        ResultActions result = register("alice@example.com", "password123")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("t-1"));

        String cookie = cookieHeader(result);
        org.assertj.core.api.Assertions.assertThat(cookie).contains("auth_token=");
        org.assertj.core.api.Assertions.assertThat(cookie).contains("HttpOnly");
        org.assertj.core.api.Assertions.assertThat(cookie).contains("SameSite=Lax");

        // cookie 中的 token 必须能被同一 JwtService 验签（签发回路闭环）
        String token = cookie.substring(cookie.indexOf("auth_token=") + 11, cookie.indexOf(';'));
        org.assertj.core.api.Assertions.assertThat(jwtService.verify(token)).isNotNull();
    }

    @Test
    void 重复注册返回409() throws Exception {
        when(authService.register(anyString(), anyString()))
                .thenThrow(new AuthService.EmailExistsException("alice@example.com"));

        register("alice@example.com", "password123")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 登录成功返回200与HttpOnlyCookie() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenReturn(new AuthService.AuthUser("u-1", "t-1", "alice@example.com"));

        ResultActions result = login("alice@example.com", "password123")
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(cookieHeader(result)).contains("HttpOnly");
    }

    @Test
    void 错误凭据返回401且文案不区分存在性() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new AuthService.InvalidCredentialsException());

        login("ghost@example.com", "whatever123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("邮箱或密码错误"));
    }

    @Test
    void 登出清除Cookie() throws Exception {
        ResultActions result = mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(cookieHeader(result))
                .contains("auth_token=")
                .contains("Max-Age=0");
    }
}
