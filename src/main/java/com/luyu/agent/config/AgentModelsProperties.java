package com.luyu.agent.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多模型配置属性，绑定 application.yml 中 agent.models 结构。
 * <p>
 * 每个模型条目含独立 base-url/api-key/model/max-tokens/timeout/temperature/roles/default-model，
 * 由 ModelConfiguration 遍历装配为独立的 OpenAiChatModel + ChatClient.Builder。
 */
@ConfigurationProperties(prefix = "agent")
public class AgentModelsProperties {

    /** 模型名 -> 模型配置 */
    private Map<String, ModelProps> models;

    public Map<String, ModelProps> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelProps> models) {
        this.models = models;
    }

    /**
     * 单个模型配置。
     */
    public static class ModelProps {

        /** OpenAI 兼容 API 地址 */
        private String baseUrl;

        /** API 密钥 */
        private String apiKey;

        /** 模型名（如 glm-5.2 / qwen3.6-35b） */
        private String model;

        /** 最大生成 token 数（GLM 防 tool call 截断用 32768，本地 qwen 适配 ctx 用 3072） */
        private Integer maxTokens;

        /** 请求超时 */
        private Duration timeout;

        /** 采样温度 */
        private Double temperature;

        /** 角色列表（chat/compaction/title/subagent），决定 forRole 路由 */
        private List<String> roles;

        /** 是否为缺省对话模型 */
        private boolean defaultModel;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }

        public boolean isDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(boolean defaultModel) {
            this.defaultModel = defaultModel;
        }
    }
}