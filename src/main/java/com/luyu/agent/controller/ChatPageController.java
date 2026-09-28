package com.luyu.agent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 平台页面路由
 * 提供入口选择页与聊天界面的 Thymeleaf 模板渲染入口
 *
 * sessionId 由前端 localStorage 管理（见 chat.js），服务端无需注入
 */
@Controller
public class ChatPageController {

    private final PlatformAdminGuard adminGuard;

    public ChatPageController(PlatformAdminGuard adminGuard) {
        this.adminGuard = adminGuard;
    }

    /**
     * 入口选择页（root-entry-selection）
     * 访问 http://localhost:8080/ 返回统一入口页，供用户选择进入主会话或 RPG 首页。
     * 管理端入口仅对 {@code platform_admin} 展示（tenant-token-metering / design D9）。
     */
    @GetMapping("/")
    public String homePage(Model model) {
        model.addAttribute("isPlatformAdmin", adminGuard.isPlatformAdmin(TenantContext.getUserId()));
        return "home";
    }

    /**
     * 聊天页面
     * 访问 http://localhost:8080/chat 返回 Thymeleaf 渲染的聊天界面
     */
    @GetMapping("/chat")
    public String chatPage() {
        return "chat";
    }
}
