package com.luyu.agent.rpg.eval.engine;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.JudgeJudgment;
import com.luyu.agent.rpg.eval.model.JudgeOutcome;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 judge 评分链单测（content-eval tasks 5.6）：rubric 版本指纹 / prompt
 * 组装四件套 / 宽反序列化各形态 / 三档 verdict 约束 / 证据核验与无证据作废 /
 * 畸形重试一次与二次降级 / judge 指纹记录（模型名 + rubric 版本）/ 未配置
 * judge 拒绝（零 LLM 调用）/ 逐调用计量 call_type=judge。
 */
class L2JudgeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ChatClientRegistry registry;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callSpec;

    private JudgePromptAssembler promptAssembler;
    private JudgeResponseParser responseParser;
    private EvidenceVerifier evidenceVerifier;
    private RubricLoader rubricLoader;
    private JudgeClient judgeClient;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(ChatClientRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(registry.forRole(JudgeClient.ROLE_JUDGE)).thenReturn(chatClient);
        when(registry.roleModelName(JudgeClient.ROLE_JUDGE)).thenReturn("glm-5.1");
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        rubricLoader = new RubricLoader();
        promptAssembler = new JudgePromptAssembler(rubricLoader);
        responseParser = new JudgeResponseParser();
        evidenceVerifier = new EvidenceVerifier();
        judgeClient = new JudgeClient(registry, promptAssembler, responseParser,
                evidenceVerifier, rubricLoader);
    }

    // ==================== RubricLoader ====================

    @Test
    void rubric_判据加载与版本指纹稳定() {
        RubricLoader loader = new RubricLoader();

        String content = loader.content();
        String version = loader.version();

        assertThat(content).contains("人物一致性").contains("密度基准");
        assertThat(version).matches("[0-9a-f]{16}");
        // classpath 资源只读：同进程任意次取值一致（judge 指纹可复现）
        assertThat(loader.content()).isEqualTo(content);
        assertThat(loader.version()).isEqualTo(version);
    }

    // ==================== JudgePromptAssembler ====================

    @Test
    void prompt_动态层全材料注入() {
        EvalCase c = dynamicCase();
        List<L1Warning> warnings = List.of(
                new L1Warning(L1Warning.CHECKER_HARD_FACTS, 1, "星位冲突预警", "景虎披挂双子座"));

        String prompt = promptAssembler.assemble(c, warnings);

        // 判据 + 被评人物 + 设定包 + 早/晚窗 + 轨迹 + 快照 + 版本指纹 + L1 预警
        assertThat(prompt).contains("人物一致性");
        assertThat(prompt).contains("长尾景虎").contains("kagetro").contains("dynamic");
        assertThat(prompt).contains("越后国主长尾家当主").contains("冷峻自持");
        assertThat(prompt).contains("早窗").contains("晚窗");
        assertThat(prompt).contains("第1轮 [assistant]: 景虎的处女座圣衣与主人共鸣。");
        assertThat(prompt).contains("第1轮 [user]: 景虎按兵不动。");
        assertThat(prompt).contains("player:cosmo +50");
        assertThat(prompt).contains("hash123");
        assertThat(prompt).contains("星位冲突预警").contains("第1轮");
    }

    @Test
    void prompt_静态层无叙述窗与无预警() {
        EvalCase c = staticCase();

        String prompt = promptAssembler.assemble(c, null);

        assertThat(prompt).contains("人物设定包").contains("冷峻自持");
        // 锚定装配器段标题（rubric 全文含「叙述窗」字样，不能用裸词断言）
        assertThat(prompt).doesNotContain("=== 叙述窗");
        assertThat(prompt).doesNotContain("hash123");
        // 无预警 → 显式「无」
        assertThat(prompt).contains("L1 规则引擎预警").contains("无");
    }

    // ==================== JudgeResponseParser ====================

    @Test
    void 解析_标准契约四条齐备() {
        List<JudgeJudgment> judgments = responseParser.parse(judgeJson());

        assertThat(judgments).hasSize(4);
        assertThat(judgments).extracting(JudgeJudgment::subDimension)
                .containsExactly("hard-facts", "motivation", "relations", "speech-style");
        assertThat(judgments).extracting(JudgeJudgment::verdict)
                .containsExactly("pass", "borderline", "fail", "pass");
        JudgeJudgment first = judgments.get(0);
        assertThat(first.rationale()).isEqualTo("事实一致");
        assertThat(first.evidence()).hasSize(1);
        assertThat(first.evidence().get(0).turn()).isEqualTo(1);
        assertThat(first.evidence().get(0).span()).isEqualTo("处女座圣衣与主人共鸣");
        assertThat(first.invalidReason()).isNull();
    }

    @Test
    void 解析_围栏与夹杂文字兜底截取() {
        String raw = "以下是评估结果：\n```json\n" + judgeJson() + "\n```\n请查收。";

        assertThat(responseParser.parse(raw)).hasSize(4);
    }

    @Test
    void 解析_宽反序列化形态互转() {
        // 字段名大写 / 子维度下划线 / verdict 大写 / rationale 数组 /
        // evidence 单对象 / turn 数字字符串 / span 数组拼接
        String raw = """
                {"Judgments": [
                  {"sub_dimension": "hard_facts", "verdict": "PASS",
                   "rationale": ["冷峻", "一致"],
                   "evidence": {"turn": "1", "span": ["处女座圣衣", "与主人共鸣"]}}
                ]}
                """;

        List<JudgeJudgment> judgments = responseParser.parse(raw);

        assertThat(judgments).hasSize(1);
        JudgeJudgment j = judgments.get(0);
        assertThat(j.subDimension()).isEqualTo("hard-facts");
        assertThat(j.verdict()).isEqualTo("pass");
        assertThat(j.rationale()).isEqualTo("冷峻 一致");
        assertThat(j.evidence()).hasSize(1);
        assertThat(j.evidence().get(0).turn()).isEqualTo(1);
        assertThat(j.evidence().get(0).span()).isEqualTo("处女座圣衣 与主人共鸣");
    }

    @Test
    void 解析_verdict非三档按畸形处理() {
        String raw = "{\"judgments\":[{\"sub_dimension\":\"hard-facts\","
                + "\"verdict\":\"很好\",\"rationale\":\"r\",\"evidence\":[{\"turn\":1,\"span\":\"s\"}]}]}";

        // spec「verdict 三档约束」：其余值走防御降级路径（重试/invalid），不落非法值
        assertThatThrownBy(() -> responseParser.parse(raw))
                .isInstanceOf(JudgeParseException.class)
                .hasMessageContaining("verdict 非三档");
    }

    @Test
    void 解析_非JSON与空输出畸形() {
        assertThatThrownBy(() -> responseParser.parse("这不是 JSON，只是闲聊。"))
                .isInstanceOf(JudgeParseException.class);
        assertThatThrownBy(() -> responseParser.parse(""))
                .isInstanceOf(JudgeParseException.class);
        assertThatThrownBy(() -> responseParser.parse(null))
                .isInstanceOf(JudgeParseException.class);
    }

    // ==================== EvidenceVerifier ====================

    @Test
    void 核验_证据定位成功与容空白模糊匹配() {
        EvalCase c = dynamicCase();
        JudgeJudgment exact = judgment("hard-facts", "pass",
                List.of(new JudgeJudgment.Evidence(1, "处女座圣衣与主人共鸣")));
        // span 内空白差异（judge 引用时的空格/换行）→ 剔空白后可匹配
        JudgeJudgment spaced = judgment("speech-style", "pass",
                List.of(new JudgeJudgment.Evidence(1, "处女座圣衣 与主人共鸣")));

        List<JudgeJudgment> verified = evidenceVerifier.verify(List.of(exact, spaced), c);

        assertThat(verified).extracting(JudgeJudgment::invalidReason)
                .containsOnly(null, null);
    }

    @Test
    void 核验_无证据判分作废() {
        EvalCase c = dynamicCase();
        JudgeJudgment noEvidence = judgment("motivation", "fail", List.of());

        List<JudgeJudgment> verified = evidenceVerifier.verify(List.of(noEvidence), c);

        assertThat(verified.get(0).invalidReason()).contains("无证据");
    }

    @Test
    void 核验_span幻觉作废仅该条_其余条目不受牵连() {
        EvalCase c = dynamicCase();
        JudgeJudgment hallucinated = judgment("relations", "fail",
                List.of(new JudgeJudgment.Evidence(2, "这句原文根本不存在于任何轮次。")));
        JudgeJudgment valid = judgment("speech-style", "pass",
                List.of(new JudgeJudgment.Evidence(2, "对峙于川中岛")));

        List<JudgeJudgment> verified = evidenceVerifier.verify(List.of(hallucinated, valid), c);

        assertThat(verified.get(0).invalidReason()).contains("无法定位");
        assertThat(verified.get(1).invalidReason()).isNull();
    }

    @Test
    void 核验_窗外轮次作废与静态层档案引用() {
        // 动态层：turn 不在窗内（轮次幻觉）
        EvalCase dynamic = dynamicCase();
        JudgeJudgment ghostTurn = judgment("hard-facts", "fail",
                List.of(new JudgeJudgment.Evidence(99, "处女座圣衣与主人共鸣")));
        assertThat(evidenceVerifier.verify(List.of(ghostTurn), dynamic).get(0).invalidReason())
                .contains("不在叙述窗");

        // 静态层：turn=-1（档案字段引用）→ 设定包文本定位
        EvalCase statik = staticCase();
        JudgeJudgment profileRef = judgment("hard-facts", "pass",
                List.of(new JudgeJudgment.Evidence(L1Warning.TURN_NONE, "越后国主长尾家当主")));
        assertThat(evidenceVerifier.verify(List.of(profileRef), statik).get(0).invalidReason())
                .isNull();
    }

    // ==================== JudgeClient ====================

    @Test
    void client_未配置judge拒绝_零LLM调用() {
        when(registry.roleModelName(JudgeClient.ROLE_JUDGE)).thenReturn(null);

        assertThat(judgeClient.isJudgeConfigured()).isFalse();
        assertThatThrownBy(() -> judgeClient.judge(dynamicCase(), List.of()))
                .isInstanceOf(EvalNotEnabledException.class)
                .hasMessageContaining("roles")
                .hasMessageContaining("judge");
        // spec：不发起任何 LLM 调用（未配置即未启用，无静默 fallback）
        verify(registry, never()).forRole(anyString());
    }

    @Test
    void client_指纹记录模型名与rubric版本() {
        assertThat(judgeClient.judgeModelName()).isEqualTo("glm-5.1");
        // judge 指纹 = 模型名 + rubric 内容 hash（spec「每条 record 记录 judge 指纹」）
        assertThat(judgeClient.judgeFingerprint()).matches("glm-5\\.1/[0-9a-f]{16}");
    }

    @Test
    void client_正常评分产出核验后结果_逐调用计量挂judge() {
        when(callSpec.content()).thenReturn(judgeJson());
        EvalCase c = dynamicCase();

        JudgeOutcome outcome = judgeClient.judge(c, List.of());

        assertThat(outcome.caseInvalidReason()).isNull();
        assertThat(outcome.judgments()).hasSize(4);
        assertThat(outcome.judgments()).extracting(JudgeJudgment::verdict)
                .contains("pass", "borderline", "fail");
        verify(registry, times(1)).forRole(JudgeClient.ROLE_JUDGE);

        // 计量惯例对齐：advisor param 捕获执行，call_type=judge
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Consumer<ChatClient.AdvisorSpec>> advisorCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(requestSpec, times(1)).advisors(advisorCaptor.capture());
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        advisorCaptor.getAllValues().forEach(consumer -> consumer.accept(advisorSpec));
        verify(advisorSpec, times(1)).param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "judge");
    }

    @Test
    void client_畸形重试一次成功不降级() {
        when(callSpec.content()).thenReturn("抱歉，我无法输出 JSON。", judgeJson());
        EvalCase c = dynamicCase();

        JudgeOutcome outcome = judgeClient.judge(c, List.of());

        // 第一次畸形 → 同 prompt 重试一次 → 第二次成功：正常产出，不降级
        assertThat(outcome.caseInvalidReason()).isNull();
        assertThat(outcome.judgments()).hasSize(4);
        verify(callSpec, times(2)).content();
    }

    @Test
    void client_二次失败降级invalid不中断() {
        when(callSpec.content()).thenReturn("两次都不是 JSON。");
        EvalCase c = dynamicCase();

        JudgeOutcome outcome = judgeClient.judge(c, List.of());

        // 三层防御终点：case 级 invalid（批跑不中断由编排层消费 outcome 保证）
        assertThat(outcome.caseInvalidReason()).contains("重试一次仍失败");
        assertThat(outcome.judgments()).isEmpty();
        verify(callSpec, times(2)).content();
    }

    // ==================== 构造辅助 ====================

    /** 标准 judge 输出（四子维度齐备，证据 span 均可定位） */
    private static String judgeJson() {
        return """
                {"judgments": [
                  {"sub_dimension": "hard-facts", "verdict": "pass", "rationale": "事实一致",
                   "evidence": [{"turn": 1, "span": "处女座圣衣与主人共鸣"}]},
                  {"sub_dimension": "motivation", "verdict": "borderline", "rationale": "动机轨迹存疑",
                   "evidence": [{"turn": 1, "span": "处女座圣衣与主人共鸣"}]},
                  {"sub_dimension": "relations", "verdict": "fail", "rationale": "关系突变无支撑",
                   "evidence": [{"turn": 2, "span": "对峙于川中岛"}]},
                  {"sub_dimension": "speech-style", "verdict": "pass", "rationale": "风格匹配档案",
                   "evidence": [{"turn": 1, "span": "处女座圣衣与主人共鸣"}]}
                ]}
                """;
    }

    private static JudgeJudgment judgment(String sub, String verdict,
                                          List<JudgeJudgment.Evidence> evidence) {
        return new JudgeJudgment(sub, verdict, "理由", evidence, null);
    }

    private static EvalCase dynamicCase() {
        return new EvalCase("dynamic", EvalCase.SOURCE_DESTINED, "kagetro", "长尾景虎",
                "{}", packJson(), List.of("景虎"),
                List.of(new EvalCase.TurnText(1, EvalCase.TurnText.ROLE_USER, "景虎按兵不动。", null),
                        new EvalCase.TurnText(1, EvalCase.TurnText.ROLE_ASSISTANT,
                                "景虎的处女座圣衣与主人共鸣。", "a1"),
                        new EvalCase.TurnText(2, EvalCase.TurnText.ROLE_ASSISTANT,
                                "晴信与景虎对峙于川中岛。", "a2")),
                List.of(new EvalCase.TurnText(1, EvalCase.TurnText.ROLE_USER, "景虎按兵不动。", null),
                        new EvalCase.TurnText(1, EvalCase.TurnText.ROLE_ASSISTANT,
                                "景虎的处女座圣衣与主人共鸣。", "a1")),
                "{}", "{}", "hash123",
                List.of(new EvalCase.TurnState(1, Map.of("player:cosmo", 50.0))));
    }

    private static EvalCase staticCase() {
        return new EvalCase("static", EvalCase.SOURCE_DESTINED, "kagetro", "长尾景虎",
                "{}", packJson(), List.of("景虎"),
                null, null, null, null, null, null);
    }

    private static String packJson() {
        try {
            return JSON.writeValueAsString(new EvalCase.SettingPack(
                    "{\"constellation\":\"处女座\",\"faction\":\"saint\"}",
                    "越后国主长尾家当主，处女座黄金圣衣命定者，人称越后之龙",
                    "冷峻自持，律己如僧，重义理而轻生死，战阵之上则如怒涛",
                    "幼年失怙、兄长乱政，十九岁便执掌越后兵权。", null, List.of("景虎", "越后之龙")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
