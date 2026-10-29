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
 * {@code rpg.lorebook.enabled=false} 时工坊冥星轨写端点停用（503）的按轨门控测试
 * （workshop-soft-delete-cascade 5.3，对齐 {@code WorkshopControllerWorldbookDisabledTest}
 * 与 {@code LorebookSetController} 按轨惯例）。
 * <p>
 * 停用检查在每个写端点最前，不触达 service；读端点恒 200 不门控；通用轨不受影响。
 */
@WebMvcTest(WorkshopController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgLorebookProperties.class, RpgWorldbookProperties.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789",
        "rpg.lorebook.enabled=false"
})
class WorkshopControllerStarDisabledTest {

    private static final String STAR_URL = "/rpg/workshop/star";

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
    void 停用时保存冥星模板返回503且不落库() throws Exception {
        mockMvc.perform(post(STAR_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"world-1\",\"starName\":\"天罡星\",\"warlordName\":\"卢灭朱\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").isNotEmpty());

        verify(workshopService, never()).saveStarIncarnation(any());
    }

    @Test
    void 停用时删除冥星模板返回503() throws Exception {
        mockMvc.perform(delete(STAR_URL + "/star-1").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        verify(workshopService, never()).deleteStarIncarnation(any());
    }

    @Test
    void 停用时读端点不受门控照常200() throws Exception {
        // 读端点恒 200（对齐 LorebookSetController 按轨惯例），停用由前端只读降级消化
        mockMvc.perform(get(STAR_URL).cookie(authCookie()).param("worldId", "world-1"))
                .andExpect(status().isOk());
    }

    @Test
    void 冥星轨停用时通用世界书轨不受影响() throws Exception {
        // rpg.lorebook 只管冥星轨；rpg.worldbook 仍启用，通用轨保存照常放行
        mockMvc.perform(post("/rpg/workshop/worldbook").cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"world-1\",\"title\":\"钟表城\",\"content\":\"设定\"}"))
                .andExpect(status().isOk());
    }
}
