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
import com.luyu.agent.rpg.story.engine.StoryGraphFinalizeReport;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.service.StoryGraphWorkshopService;
import com.luyu.agent.service.JwtService;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工坊故事图端点测试（story-graph-authoring tasks 3.1）。
 * <p>
 * 切片装配仿 {@code StageGateControllerTest}：service 全 mock，钉住端点契约——
 * 建图（200 实体 / 400 名称与模式 / 404 world）；列表；详情 404 不泄露存在性；
 * 保存（200 更新图 / 400 即时校验 errors）；整稿通过（200 报告与修复后图 /
 * 409 errors 逐条定位 / 404）；删除（204 / 409 运行副本守卫 / 404）。
 */
@WebMvcTest(StoryGraphWorkshopController.class)
@Import(JwtService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class StoryGraphWorkshopControllerTest {

    private static final String CREATE_URL = "/rpg/workshop/worlds/world-1/story-graphs";
    private static final String LIST_URL = "/rpg/workshop/worlds/world-1/story-graphs";
    private static final String GRAPH_URL = "/rpg/workshop/story-graphs/sg-1";
    private static final String SAVE_URL = "/rpg/workshop/story-graphs/sg-1";
    private static final String FINALIZE_URL = "/rpg/workshop/story-graphs/sg-1/finalize";
    private static final String DELETE_URL = "/rpg/workshop/story-graphs/sg-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private StoryGraphWorkshopService service;

    /** 切片扫描拉进 AdminWebConfig，其构造依赖 PlatformAdminGuard——mock 兑住装配（同 StageGateControllerTest） */
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

    private static StoryGraph graph() {
        StoryGraph graph = new StoryGraph("sg-1", "t-test", "world-1", "夜雾纪行");
        graph.setImproviseMode(StoryGraph.IMPROVISE_BOUNDED);
        return graph;
    }

    // ===== 建图 / 列表 / 详情 =====

    @Test
    void 建图成功返回实体() throws Exception {
        when(service.create(eq("world-1"), eq("夜雾纪行"), isNull(), isNull(), isNull())).thenReturn(graph());

        mockMvc.perform(post(CREATE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"夜雾纪行\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sg-1"))
                .andExpect(jsonPath("$.name").value("夜雾纪行"))
                .andExpect(jsonPath("$.improviseMode").value("bounded"));
    }

    @Test
    void 建图名称空400() throws Exception {
        when(service.create(eq("world-1"), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("故事图名称不能为空"));

        mockMvc.perform(post(CREATE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("故事图名称不能为空"));
    }

    @Test
    void 建图world不存在404() throws Exception {
        when(service.create(eq("world-x"), any(), any(), any(), any()))
                .thenThrow(new NoSuchElementException("世界不存在：world-x"));

        mockMvc.perform(post("/rpg/workshop/worlds/world-x/story-graphs").cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"夜雾纪行\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 建图即兴深度上限字符串透传() throws Exception {
        // 前端传字符串（Map<String,String> 契约）：optionalInt 解析后透传给 service
        StoryGraph g = graph();
        g.setImproviseDepthLimit(8);
        when(service.create(eq("world-1"), eq("夜雾纪行"), isNull(), eq(8), isNull())).thenReturn(g);

        mockMvc.perform(post(CREATE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"夜雾纪行\",\"improviseDepthLimit\":\"8\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.improviseDepthLimit").value(8));
    }

    @Test
    void 建图即兴深度上限非整数400() throws Exception {
        mockMvc.perform(post(CREATE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"夜雾纪行\",\"improviseDepthLimit\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("必须是整数")));
    }

    @Test
    void 按world列图返回清单() throws Exception {
        when(service.listByWorld("world-1")).thenReturn(List.of(graph(), graph()));

        mockMvc.perform(get(LIST_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("夜雾纪行"));
    }

    @Test
    void 详情与404不泄露存在性() throws Exception {
        when(service.get("sg-1")).thenReturn(graph());
        when(service.get("sg-x")).thenReturn(null);

        mockMvc.perform(get(GRAPH_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sg-1"));
        mockMvc.perform(get("/rpg/workshop/story-graphs/sg-x").cookie(authCookie()))
                .andExpect(status().isNotFound());
    }

    // ===== 保存（即时校验） =====

    @Test
    void 保存成功返回更新图() throws Exception {
        when(service.save(eq("sg-1"), isNull(), isNull(), isNull(), isNull(), eq("[]"))).thenReturn(graph());

        mockMvc.perform(put(SAVE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphJson\":\"[]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("sg-1"));
    }

    @Test
    void 保存即时校验错误400带清单() throws Exception {
        when(service.save(eq("sg-1"), isNull(), isNull(), isNull(), isNull(), any()))
                .thenThrow(new IllegalArgumentException("保存校验未通过（未落库）：\n节点 n1_07 variant[1] 谓词非法"));

        mockMvc.perform(put(SAVE_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"graphJson\":\"[]\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("保存校验未通过")))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("n1_07")));
    }

    // ===== 整稿通过（全图校验链） =====

    @Test
    void 整稿通过200报告与修复后图() throws Exception {
        StoryGraph graph = graph();
        when(service.finalizeGraph("sg-1")).thenReturn(
                new StoryGraphFinalizeReport(List.of(), List.of("警告：死端"), List.of("修复：e1_01"), graph));

        mockMvc.perform(post(FINALIZE_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.warnings[0]").value(
                        org.hamcrest.Matchers.containsString("死端")))
                .andExpect(jsonPath("$.repairs[0]").value(
                        org.hamcrest.Matchers.containsString("e1_01")))
                .andExpect(jsonPath("$.graph.id").value("sg-1"));
    }

    @Test
    void 整稿通过409报告逐条定位() throws Exception {
        when(service.finalizeGraph("sg-1")).thenReturn(new StoryGraphFinalizeReport(
                List.of("幕 act1 锚点 n1_07 无出口边（跨幕对接断裂，引擎拼接边缺失）"),
                List.of(), List.of(), null));

        mockMvc.perform(post(FINALIZE_URL).cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0]").value(
                        org.hamcrest.Matchers.containsString("n1_07")))
                .andExpect(jsonPath("$.graph").doesNotExist());
    }

    @Test
    void 整稿通过404与400坏JSON() throws Exception {
        when(service.finalizeGraph("sg-x")).thenThrow(new NoSuchElementException("故事图不存在"));
        mockMvc.perform(post("/rpg/workshop/story-graphs/sg-x/finalize").cookie(authCookie()))
                .andExpect(status().isNotFound());

        when(service.finalizeGraph("sg-1"))
                .thenThrow(new IllegalArgumentException("graph_json 解析失败：Unexpected character"));
        mockMvc.perform(post(FINALIZE_URL).cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("解析失败")));
    }

    // ===== 删除 =====

    @Test
    void 删图运行副本守卫409() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("该图存在故事运行副本（按存档删除级联），不能直接删除"))
                .when(service).delete("sg-1");

        mockMvc.perform(delete(DELETE_URL).cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("运行副本")));
    }

    @Test
    void 删图成功204与不存在404() throws Exception {
        org.mockito.Mockito.doNothing().when(service).delete("sg-1");
        mockMvc.perform(delete(DELETE_URL).cookie(authCookie()))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.doThrow(new NoSuchElementException("故事图不存在"))
                .when(service).delete("sg-1");
        mockMvc.perform(delete(DELETE_URL).cookie(authCookie()))
                .andExpect(status().isNotFound());
        verify(service, never()).save(any(), any(), any(), any(), any(), any());
    }
}
