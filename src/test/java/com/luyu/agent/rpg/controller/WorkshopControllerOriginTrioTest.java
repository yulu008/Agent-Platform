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
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.service.CharacterOriginService;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.rpg.story.engine.StoryRunEngine;
import com.luyu.agent.service.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /rpg/workshop/generate-origin-trio 端点测试（preset-llm-identity-trio tasks 1.3）。
 * <p>
 * 钉住治理三段式次序与拒答结构：moderation 先于 quota、quota 先于 LLM（InOrder 验证）；
 * 命中审查 422 + {moderation:true}、配额超限 402、必填缺失 400、解析失败 500 + error 文案。
 * 切片装配仿 {@code WorkshopControllerTenantGuardTest}。
 */
@WebMvcTest(WorkshopController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgLorebookProperties.class, RpgWorldbookProperties.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class WorkshopControllerOriginTrioTest {

    private static final String TRIO_URL = "/rpg/workshop/generate-origin-trio";

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

    private static String trioRequest() {
        return """
                {"name":"卢煜","gender":"male","constellation":"天龙座","starRank":"Bronze",
                 "faction":"saint","zodiacTraits":"坚韧不拔","techniques":["庐山升龙霸"]}""";
    }

    @Test
    void 正常生成返回三字段JSON且次序为moderation先于quota先于LLM() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(llmGenerator.generateOriginTrio(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), isNull()))
                .thenReturn(new WorkshopLlmGenerator.OriginTrio(
                        "天龙座青铜圣斗士", "坚韧沉默", "少年自天龙座星光中觉醒。"));

        mockMvc.perform(post(TRIO_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(trioRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("天龙座青铜圣斗士"))
                .andExpect(jsonPath("$.personality").value("坚韧沉默"))
                .andExpect(jsonPath("$.background").value("少年自天龙座星光中觉醒。"));

        InOrder order = inOrder(moderationGate, quotaGuard, llmGenerator);
        order.verify(moderationGate).check("卢煜");
        order.verify(quotaGuard).check(anyString());
        order.verify(llmGenerator).generateOriginTrio("卢煜", "male", "天龙座",
                "Bronze", "saint", "坚韧不拔", List.of("庐山升龙霸"), null);
    }

    /**
     * 人物模式（preset-destined-figures tasks 6.2）：figureKey + worldId 命中档案 →
     * 姓名覆盖为档案人物名（moderation 复查后端真源人物名）、figure 透传 8 参生成器。
     */
    @Test
    void 人物模式figureKey命中档案姓名覆盖为人物名且figure透传() throws Exception {
        DestinedFigure nobunaga = new DestinedFigure();
        nobunaga.setFigureKey("nobunaga");
        nobunaga.setDisplayName("织田信长");
        when(destinedFigureRepo.findByWorldId("w-1")).thenReturn(List.of(nobunaga));
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(llmGenerator.generateOriginTrio(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new WorkshopLlmGenerator.OriginTrio(
                        "尾张当主织田信长", "桀骜狂放", "尾张大傻瓜的倾奇者。"));

        mockMvc.perform(post(TRIO_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"织田信长","gender":"male","constellation":"天魁星",
                                 "starRank":"Specter-TianGang","faction":"specter","zodiacTraits":"桀骜",
                                 "figureKey":"nobunaga","worldId":"w-1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("尾张当主织田信长"));

        verify(moderationGate).check("织田信长");
        verify(llmGenerator).generateOriginTrio(eq("织田信长"), eq("male"), eq("天魁星"),
                eq("Specter-TianGang"), eq("specter"), eq("桀骜"), any(), eq(nobunaga));
    }

    /**
     * 非法 figureKey（未物化/未知值）400，与 /character-origin 惯例一致：不进治理链不调 LLM。
     */
    @Test
    void 非法figureKey返回400不进治理链不调LLM() throws Exception {
        when(destinedFigureRepo.findByWorldId("w-1")).thenReturn(List.of());

        mockMvc.perform(post(TRIO_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"织田信长","constellation":"天魁星",
                                 "figureKey":"hijikata","worldId":"w-1"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的命定人物: hijikata"));

        verify(moderationGate, never()).check(anyString());
        verify(llmGenerator, never()).generateOriginTrio(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void moderation命中返回422拒答结构且不查配额不调LLM() throws Exception {
        when(moderationGate.check(anyString()))
                .thenReturn(ModerationResult.blocked(Collections.emptySet()));
        when(moderationGate.refusalMessage()).thenReturn("内容不适宜");

        mockMvc.perform(post(TRIO_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(trioRequest()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.moderation").value(true))
                .andExpect(jsonPath("$.error").value("内容不适宜"));

        verify(quotaGuard, never()).check(anyString());
        verify(llmGenerator, never()).generateOriginTrio(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 配额超限返回402且不调LLM() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        doThrow(new QuotaExceededException(1_000_000, 1_000_000))
                .when(quotaGuard).check(anyString());

        mockMvc.perform(post(TRIO_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(trioRequest()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").isNotEmpty());

        verify(llmGenerator, never()).generateOriginTrio(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 必填缺失返回400且不进治理链() throws Exception {
        mockMvc.perform(post(TRIO_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"卢煜\"}"))
                .andExpect(status().isBadRequest());

        verify(moderationGate, never()).check(anyString());
        verify(llmGenerator, never()).generateOriginTrio(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 解析失败返回500与error文案不返回半截() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(llmGenerator.generateOriginTrio(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), isNull()))
                .thenThrow(new RuntimeException("解析三件套失败: 三字段不完整"));

        mockMvc.perform(post(TRIO_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(trioRequest()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("解析三件套失败: 三字段不完整"))
                .andExpect(jsonPath("$.identity").doesNotExist());
    }
}
