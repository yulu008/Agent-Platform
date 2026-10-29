package com.luyu.agent.rpg.controller;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.OriginResult;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /rpg/workshop/character-origin 的命定人物契约测试（preset-destined-figures tasks 4.4）。
 * <p>
 * 钉住 figureKey 治理：非法值/未物化 400、缺 worldId 400、命中档案透传 figure 进星命计算、
 * 缺省路径零改动（不查 repo、透传 null）。切片装配仿 {@code WorkshopControllerOriginTrioTest}。
 */
@WebMvcTest(WorkshopController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgLorebookProperties.class, RpgWorldbookProperties.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class WorkshopControllerDestinedFigureTest {

    private static final String ORIGIN_URL = "/rpg/workshop/character-origin";
    private static final String START_URL = "/rpg/workshop/start";

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

    /** preset-destined-figures：controller 直挂的命定人物只读 repo，mock 兜住切片装配 */
    @MockitoBean
    private RpgDestinedFigureRepository destinedFigureRepo;

    /** story-graph-runtime 4.1：controller 直挂的故事图运行引擎（开局物化），mock 兑住切片装配 */
    @MockitoBean
    private StoryRunEngine storyRunEngine;

    private jakarta.servlet.http.Cookie authCookie() {
        String token = jwtService.issue("u-test", "t-test");
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token);
    }

    private static DestinedFigure kagetora() {
        DestinedFigure f = new DestinedFigure();
        f.setFigureKey("kagetora");
        f.setDisplayName("长尾景虎");
        f.setFaction("saint");
        f.setConstellation("处女座");
        f.setStarRank("Gold");
        return f;
    }

    /** 请求体：extra 为 null 时不带 figureKey/worldId（缺省路径） */
    private static String body(String extra) {
        return """
                {"name":"长尾景虎","gender":"male","birthDate":"1530-01-01",
                 "timeIndex":6,"faction":"saint","rollSeed":0%s}
                """.formatted(extra == null ? "" : "," + extra);
    }

    @Test
    void 人物模式figureKey命中档案透传figure返回200() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(destinedFigureRepo.findByWorldId("w1")).thenReturn(List.of(kagetora()));
        OriginResult result = new OriginResult();
        result.setFaction("saint");
        result.setConstellation("处女座");
        result.setStarRank("Gold");
        result.setFigureKey("kagetora");
        result.setFigureDisplayName("长尾景虎");
        when(originService.calculate(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                any(DestinedFigure.class))).thenReturn(result);

        mockMvc.perform(post(ORIGIN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"figureKey\":\"kagetora\",\"worldId\":\"w1\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figureKey").value("kagetora"))
                .andExpect(jsonPath("$.figureDisplayName").value("长尾景虎"));

        // 档案按 figureKey 命中后原样透传给星命计算（绑定逻辑收敛在 service）
        verify(originService).calculate(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                argThat(f -> f != null && "kagetora".equals(f.getFigureKey())));
    }

    @Test
    void 非法figureKey按库查不到返回400不进星命计算() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(destinedFigureRepo.findByWorldId("w1")).thenReturn(List.of());

        mockMvc.perform(post(ORIGIN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"figureKey\":\"nobody\",\"worldId\":\"w1\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的命定人物: nobody"));

        verifyNoInteractions(originService);
    }

    @Test
    void figureKey非空缺worldId返回400() throws Exception {
        mockMvc.perform(post(ORIGIN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"figureKey\":\"kagetora\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("figureKey 非空时 worldId 不能为空"));

        verifyNoInteractions(originService);
    }

    @Test
    void 缺省figureKey不查repo透传null零改动() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        OriginResult result = new OriginResult();
        result.setFaction("saint");
        result.setConstellation("双子座");
        result.setStarRank("Gold");
        when(originService.calculate(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                isNull())).thenReturn(result);

        mockMvc.perform(post(ORIGIN_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.constellation").value("双子座"));

        // 缺省路径：不查人物 repo，figure 透传 null（现行流程零改动）
        verify(destinedFigureRepo, never()).findByWorldId(anyString());
        verify(originService).calculate(eq("长尾景虎"), eq("male"), any(), eq(6), eq("saint"), eq(0), isNull());
    }

    // ==================== /start 开局（tasks 7.4 后端侧） ====================

    /** 开局请求携带 figureKey（人物模式）→ 按本世界查档案 → 7 参 calculate 落 figure 三元组 */
    @Test
    void 开局figureKey命中档案走七参calculate落figure() throws Exception {
        DestinedFigure nobunaga = new DestinedFigure();
        nobunaga.setFigureKey("nobunaga");
        nobunaga.setDisplayName("织田信长");
        nobunaga.setFaction("specter");
        when(destinedFigureRepo.findByWorldId("w1")).thenReturn(List.of(nobunaga));
        OriginResult result = new OriginResult();
        result.setFigureKey("nobunaga");
        when(originService.calculate(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                argThat(f -> f != null && "nobunaga".equals(f.getFigureKey())))).thenReturn(result);
        GameState gs = new GameState();
        gs.setId("gs-1");
        when(workshopService.startGame(eq("w1"), anyString(), anyString(), same(result), isNull()))
                .thenReturn(gs);

        mockMvc.perform(post(START_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"worldId":"w1","playerCharId":"c1","sessionId":"s1",
                                 "name":"织田信长","gender":"male","birthDate":"1534-05-05",
                                 "timeIndex":6,"faction":"specter","rollSeed":0,
                                 "figureKey":"nobunaga"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("gs-1"));

        // figure 命中档案透传 7 参 calculate，带 figure 的 originResult 落入 startGame（claim 联动链路）
        verify(originService).calculate(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                argThat(f -> f != null && "nobunaga".equals(f.getFigureKey())));
    }

    /** 开局 figureKey 未命中（推演后重物化清库的罕见竞态）→ 400 硬拒，不静默降级无名新人 */
    @Test
    void 开局figureKey未命中返回400不落档() throws Exception {
        when(destinedFigureRepo.findByWorldId("w1")).thenReturn(List.of());

        mockMvc.perform(post(START_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"worldId":"w1","playerCharId":"c1","sessionId":"s1",
                                 "name":"织田信长","gender":"male","birthDate":"1534-05-05",
                                 "timeIndex":6,"faction":"specter",
                                 "figureKey":"ghost"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的命定人物: ghost"));

        verifyNoInteractions(originService);
        verifyNoInteractions(workshopService);
    }

    /** 开局 figureKey 缺省 → 7 参 calculate 传 null（现行流程零改动，请求契约向后兼容） */
    @Test
    void 开局figureKey缺省七参calculate传null零改动() throws Exception {
        OriginResult result = new OriginResult();
        result.setFaction("saint");
        when(originService.calculate(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                isNull())).thenReturn(result);
        GameState gs = new GameState();
        gs.setId("gs-2");
        when(workshopService.startGame(eq("w1"), anyString(), anyString(), same(result), isNull()))
                .thenReturn(gs);

        mockMvc.perform(post(START_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"worldId":"w1","playerCharId":"c1","sessionId":"s1",
                                 "name":"卢煜","gender":"male","birthDate":"1991-03-14",
                                 "timeIndex":6,"faction":"saint","rollSeed":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("gs-2"));

        // 缺省路径：不查人物 repo（现行流程零改动）
        verify(destinedFigureRepo, never()).findByWorldId(anyString());
        verify(originService).calculate(anyString(), anyString(), any(), anyInt(), anyString(), anyInt(),
                isNull());
    }

    // ==================== /start 故事图开局物化（story-graph-runtime 4.1） ====================

    /** 开局带 storyGraphId → startGame 落 GameState 后由 StoryRunEngine 物化运行副本 */
    @Test
    void 开局带storyGraphId触发运行引擎物化() throws Exception {
        GameState gs = new GameState();
        gs.setId("gs-9");
        when(workshopService.startGame(eq("w1"), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(gs);

        mockMvc.perform(post(START_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"worldId":"w1","playerCharId":"c1","sessionId":"s1",
                                 "storyGraphId":"sg-77"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("gs-9"));

        verify(storyRunEngine).bootstrapForGame(gs, "sg-77");
    }

    /** 开局不带 storyGraphId → 引擎零交互（无图 world 行为零增量，spec 钉子） */
    @Test
    void 开局不带storyGraphId引擎零交互现状行为不变() throws Exception {
        GameState gs = new GameState();
        gs.setId("gs-10");
        when(workshopService.startGame(eq("w1"), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(gs);

        mockMvc.perform(post(START_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"worldId":"w1","playerCharId":"c1","sessionId":"s1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("gs-10"));

        verifyNoInteractions(storyRunEngine);
    }

    /** 引擎物化被拒（图不存在/未整稿 IAE）→ 走既有 400 catch，错误信息透传 */
    @Test
    void 开局故事图无效引擎抛IAE映射400() throws Exception {
        GameState gs = new GameState();
        gs.setId("gs-11");
        when(workshopService.startGame(eq("w1"), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(gs);
        when(storyRunEngine.bootstrapForGame(gs, "sg-404"))
                .thenThrow(new IllegalArgumentException("故事图不存在或不可用: sg-404"));

        mockMvc.perform(post(START_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"worldId":"w1","playerCharId":"c1","sessionId":"s1",
                                 "storyGraphId":"sg-404"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("故事图不存在或不可用: sg-404"));
    }
}
