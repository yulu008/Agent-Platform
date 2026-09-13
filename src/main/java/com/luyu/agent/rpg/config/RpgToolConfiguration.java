package com.luyu.agent.rpg.config;

import com.luyu.agent.config.ResilientToolCallback;
import com.luyu.agent.rpg.tools.RpgGmTools;
import com.luyu.agent.rpg.tools.SaveScopedMemoryCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RPG 专属工具池装配。
 * <p>
 * 产出两组工具，供 {@code SessionConfiguration.chatClientRegistry} 组装成 RPG 独立工具池：
 * <ul>
 *   <li>{@link RpgGmToolCallbacks} —— 11 个 {@code get_*} 只读世界状态查询工具</li>
 *   <li>{@link RpgMemoryToolCallbacks} —— 4 个 {@code GmMemory*} 存档级记忆工具</li>
 * </ul>
 * <p>
 * <b>与主聊天工具池完全隔离</b>：两组都用显式持有者类型（非 {@code ToolCallback}）承载，
 * 因此不会被 {@code chatClientRegistry(List<ToolCallback> tools)} 按元素类型自动收集进主聊天池。
 * RPG 池也刻意不含 weather / SmartWebFetch / Skill / Task —— {@code gm-system-prompt.md}
 * 从未向 GM 声明它们，留着只贡献 token 与误调用面。
 * <p>
 * <b>记忆工具改名的原因</b>：{@code AutoMemoryToolsAdvisor.before()} 会用「当前请求已存在的
 * 工具名集合」过滤自己要注册的记忆工具，且 {@code StaticToolCallbackResolver} 按名建索引 ——
 * 同名不同 root 的实例无法共存，指向别的目录的第二个 advisor 其工具会被静默丢弃。
 * 改名为 {@code GmMemory*} 后两层冲突都不存在。
 * <p>
 * 同时作为 RPG 侧 {@code @ConfigurationProperties} 的注册点（沿用 {@code ModelConfiguration}
 * 对 {@code AgentModelsProperties} 的做法：属性类自身不加 {@code @Component}）。
 */
@Configuration
@EnableConfigurationProperties(RpgMemoryProperties.class)
public class RpgToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RpgToolConfiguration.class);

    /**
     * RPG 存档记忆根目录。每个存档占一个子目录 {@code <gameStateId>/}，
     * 与主聊天的 {@code ~/.agent/memories} 完全隔离。
     * <p>
     * 子目录前缀由 {@link SaveScopedMemoryCallback} 在调用期注入，
     * 因为 {@code AutoMemoryTools} 的 root 在构建期就固定、无法随存档变化。
     */
    private static final String RPG_SAVES_DIR =
            System.getProperty("user.home") + "/.agent/rpg-saves";

    /**
     * 底层 AutoMemoryTools 工具名 → 对外暴露的 GM 工具名（保序，仅用于装配与日志）。
     * <p>
     * 刻意只保留 4 个，不提供 Delete 与 Rename：
     * <ul>
     *   <li><b>Delete</b>：GM 不应销毁记忆，错误记忆用 StrReplace 改写；
     *       存档记忆的清除随删档由系统负责。</li>
     *   <li><b>Rename</b>：文件名由「一 NPC 一文件 {@code npc_<npcId>.md}」约定固定，
     *       无重命名场景。</li>
     * </ul>
     * 少两个工具即少两份 inputSchema 与 description 的 token 开销，也少两个误操作面。
     */
    private static final Map<String, String> GM_MEMORY_TOOL_NAMES = buildGmMemoryToolNames();

    /** GmMemory* 工具描述。不复述记忆类型分类学 —— 单一真源在 resources/rpg/gm-memory-prompt.md */
    private static final Map<String, String> GM_MEMORY_DESCRIPTIONS = buildGmMemoryDescriptions();

    private static Map<String, String> buildGmMemoryToolNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("MemoryView", "GmMemoryView");
        names.put("MemoryCreate", "GmMemoryCreate");
        names.put("MemoryStrReplace", "GmMemoryStrReplace");
        names.put("MemoryInsert", "GmMemoryInsert");
        return Map.copyOf(names);
    }

    private static Map<String, String> buildGmMemoryDescriptions() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("GmMemoryView",
                "读取当前存档的记忆文件。path 为相对当前存档根目录的路径，"
                        + "如 MEMORY.md（索引）或 npc_<npcId>.md；lineRange 可选，格式为\"起始行-结束行\"。");
        d.put("GmMemoryCreate",
                "在当前存档新建记忆文件。path 为相对存档根的文件名，fileText 为完整文件内容"
                        + "（须以 YAML frontmatter 开头）。新建后必须用 GmMemoryInsert 向 MEMORY.md 追加一行索引。");
        d.put("GmMemoryStrReplace",
                "替换记忆文件中的一段文本，用于累积更新既有记忆（如补充某 NPC 的新印象）。"
                        + "oldStr 必须与文件中现有文本完全一致且唯一，newStr 为替换后的完整文本。");
        d.put("GmMemoryInsert",
                "在记忆文件指定行之后插入文本，主要用于向 MEMORY.md 追加索引行。"
                        + "insertLine 为插入位置行号，insertText 为要插入的内容。");
        return Map.copyOf(d);
    }

    /**
     * 装配 11 个 GM 只读查询工具。
     * <p>
     * 返回持有者类型而非 {@code List<ToolCallback>}：后者不会被 Spring 收进
     * {@code chatClientRegistry} 的 {@code List<ToolCallback>} 参数（集合注入按元素类型匹配），
     * 曾导致这批工具从未进入任何 ChatClient。
     */
    @Bean
    public RpgGmToolCallbacks rpgGmToolCallbacks(RpgGmTools rpgGmTools) {
        ToolCallback[] rawCallbacks = ToolCallbacks.from(rpgGmTools);
        List<ToolCallback> resilientCallbacks = Arrays.stream(rawCallbacks)
                .<ToolCallback>map(ResilientToolCallback::new)
                .toList();
        log.info("RPG GM 只读工具装配完成: {} 个（已用 ResilientToolCallback 包裹）",
                resilientCallbacks.size());
        return new RpgGmToolCallbacks(resilientCallbacks);
    }

    /**
     * 装配 4 个存档作用域记忆工具。
     * <p>
     * 三层结构（由外到内）：
     * <pre>
     * ResilientToolCallback      容错 + toolcall.log 可观测性
     *   └ SaveScopedMemoryCallback  改名 + 按 ToolContext 注入 &lt;gameStateId&gt;/ 前缀
     *       └ AutoMemoryTools        文件操作底座，root = ~/.agent/rpg-saves
     * </pre>
     * <p>
     * {@code AutoMemoryTools} 实例在此就地构建，<b>不注册为 Spring bean</b>，
     * 避免其 {@code ToolCallback} 泄漏进主聊天工具池。
     */
    @Bean
    public RpgMemoryToolCallbacks rpgMemoryToolCallbacks() {
        AutoMemoryTools memoryTools = AutoMemoryTools.builder()
                .memoriesDir(RPG_SAVES_DIR)
                .build();

        Map<String, ToolCallback> rawByName = new LinkedHashMap<>();
        for (ToolCallback callback : ToolCallbacks.from(memoryTools)) {
            rawByName.put(callback.getToolDefinition().name(), callback);
        }

        List<ToolCallback> callbacks = new ArrayList<>();
        for (Map.Entry<String, String> entry : GM_MEMORY_TOOL_NAMES.entrySet()) {
            String rawName = entry.getKey();
            String gmName = entry.getValue();
            ToolCallback delegate = rawByName.get(rawName);
            if (delegate == null) {
                // 库升级改了工具名时必须显式失败，否则 GM 会静默失去记忆能力
                throw new IllegalStateException("AutoMemoryTools 未提供工具 [" + rawName
                        + "]，无法构建 " + gmName + "；实际可用工具: " + rawByName.keySet());
            }
            callbacks.add(new ResilientToolCallback(new SaveScopedMemoryCallback(
                    delegate, gmName, GM_MEMORY_DESCRIPTIONS.get(gmName))));
        }

        log.info("RPG 存档记忆工具装配完成: {} 个 {}（root={}）",
                callbacks.size(), callbacks.stream()
                        .map(tc -> tc.getToolDefinition().name())
                        .toList(),
                RPG_SAVES_DIR);
        return new RpgMemoryToolCallbacks(List.copyOf(callbacks));
    }
}
