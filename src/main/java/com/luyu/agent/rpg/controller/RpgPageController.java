package com.luyu.agent.rpg.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * RPG 页面 Controller。
 * <p>
 * GET /rpg 返回 rpg.html 模板（Thymeleaf）。
 */
@Controller
public class RpgPageController {

    @GetMapping("/rpg")
    public String rpgPage() {
        return "rpg";
    }
}
