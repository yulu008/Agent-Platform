package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StateDeltaSanitizer} 与 {@link StateDeltaStreamFilter} 单元测试。
 * <p>
 * 重点覆盖 GM 漏写闭合标签 / 标签空白变体 / 跨 chunk 分片到达等泄漏场景，
 * 纯逻辑，无需 Spring 上下文。
 */
class StateDeltaFilterTest {

    private final StateDeltaSanitizer sanitizer = new StateDeltaSanitizer();

    // ==================== StateDeltaSanitizer ====================

    @Test
    void extractNarration_完整闭合块_移除() {
        String raw = "小二迎上来。\n<state_delta>\n{\"location_change\":\"客栈\"}\n</state_delta>";
        assertThat(sanitizer.extractNarration(raw))
                .isEqualTo("小二迎上来。")
                .doesNotContain("state_delta")
                .doesNotContain("location_change");
    }

    @Test
    void extractNarration_未闭合块_从开标签截断() {
        // GM 漏写 </state_delta>：开标签后的 JSON 不能泄漏到叙述/历史回放
        String raw = "夜色渐深。\n<state_delta>\n{\"location_change\":\"客栈\",\"flags\":{\"bell\":true}}";
        assertThat(sanitizer.extractNarration(raw))
                .isEqualTo("夜色渐深。")
                .doesNotContain("location_change")
                .doesNotContain("bell");
    }

    @Test
    void extractNarration_标签空白变体_移除() {
        String raw = "叙述正文< state_delta >{\"a\":1}< / state_delta >收尾";
        assertThat(sanitizer.extractNarration(raw))
                .isEqualTo("叙述正文收尾");
    }

    @Test
    void tryExtractStateDelta_未闭合块_仍可提取() {
        String raw = "叙述<state_delta>\n{\"location_change\":\"城南\"}";
        JsonNode node = sanitizer.tryExtractStateDelta(raw);
        assertThat(node).isNotNull();
        assertThat(node.path("location_change").asText()).isEqualTo("城南");
    }

    @Test
    void hasStateDelta_未闭合开标签_返回true() {
        assertThat(sanitizer.hasStateDelta("叙述<state_delta>{")).isTrue();
        assertThat(sanitizer.hasStateDelta("纯叙述")).isFalse();
    }

    // ==================== StateDeltaStreamFilter ====================

    /** 逐 token 喂入并拼接下发内容 */
    private String streamThrough(StateDeltaStreamFilter filter, String text) {
        StringBuilder out = new StringBuilder();
        text.chars().mapToObj(c -> String.valueOf((char) c)).forEach(t -> out.append(filter.accept(t)));
        out.append(filter.finish());
        return out.toString();
    }

    @Test
    void 流式过滤_完整块_全程无泄漏且保留块后文本() {
        StateDeltaStreamFilter filter = new StateDeltaStreamFilter();
        String raw = "叙述A<state_delta>{\"flags\":{\"x\":true}}</state_delta>叙述B";
        assertThat(streamThrough(filter, raw)).isEqualTo("叙述A叙述B");
    }

    @Test
    void 流式过滤_未闭合块_整体丢弃() {
        StateDeltaStreamFilter filter = new StateDeltaStreamFilter();
        String raw = "叙述<state_delta>{\"location_change\":\"客栈\"";
        assertThat(streamThrough(filter, raw)).isEqualTo("叙述");
    }

    @Test
    void 流式过滤_跨chunk分片开标签_无泄漏() {
        // "<state_delta" 被拆成多个 chunk 到达，任一时刻下发内容都不含 JSON
        StateDeltaStreamFilter filter = new StateDeltaStreamFilter();
        String raw = "前文<state_delta>{\"a\":1}</state_delta>后文";
        // 按 3 字符一段模拟极端分片
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 3) {
            String chunk = raw.substring(i, Math.min(i + 3, raw.length()));
            out.append(filter.accept(chunk));
        }
        out.append(filter.finish());
        assertThat(out.toString()).isEqualTo("前文后文");
    }

    @Test
    void 流式过滤_标签空白变体_无泄漏() {
        StateDeltaStreamFilter filter = new StateDeltaStreamFilter();
        String raw = "前< state_delta >{\"a\":1}< / state_delta >后";
        assertThat(streamThrough(filter, raw)).isEqualTo("前后");
    }

    @Test
    void 流式过滤_块间多处文本_均保留() {
        StateDeltaStreamFilter filter = new StateDeltaStreamFilter();
        String raw = "段1<state_delta>{}</state_delta>段2<state_delta>{}</state_delta>段3";
        assertThat(streamThrough(filter, raw)).isEqualTo("段1段2段3");
    }

    @Test
    void 流式过滤_叙述中出现的尖括号_不受影响() {
        StateDeltaStreamFilter filter = new StateDeltaStreamFilter();
        String raw = "刀光一闪，3<4 为真，5>2 也为真。";
        assertThat(streamThrough(filter, raw)).isEqualTo(raw);
    }
}
