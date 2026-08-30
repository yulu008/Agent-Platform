package com.luyu.agent.rpg.config;

import com.luyu.agent.config.ResilientToolCallback;
import com.luyu.agent.rpg.tools.RpgGmTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * RPG GM 工具注册配置。
 * <p>
 * 将 {@link RpgGmTools} 的 {@code @Tool} 方法转换为 {@link ToolCallback}，
 * 并用 {@link ResilientToolCallback} 包裹，复用现有容错机制。
 * <p>
 * RPG 工具通过 {@link #rpgToolCallbacks()} bean 注册，
 * 被 {@link com.luyu.agent.config.SessionConfiguration#chatClientRegistry} 的
 * {@code List<ToolCallback>} 自动注入，加入全局工具池。
 */
@Configuration
public class RpgToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RpgToolConfiguration.class);

    /**
     * 将 GM 工具方法转为 ToolCallback 并用 ResilientToolCallback 包裹。
     * <p>
     * 返回的 bean 会被 Spring AI 自动注入到 SessionConfiguration 的
     * {@code List<ToolCallback> tools} 参数中，最终挂载到对话 ChatClient。
     */
    @Bean
    public List<ToolCallback> rpgToolCallbacks(RpgGmTools rpgGmTools) {
        ToolCallback[] rawCallbacks = ToolCallbacks.from(rpgGmTools);
        List<ToolCallback> resilientCallbacks = Arrays.stream(rawCallbacks)
                .<ToolCallback>map(ResilientToolCallback::new)
                .toList();
        log.info("RPG GM 工具注册完成: {} 个只读工具已用 ResilientToolCallback 包裹", resilientCallbacks.size());
        return resilientCallbacks;
    }
}
