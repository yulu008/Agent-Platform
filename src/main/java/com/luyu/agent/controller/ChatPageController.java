package com.luyu.agent.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 聊天页面路由
 * 提供聊天界面的 Thymeleaf 模板渲染入口
 *
 * sessionId 由前端 localStorage 管理（见 chat.js），服务端无需注入
 */
@Controller
public class ChatPageController {

    /**
     * 聊天页面
     * 访问 http://localhost:8080/ 返回 Thymeleaf 渲染的聊天界面
     */
    @GetMapping("/")
    public String chatPage() {
        return "chat";
    }
}
