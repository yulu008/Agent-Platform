package com.luyu.agent.rpg.controller;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.service.LorebookSetService;
import com.luyu.agent.service.JwtService;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LorebookSetController} Web 层测试（add-rpg-lorebook-sets 6.4）。
 * <p>
 * 钉住副本层端点的三类边界：
 * <ol>
 *   <li><b>按轨门控 503</b>：武将录写操作看 {@code rpg.lorebook.enabled}、通用写操作看
 *       {@code rpg.worldbook.enabled}，关闭即 503 且不触 service；单轨停用不影响另一轨；
 *       读端点（current / import-candidates / sources）恒 200，改由响应标志驱动前端只读降级；</li>
 *   <li><b>孤儿删除语义</b>：service 返回 -2（仍被存档引用）→ 409、-1（不存在）→ 404、正数 → 204；</li>
 *   <li><b>跨租户 404</b>：仓储租户列过滤后自然落空——resolve 返回 null、saveStar/saveEntry 返回 null、
 *       deleteStar/deleteEntry 返回 0 时端点降级为 404（对齐 {@code StarLorebookController}）。</li>
 * </ol>
 */
@WebMvcTest(LorebookSetController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgLorebookProperties.class, RpgWorldbookProperties.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class LorebookSetControllerTest {

    private static final String WORLD_ID = "preset-adventure:t-a";
    private static final String GS_ID = "gs-1";
    private static final String SET_ID = "set-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RpgLorebookProperties lorebookProperties;

    @Autowired
    private RpgWorldbookProperties worldbookProperties;

    @MockitoBean
    private LorebookSetService lorebookSetService;

    @MockitoBean
    private RpgStarIncarnationRuntimeRepository starRuntimeRepo;

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
        worldbookProperties.setEnabled(true);
    }

    // ==================== 读端点恒 200（不门控，标志驱动降级） ====================

    @Test
    void current读端点恒200_双轨停用也返回只读降级标志() throws Exception {
        lorebookProperties.setEnabled(false);
        worldbookProperties.setEnabled(false);
        when(lorebookSetService.resolve(GS_ID)).thenReturn(
                new LorebookSetService.ResolvedView(null, List.of(), List.of()));
        when(starRuntimeRepo.findByGameStateId(GS_ID)).thenReturn(List.of());

        mockMvc.perform(get("/rpg/lorebook-set/current").queryParam("gameStateId", GS_ID).cookie(tenantCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materialized").value(false))
                .andExpect(jsonPath("$.lorebookEnabled").value(false))
                .andExpect(jsonPath("$.worldbookEnabled").value(false));
    }

    @Test
    void sources返回本世界set含孤儿标志() throws Exception {
        when(lorebookSetService.listSources(WORLD_ID)).thenReturn(List.of(
                set(SET_ID, WORLD_ID, GS_ID), set("set-2", WORLD_ID, null)));

        mockMvc.perform(get("/rpg/lorebook-set/sources").queryParam("worldId", WORLD_ID).cookie(tenantCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orphan").value(false))
                .andExpect(jsonPath("$[1].orphan").value(true));
    }

    // ==================== 按轨门控 503 ====================

    @Test
    void 武将录轨停用时保存条目返回503且不触service() throws Exception {
        lorebookProperties.setEnabled(false);

        mockMvc.perform(post("/rpg/lorebook-set/entry").cookie(tenantCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameStateId\":\"gs-1\",\"track\":\"star\",\"star\":{\"starName\":\"天魁星\"}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());

        verify(lorebookSetService, never()).saveStar(any(), any());
    }

    @Test
    void 通用轨停用时保存条目返回503且不触service() throws Exception {
        worldbookProperties.setEnabled(false);

        mockMvc.perform(post("/rpg/lorebook-set/entry").cookie(tenantCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameStateId\":\"gs-1\",\"track\":\"worldbook\",\"worldbook\":{\"title\":\"钟表城\"}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());

        verify(lorebookSetService, never()).saveEntry(any(), any());
    }

    @Test
    void 单轨停用另一轨照常_武将录503时通用轨仍可写() throws Exception {
        lorebookProperties.setEnabled(false);
        WorldbookEntry saved = new WorldbookEntry();
        saved.setId("wb-1");
        when(lorebookSetService.saveEntry(eq(GS_ID), any())).thenReturn(saved);

        mockMvc.perform(post("/rpg/lorebook-set/entry").cookie(tenantCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameStateId\":\"gs-1\",\"track\":\"worldbook\",\"worldbook\":{\"title\":\"钟表城\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wb-1"));
    }

    @Test
    void 武将录轨停用时删除条目返回503() throws Exception {
        lorebookProperties.setEnabled(false);

        mockMvc.perform(delete("/rpg/lorebook-set/entry/inc-1").cookie(tenantCookie())
                        .queryParam("gameStateId", GS_ID).queryParam("track", "star"))
                .andExpect(status().isServiceUnavailable());

        verify(lorebookSetService, never()).deleteStar(any(), any());
    }

    @Test
    void 导入按refs非空的轨门控_武将录停用时starRefs导入503() throws Exception {
        lorebookProperties.setEnabled(false);

        mockMvc.perform(post("/rpg/lorebook-set/import").cookie(tenantCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameStateId\":\"gs-1\",\"starRefs\":[\"天魁星\"],\"entryRefs\":[]}"))
                .andExpect(status().isServiceUnavailable());

        verify(lorebookSetService, never()).importEntries(any(), any(), any());
    }

    // ==================== 孤儿删除 409 / 404 / 204 ====================

    @Test
    void 删除仍被存档引用的set返回409() throws Exception {
        when(lorebookSetService.deleteOrphanSet(SET_ID)).thenReturn(-2);

        mockMvc.perform(delete("/rpg/lorebook-set/{setId}", SET_ID).cookie(tenantCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 删除不存在的set返回404() throws Exception {
        when(lorebookSetService.deleteOrphanSet("nope")).thenReturn(-1);

        mockMvc.perform(delete("/rpg/lorebook-set/{setId}", "nope").cookie(tenantCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 删除孤儿set成功返回204() throws Exception {
        when(lorebookSetService.deleteOrphanSet(SET_ID)).thenReturn(1);

        mockMvc.perform(delete("/rpg/lorebook-set/{setId}", SET_ID).cookie(tenantCookie()))
                .andExpect(status().isNoContent());
    }

    // ==================== 跨租户 / 归属缺失 → 404 ====================

    @Test
    void current存档不存在或跨租户返回404() throws Exception {
        when(lorebookSetService.resolve("gs-x")).thenReturn(null);

        mockMvc.perform(get("/rpg/lorebook-set/current").queryParam("gameStateId", "gs-x").cookie(tenantCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 保存条目目标不属于本存档set返回404() throws Exception {
        when(lorebookSetService.saveStar(eq(GS_ID), any())).thenReturn(null);

        mockMvc.perform(post("/rpg/lorebook-set/entry").cookie(tenantCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameStateId\":\"gs-1\",\"track\":\"star\",\"star\":{\"id\":\"other\",\"starName\":\"天魁星\"}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 删除条目非本set返回404() throws Exception {
        when(lorebookSetService.deleteStar(GS_ID, "inc-x")).thenReturn(0);

        mockMvc.perform(delete("/rpg/lorebook-set/entry/inc-x").cookie(tenantCookie())
                        .queryParam("gameStateId", GS_ID).queryParam("track", "star"))
                .andExpect(status().isNotFound());
    }

    // ==================== 请求校验 ====================

    @Test
    void track非法返回400() throws Exception {
        mockMvc.perform(post("/rpg/lorebook-set/entry").cookie(tenantCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameStateId\":\"gs-1\",\"track\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void importCandidates单轨停用时该轨候选清空() throws Exception {
        lorebookProperties.setEnabled(false);
        when(lorebookSetService.importCandidates(GS_ID)).thenReturn(
                new LorebookSetService.ImportCandidates(
                        List.of(star("天魁星")), List.of(entry("wb-2", "雾都"))));

        // 武将录轨停用 → stars 清空；通用轨照常 → entries 保留（始终 200）
        mockMvc.perform(get("/rpg/lorebook-set/import-candidates").queryParam("gameStateId", GS_ID).cookie(tenantCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stars.length()").value(0))
                .andExpect(jsonPath("$.entries.length()").value(1));
    }

    // ==================== 构造辅助 ====================

    private static LorebookSet set(String id, String worldId, String gameStateId) {
        LorebookSet s = new LorebookSet();
        s.setId(id);
        s.setWorldId(worldId);
        s.setName("世界默认");
        s.setOrigin("世界默认");
        s.setGameStateId(gameStateId);
        return s;
    }

    private static com.luyu.agent.rpg.model.StarIncarnation star(String starName) {
        com.luyu.agent.rpg.model.StarIncarnation inc = new com.luyu.agent.rpg.model.StarIncarnation();
        inc.setId("inc-" + starName);
        inc.setStarName(starName);
        inc.setWarlordName("武将");
        inc.setRollable(true);
        return inc;
    }

    private static WorldbookEntry entry(String id, String title) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId(id);
        e.setTitle(title);
        e.setEnabled(true);
        return e;
    }
}
