package com.luyu.agent.config;

import java.security.cert.X509Certificate;
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

/**
 * 多模型装配（决策 3：完全接管模型装配）。
 * <p>
 * 排除 Spring AI 的 {@code OpenAiChatAutoConfiguration} 后，由此类遍历 {@code agent.models}
 * 为每个模型构造 {@link OpenAiChatModel}。{@link OpenAiChatModel.Builder#build()} 内部会根据
 * {@link OpenAiChatOptions} 中的 baseUrl/apiKey/timeout 自动构造 {@code OpenAIClient}，
 * 无需手动 new。产出 {@code Map<String, ChatModel>} 供 {@link SessionConfiguration} 构建各类 ChatClient。
 */
@Configuration
@EnableConfigurationProperties(AgentModelsProperties.class)
public class ModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelConfiguration.class);

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

        OpenAiHttpClientBuilderCustomizer sslCustomizer = builder -> {
            builder.sslSocketFactory(TRUST_ALL_SSL_SF);
            builder.trustManager(TRUST_ALL_TM);
            builder.hostnameVerifier(TRUST_ALL_HV);
        };

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

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .options(optionsBuilder.build())
                    .toolCallingManager(toolCallingManager)
                    .observationRegistry(obsReg)
                    .httpClientBuilderCustomizer(sslCustomizer)
                    .build();
            // 流式多 tool_call 容错：单 chunk 多 tool_calls 断言失败时降级非流式重试
            models.put(name, new StreamFallbackChatModel(chatModel, name));
            log.info("装配 ChatModel: name={} model={} base-url={} max-tokens={} timeout={} streamUsage=true obsReg={}",
                    name, props.getModel(), props.getBaseUrl(), props.getMaxTokens(), props.getTimeout(),
                    obsReg != ObservationRegistry.NOOP ? "ACTIVE" : "NOOP");
        }
        return models;
    }
}
