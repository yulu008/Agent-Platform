package com.luyu.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Agent Platform 智能代理平台启动类
 * 基于 Spring AI 2.x + spring-ai-agent-utils 0.7.0
 */
@SpringBootApplication
@EnableAsync
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
