package com.luyu.agent.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 内容审查引擎单元测试（tasks 7.1）。
 * <p>
 * 覆盖四类结果：词库命中 / 未命中 / 大小写与零宽字符归一 / {@code enabled=false} 旁路。
 * 纯内存断言，不依赖 Spring 上下文与数据库。
 */
class ContentModerationEngineTest {

    /** 构造一份测试词库：脏话(fuck/傻逼) + 歧视(黑鬼) + 色情(嫖娼)。 */
    private static WordListModerationClient testClient() {
        Map<ModerationCategory, List<String>> words = WordListModerationClient.parse(List.of(
                "# 注释行应被忽略",
                "[profanity]",
                "fuck",
                "傻逼",
                "[discrimination]",
                "黑鬼",
                "[pornography]",
                "嫖娼",
                "",
                "[unknown_section]",
                "ignored_word"));
        return new WordListModerationClient(words);
    }

    @Test
    void 命中脏话类别() {
        ModerationResult result = testClient().check("你他妈的别这样");
        // "傻逼" 未出现，但词库中的 "fuck" 也不在；用命中词验证
        ModerationResult hit = testClient().check("真是个傻逼");
        assertThat(hit.blocked()).isTrue();
        assertThat(hit.categories()).contains(ModerationCategory.PROFANITY);
        assertThat(result.blocked()).isFalse();
    }

    @Test
    void 未命中任何词条时放行() {
        ModerationResult result = testClient().check("今天天气很好，我们一起去公园散步吧");
        assertThat(result.blocked()).isFalse();
        assertThat(result.categories()).isEmpty();
    }

    @Test
    void 空输入与null放行() {
        assertThat(testClient().check(null).blocked()).isFalse();
        assertThat(testClient().check("").blocked()).isFalse();
        assertThat(testClient().check("   ").blocked()).isFalse();
    }

    @Test
    void 大小写归一命中英文词条() {
        assertThat(testClient().check("FUCK you").blocked()).isTrue();
        assertThat(testClient().check("FuCk").blocked()).isTrue();
    }

    @Test
    void 零宽字符插入不绕过命中() {
        // 在词条中插入零宽空格，归一化后仍应命中
        assertThat(testClient().check("傻\u200B逼").blocked()).isTrue();
        assertThat(testClient().check("f\u200Cuck").blocked()).isTrue();
    }

    @Test
    void 命中多类别时返回全部类别() {
        ModerationResult result = testClient().check("傻逼黑鬼嫖娼");
        assertThat(result.blocked()).isTrue();
        assertThat(result.categories()).contains(
                ModerationCategory.PROFANITY,
                ModerationCategory.DISCRIMINATION,
                ModerationCategory.PORNOGRAPHY);
    }

    @Test
    void 未知分节词条被忽略不误伤() {
        assertThat(testClient().check("ignored_word 是正常内容").blocked()).isFalse();
    }

    @Test
    void 空词库等价于无命中放行() {
        WordListModerationClient empty = new WordListModerationClient(Map.of());
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.check("傻逼黑鬼").blocked()).isFalse();
    }

    // ==================== 闸门 enabled 旁路（tasks 7.1） ====================

    private static ModerationProperties props(boolean enabled) {
        ModerationProperties p = new ModerationProperties();
        p.setEnabled(enabled);
        p.setRefusalMessage("内容不适宜，请调整后重试。");
        return p;
    }

    @Test
    void 闸门开启时命中拒答并给出话术() {
        ContentModerationGate gate = new ContentModerationGate(props(true), testClient());
        assertThat(gate.isEnabled()).isTrue();
        ModerationResult result = gate.check("真是个傻逼");
        assertThat(result.blocked()).isTrue();
        assertThat(gate.refusalMessage()).isEqualTo("内容不适宜，请调整后重试。");
    }

    @Test
    void 闸门关闭时整体旁路直接放行() {
        ContentModerationGate gate = new ContentModerationGate(props(false), testClient());
        assertThat(gate.isEnabled()).isFalse();
        // 即便输入命中词库，enabled=false 也应直接放行且不触发匹配
        assertThat(gate.check("真是个傻逼").blocked()).isFalse();
        assertThat(gate.check(null).blocked()).isFalse();
    }

    @Test
    void 引擎异常时闸门兜底放行() {
        ContentModerationClient throwing = text -> {
            throw new RuntimeException("boom");
        };
        ContentModerationGate gate = new ContentModerationGate(props(true), throwing);
        assertThat(gate.check("任意输入").blocked()).isFalse();
    }
}
