package com.luyu.agent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 认证页面入口。
 * <p>
 * 未登录访问任何受保护页面会被 JwtTenantFilter 302 到此页。
 */
@Controller
public class AuthPageController {

    /**
     * 登录/注册页
     * 访问 http://localhost:8080/login
     */
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}
