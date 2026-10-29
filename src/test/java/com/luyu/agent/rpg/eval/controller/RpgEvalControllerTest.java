package com.luyu.agent.rpg.eval.controller;

import java.util.List;

import com.luyu.agent.admin.PlatformAdminGuard;
import com.luyu.agent.config.AuthProperties;
import com.luyu.agent.controller.AuthController;
import com.luyu.agent.rpg.eval.engine.EvalNotEnabledException;
import com.luyu.agent.rpg.eval.model.EvalRecord;
import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.rpg.eval.service.EvalReviewService;
import com.luyu.agent.rpg.eval.service.EvalRunService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RpgEvalController} Web 层测试（content-eval tasks 6.3 / 7.4）。
 * <p>
 * 覆盖：admin 域鉴权（非 platform_admin 403 且不创建 run；无认证 cookie 401）、
 * POST 触发参数校验（非法 layer / scope 缺失 400）、eval 未启用 503 含配置指引、
 * run 列表与详情（404 / 汇总对象化 / verdict+subject 过滤透传）；
 * 复核域（tasks 7.4）：队列 limit 透传、终裁 200 返回更新后 record、
 * 终裁非三档与非 fail 沉淀 400、record 不存在 404。
 * <p>
 * 租户上下文由 {@code JwtTenantFilter}（随 {@code @WebMvcTest} 切片加载）从 cookie
 * 建立；AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard
 * 不在切片中，mock 之（WorldCoverControllerTest 先例）。
 */
@WebMvcTest(RpgEvalController.class)
@Import(JwtService.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class RpgEvalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private EvalRunService evalRunService;

    @MockitoBean
    private EvalReviewService evalReviewService;

    /** AdminWebConfig（WebMvcConfigurer）随切片拉入，其依赖 PlatformAdminGuard 不在切片中，mock 之 */
    @MockitoBean
    private PlatformAdminGuard platformAdminGuard;

    /** 平台管理员的有效 JWT cookie（u-admin / t-a） */
    private jakarta.servlet.http.Cookie adminCookie() {
        return new jakarta.servlet.http.Cookie(AuthController.AUTH_COOKIE,
                jwtService.issue("u-admin", "t-a"));
    }

    private void asAdmin() {
        when(platformAdminGuard.isPlatformAdmin("u-admin")).thenReturn(true);
    }

    // ==================== POST /rpg/eval/run：鉴权与校验 ====================

    @Test
    void 非admin触发批跑被拒绝_不创建run() throws Exception {
        when(platformAdminGuard.isPlatformAdmin("u-admin")).thenReturn(false);

        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"static\",\"worldId\":\"w-1\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isForbidden());

        verify(evalRunService, never()).startRun(any(), any(), any(), any());
    }

    @Test
    void 未认证无cookie时401() throws Exception {
        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"static\",\"worldId\":\"w-1\"}"))
                .andExpect(status().isUnauthorized());

        verify(evalRunService, never()).startRun(any(), any(), any(), any());
    }

    @Test
    void admin触发批跑返回runId() throws Exception {
        asAdmin();
        when(evalRunService.startRun("static", "w-1", null, null)).thenReturn("run-1");

        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"static\",\"worldId\":\"w-1\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"));
    }

    @Test
    void 动态层带窗覆盖透传service() throws Exception {
        asAdmin();
        when(evalRunService.startRun("dynamic", null, "gs-1", 7)).thenReturn("run-2");

        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"dynamic\",\"gameStateId\":\"gs-1\",\"windowTurns\":7}")
                        .cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-2"));
    }

    @Test
    void 非法layer返回400() throws Exception {
        asAdmin();

        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"xxx\",\"worldId\":\"w-1\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(evalRunService, never()).startRun(any(), any(), any(), any());
    }

    @Test
    void 静态层缺worldId返回400() throws Exception {
        asAdmin();

        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"static\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 动态层缺gameStateId返回400() throws Exception {
        asAdmin();

        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"dynamic\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void eval未启用返回503含配置指引() throws Exception {
        asAdmin();
        when(evalRunService.startRun("static", "w-1", null, null))
                .thenThrow(new EvalNotEnabledException());

        mockMvc.perform(post("/rpg/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layer\":\"static\",\"worldId\":\"w-1\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("judge")));
    }

    // ==================== GET /runs 与 /runs/{id} ====================

    @Test
    void run列表返回最近run() throws Exception {
        asAdmin();
        when(evalRunService.listRecent(20)).thenReturn(List.of(run("run-1", EvalRun.STATUS_DONE)));

        mockMvc.perform(get("/rpg/eval/runs").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("run-1"))
                .andExpect(jsonPath("$[0].status").value("done"))
                .andExpect(jsonPath("$[0].judgeModel").value("glm-5"));
    }

    @Test
    void run不存在返回404() throws Exception {
        asAdmin();
        when(evalRunService.findRun("run-x")).thenReturn(null);

        mockMvc.perform(get("/rpg/eval/runs/run-x").cookie(adminCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void run详情返回汇总与明细_过滤参数透传() throws Exception {
        asAdmin();
        EvalRun run = run("run-1", EvalRun.STATUS_DONE);
        run.setSummaryJson("{\"total\":4,\"invalid\":1}");
        when(evalRunService.findRun("run-1")).thenReturn(run);
        when(evalRunService.listRecords("run-1", "fail", "kagetoro")).thenReturn(List.of(record()));

        mockMvc.perform(get("/rpg/eval/runs/run-1")
                        .param("verdict", "fail")
                        .param("subject", "kagetoro")
                        .cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.summary.total").value(4))
                .andExpect(jsonPath("$.summary.invalid").value(1))
                .andExpect(jsonPath("$.records[0].subject").value("kagetoro"))
                .andExpect(jsonPath("$.records[0].verdict").value("fail"));

        verify(evalRunService).listRecords("run-1", "fail", "kagetoro");
    }

    // ==================== 复核域：GET review-queue 与 POST review（tasks 7.4） ====================

    @Test
    void 复核队列透传limit返回() throws Exception {
        asAdmin();
        when(evalReviewService.reviewQueue(30)).thenReturn(List.of(queueRecord()));

        mockMvc.perform(get("/rpg/eval/review-queue").param("limit", "30").cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("rec-1"))
                .andExpect(jsonPath("$[0].subject").value("kagetoro"))
                .andExpect(jsonPath("$[0].verdict").value("borderline"))
                .andExpect(jsonPath("$[0].divergenceGroup").value("div-9"));
    }

    @Test
    void 复核终裁成功返回更新后record() throws Exception {
        asAdmin();
        EvalRecord updated = queueRecord();
        updated.setReviewStatus(EvalRecord.REVIEW_OVERRIDE);
        updated.setReviewFinalVerdict(EvalRecord.VERDICT_FAIL);
        updated.setIsRegression(true);
        when(evalReviewService.review("rec-1", "fail", true)).thenReturn(updated);

        mockMvc.perform(post("/rpg/eval/records/rec-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalVerdict\":\"fail\",\"regression\":true}")
                        .cookie(adminCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("override"))
                .andExpect(jsonPath("$.reviewFinalVerdict").value("fail"))
                .andExpect(jsonPath("$.isRegression").value(true));
    }

    @Test
    void 终裁非三档返回400() throws Exception {
        asAdmin();
        when(evalReviewService.review("rec-1", "excellent", false))
                .thenThrow(new IllegalArgumentException("finalVerdict 须为 pass / borderline / fail"));

        mockMvc.perform(post("/rpg/eval/records/rec-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalVerdict\":\"excellent\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void record不存在返回404() throws Exception {
        asAdmin();
        when(evalReviewService.review("rec-x", "pass", false)).thenReturn(null);

        mockMvc.perform(post("/rpg/eval/records/rec-x/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalVerdict\":\"pass\"}")
                        .cookie(adminCookie()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 非fail终裁要求沉淀回归集返回400() throws Exception {
        asAdmin();
        when(evalReviewService.review("rec-1", "pass", true))
                .thenThrow(new IllegalArgumentException("仅 final verdict=fail 支持沉淀回归集"));

        mockMvc.perform(post("/rpg/eval/records/rec-1/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalVerdict\":\"pass\",\"regression\":true}")
                        .cookie(adminCookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("fail")));
    }

    // ==================== 辅助构造 ====================

    private static EvalRun run(String id, String status) {
        EvalRun run = new EvalRun();
        run.setId(id);
        run.setLayer(EvalRun.LAYER_STATIC);
        run.setScopeJson("{\"layer\":\"static\",\"worldId\":\"w-1\"}");
        run.setJudgeModel("glm-5");
        run.setRubricVersion("rub123");
        run.setStatus(status);
        return run;
    }

    private static EvalRecord record() {
        EvalRecord r = new EvalRecord();
        r.setSubject("kagetoro");
        r.setVerdict(EvalRecord.VERDICT_FAIL);
        r.setSubDimension(EvalRecord.SUB_SPEECH_STYLE);
        r.setRationale("前后两窗言语风格漂移无设定支撑");
        return r;
    }

    /** 复核队列行：borderline + 分歧标记（两入队通道同时命中） */
    private static EvalRecord queueRecord() {
        EvalRecord r = new EvalRecord();
        r.setId("rec-1");
        r.setSubject("kagetoro");
        r.setSubjectLabel("景虎");
        r.setSubDimension(EvalRecord.SUB_HARD_FACTS);
        r.setVerdict(EvalRecord.VERDICT_BORDERLINE);
        r.setRationale("圣衣箱描述与档案钩子存疑");
        r.setDivergenceGroup("div-9");
        return r;
    }
}
