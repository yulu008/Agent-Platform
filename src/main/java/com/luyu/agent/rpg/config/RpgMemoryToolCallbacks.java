package com.luyu.agent.rpg.config;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * RPG 存档级记忆工具（{@code GmMemory*}）的持有者。
 * <p>
 * 与 {@link RpgGmToolCallbacks} 同理，采用显式持有者类型而非 {@code List<ToolCallback>}，
 * 以免被 {@code SessionConfiguration.chatClientRegistry(List<ToolCallback> tools)}
 * 按元素类型自动收集进<b>主聊天</b>工具池 —— 那会让主聊天凭空多出 4 个能写
 * {@code ~/.agent/rpg-saves} 的工具，破坏"主聊天池保持不变"的约束。
 *
 * @param callbacks 已用 ResilientToolCallback 包裹的 4 个 GmMemory* 工具
 */
public record RpgMemoryToolCallbacks(List<ToolCallback> callbacks) {
}
