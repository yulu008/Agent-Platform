package com.luyu.agent.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.model.UsageRecord;
import com.luyu.agent.tenancy.TenantContext;

/**
 * {@link MeteringAdvisor} 单元测试（tasks 10.2 / design D1 修订、D3）。
 * <p>
 * 通过 {@code adviseCall} 驱动，用 {@link ArgumentCaptor} 捕获落库的 {@link UsageRecord}，
 * 钉住 D3 租户解析优先级：① 显式 {@code TENANT_CONTEXT_KEY} → ② 复用
 * {@link SessionMemoryAdvisor#USER_ID_CONTEXT_KEY} → ③ {@link TenantContext} ThreadLocal →
 * ④ 缺失记 {@code <missing>}；并验证 call_type / sessionId 透传、计量关闭旁路、0 usage 不计费。
 */
class MeteringAdvisorTest {

    private TenantUsageService service;
    private MeteringProperties properties;
    private MeteringAdvisor advisor;
    private CallAdvisorChain chain;

    @BeforeEach
    void setUp() {
        service = mock(TenantUsageService.class);
        properties = new MeteringProperties();
        properties.setEnabled(true);
        advisor = new MeteringAdvisor(service, properties);
        chain = mock(CallAdvisorChain.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 构造带指定 context 的请求。 */
    private static ChatClientRequest request(Map<String, Object> context) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("hi"))))
                .context(context)
                .build();
    }

    /** 构造带 usage 的响应（mock Usage 接口，返回真实模型名）。 */
    private static ChatClientResponse response(String model, long prompt, long completion, Long cached) {
        Usage usage = mock(Usage.class);
        // Usage 的 prompt/completion/total 返回 Integer，cacheRead 返回 Long
        when(usage.getPromptTokens()).thenReturn((int) prompt);
        when(usage.getCompletionTokens()).thenReturn((int) completion);
        when(usage.getTotalTokens()).thenReturn((int) (prompt + completion));
        when(usage.getCacheReadInputTokens()).thenReturn(cached);
        ChatResponseMetadata md = ChatResponseMetadata.builder()
                .usage(usage)
                .model(model)
                .build();
        ChatResponse cr = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))), md);
        return ChatClientResponse.builder().chatResponse(cr).build();
    }

    private UsageRecord captureRecorded() {
        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(service).record(captor.capture());
        return captor.getValue();
    }

    // ==================== D3 租户解析优先级 ====================

    @Test
    void 租户解析_显式TENANT_CONTEXT_KEY优先级最高() {
        TenantContext.set("u-thread", "tenant-thread");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(MeteringAdvisor.TENANT_CONTEXT_KEY, "tenant-explicit");
        ctx.put(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "tenant-userid");
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(ctx), chain);

        assertThat(captureRecorded().tenantId()).isEqualTo("tenant-explicit");
    }

    @Test
    void 租户解析_无显式key时回退USER_ID_CONTEXT_KEY() {
        TenantContext.set("u-thread", "tenant-thread");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, "tenant-userid");
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(ctx), chain);

        assertThat(captureRecorded().tenantId()).isEqualTo("tenant-userid");
    }

    @Test
    void 租户解析_无context参数时回退ThreadLocal() {
        TenantContext.set("u-thread", "tenant-thread");
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(new HashMap<>()), chain);

        assertThat(captureRecorded().tenantId()).isEqualTo("tenant-thread");
    }

    @Test
    void 租户解析_全部缺失记为missing() {
        TenantContext.clear();
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(new HashMap<>()), chain);

        assertThat(captureRecorded().tenantId()).isEqualTo(TenantUsageService.MISSING_TENANT);
    }

    // ==================== call_type / sessionId / model ====================

    @Test
    void callType与sessionId从context透传到明细() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(MeteringAdvisor.TENANT_CONTEXT_KEY, "t1");
        ctx.put(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "rpg");
        ctx.put(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, "session-9");
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(ctx), chain);

        UsageRecord r = captureRecorded();
        assertThat(r.callType()).isEqualTo("rpg");
        assertThat(r.sessionId()).isEqualTo("session-9");
    }

    @Test
    void callType缺省记为unknown() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(MeteringAdvisor.TENANT_CONTEXT_KEY, "t1");
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(ctx), chain);

        assertThat(captureRecorded().callType()).isEqualTo("unknown");
    }

    @Test
    void 判档用响应实际模型名并归一化token() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(MeteringAdvisor.TENANT_CONTEXT_KEY, "t1");
        ChatClientResponse stubbed = response("deepseek-v4.1-flash", 16, 585, 7L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(ctx), chain);

        UsageRecord r = captureRecorded();
        assertThat(r.model()).isEqualTo("deepseek-v4.1-flash");
        assertThat(r.promptTokens()).isEqualTo(16L);
        assertThat(r.completionTokens()).isEqualTo(585L);
        assertThat(r.cachedTokens()).isEqualTo(7L);
    }

    // ==================== 旁路与不计费 ====================

    @Test
    void 计量开关关闭_完全旁路不落库() {
        properties.setEnabled(false);
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(MeteringAdvisor.TENANT_CONTEXT_KEY, "t1");
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        ChatClientResponse resp = advisor.adviseCall(request(ctx), chain);

        assertThat(resp).isNotNull();
        verify(service, never()).record(any());
    }

    @Test
    void 响应usage为0_不计费避免脏数据() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(MeteringAdvisor.TENANT_CONTEXT_KEY, "t1");
        ChatClientResponse stubbed = response("glm-5.2", 0, 0, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);

        advisor.adviseCall(request(ctx), chain);

        verify(service, never()).record(any());
    }

    @Test
    void 落库异常被吞掉_不影响返回响应() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(MeteringAdvisor.TENANT_CONTEXT_KEY, "t1");
        ChatClientResponse stubbed = response("glm-5.2", 16, 585, 0L);
        when(chain.nextCall(any())).thenReturn(stubbed);
        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(service).record(any());

        ChatClientResponse resp = advisor.adviseCall(request(ctx), chain);

        assertThat(resp).isNotNull();
        assertThat(resp.chatResponse()).isNotNull();
    }
}
