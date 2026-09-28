package com.luyu.agent.tenancy;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.luyu.agent.controller.AuthController;
import com.luyu.agent.service.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT 租户认证过滤器（最高优先级）。
 * <p>
 * 职责链：验 cookie 中的 JWT → 建立 {@link TenantContext} → 业务链路 → finally 清理。
 * 认证失败分流：浏览器导航（Accept: text/html）302 到登录页；
 * XHR / API / SSE 请求返回 401（EventSource 原生携带 cookie，过期即在此终止）。
 * <p>
 * 放行白名单：登录页、认证端点、静态资源、错误转发与健康检查。
 * 刻意不放行 /h2-console 与其余 actuator 端点（生产安全优先，本地调试可临时调整）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JwtTenantFilter extends OncePerRequestFilter {

    private static final String[] PUBLIC_PATHS = {
            "/login",
            "/api/auth/",
            "/css/",
            "/js/",
            "/vendor/",
            "/images/",
            "/favicon.ico",
            "/error",
            "/actuator/health"
    };

    private static final String LOGIN_PAGE = "/login";

    private final JwtService jwtService;

    public JwtTenantFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isPublic(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractCookie(request, AuthController.AUTH_COOKIE);
        JwtService.Claims claims = jwtService.verify(token);
        if (claims == null) {
            reject(request, response);
            return;
        }

        try {
            TenantContext.set(claims.userId(), claims.tenantId());
            filterChain.doFilter(request, response);
        } finally {
            // 线程池复用防线：无论业务链路如何返回，上下文必须清理
            TenantContext.clear();
        }
    }

    private static boolean isPublic(String uri) {
        for (String path : PUBLIC_PATHS) {
            if (uri.equals(path) || uri.startsWith(path)) {
                return true;
            }
        }
        return false;
    }

    private static String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** 认证失败分流：页面导航 302，接口调用 401 */
    private static void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            response.sendRedirect(LOGIN_PAGE);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"未认证或登录已过期\"}");
    }
}
