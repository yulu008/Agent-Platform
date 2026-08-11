package com.luyu.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent Utils 配置属性
 * 绑定 spring.ai.agent-utils 前缀，控制 Skills 加载根目录、子Agent定义根目录与限流参数
 */
@ConfigurationProperties(prefix = "spring.ai.agent-utils")
public record AgentUtilsProperties(String skillsRoot, String agentsRoot, Subagent subagent) {

    /**
     * 子Agent限流配置，绑定 spring.ai.agent-utils.subagent 前缀
     */
    public record Subagent(Integer maxParallelism, Duration lifespan) {
        public Subagent(Integer maxParallelism, Duration lifespan) {
            this.maxParallelism = (maxParallelism == null || maxParallelism <= 0) ? 4 : maxParallelism;
            this.lifespan = (lifespan == null || lifespan.isZero() || lifespan.isNegative())
                    ? Duration.ofSeconds(120) : lifespan;
        }
    }

    /**
     * 规范构造器：未配置或空值时使用默认值
     */
    public AgentUtilsProperties(String skillsRoot, String agentsRoot, Subagent subagent) {
        this.skillsRoot = (skillsRoot == null || skillsRoot.isBlank())
                ? "classpath:skills" : skillsRoot;
        this.agentsRoot = (agentsRoot == null || agentsRoot.isBlank())
                ? "classpath:agents" : agentsRoot;
        this.subagent = (subagent == null) ? new Subagent(null, null) : subagent;
    }
}
