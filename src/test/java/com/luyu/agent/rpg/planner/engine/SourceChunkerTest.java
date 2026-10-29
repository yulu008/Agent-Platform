package com.luyu.agent.rpg.planner.engine;

import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.engine.SourceChunker.Chunk;
import com.luyu.agent.rpg.planner.engine.SourceChunker.ChunkResult;
import com.luyu.agent.rpg.planner.engine.SourceChunker.Strategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SourceChunker} 三级降级切块测试（tasks 1.4，spec「md 结构分级切块」
 * 全 Scenario）。
 * <p>
 * 钉住：HEADING 章级切块（`##` 随章走、fence 内 `#` 不算边界、120 章序号连续）、
 * PSEUDO 伪章识别（中文章模式 / Chapter N、首边界前置内容并入第 1 章）、
 * FALLBACK 段落边界兜底（单段超限句末锚点回退、无锚点纯字数切）、
 * 单章超上限二次切分拼接无损、确定性复现、空原文返回空列表不拒。
 */
class SourceChunkerTest {

    @Test
    void 章级标题切块小节随章走() {
        String source = "# 第一章 起点\n\n正文甲。\n\n## 小节 A\n\n小节内容。\n\n# 第二章 转折\n\n正文乙。";

        ChunkResult result = SourceChunker.split(source, 12_000);

        assertThat(result.strategy()).isEqualTo(Strategy.HEADING);
        assertThat(result.size()).isEqualTo(2);
        Chunk first = result.chunks().get(0);
        assertThat(first.index()).isEqualTo(1);
        assertThat(first.title()).isEqualTo("第一章 起点");
        // 块文本含标题行（LLM 读块自带上文语境），## 小节不单独成块随章走
        assertThat(first.text()).startsWith("# 第一章 起点").contains("## 小节 A");
        assertThat(result.chunks().get(1).index()).isEqualTo(2);
        assertThat(result.chunks().get(1).title()).isEqualTo("第二章 转折");
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 大量章级标题块序连续() {
        // spec Scenario「章级标题切块」：120 个 # 一级标题 → 120 块，序号与标题顺序一致
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            source.append("# 第 ").append(i).append(" 章\n\n章内容 ").append(i).append("。\n\n");
        }

        ChunkResult result = SourceChunker.split(source.toString(), 12_000);

        assertThat(result.size()).isEqualTo(120);
        for (int i = 0; i < 120; i++) {
            assertThat(result.chunks().get(i).index()).isEqualTo(i + 1);
            assertThat(result.chunks().get(i).title()).isEqualTo("第 " + (i + 1) + " 章");
        }
        assertThat(join(result)).isEqualTo(source.toString());
    }

    @Test
    void 围栏内井号不产生块边界() {
        String source = "# 真章\n\n正文。\n\n```\n# 围栏里的注释\n```\n\n后文。";

        ChunkResult result = SourceChunker.split(source, 12_000);

        assertThat(result.strategy()).isEqualTo(Strategy.HEADING);
        // 围栏内的 # 不算边界：全文单块，围栏内容完整保留在所属块内
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.chunks().get(0).text()).contains("# 围栏里的注释");
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 伪章模式识别切块() {
        String source = "前言若干。\n\n第一章 序幕\n\n内容甲。\n\n第十二章 终局\n\n内容乙。";

        ChunkResult result = SourceChunker.split(source, 12_000);

        assertThat(result.strategy()).isEqualTo(Strategy.PSEUDO);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.chunks().get(0).title()).isEqualTo("第一章 序幕");
        assertThat(result.chunks().get(1).title()).isEqualTo("第十二章 终局");
        // 首边界前置内容并入第 1 章（start=0，拼接无损、前置空白不产生空块）
        assertThat(result.chunks().get(0).text()).startsWith("前言若干。").contains("第一章 序幕");
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 英文Chapter模式识别() {
        String source = "Chapter 1 The Beginning\n\nText one.\n\nChapter 2 The End\n\nText two.";

        ChunkResult result = SourceChunker.split(source, 12_000);

        assertThat(result.strategy()).isEqualTo(Strategy.PSEUDO);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.chunks().get(0).title()).isEqualTo("Chapter 1 The Beginning");
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 无结构按段落边界兜底切块() {
        // 4 段各 30 字，maxChars=70：段 1+2（62 字）一块，段 3+4 一块
        String source = "第一段内容。".repeat(5)
                + "\n\n" + "第二段内容。".repeat(5)
                + "\n\n" + "第三段内容。".repeat(5)
                + "\n\n" + "第四段内容。".repeat(5);

        ChunkResult result = SourceChunker.split(source, 70);

        assertThat(result.strategy()).isEqualTo(Strategy.FALLBACK);
        assertThat(result.size()).isEqualTo(2);
        // 兜底块无结构标题可提取（事件行退化为「分块提炼 k/N」）
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.title()).isNull());
        assertThat(result.chunks().get(0).index()).isEqualTo(1);
        assertThat(result.chunks().get(1).index()).isEqualTo(2);
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 段内硬切避免句中腰斩() {
        // 单段 300 字无空行：hardSplit 切点回退到窗口内最后句末标点
        String source = "这是一句完整的话。".repeat(30);

        ChunkResult result = SourceChunker.split(source, 95);

        assertThat(result.strategy()).isEqualTo(Strategy.FALLBACK);
        assertThat(result.size()).isGreaterThan(1);
        assertThat(result.chunks())
                .allSatisfy(chunk -> assertThat(chunk.text()).endsWith("。"));
        assertThat(result.chunks())
                .allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(95));
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 无锚点长串纯字数切且无损() {
        // 无换行无标点的极端长串：窗口内无锚点退化为纯字数切（前进保证、拼接无损）
        String source = "甲".repeat(300);

        ChunkResult result = SourceChunker.split(source, 120);

        assertThat(result.strategy()).isEqualTo(Strategy.FALLBACK);
        assertThat(result.size()).isEqualTo(3);
        assertThat(result.chunks())
                .allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(120));
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 单章超上限二次切分拼接无损() {
        // 5 段各 50 字 + 标题行，maxChars=120：单章切为多块，拼接等于原章全文
        String body = "段落内容。".repeat(10);
        String source = "# 巨章\n\n" + String.join("\n\n", body, body, body, body, body);

        ChunkResult result = SourceChunker.split(source, 120);

        assertThat(result.strategy()).isEqualTo(Strategy.HEADING);
        assertThat(result.size()).isGreaterThan(1);
        // 子块标题复用章标题（事件行以序号区分）
        assertThat(result.chunks())
                .allSatisfy(chunk -> assertThat(chunk.title()).isEqualTo("巨章"));
        assertThat(join(result)).isEqualTo(source);
    }

    @Test
    void 切分确定性复现() {
        String source = 无结构长文();

        ChunkResult first = SourceChunker.split(source, 120);
        ChunkResult second = SourceChunker.split(source, 120);

        // spec Scenario「切分确定性复现」：块数量、顺序、内容完全一致
        // （断点续跑重算切分以文件序号对齐已落盘笔记的前提）
        assertThat(second.strategy()).isEqualTo(first.strategy());
        assertThat(second.chunks()).containsExactlyElementsOf(first.chunks());
    }

    @Test
    void 空原文返回空块列表不拒() {
        assertThat(SourceChunker.split(null, 100).size()).isZero();
        assertThat(SourceChunker.split("   \n\n  \n", 100).size()).isZero();
    }

    /** 无结构长文（行首「第X段」的"段"不在伪章白名单，恒走 FALLBACK）。 */
    private static String 无结构长文() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append("第").append(i).append("段正文内容。".repeat(4)).append("\n\n");
        }
        return text.toString();
    }

    /** 拼接无损断言：全部块 text 顺序拼接恒等于原文。 */
    private static String join(ChunkResult result) {
        StringBuilder joined = new StringBuilder();
        for (Chunk chunk : result.chunks()) {
            joined.append(chunk.text());
        }
        return joined.toString();
    }
}
