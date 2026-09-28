package com.luyu.agent.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理端 MVC 装配（tasks 8.1b）：把 {@link AdminAccessInterceptor} 挂到管理端页面与接口路径。
 * <p>
 * 拦截路径：{@code /admin}（页面）、{@code /admin/**}、{@code /api/admin/**}（接口）。
 * 静态资源（{@code /js/**}、{@code /css/**}）不在此列，且 {@code JwtTenantFilter} 已放行。
 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final PlatformAdminGuard adminGuard;

    public AdminWebConfig(PlatformAdminGuard adminGuard) {
        this.adminGuard = adminGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAccessInterceptor(adminGuard))
                .addPathPatterns("/admin", "/admin/**", "/api/admin/**");
    }
}
