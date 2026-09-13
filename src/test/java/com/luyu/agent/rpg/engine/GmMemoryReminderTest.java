package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.config.RpgMemoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 每 N 轮记忆整理提醒的单元测试。
 * <p>
 * 覆盖两处易错点：
 * <ol>
 *   <li><b>追加位置</b>——提醒必须落在 {@code ## GM 任务指令} 段内，绝不能落进
 *       {@code ## 玩家行动} 与下一个 {@code \n## } 之间，否则 {@link RpgHistoryCleaner}
 *       会把系统提醒当成玩家说的话回放给前端；</li>
 *   <li><b>关闭语义</b>——{@code remind-every-turns <= 0} 时必须完全关闭且不能触发除零。</li>
 * </ol>
 * 两者均为纯逻辑，无需 Spring 上下文（{@code GmContextAssembler} 构造函数只读 classpath 资源）。
 */
class GmMemoryReminderTest {

    private static final String REMINDER_MARKER = "整理笔记";
    private static final String PLAYER_ACTION = "我把玉佩当给了林掌柜";

    private final GmContextAssembler assembler = new GmContextAssembler(null, null, null, null);
    private final RpgHistoryCleaner cleaner = new RpgHistoryCleaner(new StateDeltaSanitizer());

    @Test
    void 开启提醒_追加在GM任务指令段内而非玩家行动段() {
        String prompt = assembler.assembleIncremental(PLAYER_ACTION, List.of(), true);

        int reminderIdx = prompt.indexOf(REMINDER_MARKER);
        assertThat(reminderIdx).isPositive();
        // 必须排在两个段标记之后，即位于模板末尾的 ## GM 任务指令 段内
        assertThat(reminderIdx).isGreaterThan(prompt.indexOf("## 玩家行动"));
        assertThat(reminderIdx).isGreaterThan(prompt.indexOf("## 触发器命中"));
        assertThat(reminderIdx).isGreaterThan(prompt.indexOf("## GM 任务指令"));
        // 玩家行动原文未被污染
        assertThat(prompt).contains(PLAYER_ACTION);
    }

    @Test
    void 关闭提醒_增量prompt与改造前完全一致() {
        String withFlagOff = assembler.assembleIncremental(PLAYER_ACTION, List.of(), false);
        String legacyOverload = assembler.assembleIncremental(PLAYER_ACTION, List.of());

        assertThat(withFlagOff).doesNotContain(REMINDER_MARKER);
        // 两参重载委托给三参重载并传 false，保证既有调用点行为不变
        assertThat(withFlagOff).isEqualTo(legacyOverload);
    }

    @Test
    void 带提醒的增量prompt经清洗后只剩玩家行动() {
        String prompt = assembler.assembleIncremental(PLAYER_ACTION, List.of(), true);

        assertThat(cleaner.cleanUserMessage(prompt))
                .isEqualTo(PLAYER_ACTION)
                .doesNotContain(REMINDER_MARKER)
                .doesNotContain("GmMemory");
    }

    @Test
    void 默认间隔5轮_仅整倍数轮触发() {
        RpgMemoryProperties properties = new RpgMemoryProperties();

        assertThat(properties.getRemindEveryTurns()).isEqualTo(5);
        assertThat(properties.shouldRemind(1)).isFalse();
        assertThat(properties.shouldRemind(4)).isFalse();
        assertThat(properties.shouldRemind(5)).isTrue();
        assertThat(properties.shouldRemind(10)).isTrue();
        assertThat(properties.shouldRemind(40)).isTrue();
        // 轮次从 1 开始，0 不是合法轮次
        assertThat(properties.shouldRemind(0)).isFalse();
    }

    @Test
    void 间隔设为0或负数_完全关闭且不抛除零异常() {
        RpgMemoryProperties properties = new RpgMemoryProperties();

        properties.setRemindEveryTurns(0);
        assertThatCode(() -> {
            for (int turn = 0; turn <= 50; turn++) {
                assertThat(properties.shouldRemind(turn)).isFalse();
            }
        }).doesNotThrowAnyException();

        properties.setRemindEveryTurns(-3);
        assertThatCode(() -> {
            for (int turn = 0; turn <= 50; turn++) {
                assertThat(properties.shouldRemind(turn)).isFalse();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void 间隔设为1_每轮都提醒() {
        RpgMemoryProperties properties = new RpgMemoryProperties();
        properties.setRemindEveryTurns(1);

        assertThat(properties.shouldRemind(1)).isTrue();
        assertThat(properties.shouldRemind(7)).isTrue();
    }
}
