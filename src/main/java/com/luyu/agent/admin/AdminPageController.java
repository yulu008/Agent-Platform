package com.luyu.agent.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.luyu.agent.service.AuthService;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 管理端页面路由（tasks 8.5）。
 * <p>
 * {@code GET /admin} 渲染用量看板/预算/单价维护页。访问控制在
 * {@link AdminAccessInterceptor}：非 {@code platform_admin} 返回 403 页面，未认证由
 * {@code JwtTenantFilter} 302 到登录页。
 * 全局顶栏管理端入口门控仍走 guard 保持一致（unified-global-topbar D6）。
 */
@Controller
public class AdminPageController {

    private final PlatformAdminGuard adminGuard;
    private final AuthService authService;

    public AdminPageController(PlatformAdminGuard adminGuard, AuthService authService) {
        this.adminGuard = adminGuard;
        this.authService = authService;
    }

    @GetMapping("/admin")
    public String adminPage(Model model) {
        model.addAttribute("isPlatformAdmin", adminGuard.isPlatformAdmin(TenantContext.getUserId()));
        model.addAttribute("currentUserEmail", authService.findEmailByUserId(TenantContext.getUserId()));
        return "admin";
    }
}
