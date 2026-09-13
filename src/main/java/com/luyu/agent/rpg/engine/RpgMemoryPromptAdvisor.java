package com.luyu.agent.rpg.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * GM 记忆规范提示词注入 Advisor（RPG 专属）。
 * <p>
 * 替代主聊天侧的 {@code AutoMemoryToolsAdvisor}：后者每轮无条件注入 10.9KB（≈2.7k tokens）
 * 的英文记忆系统提示词，其四类型分类学（user/feedback/project/reference）与 RPG 无关，
 * 还会诱导 GM 把玩家角色（PC）的事当成"用户信息"写进 {@code ~/.agent/memories}，污染主聊天用户画像。
 * 本 advisor 改为每轮注入 ~1.5KB 的中文 {@code rpg/gm-memory-prompt.md}。
 *
 * <h2>为什么必须每轮注入（而不是拼进首轮 system prompt）</h2>
 *
 * 对 {@code spring-ai-session-0.7.0} 做 {@code javap -c} 取证得到三条事实：
 * <ol>
 *   <li>{@code SessionMemoryAdvisor} <b>从不持久化 SystemMessage</b> —— {@code before()} 只
 *       {@code appendEvent(getLastUserOrToolResponseMessage())}，{@code after()} 只
 *       {@code appendEvent(Generation::getOutput)}（AssistantMessage）。它对 SystemMessage 的唯一
 *       处理是置顶归一化，不落库。</li>
 *   <li>{@code RpgGameController} 只在<b>首轮</b>调 {@code .system(...)}。</li>
 *   <li>增量模板 {@code gm-incremental-prompt.md} 是 <b>user</b> 消息，每轮都会被持久化。</li>
 * </ol>
 * 故：拼进首轮 system prompt 只在第 1 轮生效；塞进增量模板会在 maxEvents=30 的窗口内攒下最多
 * 30 份重复副本。唯一干净的落点是本 advisor —— 只在当轮请求中 augment，永不落库。
 *
 * <h2>order 取值依据</h2>
 *
 * 必须<b>小于</b> {@code ToolCallingAdvisor.DEFAULT_ORDER}（{@code Integer.MIN_VALUE + 300}）。
 * {@code ToolCallingAdvisor} 的工具循环用 {@code chain.copy(this).nextCall(...)} 只重跑<b>内层</b>
 * advisor，若本 advisor 落在其内层，每次工具迭代都会再追加一份记忆提示词，一轮内即可膨胀数倍。
 * 取 {@code Ordered.HIGHEST_PRECEDENCE + 200} 与库自带的 {@code AutoMemoryToolsAdvisor} 默认 order
 * 完全一致（该位置已被主聊天长期验证），同时满足 design.md D6 中「小于
 * {@code rpgSessionMemoryAdvisor}（{@code Integer.MIN_VALUE + 1000}）」的要求。
 * <p>
 * 即便如此，{@link #withMemoryPrompt} 仍做了幂等判断作为第二道保险。
 *
 * @see GmContextAssembler#getMemoryPrompt() 提示词加载（不经 PromptTemplate，避免花括号陷阱）
 */
@Component
public class RpgMemoryPromptAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RpgMemoryPromptAdvisor.class);

    /** 位于 ToolCallingAdvisor(MIN+300) 与 SessionMemoryAdvisor(MIN+1000) 之外，每轮只 augment 一次 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 200;

    private final String memoryPrompt;

    /** 幂等哨兵：提示词的首个非空行。取运行时值而非硬编码，避免与 md 文件标题脱钩 */
    private final String sentinel;

    @Autowired
    public RpgMemoryPromptAdvisor(GmContextAssembler contextAssembler) {
        this(contextAssembler.getMemoryPrompt());
    }

    /**
     * 供单测直接注入提示词文本（含「提示词加载失败」的空文本分支）。
     * <p>
     * 存在两个构造函数时 Spring 无法自行选择，故上方的 {@code GmContextAssembler}
     * 构造函数显式标了 {@code @Autowired}。
     */
    RpgMemoryPromptAdvisor(String memoryPrompt) {
        this.memoryPrompt = memoryPrompt == null ? "" : memoryPrompt;
        this.sentinel = firstNonBlankLine(this.memoryPrompt);
        if (this.memoryPrompt.isBlank()) {
            log.warn("GM 记忆规范提示词为空，RpgMemoryPromptAdvisor 将不做任何注入。"
                    + "请检查 resources/rpg/gm-memory-prompt.md 是否存在");
        } else {
            log.info("RpgMemoryPromptAdvisor 装配完成: 提示词 {} 字符, order={}",
                    this.memoryPrompt.length(), ORDER);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        // 提示词加载失败时直接放行：否则 augmentSystemMessage 会在无 SystemMessage 的轮次
        // 往 prompt 头部塞一条空 SystemMessage，部分模型服务商对空 system 消息会报错
        if (memoryPrompt.isBlank()) {
            return request;
        }
        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(this::withMemoryPrompt))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    /**
     * 把记忆规范<b>追加</b>到既有 system 消息之后。
     * <p>
     * 刻意使用 {@code augmentSystemMessage(Function)} 而非 {@code augmentSystemMessage(String)}：
     * 后者的实现是 {@code sm.mutate().text(传入字符串).build()}，即<b>整体替换</b>，
     * 首轮会把 GM 世界设定 system prompt 直接抹掉。
     * <p>
     * 无 SystemMessage 时（第 2 轮起），框架以 {@code new SystemMessage("")} 调用本函数
     * 并把结果插到消息列表首位，故需处理空文本分支。
     */
    private SystemMessage withMemoryPrompt(SystemMessage systemMessage) {
        String existing = systemMessage.getText();
        if (existing == null || existing.isBlank()) {
            return systemMessage.mutate().text(memoryPrompt).build();
        }
        if (existing.contains(sentinel)) {
            // 已注入过（理论上 order 已排除该情况，此处为第二道保险）
            return systemMessage;
        }
        return systemMessage.mutate()
                .text(existing + System.lineSeparator() + System.lineSeparator() + memoryPrompt)
                .build();
    }

    private static String firstNonBlankLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
    }
}
