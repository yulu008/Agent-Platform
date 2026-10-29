package com.luyu.agent.rpg.story.controller;

import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.rpg.story.model.SharedStoryGraph;
import com.luyu.agent.rpg.story.service.SharedStoryGraphService;
import com.luyu.agent.service.JwtService;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 共享故事图包端点测试（story-graph-authoring tasks 3.3）。
 * <p>
 * 切片装配仿 {@code StoryGraphWorkshopControllerTest}：service 全 mock，钉住
 * 端点契约——画廊列表（读侧全平台可见）；详情（200 含快照 / 404 不泄露）；
 * 发布（200 {packId, overwrite, nodeCount} / 400 参数与图无效 / 409 未过整稿
 * 链带逐条清单）；导入（200 {graphId, targetWorldId, name} / 400 preset 与
 * 目标无效 / 404 包不在架）；撤回（204 / 404 非发布者）。
 */
@WebMvcTest(SharedStoryGraphController.class)
@Import(JwtService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class SharedStoryGraphControllerTest {

    private static final String BASE_URL = "/rpg/shared-story-graphs";
    private static final String PACK_URL = BASE_URL + "/pack-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private SharedStoryGraphService service;

    /** 切片扫描拉进 AdminWebConfig，其构造依赖 PlatformAdminGuard——mock 兑住装配（同 StoryGraphWorkshopControllerTest） */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    private jakarta.servlet.http.Cookie authCookie() {
        String token = jwtService.issue("u-test", "t-test");
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token);
    }

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(service);
    }

    private static SharedStoryGraph pack() {
        SharedStoryGraph pack = new SharedStoryGraph();
        pack.setId("pack-1");
        pack.setName("夜雾纪行");
        pack.setBlurb("夜雾下的分支叙事");
        pack.setPublisherTenantId("t-other");
        pack.setOriginWorldId("w-src");
        pack.setOriginGraphId("sg-1");
        pack.setOriginWorldName("夜雾测试世界");
        pack.setSnapshotJson("{\"improviseMode\":\"free\",\"graphJson\":\"[]\"}");
        pack.setSnapshotVersion(1);
        pack.setNodeCount(4);
        return pack;
    }

    // ===== 画廊 / 详情 =====

    @Test
    void 画廊列表返回全平台在架包() throws Exception {
        when(service.listVisible()).thenReturn(List.of(pack(), pack()));

        mockMvc.perform(get(BASE_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("夜雾纪行"))
                .andExpect(jsonPath("$[0].nodeCount").value(4))
                .andExpect(jsonPath("$[0].originWorldName").value("夜雾测试世界"));
    }

    @Test
    void 详情200含快照与404不泄露() throws Exception {
        when(service.loadPack("pack-1")).thenReturn(pack());
        when(service.loadPack("pack-x")).thenReturn(null);

        mockMvc.perform(get(PACK_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pack-1"))
                .andExpect(jsonPath("$.snapshotJson").value(
                        org.hamcrest.Matchers.containsString("graphJson")));
        mockMvc.perform(get(BASE_URL + "/pack-x").cookie(authCookie()))
                .andExpect(status().isNotFound());
    }

    // ===== 发布 / 覆盖更新 =====

    @Test
    void 发布成功返回包id与节点数() throws Exception {
        when(service.publish("sg-1", "夜雾纪行", "简介"))
                .thenReturn(new SharedStoryGraphService.PublishResult("pack-1", false, 4));

        mockMvc.perform(post(BASE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphId\":\"sg-1\",\"name\":\"夜雾纪行\",\"blurb\":\"简介\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packId").value("pack-1"))
                .andExpect(jsonPath("$.overwrite").value(false))
                .andExpect(jsonPath("$.nodeCount").value(4));
    }

    @Test
    void 发布参数或图无效400() throws Exception {
        when(service.publish("sg-1", "  ", null))
                .thenThrow(new IllegalArgumentException("包名不能为空"));

        mockMvc.perform(post(BASE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphId\":\"sg-1\",\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("包名不能为空"));
    }

    @Test
    void 发布未过整稿链409带逐条清单() throws Exception {
        when(service.publish("sg-1", "夜雾纪行", null))
                .thenThrow(new IllegalStateException("图未通过整稿校验，不能发布（先在工坊修复）:\n"
                        + "幕 act1 锚点 n1_07 无出口边（跨幕对接断裂，引擎拼接边缺失）"));

        mockMvc.perform(post(BASE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphId\":\"sg-1\",\"name\":\"夜雾纪行\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("未通过整稿校验")))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("n1_07")));
    }

    // ===== 导入 =====

    @Test
    void 导入成功返回物化副本id与目标世界() throws Exception {
        when(service.importGraph("pack-1", "world-2"))
                .thenReturn(new SharedStoryGraphService.ImportResult("sg-2", "world-2", "夜雾纪行"));

        mockMvc.perform(post(PACK_URL + "/import").cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"world-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graphId").value("sg-2"))
                .andExpect(jsonPath("$.targetWorldId").value("world-2"))
                .andExpect(jsonPath("$.name").value("夜雾纪行"));
    }

    @Test
    void 导入preset或目标世界无效400() throws Exception {
        when(service.importGraph("pack-1", "preset-builtin"))
                .thenThrow(new IllegalArgumentException("不支持导入到物化预设世界（重物化会清除导入内容），请选择自建世界"));

        mockMvc.perform(post(PACK_URL + "/import").cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"preset-builtin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("物化预设世界")));
    }

    @Test
    void 导入包不存在或已下架404() throws Exception {
        when(service.importGraph("pack-x", "world-2"))
                .thenThrow(new NoSuchElementException("共享故事图不存在或已下架: pack-x"));

        mockMvc.perform(post(BASE_URL + "/pack-x/import").cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"worldId\":\"world-2\"}"))
                .andExpect(status().isNotFound());
    }

    // ===== 撤回 =====

    @Test
    void 撤回成功204与非发布者404() throws Exception {
        when(service.revoke("pack-1")).thenReturn(true);
        mockMvc.perform(delete(PACK_URL).cookie(authCookie()))
                .andExpect(status().isNoContent());

        when(service.revoke("pack-1")).thenReturn(false);
        mockMvc.perform(delete(PACK_URL).cookie(authCookie()))
                .andExpect(status().isNotFound());
        verify(service, never()).publish(any(), any(), any());
    }
}
