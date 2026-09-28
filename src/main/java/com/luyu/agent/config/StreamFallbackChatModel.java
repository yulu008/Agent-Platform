package com.luyu.agent.config;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 流式多 tool_call 容错装饰器。
 * <p>
 * 背景：Spring AI 2.0.0 的 {@code OpenAiChatModel$ChunkMerger.mergeDeltas} 在合并流式 chunk 时
 * 断言「单个 chunk 的 delta.tool_calls 不超过 1 条」，否则抛
 * {@code IllegalArgumentException: no more than one tool call per message currently supported}。
 * 而 GLM 网关会把多个 tool_calls 打包进同一个 SSE chunk（并行调用或碎片化输出），
 * 导致流式请求整条中断（前端表现为 "Stream processing failed"）。
 * <p>
 * 该异常发生在模型内部流聚合阶段，Advisor 层无法拦截，故在 ChatModel 层装饰：
 * 流式通途遇到该特定错误时，自动降级为非流式 {@code call()} 重试
 * （非流式路径原生支持多 tool_calls，且内部工具循环 + {@link MalformedToolCallSanitizer}
 * 可正确处理 GLM 碎片化 tool call），以单条完整 ChatResponse 的形式继续下游 advisor 链。
 * <p>
 * ChunkMerger 还有第二处对 GLM 不兼容：{@code chunkToChatCompletion} 对合并后的
 * tool call 直接 {@code tc.id().get()}，GLM 碎片化续包（无 id）被单独分组时抛
 * {@code NoSuchElementException: No value present}，同样需要降级处理。
 * <p>
 * 已发内容守卫（design D1）：上述错误常发生在叙述文本已流出之后（由结尾的 tool_call chunk 触发），
 * 此时静默降级重跑会把完整响应拼接在已发内容之后，造成前端与落库重复输出。故守卫记录本次订阅
 * 已发出的 chunk 数，已发出（>0）时不再降级，改抛 {@link PostEmissionStreamException} 交由
 * 调用层决策（RPG 回合 reset + 非流式重跑；主聊天仅保存已发部分）。
 */
public class StreamFallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(StreamFallbackChatModel.class);

    /** Spring AI OpenAiChatModel ChunkMerger 的断言错误消息 */
    private static final String MULTI_TOOL_CALL_CHUNK_ERROR =
            "no more than one tool call per message currently supported";

    /** ChunkMerger 类名（用于识别其内部抛出的其它流式异常） */
    private static final String CHUNK_MERGER_CLASS = "OpenAiChatModel$ChunkMerger";

    private final ChatModel delegate;
    private final String modelName;

    public StreamFallbackChatModel(ChatModel delegate, String modelName) {
        this.delegate = delegate;
        this.modelName = modelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            // 每次订阅独立的已发内容计数（冷流重订阅时重置，design D6）
            AtomicInteger emitted = new AtomicInteger(0);
            return delegate.stream(prompt)
                    .doOnNext(r -> emitted.incrementAndGet())
                    .onErrorResume(error -> {
                        if (!isChunkMergerStreamError(error)) {
                            return Flux.error(error);
                        }
                        int emittedCount = emitted.get();
                        if (emittedCount > 0) {
                            // 已发内容后禁止静默整体降级：重复拼接由调用层显式处理
                            log.warn("模型 [{}] 流式在已发出 {} 个 chunk 后遇到 ChunkMerger 不兼容格式，"
                                    + "不再静默降级，交由调用层处理: {}",
                                    modelName, emittedCount, error.getMessage());
                            return Flux.error(new PostEmissionStreamException(error, emittedCount));
                        }
                        log.warn("模型 [{}] 流式遇到 Spring AI ChunkMerger 不支持的 GLM 流式格式，"
                                + "降级为非流式 call 重试: {}", modelName, error.getMessage());
                        // 非流式调用为阻塞操作，切到 boundedElastic 避免占用 SDK 流处理线程
                        return Mono.fromCallable(() -> delegate.call(prompt))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flux();
                    });
        });
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @SuppressWarnings("removal")
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /**
     * 沿异常链识别 Spring AI ChunkMerger 对 GLM 流式格式不兼容的两类异常：
     * <ol>
     *   <li>单 chunk 多 tool_calls 断言失败（IllegalArgumentException，按消息匹配）</li>
     *   <li>碎片化续包无 id 导致的 NoSuchElementException（按堆帧类名匹配）</li>
     * </ol>
     */
    private boolean isChunkMergerStreamError(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains(MULTI_TOOL_CALL_CHUNK_ERROR)) {
                return true;
            }
            if (cur instanceof java.util.NoSuchElementException && fromChunkMerger(cur)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 异常堆栈是否出自 ChunkMerger 内部 */
    private boolean fromChunkMerger(Throwable error) {
        for (StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().endsWith(CHUNK_MERGER_CLASS)) {
                return true;
            }
        }
        return false;
    }
}
