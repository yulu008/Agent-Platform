package com.luyu.agent.admin;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import com.luyu.agent.tenancy.TenantContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 管理端访问拦截器（tasks 8.1b / design D9）。
 * <p>
 * 挂在 {@code /admin/**}（页面）与 {@code /api/admin/**}（接口）上，在 {@code JwtTenantFilter}
 * 建立租户上下文<b>之后</b>执行：用 {@code TenantContext.getUserId()}（即 JWT 的 sub）回查
 * {@code app_user.user_role}，仅 {@code platform_admin} 放行；其余（含普通租户 admin）403，
 * 上下文缺失 401（正常情况下 Filter 已先拦，此处为双保险）。
 * <p>
 * 分流：接口（非 text/html）返回 JSON {@code {error}}；页面导航（text/html）返回极简 HTML 提示，
 * 避免把 JSON 直接甩给浏览器。
 */
public class AdminAccessInterceptor implements HandlerInterceptor {

    private final PlatformAdminGuard adminGuard;

    public AdminAccessInterceptor(PlatformAdminGuard adminGuard) {
        this.adminGuard = adminGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String userId = TenantContext.getUserId();
        if (userId == null || userId.isBlank()) {
            reject(request, response, HttpServletResponse.SC_UNAUTHORIZED, "未认证或登录已过期");
            return false;
        }
        if (!adminGuard.isPlatformAdmin(userId)) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "无管理端访问权限（仅 platform_admin）");
            return false;
        }
        return true;
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response,
                               int status, String message) throws IOException {
        String accept = request.getHeader("Accept");
        boolean wantsHtml = accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
        response.setStatus(status);
        if (wantsHtml && status == HttpServletResponse.SC_FORBIDDEN) {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                    + "<title>403 · 无权限</title></head>"
                    + "<body style=\"font-family:system-ui,sans-serif;text-align:center;padding-top:96px;color:#333\">"
                    + "<h1 style=\"font-size:56px;margin:0\">403</h1>"
                    + "<p style=\"color:#666\">" + message + "</p>"
                    + "<a href=\"/\" style=\"color:#4a7cff\">返回首页</a></body></html>");
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // message 为固定中文常量，无引号/转义字符，直接内联 JSON 安全
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
