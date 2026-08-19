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
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
