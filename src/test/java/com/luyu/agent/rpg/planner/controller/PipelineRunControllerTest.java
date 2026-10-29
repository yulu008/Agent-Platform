package com.luyu.agent.rpg.planner.controller;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.planner.config.RpgPlannerProperties;
import com.luyu.agent.rpg.planner.engine.StageGenerationService;
import com.luyu.agent.rpg.planner.engine.StoryGraphGenerationService;
import com.luyu.agent.rpg.planner.model.PipelineRun;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.RunDetail;
import com.luyu.agent.rpg.planner.service.HtmlSourceService;
import com.luyu.agent.rpg.planner.service.PipelineRunService;
import com.luyu.agent.rpg.planner.service.PlannerWorldInstaller;
import com.luyu.agent.service.JwtService;

import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * run 生命周期端点测试（add-planner-pipeline tasks 2.1-2.6 + 4.6）。
 * <p>
 * 钉住：配置开关 503（tasks 2.6）；创建治理前置（必填 400 / moderation 命中 422
 * 且零副作用，spec「必填校验」「创建输入过内容审查」）；列表 200；详情 200/404/409；
 * 放弃 204/404/409；版本历史与 diff 查询 200/400/404（tasks 4.6，只读
 * 与 run 详情同族）。切片装配仿 {@code WorkshopControllerWorldbookTest}。
 */
@WebMvcTest(PipelineRunController.class)
@Import({JwtService.class, HtmlSourceService.class})
@EnableConfigurationProperties({AuthProperties.class, RpgPlannerProperties.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class PipelineRunControllerTest {

    private static final String RUNS_URL = "/rpg/planner/runs";

    /** ip_name 用例 HTML 样例（真实清洗 → h1 标题行 + p 块级换行） */
    private static final String IP_NAME_HTML = "<html><body><h1>三体</h1><p>面壁计划</p></body></html>";

    /** IP_NAME_HTML 经 HtmlSourceService.clean 的精确清洗文本（@Import 真实例，钉住 D4 契约） */
    private static final String IP_NAME_CLEANED = "# 三体\n\n面壁计划";

    private static final byte[] IP_NAME_BYTES = IP_NAME_HTML.getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RpgPlannerProperties plannerProperties;

    @MockitoBean
    private PipelineRunService runService;

    @MockitoBean
    private StageGenerationService stageGenerationService;

    /** S4.5 幕级生成编排（story-graph-authoring tasks 2.3 注入 controller） */
    @MockitoBean
    private StoryGraphGenerationService storyGraphGenerationService;

    @MockitoBean
    private ContentModerationGate moderationGate;

    @MockitoBean
    private PlannerWorldInstaller worldInstaller;

    /** 切片扫描拉进 AdminWebConfig，其构造依赖 PlatformAdminGuard——mock 兑住装配（同 WorkshopController 系列测试） */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    @BeforeEach
    void resetSwitch() {
        plannerProperties.setEnabled(true);
    }

    private jakarta.servlet.http.Cookie authCookie() {
        String token = jwtService.issue("u-test", "t-test");
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE, token);
    }

    /** 一份合法 HTML 资料页 multipart 文件（字段名 pages，与创建端点表单契约对齐）。 */
    private MockMultipartFile htmlPage(String filename) {
        return new MockMultipartFile("pages", filename, "text/html", IP_NAME_BYTES);
    }

    // ===== 配置开关门控（tasks 2.6：enabled=false 时全部管线端点 503） =====

    @Test
    void 开关关闭时全部端点返回503且不进service() throws Exception {
        plannerProperties.setEnabled(false);

        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .param("sourceMode", "text")
                        .param("styleMode", "faithful")
                        .param("text", "原文"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("策划管线功能已停用（rpg.planner.enabled=false）"));

        mockMvc.perform(get(RUNS_URL).cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(get(RUNS_URL + "/run-1").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post(RUNS_URL + "/run-1/abandon").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        // 硬删除（planner-run-delete tasks 1.4）：写端点同样受开关门控 503
        mockMvc.perform(delete(RUNS_URL + "/run-1").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());
        verify(runService, never()).deleteRun(anyString());

        // SSE 生成端点：开关停用时以 error 事件 + [DONE] 收流（连接已建立，状态码不可达）。
        // 断言用 ASCII 片段：MockMvc 模拟响应默认 ISO-8859-1（中文假象，生产 SSE 走 UTF-8）
        MvcResult sseStart = mockMvc.perform(post(RUNS_URL + "/run-1/stages/S1/generate")
                        .cookie(authCookie()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(sseStart))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rpg.planner.enabled=false")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(runService, never()).listRuns();
        verify(stageGenerationService, never()).generateStage(anyString(), anyString());

        // 版本历史 / diff 查询（tasks 4.6）：只读端点同样受开关门控 503
        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/versions").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/diff").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());
        verify(runService, never()).listStageVersions(anyString(), any());
        verify(runService, never()).diffStageVersions(anyString(), any(), any(), any());

        // 成稿安装（tasks 5.1）：写端点同样受开关门控 503
        mockMvc.perform(post(RUNS_URL + "/run-1/install").cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());
        verify(worldInstaller, never()).install(anyString());
    }

    // ===== 阶段生成 SSE（tasks 3.1：开关开启时委派 service） =====

    @Test
    void SSE生成端点委派service并透传事件流() throws Exception {
        when(stageGenerationService.generateStage("run-1", "S1"))
                .thenReturn(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "stage_start", "stage", "S1"))
                                .build(),
                        ServerSentEvent.<Object>builder().data("[DONE]").build()));

        MvcResult started = mockMvc.perform(post(RUNS_URL + "/run-1/stages/S1/generate")
                        .cookie(authCookie()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stage_start")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(stageGenerationService).generateStage("run-1", "S1");
    }

    @Test
    void S45通用生成端点转发幕级编排() throws Exception {
        // tasks 5.1（planner-s1-chained-refinement）：前端生成入口唯一，S4.5 首轮
        // 转发 generateActs(runId, null)——修复「门要求 AWAITING_CONFIRM 而生成入口
        // 不可达」的 UI 死锁；不走通用 generateStage（无 S4.5 生成器）
        when(storyGraphGenerationService.generateActs("run-1", null))
                .thenReturn(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "stage_start", "stage", "S4.5"))
                                .build(),
                        ServerSentEvent.<Object>builder().data("[DONE]").build()));

        MvcResult started = mockMvc.perform(post(RUNS_URL + "/run-1/stages/S4.5/generate")
                        .cookie(authCookie()).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stage_start")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(storyGraphGenerationService).generateActs("run-1", null);
        verify(stageGenerationService, never()).generateStage(anyString(), anyString());
    }

    // ===== 创建（tasks 2.1 + planner-html-source 3.1/3.2：multipart 表单 + pages 校验链） =====

    @Test
    void text模式创建成功返回run() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(runService.createRun(anyString(), anyString(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(run("run-1", "text", "faithful", "S1", "CREATED"));

        // multipart 表单（D1）：text 模式无文件时表单字段语义与旧 JSON 端点逐字段等价
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .param("sourceMode", "text")
                        .param("styleMode", "faithful")
                        .param("text", "一段小说原文"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.sourceMode").value("text"))
                .andExpect(jsonPath("$.currentStage").value("S1"))
                .andExpect(jsonPath("$.status").value("CREATED"));

        verify(moderationGate).check("一段小说原文");
        verify(runService).createRun("text", "faithful", "一段小说原文", null, null, null);
    }

    @Test
    void ip_name模式创建成功且清洗文本与IP名一并送审() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(runService.createRun(anyString(), anyString(), isNull(), anyString(), isNull(), anyList()))
                .thenReturn(run("run-2", "ip_name", "remix", "S1", "CREATED"));

        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .file(htmlPage("page-01.html"))
                        .param("sourceMode", "ip_name")
                        .param("styleMode", "remix")
                        .param("ipName", "三体"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-2"));

        // @Import 真实 HtmlSourceService（纯函数无依赖）：h1 → # 标题行 + p 块级换行；
        // D7 送审面 = IP 名 + "\n" + 逐份清洗文本
        verify(moderationGate).check("三体\n" + IP_NAME_CLEANED);
        // SourcePage 是 record 且 byte[] equals 为引用比较（getBytes 返回 clone），
        // 精确断言走 argThat：原始字节原样透传 + 清洗文本即 clean() 输出
        verify(runService).createRun(eq("ip_name"), eq("remix"), isNull(), eq("三体"), isNull(),
                argThat(pages -> pages.size() == 1
                        && Arrays.equals(pages.get(0).htmlBytes(), IP_NAME_BYTES)
                        && IP_NAME_CLEANED.equals(pages.get(0).cleanedText())));
    }

    @Test
    void ip_name多页创建每份独立送审() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(runService.createRun(anyString(), anyString(), isNull(), anyString(), isNull(), anyList()))
                .thenReturn(run("run-3", "ip_name", "faithful", "S1", "CREATED"));

        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .file(htmlPage("page-01.html"))
                        .file(htmlPage("page-02.html"))
                        .param("sourceMode", "ip_name")
                        .param("styleMode", "faithful")
                        .param("ipName", "三体"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-3"));

        // D7：每份独立送审限制单次输入规模（2 份 → 2 次同参调用）
        verify(moderationGate, times(2)).check("三体\n" + IP_NAME_CLEANED);
    }

    @Test
    void 资料页非html扩展名返回400且零副作用() throws Exception {
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .file(new MockMultipartFile("pages", "page-01.txt", "text/plain", IP_NAME_BYTES))
                        .param("sourceMode", "ip_name")
                        .param("styleMode", "faithful")
                        .param("ipName", "三体"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("资料页须为 .html/.htm 文件（浏览器另存 HTML）: page-01.txt"));

        // 扩展名校验在清洗与送审之前（D2 链序）
        verify(moderationGate, never()).check(anyString());
        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ip_name模式无资料页返回400() throws Exception {
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .param("sourceMode", "ip_name")
                        .param("styleMode", "faithful")
                        .param("ipName", "三体"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "ip_name 模式下 HTML 资料页不能为空（至少 1 份，请上传浏览器另存的 HTML）"));

        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 资料页空文件返回400且零副作用() throws Exception {
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .file(new MockMultipartFile("pages", "empty.html", "text/html", new byte[0]))
                        .param("sourceMode", "ip_name")
                        .param("styleMode", "faithful")
                        .param("ipName", "三体"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("资料页文件不能为空: empty.html"));

        verify(moderationGate, never()).check(anyString());
        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 资料页超数量上限返回400且零副作用() throws Exception {
        // 上限调小免传 6 份（对齐 text 超硬上限用例的调小惯例）
        plannerProperties.setMaxSourcePages(2);
        try {
            mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                            .file(htmlPage("page-01.html"))
                            .file(htmlPage("page-02.html"))
                            .file(htmlPage("page-03.html"))
                            .param("sourceMode", "ip_name")
                            .param("styleMode", "faithful")
                            .param("ipName", "三体"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            "HTML 资料页最多 2 份（当前 3 份，配置 rpg.planner.max-source-pages）"));
        } finally {
            plannerProperties.setMaxSourcePages(5);
        }

        verify(moderationGate, never()).check(anyString());
        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 资料页单份超大小上限返回413且零副作用() throws Exception {
        // 语义闸调小（真实上限 2MB）：校验链主动 413，先于清洗与送审
        plannerProperties.setMaxPageBytes(8);
        try {
            mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                            .file(htmlPage("page-01.html"))
                            .param("sourceMode", "ip_name")
                            .param("styleMode", "faithful")
                            .param("ipName", "三体"))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.error").value(
                            "HTML 资料页单份超过大小上限（rpg.planner.max-page-bytes）: page-01.html"));
        } finally {
            plannerProperties.setMaxPageBytes(2 * 1024 * 1024);
        }

        verify(moderationGate, never()).check(anyString());
        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void text模式携带资料页返回400() throws Exception {
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .file(htmlPage("page-01.html"))
                        .param("sourceMode", "text")
                        .param("styleMode", "faithful")
                        .param("text", "一段小说原文"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("text 模式不支持 HTML 资料页（仅 ip_name 模式）"));

        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void sourceMode缺失返回400() throws Exception {
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .param("styleMode", "faithful"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("sourceMode 与 styleMode 不能为空"));

        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void text模式原文缺失返回400() throws Exception {
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .param("sourceMode", "text")
                        .param("styleMode", "faithful"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("text 模式下原文不能为空"));

        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void text原文超硬上限返回400且零副作用() throws Exception {
        // 上限调小免构 50 万字 payload（planner-s1-chained-refinement tasks 3.2，
        // spec「超硬上限被拒」）：400 在 moderation 与落库之前，无 run 落库落盘
        plannerProperties.setMaxSourceChars(10);
        try {
            mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                            .param("sourceMode", "text")
                            .param("styleMode", "faithful")
                            .param("text", "0123456789A"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(containsString("超出字数硬上限")));
        } finally {
            plannerProperties.setMaxSourceChars(500_000);
        }

        verify(moderationGate, never()).check(anyString());
        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ip_name模式IP名缺失返回400() throws Exception {
        // 带 1 份文件更接近真实场景：必填校验先于 pages 链（ipName 必填在最前）
        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .file(htmlPage("page-01.html"))
                        .param("sourceMode", "ip_name")
                        .param("styleMode", "faithful"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ip_name 模式下 IP 名不能为空"));

        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 未知模式码经service校验返回400() throws Exception {
        when(moderationGate.check(anyString())).thenReturn(ModerationResult.pass());
        when(runService.createRun(anyString(), anyString(), anyString(), isNull(), isNull(), isNull()))
                .thenThrow(new IllegalArgumentException("未知的管线载体模式: xxx"));

        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .param("sourceMode", "xxx")
                        .param("styleMode", "faithful")
                        .param("text", "原文"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的管线载体模式: xxx"));
    }

    @Test
    void 审查命中返回422且零副作用不创建run() throws Exception {
        when(moderationGate.check(anyString()))
                .thenReturn(ModerationResult.blocked(Collections.emptySet()));
        when(moderationGate.refusalMessage()).thenReturn("内容不适宜");

        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .param("sourceMode", "text")
                        .param("styleMode", "faithful")
                        .param("text", "违禁词"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.moderation").value(true))
                .andExpect(jsonPath("$.error").value("内容不适宜"));

        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void ip_name审查命中返回422且零副作用不创建run() throws Exception {
        // D7：逐份送审命中即拒，422 拒答结构体与 text 版同构
        when(moderationGate.check(anyString()))
                .thenReturn(ModerationResult.blocked(Collections.emptySet()));
        when(moderationGate.refusalMessage()).thenReturn("内容不适宜");

        mockMvc.perform(multipart(RUNS_URL).cookie(authCookie())
                        .file(htmlPage("page-01.html"))
                        .param("sourceMode", "ip_name")
                        .param("styleMode", "faithful")
                        .param("ipName", "三体"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.moderation").value(true))
                .andExpect(jsonPath("$.error").value("内容不适宜"));

        verify(runService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    // ===== 列表（tasks 2.2） =====

    @Test
    void 列表返回本租户全部run() throws Exception {
        when(runService.listRuns()).thenReturn(List.of(
                run("run-1", "text", "faithful", "S3", "AWAITING"),
                run("run-2", "ip_name", "remix", "S6", "COMPLETED")));

        mockMvc.perform(get(RUNS_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("run-1"))
                .andExpect(jsonPath("$[0].status").value("AWAITING"));
    }

    // ===== 详情（tasks 2.3：跨天恢复渲染） =====

    @Test
    void 详情返回manifest渲染的阶段视图() throws Exception {
        RunDetail detail = new RunDetail();
        detail.setId("run-1");
        detail.setName("三体拆解");
        detail.setStatus("AWAITING");
        detail.setCurrentStage("S3");
        RunDetail.StageView view = new RunDetail.StageView();
        view.setCode("S3");
        view.setLabel("人物设定");
        view.setGate("L2");
        view.setStatus("AWAITING_CONFIRM");
        view.setVersion(2);
        detail.getStages().add(view);

        when(runService.getRunDetail("run-1")).thenReturn(detail);

        mockMvc.perform(get(RUNS_URL + "/run-1").cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.currentStage").value("S3"))
                .andExpect(jsonPath("$.stages[0].code").value("S3"))
                .andExpect(jsonPath("$.stages[0].gate").value("L2"))
                .andExpect(jsonPath("$.stages[0].version").value(2));
    }

    @Test
    void 详情不存在返回404() throws Exception {
        when(runService.getRunDetail("run-x"))
                .thenThrow(new NoSuchElementException("管线 run 不存在: run-x"));

        mockMvc.perform(get(RUNS_URL + "/run-x").cookie(authCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("管线 run 不存在: run-x"));
    }

    @Test
    void 详情工作区manifest缺失返回409() throws Exception {
        when(runService.getRunDetail("run-1"))
                .thenThrow(new IllegalStateException("run 工作区 manifest 缺失，数据可能已损坏: run-1"));

        mockMvc.perform(get(RUNS_URL + "/run-1").cookie(authCookie()))
                .andExpect(status().isConflict());
    }

    // ===== 放弃（tasks 2.4） =====

    @Test
    void 放弃成功返回204() throws Exception {
        mockMvc.perform(post(RUNS_URL + "/run-1/abandon").cookie(authCookie()))
                .andExpect(status().isNoContent());

        verify(runService).abandonRun("run-1");
    }

    @Test
    void 放弃不存在返回404() throws Exception {
        org.mockito.Mockito.doThrow(new NoSuchElementException("管线 run 不存在: run-x"))
                .when(runService).abandonRun("run-x");

        mockMvc.perform(post(RUNS_URL + "/run-x/abandon").cookie(authCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 放弃终态run返回409() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("非法的 run 状态转换: COMPLETED → ABANDONED"))
                .when(runService).abandonRun("run-1");

        mockMvc.perform(post(RUNS_URL + "/run-1/abandon").cookie(authCookie()))
                .andExpect(status().isConflict());
    }

    // ===== 硬删除（planner-run-delete tasks 1.5） =====

    @Test
    void 删除成功返回204() throws Exception {
        mockMvc.perform(delete(RUNS_URL + "/run-1").cookie(authCookie()))
                .andExpect(status().isNoContent());

        verify(runService).deleteRun("run-1");
    }

    @Test
    void 删除不存在返回404() throws Exception {
        org.mockito.Mockito.doThrow(new NoSuchElementException("管线 run 不存在: run-x"))
                .when(runService).deleteRun("run-x");

        mockMvc.perform(delete(RUNS_URL + "/run-x").cookie(authCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 删除生成中run返回409() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("run 正在生成中，请等待完成后再删除（活跃流守卫）"))
                .when(runService).deleteRun("run-1");

        mockMvc.perform(delete(RUNS_URL + "/run-1").cookie(authCookie()))
                .andExpect(status().isConflict());
    }

    // ===== 版本历史与 diff（tasks 4.6） =====

    @Test
    void 版本历史返回版本列表与反馈记录() throws Exception {
        when(runService.listStageVersions("run-1", PipelineStage.S3_CHARACTERS))
                .thenReturn(Map.of(
                        "stage", "S3",
                        "versions", List.of(1, 2, 3),
                        "rounds", List.of(Map.of("round", 1, "feedback", "反馈甲"))));

        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/versions").cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S3"))
                .andExpect(jsonPath("$.versions.length()").value(3))
                .andExpect(jsonPath("$.versions[0]").value(1))
                .andExpect(jsonPath("$.rounds[0].feedback").value("反馈甲"));

        verify(runService).listStageVersions("run-1", PipelineStage.S3_CHARACTERS);
    }

    @Test
    void 版本快照回看返回全文() throws Exception {
        when(runService.readStageVersion("run-1", PipelineStage.S3_CHARACTERS, 2))
                .thenReturn(Map.of("stage", "S3", "version", 2, "content", "## 人物 1：甲\n性格：狡黠"));

        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/versions/2").cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S3"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.content").value("## 人物 1：甲\n性格：狡黠"));
    }

    @Test
    void 版本快照不存在400() throws Exception {
        when(runService.readStageVersion("run-1", PipelineStage.S3_CHARACTERS, 9))
                .thenThrow(new IllegalArgumentException("版本快照不存在: v9（阶段 S3）"));

        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/versions/9").cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("版本快照不存在: v9（阶段 S3）"));
    }

    @Test
    void diff缺省相邻两版返回行级条目() throws Exception {
        when(runService.diffStageVersions("run-1", PipelineStage.S3_CHARACTERS, null, null))
                .thenReturn(Map.of(
                        "stage", "S3", "from", 2, "to", 3,
                        "lines", List.of(
                                Map.of("type", "context", "text", "## 人物 1：甲"),
                                Map.of("type", "removed", "text", "性格：沉稳"),
                                Map.of("type", "added", "text", "性格：狡黠"))));

        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/diff").cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S3"))
                .andExpect(jsonPath("$.from").value(2))
                .andExpect(jsonPath("$.to").value(3))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].type").value("context"))
                .andExpect(jsonPath("$.lines[1].type").value("removed"))
                .andExpect(jsonPath("$.lines[2].type").value("added"));

        verify(runService).diffStageVersions("run-1", PipelineStage.S3_CHARACTERS, null, null);
    }

    @Test
    void diff显式from与to透传() throws Exception {
        when(runService.diffStageVersions("run-1", PipelineStage.S3_CHARACTERS, 1, 2))
                .thenReturn(Map.of("stage", "S3", "from", 1, "to", 2, "lines", List.of()));

        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/diff")
                        .queryParam("from", "1").queryParam("to", "2").cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(1))
                .andExpect(jsonPath("$.to").value(2));

        verify(runService).diffStageVersions("run-1", PipelineStage.S3_CHARACTERS, 1, 2);
    }

    @Test
    void diff不足两版400() throws Exception {
        when(runService.diffStageVersions("run-1", PipelineStage.S3_CHARACTERS, null, null))
                .thenThrow(new IllegalArgumentException("该阶段暂无相邻版本可对比（需至少两个版本快照）"));

        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S3/diff").cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该阶段暂无相邻版本可对比（需至少两个版本快照）"));
    }

    @Test
    void 版本查询未知阶段码400() throws Exception {
        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S9/versions").cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的管线阶段码: S9"));

        mockMvc.perform(get(RUNS_URL + "/run-1/stages/S9/diff").cookie(authCookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 版本查询run不存在404() throws Exception {
        when(runService.listStageVersions("run-x", PipelineStage.S3_CHARACTERS))
                .thenThrow(new NoSuchElementException("管线 run 不存在: run-x"));

        mockMvc.perform(get(RUNS_URL + "/run-x/stages/S3/versions").cookie(authCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("管线 run 不存在: run-x"));
    }

    // ===== 成稿安装（tasks 5.1） =====

    @Test
    void 安装成功返回新世界定位与规模() throws Exception {
        when(worldInstaller.install("run-1"))
                .thenReturn(new PlannerWorldInstaller.InstallResult(
                        "world-uuid-1", "冥星武将录", 12, "graph-uuid-1", 27));

        mockMvc.perform(post(RUNS_URL + "/run-1/install").cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldId").value("world-uuid-1"))
                .andExpect(jsonPath("$.worldName").value("冥星武将录"))
                .andExpect(jsonPath("$.entryCount").value(12))
                // 故事图物化载荷（story-graph-authoring tasks 5.2）
                .andExpect(jsonPath("$.storyGraphId").value("graph-uuid-1"))
                .andExpect(jsonPath("$.storyGraphNodeCount").value(27));

        verify(worldInstaller).install("run-1");
    }

    @Test
    void 安装run不存在返回404() throws Exception {
        when(worldInstaller.install("run-x"))
                .thenThrow(new NoSuchElementException("管线 run 不存在: run-x"));

        mockMvc.perform(post(RUNS_URL + "/run-x/install").cookie(authCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("管线 run 不存在: run-x"));
    }

    @Test
    void 安装前置阶段未全确认返回409() throws Exception {
        when(worldInstaller.install("run-1"))
                .thenThrow(new IllegalStateException("前置阶段未全部确认，不可安装: S4 处于 AWAITING_CONFIRM"));

        mockMvc.perform(post(RUNS_URL + "/run-1/install").cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("前置阶段未全部确认，不可安装: S4 处于 AWAITING_CONFIRM"));
    }

    @Test
    void 安装物化失败返回500可重试() throws Exception {
        when(worldInstaller.install("run-1"))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post(RUNS_URL + "/run-1/install").cookie(authCookie()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("成稿安装失败，请重试"));
    }

    // ===== 辅助 =====

    private static PipelineRun run(String id, String sourceMode, String styleMode,
                                   String currentStage, String status) {
        PipelineRun run = new PipelineRun();
        run.setId(id);
        run.setName("测试 run");
        run.setSourceMode(sourceMode);
        run.setStyleMode(styleMode);
        run.setCurrentStage(currentStage);
        run.setStatus(status);
        return run;
    }
}
