package com.example.agent.controller;

import com.example.agent.config.SubagentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 最小对话端点
 * 主 ChatClient 挂载 SkillsTool + TaskTool（子Agent委派）工具回调，
 * 模型可在 /chat 链路中自主调用技能发现与子Agent委派。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder, List<ToolCallback> tools,
                         SubagentConfiguration subagentConfig) {
        // Spring 收集所有非 null 的 ToolCallback Bean（skillsToolCallback）
        // TaskTool 不注册为 Bean 以避免循环依赖，由 SubagentConfiguration 显式创建
        List<ToolCallback> allTools = new ArrayList<>(tools);
        ToolCallback taskTool = subagentConfig.createTaskToolCallback();
        if (taskTool != null) {
            allTools.add(taskTool);
        }

        ChatClient.Builder builder = chatClientBuilder;
        if (!allTools.isEmpty()) {
            builder = builder.defaultToolCallbacks(allTools.toArray(new ToolCallback[0]));
            log.info("主 ChatClient 挂载 {} 个工具回调（SkillsTool + TaskTool）", allTools.size());
        } else {
            log.warn("主 ChatClient 未挂载任何工具回调（skills/agents 目录均为空）");
        }
        this.chatClient = builder.build();
        log.info("主 ChatClient 构建完成");
    }

    /**
     * 对话端点
     * 请求体: {"message":"hello"}
     * 响应体: {"response":"模型回复"}
     */
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String response = chatClient.prompt()
                .user(message)
                .call()
                .content();
        return Map.of("response", response);
    }
}
