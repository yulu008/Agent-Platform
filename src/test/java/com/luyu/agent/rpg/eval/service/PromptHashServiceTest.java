package com.luyu.agent.rpg.eval.service;

import com.luyu.agent.rpg.eval.engine.PromptHashCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PromptHashService} 单元测试（content-eval tasks 2.3）。
 * <p>
 * 钉住：附着 SQL 形态与参数绑定（独立列 UPDATE 幂等）、指纹不可用/事件 ID
 * 缺失的防御跳过、UPDATE 零命中与 SQL 异常均不抛（尾包不可被附着失败阻断）。
 */
class PromptHashServiceTest {

    private PromptHashCalculator calculator;
    private JdbcTemplate jdbcTemplate;
    private PromptHashService service;

    @BeforeEach
    void setUp() {
        calculator = mock(PromptHashCalculator.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new PromptHashService(calculator, jdbcTemplate);
    }

    @Test
    void 附着执行独立列UPDATE并绑定指纹与事件ID() {
        when(calculator.currentHash()).thenReturn("ab12cd34ef56ab12");

        service.attachToEvent("ev-1");

        verify(jdbcTemplate).update(
                eq("UPDATE AI_SESSION_EVENT SET prompt_hash = ? WHERE id = ?"),
                eq("ab12cd34ef56ab12"), eq("ev-1"));
    }

    @Test
    void 指纹不可用时跳过附着事件行保持NULL() {
        when(calculator.currentHash()).thenReturn(null);

        service.attachToEvent("ev-1");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void 事件ID为null或空白时跳过() {
        service.attachToEvent(null);
        service.attachToEvent("  ");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void UPDATE零命中不抛() {
        when(calculator.currentHash()).thenReturn("ab12cd34ef56ab12");
        when(jdbcTemplate.update(anyString(), anyString(), anyString())).thenReturn(0);

        assertThatCode(() -> service.attachToEvent("ev-gone")).doesNotThrowAnyException();
    }

    @Test
    void SQL异常不抛不阻断尾包() {
        when(calculator.currentHash()).thenReturn("ab12cd34ef56ab12");
        when(jdbcTemplate.update(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("column not found"));

        assertThatCode(() -> service.attachToEvent("ev-1")).doesNotThrowAnyException();
    }
}
