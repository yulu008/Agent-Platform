package com.luyu.agent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 记忆管理页面路由
 *
 * 提供 /memories-page 独立页面的 Thymeleaf 模板渲染入口
 */
@Controller
public class MemoryPageController {

    /**
     * 记忆管理页面
     * 访问 http://localhost:8080/memories-page 返回独立记忆管理界面
     */
    @GetMapping("/memories-page")
    public String memoryPage() {
        return "memory";
    }
}
