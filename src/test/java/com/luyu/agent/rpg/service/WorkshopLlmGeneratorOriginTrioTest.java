package com.luyu.agent.rpg.service;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.rpg.model.DestinedFigure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopLlmGenerator#generateOriginTrio} 单元测试（preset-llm-identity-trio tasks 1.3）。
 * <p>
 * 钉住三件事：
 * <ol>
 *   <li><b>星命一致性硬约束在册</b>：prompt 含「身份不得与星阶/阵营矛盾」类约束，
 *       且星阶/阵营/特质/招式名等上下文全部拼入（spec「星阶一致性约束在册」）；</li>
 *   <li><b>JSON 解析</b>：围栏剥离后解析为三字段并 strip；</li>
 *   <li><b>解析失败抛错</b>：非 JSON、三字段缺失/空白都抛 RuntimeException，
 *       由控制器转 500，前端保留输入框原值（spec「解析失败不返回半截」）。</li>
 * </ol>
 * ChatClient 链 mock 仿 {@code NpcKeyRepairServiceTest}（forRole("workshop") 纯净 client）。
 */
class WorkshopLlmGeneratorOriginTrioTest {

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

    @Test
    void prompt含星命一致性硬约束与全部上下文() {
        when(callSpec.content()).thenReturn(
                "{\"identity\":\"天龙座青铜圣斗士\",\"personality\":\"坚韧\",\"background\":\"背景故事\"}");

        generator.generateOriginTrio("卢煜", "male", "天龙座", "Bronze", "saint",
                "坚韧不拔", List.of("庐山升龙霸", "龙飞翔"));

        // 直接断言组装函数（包内可见），与 callLlm 实际发送的 prompt 同源
        String prompt = WorkshopLlmGenerator.buildOriginTrioPrompt("卢煜", "male", "天龙座",
                "Bronze", "saint", "坚韧不拔", List.of("庐山升龙霸", "龙飞翔"));
        assertThat(prompt)
                .contains("身份不得与星阶/阵营矛盾")
                .contains("不得写成黄金圣斗士")
                .contains("禁止罗马音、纯英文")
                .contains("50-150 字")
                .contains("卢煜").contains("天龙座").contains("Bronze")
                .contains("saint").contains("坚韧不拔")
                .contains("庐山升龙霸、龙飞翔");
    }

    /**
     * 人物模式（preset-destined-figures tasks 6.2）：prompt 追加人物段——人物名、
     * 档案底稿（identity/personality）与史实处境入 prompt，语义升级为「按星命 + 人物身份改写」；
     * base 段的星命上下文照常在场。
     */
    @Test
    void 人物模式prompt附人物名与档案底稿() {
        DestinedFigure figure = new DestinedFigure();
        figure.setFigureKey("nobunaga");
        figure.setDisplayName("织田信长");
        figure.setIdentity("尾张当主织田信长，天魁星冥衣命定者，人称第六天魔王");
        figure.setPersonality("桀骜狂放，视常理如无物");
        figure.setEraNote("弘治二年二十二岁，新取清洲的尾张当主");

        String prompt = WorkshopLlmGenerator.buildOriginTrioPrompt("织田信长", "male", "天魁星",
                "Specter-TianGang", "specter", "桀骜", null, figure);

        assertThat(prompt)
                .contains("命定人物模式追加要求")
                .contains("「织田信长」为命定历史人物")
                .contains("尾张当主织田信长，天魁星冥衣命定者，人称第六天魔王")
                .contains("桀骜狂放，视常理如无物")
                .contains("弘治二年二十二岁，新取清洲的尾张当主")
                .contains("不要逐字照抄底稿")
                .contains("天魁星").contains("Specter-TianGang").contains("specter");
    }

    /**
     * figure 为 null 时 8 参版与 7 参版逐字一致：无名新人路径零改动。
     */
    @Test
    void figure为null时八参版与七参版逐字一致() {
        String seven = WorkshopLlmGenerator.buildOriginTrioPrompt("卢煜", "male", "天龙座",
                "Bronze", "saint", "坚韧不拔", null);
        String eightNull = WorkshopLlmGenerator.buildOriginTrioPrompt("卢煜", "male", "天龙座",
                "Bronze", "saint", "坚韧不拔", null, null);

        assertThat(eightNull).isEqualTo(seven).doesNotContain("命定人物模式追加要求");
    }

    @Test
    void 招式为空时prompt显示无() {
        String prompt = WorkshopLlmGenerator.buildOriginTrioPrompt("卢煜", null, "天龙座",
                null, null, null, null);
        assertThat(prompt).contains("招式：无").contains("（未设定）");
    }

    @Test
    void 围栏包裹的JSON可解析为三字段并strip() {
        when(callSpec.content()).thenReturn("""
                ```json
                {"identity":" 天龙座青铜圣斗士 ","personality":"坚韧沉默","background":"少年自天龙座星光中觉醒。"}
                ```""");

        WorkshopLlmGenerator.OriginTrio trio = generator.generateOriginTrio(
                "卢煜", "male", "天龙座", "Bronze", "saint", "坚韧", null);

        assertThat(trio.identity()).isEqualTo("天龙座青铜圣斗士");
        assertThat(trio.personality()).isEqualTo("坚韧沉默");
        assertThat(trio.background()).isEqualTo("少年自天龙座星光中觉醒。");
    }

    @Test
    void 非JSON输出抛错() {
        when(callSpec.content()).thenReturn("抱歉，我无法生成。");

        assertThatThrownBy(() -> generator.generateOriginTrio(
                "卢煜", "male", "天龙座", "Bronze", "saint", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("解析三件套失败");
    }

    @Test
    void 三字段缺失或空白抛错() {
        when(callSpec.content()).thenReturn(
                "{\"identity\":\"天龙座青铜圣斗士\",\"personality\":\"\",\"background\":null}");

        assertThatThrownBy(() -> generator.generateOriginTrio(
                "卢煜", "male", "天龙座", "Bronze", "saint", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("解析三件套失败");
    }

    @Test
    void llm调用返回null抛错() {
        when(callSpec.content()).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> generator.generateOriginTrio(
                "卢煜", "male", "天龙座", "Bronze", "saint", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("LLM 生成三件套失败");
    }
}
