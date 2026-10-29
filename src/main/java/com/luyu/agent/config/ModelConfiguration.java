package com.luyu.agent.config;

import java.lang.reflect.Field;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.luyu.agent.config.AgentModelsProperties.ModelProps;
import com.openai.core.Timeout;

/**
 * 多模型装配（决策 3：完全接管模型装配）。
 * <p>
 * 排除 Spring AI 的 {@code OpenAiChatAutoConfiguration} 后，由此类遍历 {@code agent.models}
 * 为每个模型构造 {@link OpenAiChatModel}。{@link OpenAiChatModel.Builder#build()} 内部会根据
 * {@link OpenAiChatOptions} 中的 baseUrl/apiKey/timeout 自动构造 {@code OpenAIClient}，
 * 无需手动 new；builder customizer 随后把配置 timeout 覆盖为「读空闲超时」语义
 * （见 {@link #chatModels} 内 clientCustomizer）。产出 {@code Map<String, ChatModel>}
 * 供 {@link SessionConfiguration} 构建各类 ChatClient。
 * <p>
 * 超时语义需要双重覆盖：customizer 只作用于基础 OkHttpClient，openai-java 的
 * {@code RequestOptions.from(ClientOptions)} 会把 {@code ClientOptions.timeout}
 *（源自 {@code OpenAiChatOptions.timeout}，默认映射 {@code Timeout.request}）
 * 注入每个请求，{@code SpringAiOpenAiHttpClient.newCall} 检测到后按请求重设
 * callTimeout，把基础客户端禁用的 callTimeout 完全架空（run 5ffd6d5b 的
 * 60s 撞线教训）。因此 build 后还需反射改写 ClientOptions.timeout 字段为
 * 同语义的读空闲值（见 {@link #neutralizePerRequestCallTimeout}）。
 */
@Configuration
@EnableConfigurationProperties(AgentModelsProperties.class)
public class ModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelConfiguration.class);

    /** 未配置 timeout 时的默认读空闲超时（流式 token 间隔正常亚秒级，思考期停顿数十秒内） */
    private static final Duration DEFAULT_READ_IDLE_TIMEOUT = Duration.ofSeconds(60);

    // 信任所有证书的 SSL 配置（解决内网/第三方 API 证书链不完整问题）
    private static final X509TrustManager TRUST_ALL_TM = new X509TrustManager() {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] certs, String authType) { }
        public void checkServerTrusted(X509Certificate[] certs, String authType) { }
    };

    private static final SSLSocketFactory TRUST_ALL_SSL_SF;
    private static final HostnameVerifier TRUST_ALL_HV = (hostname, session) -> true;

    static {
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{TRUST_ALL_TM}, new java.security.SecureRandom());
            TRUST_ALL_SSL_SF = sc.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("初始化宽松 SSL 失败", e);
        }
    }

    /**
     * 装配所有模型的 ChatModel 实例（参数隔离：每个模型独立 base-url/api-key/max-tokens/timeout）。
     */
    @Bean
    public Map<String, ChatModel> chatModels(
            AgentModelsProperties properties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry) {

        Map<String, ModelProps> config = properties.getModels();
        if (config == null || config.isEmpty()) {
            throw new IllegalStateException(
                    "agent.models 未配置任何模型，请在 application.yml 配置 agent.models");
        }

        ObservationRegistry obsReg = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
        Map<String, ChatModel> models = new LinkedHashMap<>();

        for (Map.Entry<String, ModelProps> entry : config.entrySet()) {
            String name = entry.getKey();
            ModelProps props = entry.getValue();

            // model()/temperature() 在父类 DefaultToolCallingChatOptions.Builder，单行调用避免链式类型歧义
            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .baseUrl(props.getBaseUrl())
                    .apiKey(props.getApiKey());
            optionsBuilder.model(props.getModel());
            // 启用流式 usage 回传：GLM API 在最后一个 chunk 携带 token 用量，
            // 配合 actuator 的 ObservationRegistry，使 gen_ai_client_token_usage_total 指标可见
            optionsBuilder.streamUsage(true);
            // 限制模型每条消息至多返回 1 个 tool_call：Spring AI 2.0.0 流式 ChunkMerger
            // 对单 chunk 含多个 tool_calls 会抛断言异常（GLM 网关会打包发送），
            // 请求层关闭并行工具调用以降低触发概率；兜底见 StreamFallbackChatModel
            optionsBuilder.parallelToolCalls(false);
            if (props.getMaxTokens() != null) {
                optionsBuilder.maxTokens(props.getMaxTokens());
            }
            if (props.getTemperature() != null) {
                optionsBuilder.temperature(props.getTemperature());
            }
            if (props.getTimeout() != null) {
                optionsBuilder.timeout(props.getTimeout());
            }

            // 超时语义覆盖 + 宽松 SSL：customizer 在 OpenAiSetup 默认 timeout 之后执行
            //（字节码验证的调用序：timeout(Duration) → customizers → build），可完整覆盖
            // 基础 OkHttpClient。默认映射 Timeout.request → okhttp callTimeout（整次调用
            // 墙钟上限）——glm-5.2 长流的生产教训：planner S3 单批 20 张人物卡实测
            // 100~170s+，token 持续流动中也会被撞线杀流。覆盖为「读空闲超时」
            //（Timeout.read → readTimeout）+ callTimeout 置 0（Duration.ZERO = 不限）：
            // token 在流动即不限总时长，仅网络/模型停摆超阈值才断流；connect 保持
            // SDK 默认 1 分钟。注意非流式（阻塞）调用同受 readTimeout：服务端完整
            // 生成后才首个字节，阈值对超长非流输出可能撞线（StreamFallbackChatModel
            // 降级路径需关注）
            Duration readIdle = props.getTimeout() != null ? props.getTimeout()
                    : DEFAULT_READ_IDLE_TIMEOUT;
            OpenAiHttpClientBuilderCustomizer clientCustomizer = builder -> {
                builder.sslSocketFactory(TRUST_ALL_SSL_SF);
                builder.trustManager(TRUST_ALL_TM);
                builder.hostnameVerifier(TRUST_ALL_HV);
                builder.timeout(Timeout.builder()
                        .read(readIdle)
                        .request(Duration.ZERO)
                        .build());
            };

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .options(optionsBuilder.build())
                    .toolCallingManager(toolCallingManager)
                    .observationRegistry(obsReg)
                    .httpClientBuilderCustomizer(clientCustomizer)
                    .build();
            // 每请求超时覆盖中和：基础客户端之上，还需改写 ClientOptions.timeout
            //（RequestOptions 回流源），否则每次请求都按 Timeout.request 重设
            // callTimeout，customizer 的禁用被架空
            neutralizePerRequestCallTimeout(chatModel, name, readIdle);
            // 流式多 tool_call 容错：单 chunk 多 tool_calls 断言失败时降级非流式重试
            models.put(name, new StreamFallbackChatModel(chatModel, name));
            log.info("装配 ChatModel: name={} model={} base-url={} max-tokens={} timeout={} streamUsage=true obsReg={}",
                    name, props.getModel(), props.getBaseUrl(), props.getMaxTokens(), props.getTimeout(),
                    obsReg != ObservationRegistry.NOOP ? "ACTIVE" : "NOOP");
        }
        return models;
    }

    /**
     * 改写 openai-java 客户端持有的 {@code ClientOptions.timeout} 为「读空闲」语义
     * （read = 配置值，request = ZERO → callTimeout 禁用）。
     * <p>
     * 根因（run 5ffd6d5b 60s 撞线，字节码 + 运行时探针双重实证）：
     * {@code OpenAiChatOptions.timeout} 经 {@code OpenAiSetup} 同时写入两条路径——
     * ① 基础 OkHttpClient（customizer 可覆盖，已验证生效）；② {@code ClientOptions.timeout}
     * = {@code Timeout{request=值}}。生成服务实现每请求执行
     * {@code RequestOptions.from(ClientOptions).applyDefaults(...)}，
     * {@code SpringAiOpenAiHttpClient.newCall} 检测到非空 timeout 即按请求重设
     * callTimeout——② 永远覆盖 ①，customizer 的 callTimeout(0) 从未真正生效。
     * <p>
     * 此处在 build 后对 sync/async 两客户端的 {@code clientOptions} /
     * {@code clientOptionsWithUserAgent} 反射改写 timeout 字段，使每请求覆盖携带
     * 的值与基础客户端同语义。失败仅告警不中断装配（SDK 升级字段变动时退化为
     * 既有每请求 callTimeout 行为，由 {@link ModelConfigurationTimeoutProbeTest}
     * 钉子测试守护）。
     */
    private static void neutralizePerRequestCallTimeout(
            OpenAiChatModel chatModel, String name, Duration readIdle) {
        Timeout readIdleTimeout = Timeout.builder()
                .read(readIdle)
                .request(Duration.ZERO)
                .build();
        for (String clientField : new String[] {"openAiClient", "openAiClientAsync"}) {
            Object client = readField(chatModel, clientField, name);
            if (client == null) {
                continue;
            }
            for (String optionsField : new String[] {"clientOptions", "clientOptionsWithUserAgent"}) {
                Object options = readField(client, optionsField, name);
                if (options == null) {
                    continue;
                }
                Field timeoutField = resolveField(options.getClass(), "timeout");
                if (timeoutField == null) {
                    log.warn("未找到 ClientOptions.timeout 字段，每请求超时覆盖未中和: model={}", name);
                    continue;
                }
                try {
                    timeoutField.setAccessible(true);
                    timeoutField.set(options, readIdleTimeout);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    log.warn("改写 ClientOptions.timeout 失败，每请求超时覆盖未中和: model={}", name, e);
                }
            }
        }
    }

    /** 反射读取字段值，任一异常仅告警并返回 null（结构漂移时跳过该路径） */
    private static Object readField(Object target, String fieldName, String name) {
        Field field = resolveField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn("反射读取字段 {}.{} 失败: model={}", target.getClass().getName(), fieldName, name, e);
            return null;
        }
    }

    /** 沿类层级向上解析字段（含私有），未找到返回 null */
    private static Field resolveField(Class<?> type, String fieldName) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // 继续向上
            }
        }
        return null;
    }
}
