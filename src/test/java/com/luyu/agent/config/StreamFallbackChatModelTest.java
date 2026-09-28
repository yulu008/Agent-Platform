package com.luyu.agent.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StreamFallbackChatModel} 已发内容守卫的单元测试（design D1/D6）。
 * <p>
 * 钉住三条路径：
 * <ol>
 *   <li>未发内容 + ChunkMerger 错误 → 维持原静默降级（非流式 call 重试）</li>
 *   <li>已发内容 + ChunkMerger 错误 → 抛 {@link PostEmissionStreamException}，不触发降级</li>
 *   <li>非 ChunkMerger 错误 → 原样传播，不降级</li>
 * </ol>
 */
class StreamFallbackChatModelTest {

    /** Spring AI OpenAiChatModel ChunkMerger 的单 chunk 多 tool_call 断言错误 */
    private static final IllegalArgumentException MULTI_TOOL_ERROR =
            new IllegalArgumentException("no more than one tool call per message currently supported");

    private final ChatModel delegate = mock(ChatModel.class);
    private final StreamFallbackChatModel model = new StreamFallbackChatModel(delegate, "test-model");
    private final Prompt prompt = new Prompt("玩家行动");

    private static ChatResponse resp(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void 未发内容遇ChunkMerger错误维持静默降级() {
        when(delegate.stream(prompt)).thenReturn(Flux.error(MULTI_TOOL_ERROR));
        ChatResponse fallback = resp("完整重试回复");
        when(delegate.call(prompt)).thenReturn(fallback);

        List<ChatResponse> out = model.stream(prompt)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(out).containsExactly(fallback);
    }

    @Test
    void ChunkMerger错误被中间层包装时仍能识别并降级() {
        // MessageAggregator 会把下游异常包成 "Aggregation Error" 再抛
        RuntimeException wrapped = new RuntimeException("Aggregation Error", MULTI_TOOL_ERROR);
        when(delegate.stream(prompt)).thenReturn(Flux.error(wrapped));
        ChatResponse fallback = resp("完整重试回复");
        when(delegate.call(prompt)).thenReturn(fallback);

        List<ChatResponse> out = model.stream(prompt)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(out).containsExactly(fallback);
    }

    @Test
    void 已发内容遇ChunkMerger错误抛专用异常不降级() {
        when(delegate.stream(prompt)).thenReturn(
                Flux.just(resp("半截叙述一"), resp("半截叙述二"))
                        .concatWith(Flux.error(MULTI_TOOL_ERROR)));
        when(delegate.call(any(Prompt.class)))
                .thenThrow(new AssertionError("已发内容后不应再静默降级重试"));

        List<ChatResponse> received = new ArrayList<>();
        AtomicReference<Throwable> caught = new AtomicReference<>();
        model.stream(prompt)
                .doOnNext(received::add)
                .doOnError(caught::set)
                .onErrorResume(e -> Flux.empty()) // 吞掉错误以便断言收集结果
                .blockLast(Duration.ofSeconds(5));

        // 已发的两个 chunk 正常到达下游，之后以专用异常终止
        assertThat(received).hasSize(2);
        assertThat(caught.get()).isInstanceOf(PostEmissionStreamException.class);
        assertThat(((PostEmissionStreamException) caught.get()).getEmittedCount()).isEqualTo(2);
        assertThat(caught.get().getCause()).isSameAs(MULTI_TOOL_ERROR);
        verify(delegate, never()).call(any(Prompt.class));
    }

    @Test
    void 非ChunkMerger错误原样传播不降级() {
        RuntimeException boom = new RuntimeException("模型超时");
        when(delegate.stream(prompt)).thenReturn(Flux.error(boom));

        AtomicReference<Throwable> caught = new AtomicReference<>();
        model.stream(prompt)
                .doOnError(caught::set)
                .onErrorResume(e -> Flux.empty())
                .blockLast(Duration.ofSeconds(5));

        assertThat(caught.get()).isSameAs(boom);
        verify(delegate, never()).call(any(Prompt.class));
    }
}
