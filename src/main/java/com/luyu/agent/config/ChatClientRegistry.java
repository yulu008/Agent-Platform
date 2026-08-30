package com.luyu.agent.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.util.Assert;

/**
 * 多模型 ChatClient 注册表（决策 5：三索引与裸 Builder 复用）。
 * <p>
 * 维护三个索引：
 * <ul>
 *   <li>{@code byName} —— 对话 client（挂完整 advisor 链），按模型名查询，供请求级路由</li>
 *   <li>{@code byRole} —— 辅助纯净 client（无 advisor），按角色查询（title/compaction）</li>
 *   <li>{@code byRoleBuilder} —— 辅助裸 ChatClient.Builder（无 advisor），按角色查询（subagent 需 Builder 而非 client）</li>
 * </ul>
 * 一个 role 多模型时取 default 标记的，否则取装配时首个注册的。
 */
public class ChatClientRegistry {

    private final Map<String, ChatClient> byName;
    private final Map<String, ChatClient> byRole;
    private final Map<String, ChatClient.Builder> byRoleBuilder;
    private final String defaultName;

    /** 工具定义的估算 token 开销（启动时一次性计算并缓存） */
    private final AtomicInteger cachedToolTokens = new AtomicInteger(0);

    public ChatClientRegistry(Map<String, ChatClient> byName,
                               Map<String, ChatClient> byRole,
                               Map<String, ChatClient.Builder> byRoleBuilder,
                               String defaultName) {
        Assert.notEmpty(byName, "至少配置一个 roles 含 chat 的模型");
        Assert.hasText(defaultName, "未配置 default-model=true 的对话模型");
        Assert.isTrue(byName.containsKey(defaultName),
                "default 模型 [" + defaultName + "] 不在对话模型列表 " + byName.keySet());
        this.byName = Collections.unmodifiableMap(byName);
        this.byRole = Collections.unmodifiableMap(byRole);
        this.byRoleBuilder = Collections.unmodifiableMap(byRoleBuilder);
        this.defaultName = defaultName;
    }

    /**
     * 请求级对话路由：缺省（null/空）取 default 模型，未知模型抛 400 友好提示。
     */
    public ChatClient forChat(String name) {
        String key = (name == null || name.isBlank()) ? defaultName : name;
        ChatClient client = byName.get(key);
        if (client == null) {
            throw new IllegalArgumentException(
                    "未知模型: " + name + "，可用模型: " + byName.keySet());
        }
        return client;
    }

    /**
     * 辅助任务按角色取纯净 client（title/compaction）。
     */
    public ChatClient forRole(String role) {
        ChatClient client = byRole.get(role);
        if (client == null) {
            throw new IllegalArgumentException("未配置辅助角色: " + role);
        }
        return client;
    }

    /**
     * 辅助任务按角色取裸 Builder（subagent，传给 ClaudeSubagentType.chatClientBuilder）。
     */
    public ChatClient.Builder forRoleBuilder(String role) {
        ChatClient.Builder builder = byRoleBuilder.get(role);
        if (builder == null) {
            throw new IllegalArgumentException("未配置辅助角色 builder: " + role);
        }
        return builder;
    }

    /**
     * 前端模型选择器列表（仅对话角色模型）。
     */
    public List<String> listChatModels() {
        return List.copyOf(byName.keySet());
    }

    public String getDefaultName() {
        return defaultName;
    }

    /**
     * 设置工具定义的 token 开销缓存（启动时由 SessionConfiguration 调用）
     */
    public void setCachedToolTokens(int tokens) {
        cachedToolTokens.set(tokens);
    }

    /**
     * 获取工具定义的估算 token 开销
     */
    public int getCachedToolTokens() {
        return cachedToolTokens.get();
    }
}