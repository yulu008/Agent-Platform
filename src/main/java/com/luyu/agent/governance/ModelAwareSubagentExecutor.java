package com.luyu.agent.governance;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentDefinition;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

/**
 * 修复 ClaudeSubagentExecutor 的 model 名覆盖缺陷。
 * <p>
 * <b>背景</b>：ClaudeSubagentExecutor.doFindChatClientBuilder() 在解析子Agent定义的
 * {@code model:} frontmatter 字段时，将值同时用作 <b>builder key</b>（查找 ChatClient.Builder）
 * 和 <b>模型名</b>（通过 {@code ChatOptions.builder().model(value)} 覆盖到 ChatClient）。
 * <p>
 * 内置子Agent（Bash、Plan）的 markdown 定义中写有 {@code model: default}，
 * "default" 是 builder key 而非真实模型名。这导致 API 请求中 model 被覆盖为 "default"，
 * 非 Claude API（如 GLM）会返回 {@code 403: 您无权访问default}。
 * <p>
 * <b>修复策略</b>：覆写 doFindChatClientBuilder，当 model 值为 "default" 时跳过模型名覆盖，
 * 让 ChatModel 自身的 OpenAiChatOptions（如 model=glm-5.2）生效。
 * 支持 {@code model: default:实际模型名} 语法（冒号分隔），此时 builderKey=default、modelName=实际模型名。
 */
public class ModelAwareSubagentExecutor extends ClaudeSubagentExecutor {

    private static final Logger log = LoggerFactory.getLogger(ModelAwareSubagentExecutor.class);

    /** 父类的 chatClientBuilderMap 是 private，子类需持有自有引用 */
    private final Map<String, ChatClient.Builder> builderMap;

    public ModelAwareSubagentExecutor(Map<String, ChatClient.Builder> chatClientBuilderMap,
                                      List<ToolCallback> tools,
                                      List<String> skillsDirectories) {
        super(chatClientBuilderMap, tools, skillsDirectories);
        this.builderMap = chatClientBuilderMap;
    }

    /**
     * 覆写父类方法：当 {@code model: default} 时跳过模型名覆盖。
     * <p>
     * 父类逻辑会将 "default" 同时作为 builder key 和 model name，
     * 导致 {@code ChatOptions.model("default")} 覆盖了 ChatModel 的真实模型名。
     * 此处拦截 "default"，直接返回 default builder 不做 model 覆盖。
     * <p>
     * 对于 {@code model: default:glm-5.2}（冒号语法），父类会正确拆分为
     * builderKey=default、modelName=glm-5.2，此时不拦截，委托父类处理。
     */
    @Override
    protected ChatClient.Builder doFindChatClientBuilder(ClaudeSubagentDefinition definition) {
        String model = definition.getModel();
        if (StringUtils.hasText(model)) {
            String trimmed = model.trim();
            // 仅当 model 值就是 "default"（无冒号分隔）时才需要拦截
            // 冒号语法 "default:xxx" 由父类正确处理
            if ("default".equals(trimmed)) {
                log.debug("子Agent model=default，跳过模型名覆盖，使用 ChatModel 原始配置: definition={}",
                        definition.getName());
                return builderMap.get("default");
            }
        }
        return super.doFindChatClientBuilder(definition);
    }
}
