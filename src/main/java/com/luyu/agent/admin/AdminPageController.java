package com.luyu.agent.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理端页面路由（tasks 8.5）。
 * <p>
 * {@code GET /admin} 渲染用量看板/预算/单价维护页。访问控制在
 * {@link AdminAccessInterceptor}：非 {@code platform_admin} 返回 403 页面，未认证由
 * {@code JwtTenantFilter} 302 到登录页。
 */
@Controller
public class AdminPageController {

    @GetMapping("/admin")
    public String adminPage() {
        return "admin";
    }
}
