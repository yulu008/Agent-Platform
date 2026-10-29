package com.luyu.agent.rpg.controller;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.service.CharacterOriginService;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.rpg.story.engine.StoryRunEngine;
import com.luyu.agent.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code rpg.worldbook.enabled=false} 时世界书端点整体停用（503）的回滚路径测试
 * （add-rpg-worldbook task 3.5 / 6.5，对齐 {@code StarLorebookController} 的开关处理）。
 * <p>
 * 停用检查在每个端点最前，故不触达 service / moderation / quota / LLM。
 */
@WebMvcTest(WorkshopController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgLorebookProperties.class, RpgWorldbookProperties.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789",
        "rpg.worldbook.enabled=false"
})
class WorkshopControllerWorldbookDisabledTest {

    private static final String SAVE_URL = "/rpg/workshop/worldbook";
    private static final String GEN_URL = "/rpg/workshop/generate-worldbook";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private WorkshopService workshopService;

    @MockitoBean
    private WorkshopLlmGenerator llmGenerator;

    @MockitoBean
    private CharacterOriginService originService;

    @MockitoBean
    private ContentModerationGate moderationGate;

    @MockitoBean
    private QuotaGuard quotaGuard;

    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    /** preset-destined-figures：controller 直挂的命定人物只读 repo，mock 兑住切片装配 */
    @MockitoBean
    private RpgDestinedFigureRepository destinedFigureRepo;

    /** story-graph-runtime 4.1：controller 直挂的故事图运行引擎（开局物化），mock 兑住切片装配 */
    @MockitoBean
    private StoryRunEngine storyRunEngine;

    private jakarta.servlet.http.Cookie authCookie() {
        String token = jwtService.issue("u-test", "t-test");
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token);
    }

    @Test
    void 停用时保存返回503且不落库() throws Exception {
        mockMvc.perform(post(SAVE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"world-1\",\"title\":\"钟表城\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").isNotEmpty());

        verify(workshopService, never()).saveWorldbookEntry(any());
    }

    @Test
    void 停用时列条目返回503() throws Exception {
        mockMvc.perform(get(SAVE_URL).cookie(authCookie()).param("worldId", "world-1"))
                .andExpect(status().isServiceUnavailable());

        verify(workshopService, never()).listWorldbookEntries(any());
    }

    @Test
    void 停用时删除返回503() throws Exception {
        mockMvc.perform(delete(SAVE_URL + "/wb-1").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        verify(workshopService, never()).deleteWorldbookEntry(any());
    }

    @Test
    void 停用时生成返回503且不调LLM() throws Exception {
        mockMvc.perform(post(GEN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keywords\":\"钟表城\"}"))
                .andExpect(status().isServiceUnavailable());

        verify(llmGenerator, never()).generateWorldbookEntries(any(), any());
    }
}
