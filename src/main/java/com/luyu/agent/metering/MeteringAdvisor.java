package com.luyu.agent.metering;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import com.luyu.agent.metering.config.MeteringProperties;
import com.luyu.agent.metering.model.UsageRecord;
import com.luyu.agent.tenancy.TenantContext;

import reactor.core.publisher.Flux;

/**
 * 计量切面（tasks 4.1-4.3 / design D1 修订、D2、D3）。
 * <p>
 * <b>为什么不用 {@code BaseAdvisor}</b>：spike 实测证明 {@code BaseAdvisor.after()} 在流式下每帧触发
 * 且拿到的都是 0 usage，真 usage 落在被 {@code onFinishReason()} 过滤掉的尾帧。故本类<b>直接实现</b>
 * {@link CallAdvisor} + {@link StreamAdvisor}，精确控制计量时机：
 * <ul>
 *   <li>{@link #adviseCall}：{@code chain.nextCall(req)} 得到唯一响应，读其 usage 计一次。</li>
 *   <li>{@link #adviseStream}：对 {@code chain.nextStream(req)} 的 {@link Flux} 用 {@code doOnNext}
 *       捕获 {@code totalTokens>0} 的帧（实测为尾帧）到 {@link AtomicReference}，在 {@code doOnComplete}
 *       计一次；{@code doOnError}/取消不计。租户与 call_type 在<b>方法体（装配期、控制器线程）</b>读出并
 *       闭包捕获，不依赖 reactor 线程的 ThreadLocal。</li>
 * </ul>
 * <b>兜底一致性</b>：{@code StreamFallbackChatModel} 降级为非流式时 flux 只发一帧且带完整 usage，
 * {@code doOnNext} 同样能捕获，两路径统一。
 * <p>
 * <b>order = 链尾</b>（最靠近模型，{@link Ordered#LOWEST_PRECEDENCE}）：确保读到最终 usage；工具循环中
 * 每次模型往返都会经过本 advisor，逐次计量（每次往返都真实消耗 token）。
 * <p>
 * <b>绝不影响响应</b>：计量全程 try/catch 吞异常 + 告警，任何计量失败都不阻断或改写 LLM 响应。
 */
@Component
public class MeteringAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(MeteringAdvisor.class);

    /**
     * 显式 call_type advisor param key：标注本次调用的业务类型
     * （chat / rpg / workshop / compaction / state_delta / title / subagent）。缺省记 {@code unknown}。
     */
    public static final String CALL_TYPE_CONTEXT_KEY = "metering_call_type";

    /**
     * 显式租户 advisor param key（内部/异步路径专用）。优先级高于 {@link TenantContext} ThreadLocal，
     * 与主聊天/RPG 复用的 {@link SessionMemoryAdvisor#USER_ID_CONTEXT_KEY} 等价（见 {@link #resolveTenant}）。
     */
    public static final String TENANT_CONTEXT_KEY = "metering_tenant_id";

    /** 计量 advisor 位于链尾（最靠近模型），确保读到最终 usage。 */
    private static final int ORDER = Ordered.LOWEST_PRECEDENCE;

    private final TenantUsageService tenantUsageService;
    private final MeteringProperties properties;

    public MeteringAdvisor(TenantUsageService tenantUsageService, MeteringProperties properties) {
        this.tenantUsageService = tenantUsageService;
        this.properties = properties;
    }

    @Override
    public String getName() {
        return "MeteringAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    // ==================== 非流式：读唯一响应 usage 计一次 ====================

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 装配期（调用线程）先解析租户/call_type：ThreadLocal 在此线程可靠
        final String tenantId = resolveTenant(request);
        final String callType = resolveCallType(request);
        final String sessionId = resolveSessionId(request);
        final String requestModel = resolveRequestModel(request);

        ChatClientResponse response = chain.nextCall(request);

        if (properties.isEnabled()) {
            try {
                Usage usage = extractUsage(response);
                if (usage != null && usage.getTotalTokens() > 0) {
                    String model = resolveResponseModel(response, requestModel);
                    tenantUsageService.record(buildRecord(tenantId, model, callType, sessionId, usage));
                }
            } catch (Exception e) {
                log.warn("非流式计量失败（已吞掉，不影响响应）：callType={}", callType, e);
            }
        }
        return response;
    }

    // ==================== 流式：doOnNext 捕获尾帧 usage，doOnComplete 计一次 ====================

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        if (!properties.isEnabled()) {
            return chain.nextStream(request);
        }
        // 关键：在方法体（装配期、控制器线程）读取租户/call_type 并闭包捕获，
        // doOnComplete 运行在 reactor 线程，ThreadLocal 不可靠（design D1 修订 / D5）。
        final String tenantId = resolveTenant(request);
        final String callType = resolveCallType(request);
        final String sessionId = resolveSessionId(request);
        final String requestModel = resolveRequestModel(request);

        final AtomicReference<Usage> usageRef = new AtomicReference<>();
        final AtomicReference<String> modelRef = new AtomicReference<>();

        return chain.nextStream(request)
                .doOnNext(ccr -> {
                    try {
                        Usage usage = extractUsage(ccr);
                        if (usage != null && usage.getTotalTokens() > 0) {
                            usageRef.set(usage);
                            String m = responseModelOf(ccr);
                            if (m != null && !m.isBlank()) {
                                modelRef.set(m);
                            }
                        }
                    } catch (Exception ignore) {
                        // 单帧解析失败不影响后续帧与响应
                    }
                })
                .doOnComplete(() -> {
                    try {
                        Usage usage = usageRef.get();
                        if (usage == null) {
                            // 未捕获到任何带 usage 的帧（异常/取消/空流）：不计费，避免脏数据
                            return;
                        }
                        String model = modelRef.get() != null ? modelRef.get() : requestModel;
                        tenantUsageService.record(buildRecord(tenantId, model, callType, sessionId, usage));
                    } catch (Exception e) {
                        log.warn("流式计量失败（已吞掉，不影响响应）：callType={}", callType, e);
                    }
                });
    }

    // ==================== 租户 / call_type / session / model 解析 ====================

    /**
     * D3 租户解析优先级：① 显式 advisor param（{@link #TENANT_CONTEXT_KEY} 或复用的
     * {@link SessionMemoryAdvisor#USER_ID_CONTEXT_KEY}）→ ② {@link TenantContext} ThreadLocal
     * → ③ 缺失记 {@link TenantUsageService#MISSING_TENANT}（由 service 侧 log.warn 告警）。
     */
    private String resolveTenant(ChatClientRequest request) {
        Map<String, Object> ctx = request.context();
        if (ctx != null) {
            Object explicit = ctx.get(TENANT_CONTEXT_KEY);
            if (isNonBlank(explicit)) {
                return explicit.toString();
            }
            Object userId = ctx.get(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY);
            if (isNonBlank(userId)) {
                return userId.toString();
            }
        }
        String threadLocal = TenantContext.getTenantId();
        if (threadLocal != null && !threadLocal.isBlank()) {
            return threadLocal;
        }
        return TenantUsageService.MISSING_TENANT;
    }

    private String resolveCallType(ChatClientRequest request) {
        Map<String, Object> ctx = request.context();
        if (ctx != null) {
            Object v = ctx.get(CALL_TYPE_CONTEXT_KEY);
            if (isNonBlank(v)) {
                return v.toString();
            }
        }
        return "unknown";
    }

    private String resolveSessionId(ChatClientRequest request) {
        Map<String, Object> ctx = request.context();
        if (ctx != null) {
            Object v = ctx.get(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY);
            if (isNonBlank(v)) {
                return v.toString();
            }
        }
        return null;
    }

    /** 请求侧模型名（响应名缺失时回退用）。 */
    private String resolveRequestModel(ChatClientRequest request) {
        try {
            if (request.prompt() != null) {
                ChatOptions options = request.prompt().getOptions();
                if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
                    return options.getModel();
                }
            }
        } catch (Exception ignore) {
            // 解析请求模型名失败不影响计量（回退响应名或 unknown）
        }
        return null;
    }

    /** 响应侧实际模型名（判档首选，spike 实测响应名可能与请求名不同但同档）。 */
    private String resolveResponseModel(ChatClientResponse response, String fallback) {
        String m = responseModelOf(response);
        return (m != null && !m.isBlank()) ? m : fallback;
    }

    private String responseModelOf(ChatClientResponse response) {
        if (response == null) {
            return null;
        }
        ChatResponse cr = response.chatResponse();
        if (cr == null) {
            return null;
        }
        ChatResponseMetadata md = cr.getMetadata();
        return md == null ? null : md.getModel();
    }

    private Usage extractUsage(ChatClientResponse response) {
        if (response == null) {
            return null;
        }
        ChatResponse cr = response.chatResponse();
        if (cr == null) {
            return null;
        }
        ChatResponseMetadata md = cr.getMetadata();
        return md == null ? null : md.getUsage();
    }

    /**
     * 归一化为 {@link UsageRecord}。缓存 token 用 {@code getCacheReadInputTokens()}（spike 实测可得，
     * 无命中为 0）；返回 {@code null} 时透传 null，由 {@link CostCalculator} 降级为不区分缓存。
     */
    private UsageRecord buildRecord(String tenantId, String model, String callType, String sessionId, Usage usage) {
        long prompt = usage.getPromptTokens();
        long completion = usage.getCompletionTokens();
        Long cached = usage.getCacheReadInputTokens();
        return new UsageRecord(tenantId, model, callType, sessionId, prompt, completion, cached);
    }

    private static boolean isNonBlank(Object v) {
        return v != null && !v.toString().isBlank();
    }
}
