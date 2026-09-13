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
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.luyu.agent.config.AgentModelsProperties.ModelProps;
import com.luyu.agent.rpg.config.RpgGmToolCallbacks;
import com.luyu.agent.rpg.config.RpgMemoryToolCallbacks;
import com.luyu.agent.rpg.engine.RpgMemoryPromptAdvisor;
import com.luyu.agent.service.TokenEstimator;

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
 * - ChatClientRegistry（多模型 ChatClient 注册表：对话 client 挂完整 advisor 链，辅助 client 纯净版）
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
     * 压缩策略：当上下文 token 总量达到 128k × 70% = 89,600 时自动触发压缩，
     * 保留最近 10 个事件（滑动窗口）
     */
    @Bean
    public SessionMemoryAdvisor sessionMemoryAdvisor(SessionService sessionService) {
        return SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId("default-user")
                .compactionTrigger(new TokenThresholdCompactionTrigger())
                .compactionStrategy(
                        SlidingWindowCompactionStrategy.builder()
                                .maxEvents(10)
                                .build())
                .build();
    }

    /**
     * 构建 RPG 专属 SessionMemoryAdvisor（maxEvents=30）。
     * <p>
     * RPG 单轮事件较大（包裹 prompt + 长叙述 + state_delta），且需要更长的可回看窗口，
     * 故与主聊天（maxEvents=10）隔离：仅由 RPG 专属 ChatClient 使用，
     * 不影响主聊天每请求携带的上下文体积/成本。其余配置（触发器、userId）与主聊天一致。
     */
    @Bean
    public SessionMemoryAdvisor rpgSessionMemoryAdvisor(SessionService sessionService) {
        return SessionMemoryAdvisor.builder(sessionService)
                .defaultUserId("default-user")
                .compactionTrigger(new TokenThresholdCompactionTrigger())
                .compactionStrategy(
                        SlidingWindowCompactionStrategy.builder()
                                .maxEvents(30)
                                .build())
                .build();
    }

    /**
     * 子 Agent 专用裸 Builder（固定 subagent 角色模型，供 SubagentConfiguration @Lazy 注入）。
     * <p>
     * 独立 bean 避免与 ChatClientRegistry 装配形成循环：SubagentConfiguration 装配时
     * 仅持有 @Lazy 代理，运行时子 agent 实际执行才解析为真实 Builder。
     */
    @Bean
    public ChatClient.Builder subagentBuilder(Map<String, ChatModel> chatModels,
                                               AgentModelsProperties properties) {
        for (Map.Entry<String, ModelProps> entry : properties.getModels().entrySet()) {
            List<String> roles = entry.getValue().getRoles();
            if (roles != null && roles.contains("subagent")) {
                ChatModel model = chatModels.get(entry.getKey());
                if (model == null) {
                    throw new IllegalStateException(
                            "subagent 角色模型 " + entry.getKey() + " 未装配 ChatModel");
                }
                return ChatClient.builder(model);
            }
        }
        throw new IllegalStateException("未配置 roles 含 subagent 的模型");
    }

    /**
     * 多模型 ChatClientRegistry（决策 5：三索引与裸 Builder 复用）。
     * <p>
     * 主聊天侧的容错工具链建一次共享，所有对话 client 复用同一套 advisor（advisor 无状态，通过 context 传 sessionId）。
     * 对话 client（byName）：每个模型用 fresh builder 挂完整 advisor 链 + 容错工具；
     * RPG client（rpgByName）：<b>独立工具池与独立容错链</b>，不挂 autoMemoryToolsAdvisor；
     * 辅助 client（byRole）：裸 build 纯净版（title/compaction）；
     * 辅助 builder（byRoleBuilder）：subagent 复用 subagentBuilder bean，其余裸 builder。
     *
     * 容错层级：
     * 1. ResilientToolCallbackResolver - 拦截空/未知工具名
     * 2. ResilientToolCallback - 拦截 JSON 解析失败
     */
    @Bean
    public ChatClientRegistry chatClientRegistry(Map<String, ChatModel> chatModels,
                                                  AgentModelsProperties properties,
                                                  AutoMemoryToolsAdvisor autoMemoryToolsAdvisor,
                                                  @Qualifier("sessionMemoryAdvisor") SessionMemoryAdvisor sessionMemoryAdvisor,
                                                  @Qualifier("rpgSessionMemoryAdvisor") SessionMemoryAdvisor rpgSessionMemoryAdvisor,
                                                  List<ToolCallback> tools,
                                                  RpgGmToolCallbacks rpgGmTools,
                                                  RpgMemoryToolCallbacks rpgMemoryTools,
                                                  RpgMemoryPromptAdvisor rpgMemoryPromptAdvisor,
                                                  SubagentConfiguration subagentConfig,
                                                  ChatClient.Builder subagentBuilder) {
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

        // 构建带容错 resolver 的 ToolCallingManager（共享，所有对话 client 复用）
        DefaultToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(resilientResolver)
                .build();

        // 构建共享 ToolCallingAdvisor
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .build();

        // ── RPG 专属工具池（与主聊天池完全隔离）──────────────────────────────
        // 内容 = 11 个 get_* 只读世界状态工具 + 4 个 GmMemory* 存档级记忆工具，共 15 个。
        // 刻意不含 weather / SmartWebFetch / Skill / Task：GM 在沙盒叙事中不应联网、不应查天气、
        // 不应派子 agent，更不应触碰主聊天的全局记忆根目录 ~/.agent/memories。
        // 两组 callback 在 RpgToolConfiguration 中已各自用 ResilientToolCallback 包裹，此处不再重复包裹。
        List<ToolCallback> rpgTools = new ArrayList<>(rpgGmTools.callbacks());
        rpgTools.addAll(rpgMemoryTools.callbacks());

        // RPG 独立的 resolver / manager / advisor：不与主聊天共享。
        // StaticToolCallbackResolver 按工具名建索引，两个池的索引必须分开，
        // 否则主聊天侧一旦引入同名工具就会互相覆盖。
        StaticToolCallbackResolver rpgStaticResolver = new StaticToolCallbackResolver(rpgTools);
        ResilientToolCallbackResolver rpgResilientResolver = new ResilientToolCallbackResolver(rpgStaticResolver);
        DefaultToolCallingManager rpgToolCallingManager = DefaultToolCallingManager.builder()
                .toolCallbackResolver(rpgResilientResolver)
                .build();
        ToolCallingAdvisor rpgToolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(rpgToolCallingManager)
                .build();

        // 遍历模型构建对话 client（byName）+ RPG client（rpgByName）+ 辅助 client（byRole）+ 辅助 builder（byRoleBuilder）
        Map<String, ChatClient> byName = new LinkedHashMap<>();
        Map<String, ChatClient> rpgByName = new LinkedHashMap<>();
        Map<String, ChatClient> byRole = new HashMap<>();
        Map<String, ChatClient.Builder> byRoleBuilder = new HashMap<>();
        String defaultName = null;

        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            String name = entry.getKey();
            ChatModel model = entry.getValue();
            ModelProps props = properties.getModels().get(name);
            List<String> roles = props.getRoles() != null ? props.getRoles() : List.of();

            // 对话 client：挂完整 advisor 链（每个用 fresh builder 避免 advisor 污染）
            // Advisor 顺序：AutoMemoryToolsAdvisor → SessionMemoryAdvisor → ToolCallingAdvisor → MalformedToolCallSanitizer
            if (roles.contains("chat")) {
                ChatClient chatClient = ChatClient.builder(model)
                        .defaultAdvisors(autoMemoryToolsAdvisor, sessionMemoryAdvisor,
                                toolCallingAdvisor, new MalformedToolCallSanitizer())
                        .defaultOptions(ToolCallingChatOptions.builder()
                                .toolCallbacks(resilientTools))
                        .build();
                byName.put(name, chatClient);

                // RPG 专属 client：同模型，但用 rpgSessionMemoryAdvisor（maxEvents=30）+ RPG 独立工具池。
                // advisor 链不含 autoMemoryToolsAdvisor —— GM 的记忆规范由 rpgMemoryPromptAdvisor 每轮注入，
                // 记忆工具由 GmMemory* 提供（落盘 ~/.agent/rpg-saves/<gameStateId>/）。
                // 这样既省掉主聊天那份 10.9KB 英文记忆提示词的每轮开销，
                // 也避免 GM 把玩家角色（PC）的事当成"用户信息"写进全局记忆污染主聊天用户画像。
                // rpgMemoryPromptAdvisor 的 order（MIN+200）在链中最小，故它最先执行且不受
                // ToolCallingAdvisor 工具循环重入影响（详见该类 javadoc）。
                ChatClient rpgClient = ChatClient.builder(model)
                        .defaultAdvisors(rpgMemoryPromptAdvisor, rpgSessionMemoryAdvisor,
                                rpgToolCallingAdvisor, new MalformedToolCallSanitizer())
                        .defaultOptions(ToolCallingChatOptions.builder()
                                .toolCallbacks(rpgTools))
                        .build();
                rpgByName.put(name, rpgClient);

                if (props.isDefaultModel()) {
                    defaultName = name;
                }
            }

            // 辅助 client + 裸 builder（固定到首个含该 role 的模型，即 glm）
            for (String role : roles) {
                if (!"chat".equals(role)) {
                    byRole.putIfAbsent(role, ChatClient.builder(model).build());
                    // subagent 复用 subagentBuilder bean（与 SubagentConfiguration @Lazy 注入一致）
                    if ("subagent".equals(role)) {
                        byRoleBuilder.putIfAbsent(role, subagentBuilder);
                    } else {
                        byRoleBuilder.putIfAbsent(role, ChatClient.builder(model));
                    }
                }
            }
        }

        if (!resilientTools.isEmpty()) {
            log.info("ChatClientRegistry 装配完成: 对话模型={} 辅助角色={} 工具数={}",
                    byName.keySet(), byRole.keySet(), resilientTools.size());
        } else {
            log.warn("ChatClientRegistry 未挂载任何工具回调（skills/agents 目录均为空）");
        }

        // RPG 专属对话 client 已按模型构建（rpgByName），与主对话 client 的差异有三处：
        //   1. SessionMemoryAdvisor 用 rpgSessionMemoryAdvisor（maxEvents=30，更长的可回看窗口）
        //   2. 工具池用 rpgTools（15 个：11 个 get_* + 4 个 GmMemory*），不含主聊天的记忆工具与联网工具
        //   3. 不挂 autoMemoryToolsAdvisor，改由 rpgMemoryPromptAdvisor 注入 RPG 记忆规范
        // ⚠️ 必须是独立 client，其 advisor 链中只含 rpgSessionMemoryAdvisor，不与默认 sessionMemoryAdvisor
        //    并存，否则记忆会被加载/写入两次（双写陷阱）。

        // 一次性计算工具定义 token 开销（名称 + 描述 + inputSchema）
        ChatClientRegistry registry = new ChatClientRegistry(byName, rpgByName, byRole, byRoleBuilder, defaultName);
        int toolTokens = resilientTools.stream()
                .mapToInt(tc -> {
                    var def = tc.getToolDefinition();
                    String schema = def.name() + " " + def.description() + " " + def.inputSchema();
                    return TokenEstimator.estimateTokens(schema);
                })
                .sum();
        registry.setCachedToolTokens(toolTokens);
        log.info("工具定义 token 开销估算: {} tokens ({} 个工具)", toolTokens, resilientTools.size());

        // RPG 池独立统计：与主聊天池的统计行并列打印，便于对比两侧的工具数与 token 开销
        int rpgToolTokens = rpgTools.stream()
                .mapToInt(tc -> {
                    var def = tc.getToolDefinition();
                    String schema = def.name() + " " + def.description() + " " + def.inputSchema();
                    return TokenEstimator.estimateTokens(schema);
                })
                .sum();
        log.info("RPG 专属工具池装配完成: GM 只读工具={} 存档记忆工具={} 合计={} 工具定义 token 开销估算={} tokens",
                rpgGmTools.callbacks().size(), rpgMemoryTools.callbacks().size(), rpgTools.size(), rpgToolTokens);

        return registry;
    }
}
