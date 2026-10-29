package com.luyu.agent.rpg.controller;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.model.WorldbookEntry;
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

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 世界书 CRUD + LLM 生成端点测试（add-rpg-worldbook task 6.5）。
 * <p>
 * 钉住：save 的 worldId 缺失 400 / 正常 200；list 200；delete 存在 204 / 不存在 404；
 * generate-worldbook 治理三段式（moderation 命中 422、配额超限 402、成功返回草稿数组<b>不落库</b>）。
 * 切片装配仿 {@code WorkshopControllerOriginTrioTest}。
 */
@WebMvcTest(WorkshopController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgLorebookProperties.class, RpgWorldbookProperties.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class WorkshopControllerWorldbookTest {

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

    private static WorldbookEntry entry(String id, String title) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId(id);
        e.setWorldId("world-1");
        e.setTitle(title);
        e.setKeywords(title);
        e.setContent(title + " 的设定");
        e.setEnabled(true);
        e.setSortOrder(0);
        return e;
    }

    // ===== save =====

    @Test
    void 保存条目正常返回200与落库结果() throws Exception {
        when(workshopService.saveWorldbookEntry(any())).thenReturn(entry("wb-1", "钟表城"));

        mockMvc.perform(post(SAVE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"world-1\",\"title\":\"钟表城\",\"keywords\":\"雾都\",\"content\":\"设定\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wb-1"))
                .andExpect(jsonPath("$.title").value("钟表城"));
    }

    @Test
    void 保存条目缺worldId返回400且不落库() throws Exception {
        mockMvc.perform(post(SAVE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"钟表城\"}"))
                .andExpect(status().isBadRequest());

        verify(workshopService, never()).saveWorldbookEntry(any());
    }

    // ===== list =====

    @Test
    void 列世界条目返回200数组() throws Exception {
        when(workshopService.listWorldbookEntries("world-1"))
                .thenReturn(List.of(entry("wb-1", "钟表城"), entry("wb-2", "齿轮塔")));

        mockMvc.perform(get(SAVE_URL).cookie(authCookie()).param("worldId", "world-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("钟表城"));
    }

    // ===== delete =====

    @Test
    void 删除存在条目返回204() throws Exception {
        when(workshopService.deleteWorldbookEntry("wb-1")).thenReturn(true);

        mockMvc.perform(delete(SAVE_URL + "/wb-1").cookie(authCookie()))
                .andExpect(status().isNoContent());
    }

    @Test
    void 删除不存在条目返回404() throws Exception {
        when(workshopService.deleteWorldbookEntry("wb-x")).thenReturn(false);

        mockMvc.perform(delete(SAVE_URL + "/wb-x").cookie(authCookie()))
                .andExpect(status().isNotFound());
    }

    // ===== generate-worldbook（治理三段式 + 草稿不落库）=====

    @Test
    void 生成草稿成功返回数组且不落库() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(llmGenerator.generateWorldbookEntries(anyString(), any()))
                .thenReturn(List.of(entry(null, "钟表城"), entry(null, "齿轮塔")));

        mockMvc.perform(post(GEN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keywords\":\"钟表城 齿轮塔\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("钟表城"));

        // 关键：草稿仅返回前端预填，绝不自动落库
        verify(workshopService, never()).saveWorldbookEntry(any());
    }

    @Test
    void 生成命中审查返回422且不调LLM() throws Exception {
        when(moderationGate.check(anyString()))
                .thenReturn(ModerationResult.blocked(Collections.emptySet()));
        when(moderationGate.refusalMessage()).thenReturn("内容不适宜");

        mockMvc.perform(post(GEN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keywords\":\"违禁词\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.moderation").value(true))
                .andExpect(jsonPath("$.error").value("内容不适宜"));

        verify(quotaGuard, never()).check(anyString());
        verify(llmGenerator, never()).generateWorldbookEntries(anyString(), any());
    }

    @Test
    void 生成配额超限返回402且不调LLM() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        doThrow(new QuotaExceededException(1_000_000, 1_000_000))
                .when(quotaGuard).check(anyString());

        mockMvc.perform(post(GEN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keywords\":\"钟表城\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").isNotEmpty());

        verify(llmGenerator, never()).generateWorldbookEntries(anyString(), any());
    }

    @Test
    void 生成缺keywords返回400() throws Exception {
        mockMvc.perform(post(GEN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(llmGenerator, never()).generateWorldbookEntries(anyString(), any());
    }
}
