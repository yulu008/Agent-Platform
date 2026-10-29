package com.luyu.agent.rpg.controller;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.service.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link StarLorebookController} Web 层测试（star-lorebook tasks 5.1 / 5.2）。
 * <p>
 * 钉住只读端点的核心行为：名录返回（含 NPC 专属星 rollable=false）、
 * 可选 gameStateId 的激活/抢星状态合并、校验拒绝路径、开关停用。
 * 租户上下文由 {@code JwtTenantFilterTest} 覆盖；Repository 层租户列过滤
 * 由 mock 直接体现（跨租户查询返回空，端点降级为空列表而非报错）。
 */
@WebMvcTest(StarLorebookController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgLorebookProperties.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class StarLorebookControllerTest {

    private static final String WORLD_ID = "preset-adventure:t-a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RpgLorebookProperties lorebookProperties;

    @MockitoBean
    private RpgStarIncarnationRepository starRepo;

    @MockitoBean
    private RpgStarIncarnationRuntimeRepository starRuntimeRepo;

    @MockitoBean
    private RpgGameStateRepository stateRepo;

    /** AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard 不在切片中，mock 之 */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    private jakarta.servlet.http.Cookie tenantCookie() {
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, jwtService.issue("u-a", "t-a"));
    }

    @AfterEach
    void restoreEnabled() {
        // 「开关关闭」测试直接改真实 properties bean，恢复默认免污染后续测试
        lorebookProperties.setEnabled(true);
    }

    @Test
    void 返回名录列表含NPC专属星标注() throws Exception {
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(
                incarnation("id-1", "天魁星", "织田信长", "魔王", true),
                incarnation("id-2", "天兽星", "前田庆次", null, false)));

        // 不带 gameStateId：静态名录视图（activated/claimedByPlayer 恒 false）
        mockMvc.perform(get("/rpg/lorebook/{worldId}", WORLD_ID).cookie(tenantCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].starName").value("天魁星"))
                .andExpect(jsonPath("$[0].warlordName").value("织田信长"))
                .andExpect(jsonPath("$[0].lore").value("命定叙事"))
                .andExpect(jsonPath("$[0].aliases").value("魔王"))
                .andExpect(jsonPath("$[0].rollable").value(true))
                .andExpect(jsonPath("$[0].activated").value(false))
                .andExpect(jsonPath("$[0].claimedByPlayer").value(false))
                .andExpect(jsonPath("$[1].starName").value("天兽星"))
                .andExpect(jsonPath("$[1].rollable").value(false));
    }

    @Test
    void 携带gameStateId时合并激活与抢星状态() throws Exception {
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(
                incarnation("id-1", "天魁星", "织田信长", "魔王", true)));
        GameState gs = new GameState();
        gs.setId("gs-1");
        gs.setWorldId(WORLD_ID);
        when(stateRepo.findById("gs-1")).thenReturn(gs);
        // 开局抢星行：claimed=true、activated_turn=0
        StarIncarnationRuntime rt = new StarIncarnationRuntime();
        rt.setStarName("天魁星");
        rt.setClaimedByPlayer(true);
        rt.setActivatedTurn(0);
        when(starRuntimeRepo.findByGameStateId("gs-1")).thenReturn(List.of(rt));

        mockMvc.perform(get("/rpg/lorebook/{worldId}", WORLD_ID)
                        .queryParam("gameStateId", "gs-1").cookie(tenantCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activated").value(true))
                .andExpect(jsonPath("$[0].claimedByPlayer").value(true))
                .andExpect(jsonPath("$[0].activatedTurn").value(0));
    }

    @Test
    void 存档不存在返回404() throws Exception {
        when(stateRepo.findById("gs-x")).thenReturn(null);

        mockMvc.perform(get("/rpg/lorebook/{worldId}", WORLD_ID)
                        .queryParam("gameStateId", "gs-x").cookie(tenantCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 存档不属于该世界返回400() throws Exception {
        GameState gs = new GameState();
        gs.setId("gs-2");
        gs.setWorldId("other-world");
        when(stateRepo.findById("gs-2")).thenReturn(gs);

        mockMvc.perform(get("/rpg/lorebook/{worldId}", WORLD_ID)
                        .queryParam("gameStateId", "gs-2").cookie(tenantCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 名录为空返回空列表() throws Exception {
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());

        mockMvc.perform(get("/rpg/lorebook/{worldId}", WORLD_ID).cookie(tenantCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 开关关闭时端点整体停用503() throws Exception {
        lorebookProperties.setEnabled(false);

        mockMvc.perform(get("/rpg/lorebook/{worldId}", WORLD_ID).cookie(tenantCookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    private static StarIncarnation incarnation(String id, String star, String warlord,
                                                String aliases, boolean rollable) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId(id);
        inc.setStarName(star);
        inc.setWarlordName(warlord);
        inc.setLore("命定叙事");
        inc.setAliases(aliases);
        inc.setRollable(rollable);
        return inc;
    }
}
