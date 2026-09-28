package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RpgMemoryPromptAdvisor} 单元测试。
 * <p>
 * 该 advisor 是「分支 B2」的全部落点，且踩在两个反直觉的框架行为上，故用测试钉住：
 * <ol>
 *   <li>{@code Prompt.augmentSystemMessage(String)} 是<b>整体替换</b>而非追加，
 *       误用会抹掉首轮的 GM 世界设定；</li>
 *   <li>{@code RpgGameController} 只在首轮调 {@code .system(...)}，第 2 轮起 prompt 里
 *       没有任何 SystemMessage，框架会以 {@code new SystemMessage("")} 调用注入函数。</li>
 * </ol>
 * 提示词经真实的 {@link GmContextAssembler} 从 classpath 加载（记忆规范 + 叙事规范合并为单一注入文本），
 * 因此这些用例同时验证了 {@code rpg/gm-memory-prompt.md}、{@code rpg/gm-narrative-rules.md} 的
 * 可加载性与内容完整性。advisor 为纯逻辑，无需 Spring 上下文
 * （{@code GmContextAssembler} 的构造函数只读 classpath 资源，不触碰仓库，可传 null）。
 */
class RpgMemoryPromptAdvisorTest {

    /** GM 首轮 system prompt 的起始行，取自 rpg/gm-system-prompt.md */
    private static final String GM_WORLD_PROMPT_HEAD = "# GM System Prompt（首轮注入）";

    /** 记忆规范提示词的标题，也是幂等哨兵 */
    private static final String MEMORY_PROMPT_HEAD = "# GM 记忆规范";

    /** 叙事规范提示词的标题，取自 rpg/gm-narrative-rules.md */
    private static final String NARRATIVE_RULES_HEAD = "# GM 叙事规范";

    private final GmContextAssembler contextAssembler =
            new GmContextAssembler(null, null, null, null);

    private final RpgMemoryPromptAdvisor advisor = new RpgMemoryPromptAdvisor(contextAssembler);

    private static ChatClientRequest requestWith(Message... messages) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(List.of(messages)))
                .build();
    }

    private static String systemTextOf(ChatClientRequest request) {
        SystemMessage systemMessage = request.prompt().getSystemMessage();
        return systemMessage == null ? null : systemMessage.getText();
    }

    @Test
    void 记忆规范提示词能从classpath加载且非空() {
        String prompt = new GmContextAssembler(null, null, null, null).getMemoryPrompt();
        assertThat(prompt).isNotBlank();
        assertThat(prompt).startsWith(MEMORY_PROMPT_HEAD);
        // 五类型定义齐全（rpg-player-memory：四类型→五类型，新增 player_memory）
        assertThat(prompt).contains("npc_memory", "foreshadow", "world_lore", "player_style", "player_memory");
        // player.md 骨架：frontmatter 类型/scope 与固定小节
        assertThat(prompt).contains("type: player_memory", "scope: player", "player.md");
        assertThat(prompt).contains("身份与背景", "秘密", "旅途节点", "能力与财物备注");
        // 数值/枚举事实走 player 节点、禁止写入 player.md 的边界条款
        assertThat(prompt).contains("player` 节点", "双写漂移");
        // frontmatter 五字段与 200 行截断告诫
        assertThat(prompt).contains("scope", "turn", "200 行");
        // 两步保存流程
        assertThat(prompt).contains("GmMemoryCreate", "GmMemoryInsert");
    }

    @Test
    void 提示词中的JSON花括号原样保留未被模板引擎吞掉() {
        // 任务 4.4：GmContextAssembler 用 ClassPathResource 原样读取、不经 PromptTemplate 渲染，
        // 花括号既不会被当成占位符抛异常，也不会被替换成空
        assertThat(advisor.before(requestWith(new UserMessage("我推开木门")), null)
                .prompt().getSystemMessage().getText())
                .contains("{\"flags\": {\"knows_secret_door\": true}}");
    }

    @Test
    void 第2轮起无SystemMessage_记忆规范被注入到消息首位() {
        ChatClientRequest result = advisor.before(requestWith(new UserMessage("我推开木门")), null);

        List<Message> messages = result.prompt().getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(systemTextOf(result)).startsWith(MEMORY_PROMPT_HEAD);
    }

    @Test
    void 首轮已有GM世界设定_记忆规范追加在其后而非覆盖() {
        String worldPrompt = GM_WORLD_PROMPT_HEAD + "\n\n你是一个沙盒 RPG 的 Game Master。";
        ChatClientRequest result = advisor.before(
                requestWith(new SystemMessage(worldPrompt), new UserMessage("我推开木门")), null);

        String text = systemTextOf(result);
        // 关键：augmentSystemMessage(String) 会整体替换，只有 Function 重载才能保住世界设定
        assertThat(text).startsWith(GM_WORLD_PROMPT_HEAD);
        assertThat(text).contains("你是一个沙盒 RPG 的 Game Master。");
        assertThat(text).contains(MEMORY_PROMPT_HEAD);
        assertThat(text.indexOf(GM_WORLD_PROMPT_HEAD)).isLessThan(text.indexOf(MEMORY_PROMPT_HEAD));
        // 消息条数不变，未额外插入一条 system
        assertThat(result.prompt().getInstructions()).hasSize(2);
    }

    @Test
    void 重复执行before_记忆规范只出现一次() {
        // order 已把本 advisor 排在 ToolCallingAdvisor 之外，工具循环不会重入；
        // 幂等哨兵是第二道保险，此处直接验证该保险有效
        ChatClientRequest once = advisor.before(requestWith(new UserMessage("我推开木门")), null);
        ChatClientRequest twice = advisor.before(once, null);

        assertThat(systemTextOf(twice)).containsOnlyOnce(MEMORY_PROMPT_HEAD);
    }

    @Test
    void 叙事规范提示词能从classpath加载且非空() {
        String rules = contextAssembler.getNarrativeRules();

        assertThat(rules).isNotBlank();
        assertThat(rules).startsWith(NARRATIVE_RULES_HEAD);
        // 规范 1：汉字指称且保留文化风格
        assertThat(rules).contains("汉字", "禁止罗马音");
        // 规范 2：禁章节字眼与章节式标题
        assertThat(rules).contains("序幕", "章节");
        // 规范 1 附带：npc_states 的 key 用角色卡 ID
        assertThat(rules).contains("npc_states", "角色卡 ID");
    }

    @Test
    void 注入文本同时含记忆规范与叙事规范() {
        // 两者合并为单一注入文本（D1）：一次 augment，不是两条 system 消息
        ChatClientRequest result = advisor.before(requestWith(new UserMessage("我推开木门")), null);

        String text = systemTextOf(result);
        assertThat(text).contains(MEMORY_PROMPT_HEAD).contains(NARRATIVE_RULES_HEAD);
        // 记忆规范在前、叙事规范紧随其后，且哨兵仍是记忆规范标题（幂等判断不受拼接影响）
        assertThat(text).startsWith(MEMORY_PROMPT_HEAD);
        assertThat(text.indexOf(MEMORY_PROMPT_HEAD)).isLessThan(text.indexOf(NARRATIVE_RULES_HEAD));
        assertThat(result.prompt().getInstructions()).hasSize(2);
    }

    @Test
    void 重复执行before_叙事规范只出现一次() {
        // 拼接后整体走同一条幂等哨兵，叙事规范不会在重入时多出一份
        ChatClientRequest once = advisor.before(requestWith(new UserMessage("我推开木门")), null);
        ChatClientRequest twice = advisor.before(once, null);

        assertThat(systemTextOf(twice)).containsOnlyOnce(NARRATIVE_RULES_HEAD);
    }

    @Test
    void 记忆与叙事规范都加载失败时整体放行() {
        // joinTemplates 对双空返回空串，advisor 必须走空文本放行分支而非塞一条空 SystemMessage
        GmContextAssembler broken = mock(GmContextAssembler.class);
        when(broken.getMemoryPrompt()).thenReturn("");
        when(broken.getNarrativeRules()).thenReturn("");
        RpgMemoryPromptAdvisor advisorWithoutPrompt = new RpgMemoryPromptAdvisor(broken);
        ChatClientRequest request = requestWith(new UserMessage("我推开木门"));

        assertThat(advisorWithoutPrompt.before(request, null)).isSameAs(request);
    }

    @Test
    void 仅叙事规范加载失败时只注入记忆规范() {
        GmContextAssembler partial = mock(GmContextAssembler.class);
        when(partial.getMemoryPrompt()).thenReturn(MEMORY_PROMPT_HEAD + "\n\n正文");
        when(partial.getNarrativeRules()).thenReturn(null);
        RpgMemoryPromptAdvisor memoryOnly = new RpgMemoryPromptAdvisor(partial);

        String text = systemTextOf(memoryOnly.before(requestWith(new UserMessage("我推开木门")), null));

        assertThat(text).contains(MEMORY_PROMPT_HEAD).doesNotContain(NARRATIVE_RULES_HEAD);
    }

    @Test
    void 提示词加载失败时不注入空SystemMessage() {
        // 空 system 消息会被部分模型服务商拒绝，故加载失败必须整体放行
        RpgMemoryPromptAdvisor broken = new RpgMemoryPromptAdvisor("");
        ChatClientRequest request = requestWith(new UserMessage("我推开木门"));

        ChatClientRequest result = broken.before(request, null);

        assertThat(result).isSameAs(request);
        assertThat(result.prompt().getInstructions()).hasSize(1);
    }

    @Test
    void order位于ToolCallingAdvisor与SessionMemoryAdvisor之外() {
        // ToolCallingAdvisor.DEFAULT_ORDER = MIN_VALUE + 300，其工具循环用 chain.copy(this)
        // 只重跑内层 advisor；本 advisor 必须更小，否则每次工具迭代都会追加一份提示词。
        // SessionMemoryAdvisor 默认 order = MIN_VALUE + 1000（design.md D6 要求小于它）。
        assertThat(advisor.getOrder())
                .isLessThan(Ordered.HIGHEST_PRECEDENCE + 300)
                .isLessThan(Ordered.HIGHEST_PRECEDENCE + 1000);
    }
}
