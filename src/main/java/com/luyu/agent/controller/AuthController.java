package com.luyu.agent.controller;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.service.AuthService;
import com.luyu.agent.service.JwtService;

/**
 * 认证端点（多租户身份基建）。
 * <p>
 * 登录/注册成功后 JWT 经 HttpOnly + SameSite=Lax cookie 下发
 * （EventSource 原生携带 cookie，SSE 流式端点零前端改造）。
 * 本地 http 部署暂不加 Secure（TLS 收紧为独立变更）。
 */
@RestController
@EnableConfigurationProperties(AuthProperties.class)
public class AuthController {

    /** 认证 cookie 名 */
    public static final String AUTH_COOKIE = "auth_token";

    private final AuthService authService;
    private final JwtService jwtService;
    private final AuthProperties authProperties;

    public AuthController(AuthService authService, JwtService jwtService, AuthProperties authProperties) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.authProperties = authProperties;
    }

    /**
     * 注册：创建租户与管理员用户并直接登录（免二次输入）。
     *
     * @return 201 + Set-Cookie（JWT）；邮箱已注册 409；格式不合规 400
     */
    @PostMapping("/api/auth/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody Map<String, String> body) {
        AuthService.AuthUser user = authService.register(
                body.get("email"), body.get("password"));
        return authResponse(user, HttpStatus.CREATED);
    }

    /**
     * 登录：校验凭据并下发 JWT cookie。
     *
     * @return 200 + Set-Cookie（JWT）；凭据错误 401（不泄露邮箱是否存在）
     */
    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> body) {
        AuthService.AuthUser user = authService.login(
                body.get("email"), body.get("password"));
        return authResponse(user, HttpStatus.OK);
    }

    /**
     * 登出：清除认证 cookie。
     */
    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cookie = ResponseCookie.from(AUTH_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    private ResponseEntity<Map<String, String>> authResponse(AuthService.AuthUser user, HttpStatus status) {
        String token = jwtService.issue(user.userId(), user.tenantId());
        ResponseCookie cookie = ResponseCookie.from(AUTH_COOKIE, token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(authProperties.getTtl())
                .build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(Map.of("email", user.email(), "tenantId", user.tenantId()));
    }

    /** 注册：邮箱已存在 */
    @ExceptionHandler(AuthService.EmailExistsException.class)
    public ResponseEntity<Map<String, String>> emailExists(AuthService.EmailExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** 登录：凭据无效 */
    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> invalidCredentials(AuthService.InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    /** 注册：邮箱格式/密码强度不合规 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
