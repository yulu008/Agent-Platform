package com.luyu.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.advisors.AutoMemoryToolsAdvisor;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.ai.session.compaction.SlidingWindowCompactionStrategy;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Session API 配置
 * 
 * 基于 spring-ai-session 社区库，配置 SessionMemoryAdvisor 替代原有的
 * MessageChatMemoryAdvisor + H2ChatMemory 组合。
 * 
 * 自动配置提供：
 * - JdbcSessionRepository（H2 数据库，schema 自动初始化）
 * - DefaultSessionService（会话生命周期管理）
 * 
 * 本配置类提供：
 * - AutoMemoryToolsAdvisor（长期记忆：LLM 自主读写记忆文件）
 * - SessionMemoryAdvisor（短期记忆：上下文加载、消息追加、自动压缩）
 * - ChatClient（带 Advisor + SkillsTool + TaskTool + 记忆工具 + 容错工具调用）
 */
@Configuration
public class SessionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionConfiguration.class);

    /** 长期记忆文件存储目录 */
    private static final String MEMORIES_DIR =
            System.getProperty("user.home") + "/.agent/memories";

    /**
     * 构建 AutoMemoryToolsAdvisor（长期记忆层）
     *
     * 注入记忆系统提示词，注册 6 个记忆文件操作工具，
     * 让 LLM 在对话中自主决定写入/读取跨会话记忆。
     * 必须在 SessionMemoryAdvisor 之前执行。
     */
    @Bean
    public AutoMemoryToolsAdvisor autoMemoryToolsAdvisor() {
        return AutoMemoryToolsAdvisor.builder()
                .memoriesRootDirectory(MEMORIES_DIR)
                .build();
    }

    /**
     * 构建 SessionMemoryAdvisor
     * 
     * 压缩策略：当累积 20 个轮次时触发，保留最近 10 个事件（滑动窗口）
     * 轮次 = 一条 UserMessage + 后续所有响应（assistant/tool）
     */
    @Bean
    public SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
        return SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId("default-user")
                .compactionTrigger(new TurnCountTrigger(20))
                .compactionStrategy(
                        SlidingWindowCompactionStrategy.builder()
                                .maxEvents(10)
                                .build())
                .build();
    }

    /**
     * 构建带 SessionMemoryAdvisor + SkillsTool + TaskTool + 容错工具调用的 ChatClient
     * 
     * 不使用 defaultToolCallbacks()（其内部 resolver 不容错），
     * 而是手动构建 ToolCallingAdvisor + 容错 resolver，
     * 通过 defaultOptions 传递工具定义给模型。
     * 
     * 容错层级：
     * 1. ResilientToolCallbackResolver - 拦截空/未知工具名
     * 2. ResilientToolCallback - 拦截 JSON 解析失败
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel,
                                 AutoMemoryToolsAdvisor autoMemoryToolsAdvisor,
                                 SessionMemoryAdvisor sessionMemoryAdvisor,
                                 List<ToolCallback> tools,
                                 SubagentConfiguration subagentConfig) {
        // 收集所有工具回调
        List<ToolCallback> allTools = new ArrayList<>(tools);
        ToolCallback taskTool = subagentConfig.createTaskToolCallback();
        if (taskTool != null) {
            allTools.add(taskTool);
        }

        // 构建 AutoMemoryTools 实例，转换为 ToolCallback[] 并加入工具列表
        AutoMemoryTools memoryTools = AutoMemoryTools.builder()
                .memoriesDir(MEMORIES_DIR)
                .build();
        allTools.addAll(Arrays.asList(ToolCallbacks.from(memoryTools)));

        // 用容错包装器包裹每个工具（拦截 JSON 解析失败）
        List<ToolCallback> resilientTools = allTools.stream()
                .<ToolCallback>map(ResilientToolCallback::new)
                .toList();

        // 构建容错 resolver（拦截空/未知工具名）
        StaticToolCallbackResolver staticResolver = new StaticToolCallbackResolver(resilientTools);
        ResilientToolCallbackResolver resilientResolver = new ResilientToolCallbackResolver(staticResolver);

        // 构建带容错 resolver 的 ToolCallingManager
        DefaultToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(resilientResolver)
                .build();

        // 构建 ToolCallingAdvisor
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .build();

        // 通过 defaultOptions 传递工具定义给模型（让模型知道有哪些工具可用）
        // Advisor 顺序：AutoMemoryToolsAdvisor → SessionMemoryAdvisor → ToolCallingAdvisor → MalformedToolCallSanitizer
        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultAdvisors(autoMemoryToolsAdvisor, sessionMemoryAdvisor,
                        toolCallingAdvisor, new MalformedToolCallSanitizer())
                .defaultOptions(ToolCallingChatOptions.builder()
                        .toolCallbacks(resilientTools));

        if (!resilientTools.isEmpty()) {
            log.info("ChatClient 挂载 {} 个工具回调（SkillsTool + TaskTool + MemoryTools，已包装双层容错）", resilientTools.size());
        } else {
            log.warn("ChatClient 未挂载任何工具回调（skills/agents 目录均为空）");
        }

        return builder.build();
    }
}
