package com.luyu.agent.rpg.eval.engine;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PromptHashCalculator} 单元测试（content-eval tasks 2.3）。
 * <p>
 * 钉住：同资源版本多轮 hash 一致（spec「同版本指纹稳定」）、资源内容/顺序
 * 变化 → 指纹变化、资源缺失 → null（防御不抛，事件行保持 NULL 语义）。
 */
class PromptHashCalculatorTest {

    private static Resource resource(String content) {
        return new ByteArrayResource(content.getBytes());
    }

    private ResourcePatternResolver resolverReturning(Resource... resources) throws IOException {
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(anyString())).thenReturn(resources);
        return resolver;
    }

    @Test
    void 同资源序列两次hash一致() {
        List<Resource> a = List.of(resource("GM system prompt"), resource("叙事规范"));
        List<Resource> b = List.of(resource("GM system prompt"), resource("叙事规范"));

        assertThat(PromptHashCalculator.hashOf(a)).isEqualTo(PromptHashCalculator.hashOf(b));
    }

    @Test
    void 指纹为十六进制前16位() {
        String hash = PromptHashCalculator.hashOf(List.of(resource("x")));

        assertThat(hash).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void 资源内容变化指纹变化() {
        String v1 = PromptHashCalculator.hashOf(List.of(resource("GM system prompt v1")));
        String v2 = PromptHashCalculator.hashOf(List.of(resource("GM system prompt v2")));

        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void 资源顺序对调指纹变化() {
        String ordered = PromptHashCalculator.hashOf(List.of(resource("A"), resource("B")));
        String swapped = PromptHashCalculator.hashOf(List.of(resource("B"), resource("A")));

        assertThat(ordered).isNotEqualTo(swapped);
    }

    @Test
    void 同一实例多轮计算走缓存且一致() throws IOException {
        PromptHashCalculator calculator = new PromptHashCalculator(
                resolverReturning(resource("GM system prompt"), resource("叙事规范")));

        String first = calculator.currentHash();
        String second = calculator.currentHash();

        assertThat(first).isNotNull().isEqualTo(second);
    }

    @Test
    void 资源清单任一缺失返回null防御不抛() throws IOException {
        // 五份资源里第三份缺失（getResources 返回空数组）
        ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
        when(resolver.getResources(anyString()))
                .thenReturn(new Resource[]{resource("a")})
                .thenReturn(new Resource[]{resource("b")})
                .thenReturn(new Resource[0]);
        PromptHashCalculator calculator = new PromptHashCalculator(resolver);

        assertThat(calculator.currentHash()).isNull();
    }
}
