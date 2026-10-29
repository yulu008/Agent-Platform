package com.luyu.agent.rpg.planner.controller;

import java.util.Collections;
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
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.service.StageGateService;
import com.luyu.agent.service.JwtService;

import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 确认门操作端点测试（add-planner-pipeline tasks 4.2-4.5）。
 * <p>
 * 切片装配仿 {@code PipelineRunControllerTest}：service 全 mock，钉住端点
 * 契约——配置开关 503；编辑保存（content 必填 400 / 未知阶段码 400 / 200
 * 版本回报 / 404 / 409 锁定态与门级拒绝）；整稿通过（200 {stage,
 * confirmedVersion, nextStage} / 409 待定候选与 S6 安装语义）；
 * L2 逐卡操作（200 状态/版本回报 / 409 已毙候选终态 / 400 未知候选）；
 * 返工 SSE（moderation 拒答零副作用 / 前置失败 error 事件收流，asyncDispatch
 * 断言用 ASCII 片段——MockMvc 模拟响应默认 ISO-8859-1）；回退解锁
 * （200 {stage, status, archivedVersion, currentStage} / 409 未锁定与跨级）；
 * S4.5 幕级返工/回退解锁（story-graph-authoring 2.6：rework SSE 同返工
 * 惯例 / unlock JSON 同回退解锁惯例）。
 */
@WebMvcTest(StageGateController.class)
@Import(JwtService.class)
@EnableConfigurationProperties({AuthProperties.class, RpgPlannerProperties.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class StageGateControllerTest {

    private static final String DRAFT_URL = "/rpg/planner/runs/run-1/stages/S2/draft";
    private static final String CONFIRM_URL = "/rpg/planner/runs/run-1/stages/S2/confirm";
    private static final String REWORK_URL = "/rpg/planner/runs/run-1/stages/S3/rework";
    private static final String REGEN_URL = "/rpg/planner/runs/run-1/stages/S3/regenerate";
    private static final String UNLOCK_URL = "/rpg/planner/runs/run-1/stages/S2/unlock";
    private static final String ACT_REWORK_URL =
            "/rpg/planner/runs/run-1/stages/S4.5/candidates/candidate-02/rework";
    private static final String ACT_UNLOCK_URL =
            "/rpg/planner/runs/run-1/stages/S4.5/candidates/candidate-01/unlock";
    private static final String APPEND_URL = "/rpg/planner/runs/run-1/stages/S3/append-batch";
    private static final String GENFULL_URL = "/rpg/planner/runs/run-1/stages/S3/generate-full";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RpgPlannerProperties plannerProperties;

    @MockitoBean
    private StageGateService gateService;

    /** D11 返工反馈前置审查（同 PipelineRunControllerTest 的 moderation mock 惯例） */
    @MockitoBean
    private ContentModerationGate moderationGate;

    /** 切片扫描拉进 AdminWebConfig，其构造依赖 PlatformAdminGuard——mock 兑住装配（同 PipelineRunControllerTest） */
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

    // ===== 配置开关门控（false 时门操作端点 503） =====

    @Test
    void 开关关闭时门端点返回503() throws Exception {
        plannerProperties.setEnabled(false);

        mockMvc.perform(post(DRAFT_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"编辑后内容\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("策划管线功能已停用（rpg.planner.enabled=false）"));

        mockMvc.perform(post(CONFIRM_URL).cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        // SSE 返工端点：开关停用时以 error 事件 + [DONE] 收流（连接已建立，状态码不可达）
        MvcResult sseStart = mockMvc.perform(post(REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(sseStart))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rpg.planner.enabled=false")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(gateService, never()).reworkStage(anyString(), any(), anyString());

        // SSE 重算端点：开关停用同返工（error 事件 + [DONE] 收流）
        MvcResult regenStart = mockMvc.perform(post(REGEN_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(regenStart))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rpg.planner.enabled=false")))
                .andExpect(content().string(containsString("[DONE]")));
        verify(gateService, never()).regenerateStage(anyString(), any());

        // S3 补批/全量生成端点：开关停用同返工/重算（error 事件 + [DONE] 收流，
        // 委派零副作用）
        MvcResult appendStart = mockMvc.perform(post(APPEND_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(appendStart))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rpg.planner.enabled=false")))
                .andExpect(content().string(containsString("[DONE]")));
        verify(gateService, never()).appendBatch(anyString(), any());

        MvcResult fullStart = mockMvc.perform(post(GENFULL_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(fullStart))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("rpg.planner.enabled=false")))
                .andExpect(content().string(containsString("[DONE]")));
        verify(gateService, never()).generateFull(anyString(), any());

        mockMvc.perform(post(UNLOCK_URL).cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());
        verify(gateService, never()).unlockStage(anyString(), any());
    }

    // ===== L1 草稿编辑保存 =====

    @Test
    void 编辑保存成功返回新版本() throws Exception {
        when(gateService.saveL1Draft("run-1", PipelineStage.S2_WORLD_SETTING, "编辑后内容"))
                .thenReturn(Map.of("stage", "S2", "version", 2));

        mockMvc.perform(post(DRAFT_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"编辑后内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S2"))
                .andExpect(jsonPath("$.version").value(2));

        verify(gateService).saveL1Draft("run-1", PipelineStage.S2_WORLD_SETTING, "编辑后内容");
    }

    @Test
    void 编辑保存content缺失或空白400() throws Exception {
        mockMvc.perform(post(DRAFT_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("content 不能为空"));

        mockMvc.perform(post(DRAFT_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("content 不能为空"));
    }

    @Test
    void 编辑保存未知阶段码400() throws Exception {
        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S9/draft").cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的管线阶段码: S9"));
    }

    @Test
    void 编辑保存run不存在404() throws Exception {
        when(gateService.saveL1Draft("run-1", PipelineStage.S2_WORLD_SETTING, "内容"))
                .thenThrow(new NoSuchElementException("管线 run 不存在: run-1"));

        mockMvc.perform(post(DRAFT_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"内容\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("管线 run 不存在: run-1"));
    }

    @Test
    void 编辑保存锁定态409() throws Exception {
        when(gateService.saveL1Draft("run-1", PipelineStage.S2_WORLD_SETTING, "越权改稿"))
                .thenThrow(new IllegalStateException("阶段当前状态不可操作（锁定态须先回退解锁）: CONFIRMED"));

        mockMvc.perform(post(DRAFT_URL).cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"越权改稿\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("阶段当前状态不可操作（锁定态须先回退解锁）: CONFIRMED"));
    }

    // ===== 整稿通过 =====

    @Test
    void 整稿通过成功返回确认版本与下一阶段() throws Exception {
        when(gateService.confirmStage("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenReturn(Map.of("stage", "S2", "confirmedVersion", 1, "nextStage", "S3"));

        mockMvc.perform(post(CONFIRM_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S2"))
                .andExpect(jsonPath("$.confirmedVersion").value(1))
                .andExpect(jsonPath("$.nextStage").value("S3"));

        verify(gateService).confirmStage("run-1", PipelineStage.S2_WORLD_SETTING);
    }

    @Test
    void 整稿通过待定候选409() throws Exception {
        when(gateService.confirmStage("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenThrow(new IllegalStateException("存在待定候选（须逐个确认或毙掉）: 3 个"));

        mockMvc.perform(post(CONFIRM_URL).cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("存在待定候选（须逐个确认或毙掉）: 3 个"));
    }

    @Test
    void 整稿通过S6安装语义409() throws Exception {
        when(gateService.confirmStage("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenThrow(new IllegalStateException("成稿安装阶段不走通用确认门（安装端点专用语义）"));

        mockMvc.perform(post(CONFIRM_URL).cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("成稿安装阶段不走通用确认门（安装端点专用语义）"));
    }

    @Test
    void 整稿通过未知阶段码400() throws Exception {
        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/SX/confirm").cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的管线阶段码: SX"));
    }

    // ===== L2 门逐卡操作（tasks 4.3） =====

    @Test
    void 逐卡毙掉成功返回状态() throws Exception {
        when(gateService.rejectCandidate("run-1", PipelineStage.S3_CHARACTERS, "candidate-01"))
                .thenReturn(Map.of("stage", "S3", "candidateId", "candidate-01",
                        "status", "REJECTED"));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/candidate-01/reject")
                        .cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S3"))
                .andExpect(jsonPath("$.candidateId").value("candidate-01"))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(gateService).rejectCandidate("run-1", PipelineStage.S3_CHARACTERS, "candidate-01");
    }

    @Test
    void 逐卡确认已毙候选409() throws Exception {
        when(gateService.confirmCandidate("run-1", PipelineStage.S3_CHARACTERS, "candidate-01"))
                .thenThrow(new IllegalStateException("已毙候选不可确认（救回走返工重生成）"));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/candidate-01/confirm")
                        .cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("已毙候选不可确认（救回走返工重生成）"));
    }

    @Test
    void 逐卡编辑成功返回版本() throws Exception {
        when(gateService.saveCandidateEdit("run-1", PipelineStage.S3_CHARACTERS,
                "candidate-01", "编辑后单卡"))
                .thenReturn(Map.of("stage", "S3", "candidateId", "candidate-01", "version", 2));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/candidate-01")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"编辑后单卡\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S3"))
                .andExpect(jsonPath("$.candidateId").value("candidate-01"))
                .andExpect(jsonPath("$.version").value(2));

        verify(gateService).saveCandidateEdit("run-1", PipelineStage.S3_CHARACTERS,
                "candidate-01", "编辑后单卡");
    }

    @Test
    void 逐卡编辑content缺失400() throws Exception {
        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/candidate-01")
                        .cookie(authCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("content 不能为空"));
    }

    @Test
    void 逐卡操作未知候选400() throws Exception {
        when(gateService.rejectCandidate("run-1", PipelineStage.S3_CHARACTERS, "candidate-99"))
                .thenThrow(new IllegalArgumentException("候选不存在或已过期: candidate-99"));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/candidate-99/reject")
                        .cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("候选不存在或已过期: candidate-99"));
    }

    // ===== 批量确认与 S3 组合动作（planner-s3-batch-confirm） =====

    @Test
    void 批量确认成功返回confirmedCount() throws Exception {
        when(gateService.confirmAllCandidates("run-1", PipelineStage.S3_CHARACTERS))
                .thenReturn(Map.of("stage", "S3", "confirmedCount", 12));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/confirm-all")
                        .cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S3"))
                .andExpect(jsonPath("$.confirmedCount").value(12));

        verify(gateService).confirmAllCandidates("run-1", PipelineStage.S3_CHARACTERS);
    }

    @Test
    void 批量确认L1门409() throws Exception {
        when(gateService.confirmAllCandidates("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenThrow(new IllegalStateException("S2 为 L1 编辑门，无候选级操作"));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S2/candidates/confirm-all")
                        .cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("S2 为 L1 编辑门，无候选级操作"));
    }

    @Test
    void 批量确认未知阶段码400() throws Exception {
        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/SX/candidates/confirm-all")
                        .cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的管线阶段码: SX"));
    }

    @Test
    void 确认并整稿通过成功返回组合结果() throws Exception {
        when(gateService.confirmRemainingAndPass("run-1", PipelineStage.S3_CHARACTERS))
                .thenReturn(Map.of("stage", "S3", "confirmedCount", 5,
                        "confirmedVersion", 3, "nextStage", "S4"));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/confirm-remaining")
                        .cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S3"))
                .andExpect(jsonPath("$.confirmedCount").value(5))
                .andExpect(jsonPath("$.confirmedVersion").value(3))
                .andExpect(jsonPath("$.nextStage").value("S4"));

        verify(gateService).confirmRemainingAndPass("run-1", PipelineStage.S3_CHARACTERS);
    }

    @Test
    void 确认并整稿通过非S3阶段409() throws Exception {
        when(gateService.confirmRemainingAndPass("run-1", PipelineStage.S5_BRANCH_DESIGN))
                .thenThrow(new IllegalStateException("「确认并整稿通过」仅对 S3 人物设定开放: S5"));

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S5/confirm-remaining")
                        .cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("「确认并整稿通过」仅对 S3 人物设定开放: S5"));
    }

    @Test
    void 批量端点开关关闭503() throws Exception {
        plannerProperties.setEnabled(false);

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/confirm-all")
                        .cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/confirm-remaining")
                        .cookie(authCookie()))
                .andExpect(status().isServiceUnavailable());

        verify(gateService, never()).confirmAllCandidates(anyString(), any());
        verify(gateService, never()).confirmRemainingAndPass(anyString(), any());
    }

    // ===== L2 返工 SSE（tasks 4.4） =====

    @Test
    void 返工成功委派service并透传SSE流() throws Exception {
        when(moderationGate.check("光秀要更悲情")).thenReturn(ModerationResult.pass());
        when(gateService.reworkStage("run-1", PipelineStage.S3_CHARACTERS, "光秀要更悲情"))
                .thenReturn(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "stage_start", "stage", "S3"))
                                .build(),
                        ServerSentEvent.<Object>builder().data("[DONE]").build()));

        MvcResult started = mockMvc.perform(post(REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"光秀要更悲情\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stage_start")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(moderationGate).check("光秀要更悲情");
        verify(gateService).reworkStage("run-1", PipelineStage.S3_CHARACTERS, "光秀要更悲情");
    }

    @Test
    void 返工反馈命中审查拒答且零副作用() throws Exception {
        when(moderationGate.check("违禁反馈"))
                .thenReturn(ModerationResult.blocked(Collections.emptySet()));
        when(moderationGate.refusalMessage()).thenReturn("content-refused");

        MvcResult started = mockMvc.perform(post(REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"违禁反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 拒答事件带 moderation 标记 + [DONE] 收流（spec「返工反馈审查」：
        // SSE 连接已建立状态码不可达；ASCII 断言片段回避 MockMvc 编码假象）
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("moderation")))
                .andExpect(content().string(containsString("content-refused")))
                .andExpect(content().string(containsString("[DONE]")));

        // 零副作用：记账与生成都不执行（版本树停在当前版本、无生成消耗）
        verify(gateService, never()).reworkStage(anyString(), any(), anyString());
    }

    @Test
    void 返工反馈缺失SSE拒答() throws Exception {
        MvcResult started = mockMvc.perform(post(REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("feedback")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(moderationGate, never()).check(anyString());
        verify(gateService, never()).reworkStage(anyString(), any(), anyString());
    }

    @Test
    void 返工未知阶段码SSE拒答() throws Exception {
        MvcResult started = mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S9/rework")
                        .cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("S9")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(moderationGate, never()).check(anyString());
        verify(gateService, never()).reworkStage(anyString(), any(), anyString());
    }

    @Test
    void 返工锁定态SSE拒答() throws Exception {
        when(moderationGate.check("反馈")).thenReturn(ModerationResult.pass());
        when(gateService.reworkStage("run-1", PipelineStage.S3_CHARACTERS, "反馈"))
                .thenThrow(new IllegalStateException("阶段当前状态不可操作（锁定态须先回退解锁）: CONFIRMED"));

        MvcResult started = mockMvc.perform(post(REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CONFIRMED")))
                .andExpect(content().string(containsString("[DONE]")));
    }

    @Test
    void 返工run不存在SSE拒答() throws Exception {
        when(moderationGate.check("反馈")).thenReturn(ModerationResult.pass());
        when(gateService.reworkStage("run-1", PipelineStage.S3_CHARACTERS, "反馈"))
                .thenThrow(new NoSuchElementException("管线 run 不存在: run-1"));

        MvcResult started = mockMvc.perform(post(REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("run-1")))
                .andExpect(content().string(containsString("[DONE]")));
    }

    // ===== 上游已变重算（无请求体：无用户输入线，moderation 不适用） =====

    @Test
    void 重算成功委派service并透传SSE流() throws Exception {
        when(gateService.regenerateStage("run-1", PipelineStage.S3_CHARACTERS))
                .thenReturn(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "stage_start", "stage", "S3"))
                                .build(),
                        ServerSentEvent.<Object>builder().data("[DONE]").build()));

        MvcResult started = mockMvc.perform(post(REGEN_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("stage_start")))
                .andExpect(content().string(containsString("[DONE]")));

        // 无用户输入线：moderation 不适用（对齐返工「反馈是唯一输入线」的治理边界）
        verify(moderationGate, never()).check(anyString());
        verify(gateService).regenerateStage("run-1", PipelineStage.S3_CHARACTERS);
    }

    @Test
    void 重算前置失败SSE拒答() throws Exception {
        when(gateService.regenerateStage("run-1", PipelineStage.S3_CHARACTERS))
                .thenThrow(new IllegalStateException("上游确认稿未变化（v2 不高于生成基线），无需重算；修改本阶段稿请走编辑/返工"));

        MvcResult started = mockMvc.perform(post(REGEN_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 前置失败以 SSE error 事件 + [DONE] 收流（连接已建立，状态码不可达，
        // 同返工惯例；ASCII 片段断言规避 MockMvc 编码假象）
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("v2")))
                .andExpect(content().string(containsString("[DONE]")));
    }

    // ===== 幕级 L2 返工门（story-graph-authoring tasks 2.6） =====

    @Test
    void 幕级返工成功委派service并透传SSE流() throws Exception {
        when(moderationGate.check("幕 2 节奏太拖")).thenReturn(ModerationResult.pass());
        when(gateService.reworkAct("run-1", "candidate-02", "幕 2 节奏太拖"))
                .thenReturn(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "act_start", "act", 2, "total", 3, "theme", "act-2"))
                                .build(),
                        ServerSentEvent.<Object>builder().data("[DONE]").build()));

        MvcResult started = mockMvc.perform(post(ACT_REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"幕 2 节奏太拖\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("act_start")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(moderationGate).check("幕 2 节奏太拖");
        verify(gateService).reworkAct("run-1", "candidate-02", "幕 2 节奏太拖");
    }

    @Test
    void 幕级返工非S4_5阶段SSE拒答() throws Exception {
        MvcResult started = mockMvc.perform(
                post("/rpg/planner/runs/run-1/stages/S3/candidates/candidate-01/rework")
                        .cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("S4.5")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(gateService, never()).reworkAct(anyString(), anyString(), anyString());
    }

    @Test
    void 幕级返工反馈命中审查拒答且零副作用() throws Exception {
        when(moderationGate.check("违禁反馈"))
                .thenReturn(ModerationResult.blocked(Collections.emptySet()));
        when(moderationGate.refusalMessage()).thenReturn("content-refused");

        MvcResult started = mockMvc.perform(post(ACT_REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"违禁反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("moderation")))
                .andExpect(content().string(containsString("content-refused")))
                .andExpect(content().string(containsString("[DONE]")));

        // 零副作用：复活与记账都不执行（同 reworkStage 惯例）
        verify(gateService, never()).reworkAct(anyString(), anyString(), anyString());
    }

    @Test
    void 幕级返工已确认幕SSE拒答提示先解锁() throws Exception {
        when(moderationGate.check("反馈")).thenReturn(ModerationResult.pass());
        when(gateService.reworkAct("run-1", "candidate-02", "反馈"))
                .thenThrow(new IllegalStateException("已确认幕须先回退解锁（unlockAct）后返工"));

        MvcResult started = mockMvc.perform(post(ACT_REWORK_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"feedback\":\"反馈\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // ASCII 片段断言（unlockAct）回避 MockMvc 编码假象
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("unlockAct")))
                .andExpect(content().string(containsString("[DONE]")));
    }

    @Test
    void 幕级解锁成功返回复位状态与图失效标记() throws Exception {
        when(gateService.unlockAct("run-1", "candidate-01"))
                .thenReturn(Map.of("stage", "S4.5", "candidateId", "candidate-01",
                        "status", "PENDING", "graphInvalidated", true));

        mockMvc.perform(post(ACT_UNLOCK_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S4.5"))
                .andExpect(jsonPath("$.candidateId").value("candidate-01"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.graphInvalidated").value(true));

        verify(gateService).unlockAct("run-1", "candidate-01");
    }

    @Test
    void 幕级解锁非已确认幕409() throws Exception {
        when(gateService.unlockAct("run-1", "candidate-01"))
                .thenThrow(new IllegalStateException("仅已确认候选可回退解锁，当前状态: PENDING"));

        mockMvc.perform(post(ACT_UNLOCK_URL).cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("仅已确认候选可回退解锁，当前状态: PENDING"));
    }

    @Test
    void 幕级解锁非S4_5阶段400() throws Exception {
        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S3/candidates/candidate-01/unlock")
                        .cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("幕级回退解锁仅 S4.5 故事图骨架阶段可用"));
    }

    // ===== 回退解锁（tasks 4.5） =====

    @Test
    void 解锁成功返回回退状态与存档版本() throws Exception {
        when(gateService.unlockStage("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenReturn(Map.of("stage", "S2", "status", "AWAITING_CONFIRM",
                        "archivedVersion", 1, "currentStage", "S2"));

        mockMvc.perform(post(UNLOCK_URL).cookie(authCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S2"))
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRM"))
                .andExpect(jsonPath("$.archivedVersion").value(1))
                .andExpect(jsonPath("$.currentStage").value("S2"));

        verify(gateService).unlockStage("run-1", PipelineStage.S2_WORLD_SETTING);
    }

    @Test
    void 解锁未锁定阶段409() throws Exception {
        when(gateService.unlockStage("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenThrow(new IllegalStateException("仅锁定阶段可回退解锁，当前状态: AWAITING_CONFIRM"));

        mockMvc.perform(post(UNLOCK_URL).cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("仅锁定阶段可回退解锁，当前状态: AWAITING_CONFIRM"));
    }

    @Test
    void 跨级解锁409() throws Exception {
        when(gateService.unlockStage("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenThrow(new IllegalStateException(
                        "跨级解锁须先回退解锁下游阶段（当前阶段: S4，本阶段解锁前置: S3）"));

        mockMvc.perform(post(UNLOCK_URL).cookie(authCookie()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("跨级解锁须先回退解锁下游阶段（当前阶段: S4，本阶段解锁前置: S3）"));
    }

    @Test
    void 解锁run不存在404() throws Exception {
        when(gateService.unlockStage("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenThrow(new NoSuchElementException("管线 run 不存在: run-1"));

        mockMvc.perform(post(UNLOCK_URL).cookie(authCookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("管线 run 不存在: run-1"));
    }

    @Test
    void 解锁未知阶段码400() throws Exception {
        mockMvc.perform(post("/rpg/planner/runs/run-1/stages/S9/unlock").cookie(authCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("未知的管线阶段码: S9"));
    }

    // ===== S3 补批/全量生成门（planner-s3-batch-generate tasks 3.1：无请求体
    // SSE，守卫拒绝以 error 事件 + [DONE] 收流） =====

    @Test
    void S3补批成功委派service并透传SSE流() throws Exception {
        when(gateService.appendBatch("run-1", PipelineStage.S3_CHARACTERS))
                .thenReturn(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "batch_start", "stage", "S3",
                                        "index", 3, "total", 5, "count", 2))
                                .build(),
                        ServerSentEvent.<Object>builder().data("[DONE]").build()));

        MvcResult started = mockMvc.perform(post(APPEND_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("batch_start")))
                .andExpect(content().string(containsString("[DONE]")));

        // 无请求体无用户输入线：moderation 不适用（对齐重算口径）
        verify(moderationGate, never()).check(anyString());
        verify(gateService).appendBatch("run-1", PipelineStage.S3_CHARACTERS);
    }

    @Test
    void S3全量生成成功委派service并透传SSE流() throws Exception {
        when(gateService.generateFull("run-1", PipelineStage.S3_CHARACTERS))
                .thenReturn(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "batch_start", "stage", "S3",
                                        "index", 1, "total", 3, "count", 2))
                                .build(),
                        ServerSentEvent.<Object>builder()
                                .data(Map.of("type", "draft_ready", "stage", "S3",
                                        "version", 2, "candidates", 5))
                                .build(),
                        ServerSentEvent.<Object>builder().data("[DONE]").build()));

        MvcResult started = mockMvc.perform(post(GENFULL_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("batch_start")))
                .andExpect(content().string(containsString("draft_ready")))
                .andExpect(content().string(containsString("[DONE]")));

        verify(moderationGate, never()).check(anyString());
        verify(gateService).generateFull("run-1", PipelineStage.S3_CHARACTERS);
    }

    @Test
    void S3补批非S3阶段SSE拒答() throws Exception {
        when(gateService.appendBatch("run-1", PipelineStage.S2_WORLD_SETTING))
                .thenThrow(new IllegalStateException("补批/全量生成门动作仅 S3 人物候选阶段可用: S2"));

        MvcResult started = mockMvc.perform(
                post("/rpg/planner/runs/run-1/stages/S2/append-batch").cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 守卫拒绝以 SSE error 事件 + [DONE] 收流（连接已建立，状态码不可达；
        // ASCII 片段断言规避 MockMvc 编码假象）
        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("S2")))
                .andExpect(content().string(containsString("[DONE]")));
    }

    @Test
    void S3全量生成锁定态SSE拒答() throws Exception {
        when(gateService.generateFull("run-1", PipelineStage.S3_CHARACTERS))
                .thenThrow(new IllegalStateException("阶段当前状态不可操作（锁定态须先回退解锁）: CONFIRMED"));

        MvcResult started = mockMvc.perform(post(GENFULL_URL).cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CONFIRMED")))
                .andExpect(content().string(containsString("[DONE]")));
    }

    @Test
    void S3补批未知阶段码SSE拒答() throws Exception {
        MvcResult started = mockMvc.perform(
                post("/rpg/planner/runs/run-1/stages/S9/append-batch").cookie(authCookie())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("S9")))
                .andExpect(content().string(containsString("[DONE]")));

        // 阶段码解析失败在委派前拦截，service 零调用
        verify(gateService, never()).appendBatch(anyString(), any());
    }
}
