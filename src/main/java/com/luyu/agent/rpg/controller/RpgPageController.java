package com.luyu.agent.rpg.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.service.AuthService;
import com.luyu.agent.tenancy.TenantContext;

/**
 * RPG 页面 Controller。
 * <p>
 * GET /rpg 返回 rpg.html 模板（Thymeleaf）。
 * 全局顶栏管理端入口门控同 homePage（unified-global-topbar D6）。
 */
@Controller
public class RpgPageController {

    private final PlatformAdminGuard adminGuard;
    private final AuthService authService;

    public RpgPageController(PlatformAdminGuard adminGuard, AuthService authService) {
        this.adminGuard = adminGuard;
        this.authService = authService;
    }

    @GetMapping("/rpg")
    public String rpgPage(Model model) {
        model.addAttribute("isPlatformAdmin", adminGuard.isPlatformAdmin(TenantContext.getUserId()));
        model.addAttribute("currentUserEmail", authService.findEmailByUserId(TenantContext.getUserId()));
        return "rpg";
    }
}
