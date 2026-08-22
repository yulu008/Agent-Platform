package com.luyu.agent.config;

import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
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
                    .build();
            models.put(name, chatModel);
            log.info("装配 ChatModel: name={} model={} base-url={} max-tokens={} timeout={} streamUsage=true obsReg={}",
                    name, props.getModel(), props.getBaseUrl(), props.getMaxTokens(), props.getTimeout(),
                    obsReg != ObservationRegistry.NOOP ? "ACTIVE" : "NOOP");
        }
        return models;
    }
}
