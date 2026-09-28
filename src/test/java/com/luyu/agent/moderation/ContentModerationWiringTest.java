package com.luyu.agent.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;

import org.springframework.ai.session.SessionService;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.controller.ChatStreamController;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.rpg.controller.RpgGameController;
import com.luyu.agent.rpg.controller.WorkshopController;
import com.luyu.agent.rpg.engine.GameLoopService;
import com.luyu.agent.rpg.engine.OpeningNarrationService;
import com.luyu.agent.rpg.engine.RollbackService;
import com.luyu.agent.rpg.engine.RpgHistoryCleaner;
import com.luyu.agent.rpg.engine.SaveDeletionService;
import com.luyu.agent.rpg.engine.StateDeltaSanitizer;
import com.luyu.agent.rpg.engine.TurnGuardService;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.service.ContextInfoService;
import com.luyu.agent.service.ImageUploadService;
import com.luyu.agent.service.SessionTitleGenerator;
import com.luyu.agent.tenancy.SessionTenantGuard;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 三条输入线接入内容审查闸门的接线测试（tasks 7.2 / 7.3）。
 * <p>
 * 用 mock 式「零副作用」断言验证：命中拒答时提前返回拒答事件，且<b>不进入任何持久化/生成链路</b>——
 * 主聊天不触碰 {@code SessionService}（AI_SESSION）、RPG 不触发 {@code prepareTurn}/{@code finalizeTurn}
 * （rpg_event_log）、工坊不调用 {@code WorkshopLlmGenerator}。这是「被拒输入不落库」铁律的可测代理。
 */
class ContentModerationWiringTest {

    private static final String REFUSAL = "抱歉，您的输入包含不适宜的内容，我无法处理。请调整后重试。";
    private static final String BLOCKED_INPUT = "你个傻逼";

    /** 命中即拒答的闸门 mock。 */
    private ContentModerationGate blockedGate() {
        ContentModerationGate gate = mock(ContentModerationGate.class);
        when(gate.check(anyString())).thenReturn(
                ModerationResult.blocked(java.util.Set.of(ModerationCategory.PROFANITY)));
        when(gate.refusalMessage()).thenReturn(REFUSAL);
        return gate;
    }

    @BeforeEach
    void setUp() {
        // 生产环境由 JwtTenantFilter 在请求线程建立租户上下文
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 主聊天（tasks 3.3 / 7.2） ====================

    @Test
    void 主聊天命中拒答且不触碰会话持久化() {
        SessionTenantGuard guard = mock(SessionTenantGuard.class);
        when(guard.isForeign(any())).thenReturn(false);
        SessionService sessionService = mock(SessionService.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);

        ChatStreamController controller = new ChatStreamController(
                registry, sessionService, mock(SessionTitleGenerator.class),
                mock(ContextInfoService.class), mock(ImageUploadService.class),
                guard, blockedGate(), mock(QuotaGuard.class));

        List<ServerSentEvent<Object>> events = controller.streamChat(
                Map.of("message", BLOCKED_INPUT, "sessionId", "s-1"), null)
                .collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).isEqualTo(Map.of("content", REFUSAL));
        assertThat(events.get(1).data()).isEqualTo("[DONE]");

        // 零副作用：不进模型路由、不读/写会话历史（AI_SESSION 不落被拒输入）
        verify(registry, never()).forChat(any());
        verify(sessionService, never()).getEvents(anyString());
        verify(sessionService, never()).appendMessage(anyString(), any());
    }

    // ==================== RPG 回合（tasks 4.3 / 4.4 / 7.2） ====================

    @Test
    void RPG回合命中拒答且不落事件日志() {
        SessionTenantGuard guard = mock(SessionTenantGuard.class);
        when(guard.isForeign(any())).thenReturn(false);
        GameLoopService gameLoop = mock(GameLoopService.class);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);

        RpgGameController controller = new RpgGameController(
                registry, gameLoop, mock(OpeningNarrationService.class),
                mock(StateDeltaSanitizer.class), mock(WorkshopService.class),
                mock(SessionService.class), mock(RpgHistoryCleaner.class),
                mock(RollbackService.class), new TurnGuardService(),
                mock(SaveDeletionService.class), guard, blockedGate(), mock(QuotaGuard.class));

        List<ServerSentEvent<Object>> events = controller.gameTurn(
                Map.of("gameStateId", "gs-1", "sessionId", "s-1", "message", BLOCKED_INPUT))
                .collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).data()).isEqualTo(Map.of("content", REFUSAL));
        assertThat(events.get(1).data()).isEqualTo("[DONE]");

        // 零副作用：不进 prepareTurn（不落玩家事件）、不路由模型、不 finalizeTurn（rpg_event_log 不落被拒输入）
        verify(gameLoop, never()).prepareTurn(anyString(), anyString());
        verify(gameLoop, never()).finalizeTurn(anyString(), anyString(), any());
        verify(registry, never()).forRpg(any());
    }

    // ==================== 工坊生成（tasks 5.2 / 7.3） ====================

    @Test
    void 工坊生成世界观命中返回拒答结构体且不调模型() {
        WorkshopService workshopService = mock(WorkshopService.class);
        WorkshopLlmGenerator llmGenerator = mock(WorkshopLlmGenerator.class);

        WorkshopController controller = new WorkshopController(
                workshopService, llmGenerator, blockedGate(), mock(QuotaGuard.class));

        ResponseEntity<?> response = controller.generateWorld(Map.of("keywords", BLOCKED_INPUT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isEqualTo(Map.of("moderation", true, "error", REFUSAL));
        verify(llmGenerator, never()).generateWorld(anyString());
    }

    @Test
    void 工坊生成角色卡命中返回拒答结构体且不调模型() {
        WorkshopService workshopService = mock(WorkshopService.class);
        WorkshopLlmGenerator llmGenerator = mock(WorkshopLlmGenerator.class);

        WorkshopController controller = new WorkshopController(
                workshopService, llmGenerator, blockedGate(), mock(QuotaGuard.class));

        ResponseEntity<?> response = controller.generateCharacter(Map.of("description", BLOCKED_INPUT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isEqualTo(Map.of("moderation", true, "error", REFUSAL));
        verify(llmGenerator, never()).generateCharacter(anyString());
    }
}
