package com.luyu.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.openai.core.Timeout;

import okhttp3.OkHttpClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

/**
 * 超时语义覆盖运行时钉子（读空闲超时改造的实证验证）。
 * <p>
 * 生产教训链：OpenAiChatOptions.timeout(Duration) 经 OpenAiSetup 默认映射为
 * {@code Timeout.request → okhttp callTimeout}（整次调用墙钟上限）——planner
 * S3 批流 100~170s+ 也会被撞线杀流（run 5ffd6d5b 批 10 的 180s 教训）。改造
 * 为 builder customizer 覆盖 {@code read=配置值 / request=ZERO}：token 在流动
 * 即不限总时长。本测试按 ModelConfiguration 同构方式真实构建 ChatModel，
 * 反射挖出最终 OkHttpClient 断言四个超时分量——防止 SDK 内部行为变化
 * （如 ClientOptions.timeout 按请求覆盖回流 callTimeout）悄然破坏语义。
 * <p>
 * 第二钉子（每请求覆盖中和）：openai-java 生成服务每请求执行
 * {@code RequestOptions.from(ClientOptions)}，若 {@code ClientOptions.timeout}
 * 仍为 {@code Timeout{request=值}}，{@code SpringAiOpenAiHttpClient.newCall}
 * 会按请求重设 callTimeout 架空基础客户端的禁用（run 5ffd6d5b 60s 撞线
 * 二次教训——昨日 customizer 改造实际从未生效）。ModelConfiguration.build
 * 后的反射改写（neutralizePerRequestCallTimeout）需把这四个 ClientOptions
 * 实例的 timeout 字段也改为读空闲语义，本测试同构复现并断言。
 */
class ModelConfigurationTimeoutProbeTest {

    /** 与 ModelConfiguration 相同的读空闲超时（60s）与禁用 callTimeout（ZERO） */
    private static final Duration READ_IDLE = Duration.ofSeconds(60);

    @Test
    void customizer覆盖为读空闲语义且callTimeout禁用() throws Exception {
        OpenAiChatModel model = buildProbeModel();

        // 同步与异步客户端在 build() 时均已构造（Builder 字节码：两路
        // requireNonNullElseGet 均即时调用 setupSyncClient/setupAsyncClient）
        OkHttpClient syncClient = digOkHttp(field(model, "openAiClient"));
        OkHttpClient asyncClient = digOkHttp(field(model, "openAiClientAsync"));

        for (OkHttpClient ok : new OkHttpClient[] { syncClient, asyncClient }) {
            // 核心断言：callTimeout 禁用（0 = okhttp 无超时），read = 读空闲 60s
            assertThat(ok.callTimeoutMillis())
                    .as("callTimeout 应为 0（禁用）——非零意味着总时长墙钟上限回归")
                    .isZero();
            assertThat(ok.readTimeoutMillis())
                    .as("readTimeout 应为读空闲超时 60s")
                    .isEqualTo(60_000);
            // connect 保持 SDK 默认 1 分钟；write 未设回落 request()=ZERO 即禁用
            assertThat(ok.connectTimeoutMillis()).isEqualTo(60_000);
        }
    }

    @Test
    void 每请求超时源中和为读空闲语义() throws Exception {
        OpenAiChatModel model = buildProbeModel();

        // 改写前：ClientOptions.timeout = Timeout{request=60s}（每请求覆盖源，
        // 会回流 callTimeout 架空基础客户端）——先证明问题存在
        Timeout before = clientOptionsTimeout(model, "openAiClient", "clientOptions");
        assertThat(before.request())
                .as("改写前 request 应为 60s（证明每请求覆盖源真实存在）")
                .isEqualTo(READ_IDLE);

        // 同构复现 ModelConfiguration.neutralizePerRequestCallTimeout 的改写
        Timeout readIdleTimeout = Timeout.builder()
                .read(READ_IDLE)
                .request(Duration.ZERO)
                .build();
        int rewritten = 0;
        for (String clientField : new String[] { "openAiClient", "openAiClientAsync" }) {
            Object client = field(model, clientField);
            for (String optionsField : new String[] { "clientOptions", "clientOptionsWithUserAgent" }) {
                Object options = field(client, optionsField);
                if (options == null) {
                    continue;
                }
                Field timeoutField = options.getClass().getDeclaredField("timeout");
                timeoutField.setAccessible(true);
                timeoutField.set(options, readIdleTimeout);
                rewritten++;
            }
        }
        assertThat(rewritten)
                .as("应改写 4 个 ClientOptions（sync/async × clientOptions/clientOptionsWithUserAgent）")
                .isEqualTo(4);

        // 改写后：四个实例的 timeout 均为读空闲语义
        for (String clientField : new String[] { "openAiClient", "openAiClientAsync" }) {
            for (String optionsField : new String[] { "clientOptions", "clientOptionsWithUserAgent" }) {
                Timeout t = clientOptionsTimeout(model, clientField, optionsField);
                assertThat(t.read())
                        .as("%s.%s.timeout.read 应为读空闲 60s", clientField, optionsField)
                        .isEqualTo(READ_IDLE);
                assertThat(t.request())
                        .as("%s.%s.timeout.request 应为 ZERO（callTimeout 禁用）", clientField, optionsField)
                        .isEqualTo(Duration.ZERO);
            }
        }
    }

    /** 按 ModelConfiguration 同构方式构建探针模型（同 customizer / 同 timeout） */
    private static OpenAiChatModel buildProbeModel() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl("https://example.invalid/api")
                .apiKey("probe-key")
                .model("probe-model")
                .timeout(READ_IDLE)
                .build();

        OpenAiHttpClientBuilderCustomizer clientCustomizer = builder -> builder.timeout(Timeout.builder()
                .read(READ_IDLE)
                .request(Duration.ZERO)
                .build());

        return OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(clientCustomizer)
                .build();
    }

    /** 读取 model.<clientField>.<optionsField>.timeout */
    private static Timeout clientOptionsTimeout(
            Object model, String clientField, String optionsField) throws Exception {
        Object client = field(model, clientField);
        Object options = field(client, optionsField);
        assertThat(options).as("%s.%s 应可反射定位", clientField, optionsField).isNotNull();
        return (Timeout) field(options, "timeout");
    }

    /** OpenAIClient(Async)Impl → clientOptions → (original)httpClient → …递归定位 OkHttpClient */
    private static OkHttpClient digOkHttp(Object openAiClient) throws Exception {
        Object clientOptions = field(openAiClient, "clientOptions");
        Object httpClient = null;
        for (String fieldName : new String[] { "httpClient", "originalHttpClient" }) {
            try {
                httpClient = field(clientOptions, fieldName);
                break;
            } catch (NoSuchFieldException ignored) {
                // 尝试下一个字段名
            }
        }
        assertThat(httpClient).as("ClientOptions.httpClient 应可反射定位").isNotNull();
        System.out.println("[probe] openAiClient=" + openAiClient.getClass().getName()
                + " httpClient=" + httpClient.getClass().getName());
        for (Field f : httpClient.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object v = f.get(httpClient);
            System.out.println("[probe]   " + f.getName() + " = "
                    + (v == null ? "null" : v.getClass().getName()));
        }
        OkHttpClient found = findOkHttp(httpClient, 0);
        assertThat(found).as("应能定位到底层 OkHttpClient").isNotNull();
        return found;
    }

    /** 深度优先（限 6 层）在对象图里找 OkHttpClient 实例（只遍历 SDK 包；全程打印便于诊断包装链） */
    private static OkHttpClient findOkHttp(Object node, int depth) throws Exception {
        if (node == null || depth > 6) {
            return null;
        }
        if (node instanceof OkHttpClient ok) {
            System.out.println("[probe] ★ OkHttpClient at depth=" + depth);
            return ok;
        }
        String cn = node.getClass().getName();
        if (!(cn.startsWith("com.openai.") || cn.startsWith("org.springframework.")
                || cn.startsWith("okhttp3.") || cn.startsWith("okio."))) {
            return null;
        }
        System.out.println("[probe] visit depth=" + depth + " " + cn);
        for (Field f : node.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object child = f.get(node);
            System.out.println("[probe]   " + f.getName() + " = "
                    + (child == null ? "null" : child.getClass().getName()));
            if (child == null || child == node) {
                continue;
            }
            OkHttpClient found = findOkHttp(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
