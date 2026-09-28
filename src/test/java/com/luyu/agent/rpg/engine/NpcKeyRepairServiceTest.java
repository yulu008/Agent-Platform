package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.config.ChatClientRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NpcKeyRepairService} 的单元测试（mock ChatClient 链）。
 * <p>
 * 覆盖三条路径：修复成功（返回合法汉字 key JSON）、修复输出仍含非法 key /
 * 非对象（返回节点由调用方再校验）、调用抛异常（返回 null）。
 * 并断言修复调用的请求体包含叙述原文与合法 key 清单。
 */
class NpcKeyRepairServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatClientRegistry registry;
    private ChatClient client;
    private ChatClient.ChatClientRequestSpec spec;
    private ChatClient.CallResponseSpec callSpec;
    private NpcKeyRepairService service;

    @BeforeEach
    void setUp() {
        registry = mock(ChatClientRegistry.class);
        client = mock(ChatClient.class);
        spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(registry.forRole("workshop")).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        // NpcKeyRepairService 链上带 MeteringAdvisor 参数（CALL_TYPE_CONTEXT_KEY），mock 需 stub advisors
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        service = new NpcKeyRepairService(registry);
    }

    private JsonNode delta() throws Exception {
        return MAPPER.readTree(
                "{\"npc_states\": {\"gray_robed_servant\": {\"status\": \"引路\"}}}");
    }

    @Test
    void 修复成功返回合法汉字key的npcStates对象() throws Exception {
        when(callSpec.content()).thenReturn("""
                ```json
                {"灰袍仆人": {"status": "引路"}}
                ```
                """);

        JsonNode repaired = service.repair("灰袍仆人提着灯笼在前面引路。",
                delta(), Set.of("gray_robed_servant"), Set.of("苏枕雪"));

        assertThat(repaired).isNotNull();
        assertThat(repaired.isObject()).isTrue();
        assertThat(repaired.has("灰袍仆人")).isTrue();
        assertThat(repaired.get("灰袍仆人").get("status").asText()).isEqualTo("引路");
    }

    @Test
    void 修复输出仍含非法key时原样返回由调用方再校验() throws Exception {
        // 服务职责边界：只解析返回节点；key 是否仍非法由调用方用 StateDeltaKeyValidator 再校验
        when(callSpec.content()).thenReturn(
                "{\"gray_robed_servant\": {\"status\": \"引路\"}, \"灰袍仆人\": {\"status\": \"提灯\"}}");

        JsonNode repaired = service.repair("灰袍仆人提灯引路。",
                delta(), Set.of("gray_robed_servant"), Set.of());

        assertThat(repaired).isNotNull();
        assertThat(repaired.has("gray_robed_servant")).isTrue();
        assertThat(repaired.has("灰袍仆人")).isTrue();
    }

    @Test
    void 调用抛异常时返回null() throws Exception {
        when(spec.call()).thenThrow(new RuntimeException("模型超时"));

        JsonNode repaired = service.repair("叙述", delta(),
                Set.of("gray_robed_servant"), Set.of());

        assertThat(repaired).isNull();
    }

    @Test
    void 输出非JSON对象时返回null() throws Exception {
        when(callSpec.content()).thenReturn("抱歉，我无法处理该请求。");

        JsonNode repaired = service.repair("叙述", delta(),
                Set.of("gray_robed_servant"), Set.of());

        assertThat(repaired).isNull();
    }

    @Test
    void 修复请求体包含叙述原文与合法key清单() throws Exception {
        when(callSpec.content()).thenReturn("{\"灰袍仆人\": {\"status\": \"引路\"}}");

        service.repair("一个灰袍仆人提着灯笼走过来。",
                delta(), Set.of("gray_robed_servant"), Set.of("苏枕雪", "船老大"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(spec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt)
                .contains("一个灰袍仆人提着灯笼走过来。")
                .contains("gray_robed_servant")
                .contains("苏枕雪")
                .contains("船老大");
    }

    @Test
    void 叙述含乱码替换符时prompt剔除乱码并提示叠字可能() throws Exception {
        when(callSpec.content()).thenReturn("{\"的场\": {\"status\": \"戒备\"}}");

        service.repair("「……我叫\uFFFD的场\uFFFD的场。」他顿了顿。",
                delta(), Set.of("matoba"), Set.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(spec).user(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt)
                .contains("我叫的场的场")
                .doesNotContain("\uFFFD")
                .contains("叠字");
    }

    @Test
    void 修复输出叠字名自动折半() throws Exception {
        // 兜底：即使修复模型仍输出叠加名「的场的场」，后处理折半为「的场」
        when(callSpec.content()).thenReturn(
                "{\"的场的场\": {\"status\": \"已自报姓名\"}, \"灰袍仆人\": {\"status\": \"引路\"}}");

        JsonNode repaired = service.repair("我叫的场的场。",
                delta(), Set.of("matoba"), Set.of());

        assertThat(repaired).isNotNull();
        assertThat(repaired.has("的场")).isTrue();
        assertThat(repaired.has("的场的场")).isFalse();
        assertThat(repaired.has("灰袍仆人")).isTrue();
    }

    @Test
    void 修复输出key含乱码替换符时被清洗() throws Exception {
        when(callSpec.content()).thenReturn(
                "{\"的场\uFFFD\": {\"status\": \"戒备\"}}");

        JsonNode repaired = service.repair("我叫的场。",
                delta(), Set.of("matoba"), Set.of());

        assertThat(repaired).isNotNull();
        assertThat(repaired.has("的场")).isTrue();
        assertThat(repaired.size()).isEqualTo(1);
    }

    @Test
    void 修复输出key清洗后为空时丢弃该条目() throws Exception {
        when(callSpec.content()).thenReturn(
                "{\"\uFFFD\": {\"status\": \"戒备\"}}");

        JsonNode repaired = service.repair("叙述", delta(), Set.of("matoba"), Set.of());

        assertThat(repaired).isNotNull();
        assertThat(repaired.isEmpty()).isTrue();
    }
}
