package com.luyu.agent.auth;

import com.luyu.agent.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * JWT 认证拦截器。
 * <p>
 * 解析 {@code Authorization: Bearer <token>} 头，验证 JWT，
 * 提取 {@code userId} 存入 {@link TenantContext}。
 * <p>
 * 公开路径（登录、注册、静态资源）跳过认证。
 * 请求结束后在 {@code finally} 中清理 ThreadLocal。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /** 公开路径前缀（不拦截）：页面路由 + 静态资源 + 认证 API */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/login",
            "/",
            "/rpg",
            "/memory",
            "/css/",
            "/js/",
            "/images/",
            "/vendor/",
            "/favicon.ico"
    );

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws IOException {
        try {
            String path = request.getRequestURI();

            // 公开路径放行
            if (isPublicPath(path)) {
                filterChain.doFilter(request, response);
                return;
            }

            // 解析 Authorization 头
            String authHeader = request.getHeader(AUTH_HEADER);
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"未认证\"}");
                return;
            }

            String token = authHeader.substring(BEARER_PREFIX.length());
            Claims claims = jwtUtil.verify(token);
            if (claims == null) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"token 无效或已过期\"}");
                return;
            }

            // 设置当前用户身份
            String userId = jwtUtil.getUserId(claims);
            TenantContext.set(userId);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.error("JWT 认证过滤器异常: {}", e.getMessage(), e);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"认证失败\"}");
        } finally {
            // 无论成功失败都清理 ThreadLocal，防止线程池复用串号
            TenantContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        for (String prefix : PUBLIC_PATHS) {
            if (path.startsWith(prefix) || path.equals(prefix)) {
                return true;
            }
        }
        return false;
    }
}
