package com.luyu.agent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import com.luyu.agent.admin.PlatformAdminGuard;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * {@link ChatPageController} 路由测试（root-entry-selection 变更）。
 * <p>
 * 钉住：根路径 / 渲染入口选择页视图 home；/chat 渲染主聊天视图 chat。
 * 采用 standaloneSetup，仅校验解析出的视图名，不触发模板渲染。
 */
class ChatPageControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 配带前缀/后缀的视图解析器，避免视图名 chat 与 URL /chat 同名触发循环视图路径
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/templates/");
        viewResolver.setSuffix(".html");
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatPageController(mock(PlatformAdminGuard.class)))
                .setViewResolvers(viewResolver)
                .build();
    }

    @Test
    void 根路径返回入口选择页视图() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"));
    }

    @Test
    void chat路径返回主聊天视图() throws Exception {
        mockMvc.perform(get("/chat"))
                .andExpect(status().isOk())
                .andExpect(view().name("chat"));
    }
}
