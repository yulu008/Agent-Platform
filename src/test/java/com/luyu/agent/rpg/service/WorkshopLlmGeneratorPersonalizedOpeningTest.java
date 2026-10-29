package com.luyu.agent.rpg.service;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.WorldSetting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopLlmGenerator#generatePersonalizedOpening} 单元测试
 * （preset-destined-figures tasks 6.1）。
 * <p>
 * 钉住 prompt 要素契约（spec「人物模式走 LLM 开场白」）：人物名、史实处境（eraNote）、
 * 1556 稻生合战前夕格局、世界观与玩家角色三件套全部拼入，且语义锚定「玩家即该人物本人」；
 * LLM 输出 strip 后返回；调用失败抛错由调用方降级模板路径。
 * ChatClient 链 mock 仿 {@code WorkshopLlmGeneratorOriginTrioTest}。
 */
class WorkshopLlmGeneratorPersonalizedOpeningTest {

    private ChatClient.CallResponseSpec callSpec;
    private WorkshopLlmGenerator generator;

    @BeforeEach
    void setUp() {
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        when(registry.forRole("workshop")).thenReturn(client);
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        generator = new WorkshopLlmGenerator(registry);
    }

    private static WorldSetting world() {
        WorldSetting w = new WorldSetting();
        w.setName("战国圣衣录");
        w.setEra("弘治二年（西历 1556 年）");
        w.setSettingDesc("圣衣与冥衣并立的战国");
        w.setAtmosphere("下克上之风席卷列国");
        return w;
    }

    private static CharacterCard player() {
        CharacterCard c = new CharacterCard();
        c.setName("织田信长");
        c.setIdentity("尾张当主织田信长，天魁星冥衣命定者，人称第六天魔王");
        c.setPersonality("桀骜狂放，视常理如无物");
        c.setBackground("人称「尾张大傻瓜」的倾奇者");
        c.setMotivation("天下布武");
        return c;
    }

    @Test
    void prompt含人物身份史实处境与稻生格局要素() {
        String prompt = WorkshopLlmGenerator.buildPersonalizedOpeningPrompt(world(), player(),
                "织田信长",
                "弘治二年二十二岁，新取清洲的尾张当主，与其弟信胜陈兵相向，稻生合战未起");

        assertThat(prompt)
                .contains("织田信长")
                .contains("弘治二年二十二岁，新取清洲的尾张当主，与其弟信胜陈兵相向，稻生合战未起")
                .contains("1556")
                .contains("稻生合战")
                .contains("下克上")
                .contains("玩家即该人物本人")
                .contains("战国圣衣录")
                .contains("弘治二年（西历 1556 年）")
                .contains("尾张当主织田信长，天魁星冥衣命定者，人称第六天魔王")
                .contains("桀骜狂放，视常理如无物")
                .contains("禁止罗马音、纯英文");
    }

    @Test
    void 人物名与处境缺省时显降级文案不NPE() {
        String prompt = WorkshopLlmGenerator.buildPersonalizedOpeningPrompt(world(), null,
                null, null);

        assertThat(prompt)
                .contains("（未设定）")
                .contains("（未命名）");
    }

    @Test
    void llm输出strip后返回() {
        when(callSpec.content()).thenReturn("  尾张，清洲城。信长睁眼。  \n");

        String opening = generator.generatePersonalizedOpening(world(), player(),
                "织田信长", "弘治二年二十二岁");

        assertThat(opening).isEqualTo("尾张，清洲城。信长睁眼。");
    }

    @Test
    void llm调用失败抛错() {
        when(callSpec.content()).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> generator.generatePersonalizedOpening(world(), player(),
                "织田信长", "弘治二年二十二岁"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM 生成人物开场白失败");
    }
}
