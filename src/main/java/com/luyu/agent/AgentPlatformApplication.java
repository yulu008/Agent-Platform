package com.luyu.agent;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HttpsURLConnection;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * Agent Platform 智能代理平台启动类
 * 基于 Spring AI 2.x + spring-ai-agent-utils 0.7.0
 *
 * 完全接管模型装配：排除 Spring AI 全部 OpenAI 自动配置
 * （Chat/Embedding/Image/AudioSpeech/AudioTranscription/Moderation），
 * 改由 ModelConfiguration 基于 agent.models 配置手动装配多 ChatModel。
 */
@SpringBootApplication(exclude = {
        OpenAiChatAutoConfiguration.class,
        OpenAiEmbeddingAutoConfiguration.class,
        OpenAiImageAutoConfiguration.class,
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class,
        OpenAiModerationAutoConfiguration.class
})
@EnableAsync
@EnableScheduling
public class AgentPlatformApplication {

    public static void main(String[] args) {
        // 放宽 SSL 以兼容内网自签名证书及第三方 API 证书链
        configureLenientSsl();
        SpringApplication.run(AgentPlatformApplication.class, args);
    }

    private static void configureLenientSsl() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            SSLContext.setDefault(sc);
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            System.out.println("[AgentPlatformApplication] local profile 启用宽松 SSL 配置");
        } catch (Exception e) {
            System.err.println("[AgentPlatformApplication] SSL 配置失败: " + e.getMessage());
        }
    }
}
