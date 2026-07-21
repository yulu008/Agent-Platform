package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent Platform 智能代理平台启动类
 * 基于 Spring AI 2.x + spring-ai-agent-utils 0.7.0
 */
@SpringBootApplication
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
