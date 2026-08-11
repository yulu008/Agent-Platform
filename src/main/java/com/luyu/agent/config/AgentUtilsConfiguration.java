package com.luyu.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Spring AI Agent 工具配置
 * 装配 SkillsTool Bean，从配置的 skills 根目录加载技能资源
 */
@Configuration
@EnableConfigurationProperties(AgentUtilsProperties.class)
public class AgentUtilsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentUtilsConfiguration.class);

    private final ResourceLoader resourceLoader;
    private final AgentUtilsProperties properties;

    public AgentUtilsConfiguration(ResourceLoader resourceLoader, AgentUtilsProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    /**
     * 装配 SkillsTool 工具回调
     * skills 目录不存在或为空时跳过装配，不阻断应用启动
     */
    @Bean
    public ToolCallback skillsToolCallback() {
        String skillsRoot = properties.skillsRoot();
        Resource resource = resourceLoader.getResource(skillsRoot);
        log.info("AgentUtils SkillsTool 装配中，skillsRoot={}", skillsRoot);

        if (!resource.exists()) {
            // 容错：skills 根目录不存在时跳过 SkillsTool 装配
            log.warn("skills 根目录不存在: {}，跳过 SkillsTool 装配", skillsRoot);
            return null;
        }

        try {
            return SkillsTool.builder()
                    .addSkillsResource(resource)
                    .build();
        } catch (IllegalArgumentException e) {
            // 容错：skills 目录无可用技能时跳过装配
            log.warn("skills 目录无可用技能，跳过 SkillsTool 装配: {}", e.getMessage());
            return null;
        }
    }
}
