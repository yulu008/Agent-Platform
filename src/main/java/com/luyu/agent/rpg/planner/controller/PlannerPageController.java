package com.luyu.agent.rpg.planner.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.service.AuthService;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 策划管线页面 Controller（add-planner-pipeline tasks 6.1）。
 * <p>
 * GET /rpg/planner 返回 planner.html 模板（Thymeleaf）。页面开关不在此处
 * 门控——停用时列表接口返回 503，页面渲染停用横幅（run 列表数据驱动，
 * 页壳始终可渲染）；全局顶栏管理端入口门控同 {@code RpgPageController}
 * （unified-global-topbar D6）。
 */
@Controller
public class PlannerPageController {

    private final PlatformAdminGuard adminGuard;
    private final AuthService authService;

    public PlannerPageController(PlatformAdminGuard adminGuard, AuthService authService) {
        this.adminGuard = adminGuard;
        this.authService = authService;
    }

    @GetMapping("/rpg/planner")
    public String plannerPage(Model model) {
        model.addAttribute("isPlatformAdmin", adminGuard.isPlatformAdmin(TenantContext.getUserId()));
        model.addAttribute("currentUserEmail", authService.findEmailByUserId(TenantContext.getUserId()));
        return "planner";
    }
}
