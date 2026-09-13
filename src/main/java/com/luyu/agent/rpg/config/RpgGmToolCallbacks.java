package com.luyu.agent.rpg.config;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * RPG GM 只读世界状态查询工具的持有者。
 * <p>
 * <b>为什么需要这个包装类型</b>：Spring 对集合型注入点是按<b>元素类型</b>收集 bean 的，
 * 一个 {@code List<ToolCallback>} 类型的 bean 本身并不是 {@code ToolCallback}，
 * 因此不会被收进另一处的 {@code List<ToolCallback>} 参数里。此前直接返回
 * {@code List<ToolCallback>} 导致 11 个 {@code get_*} 工具从未进入任何 ChatClient。
 * 改用显式持有者类型后可被精确注入。
 * <p>
 * 同时该类型不是 {@code ToolCallback}，也不会被主聊天的工具池自动收集，
 * 保证主聊天侧零变更。
 *
 * @param callbacks 已用 ResilientToolCallback 包裹的 11 个 get_* 工具
 */
public record RpgGmToolCallbacks(List<ToolCallback> callbacks) {
}
