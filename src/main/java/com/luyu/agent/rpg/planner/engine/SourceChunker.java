package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S1 text 链式提炼的原文切块器（planner-s1-chained-refinement tasks 1.2/1.3 /
 * design D3，spec「md 结构分级切块」）。
 * <p>
 * 纯函数（无状态、确定性）：同一原文重复切分恒得同一块序列——断点续跑重算
 * 切分以文件序号对齐已落盘笔记的前提。三级降级策略，结构缺失<b>永不拒绝</b>
 * （格式只影响切块质量，不影响可处理性）：
 * <ol>
 *   <li><b>HEADING</b>：ATX {@code #} 一级标题为块边界（fence-aware——代码围栏
 *       内的 {@code #} 不当标题；{@code ##} 及以下不单独成块随章走）；</li>
 *   <li><b>PSEUDO</b>：无 {@code #} 但存在章节模式独立行（「第X章/卷/节/回」
 *       「Chapter N」「序章/楔子/尾声/番外」等）时转伪章边界；</li>
 *   <li><b>FALLBACK</b>：都无 → 按块字数上限在段落边界（空行）优先切块，
 *       单段超限再段内硬切（回退到最近的换行/句末标点，避免句中腰斩）。</li>
 * </ol>
 * 单章超过 {@code chunkMaxChars} 时章内二次切分。<b>拼接无损</b>：每块 text
 * 是原文的精确子串（块区间连续覆盖全文无丢失无重叠），全部块顺序拼接恒等于
 * 原文——spec「单章超上限二次切分」的拼接完整性由此结构性保证。
 */
public final class SourceChunker {

    /** 切块策略标记（供事件流提示与测试断言：HEADING/PSEUDO/FALLBACK 三级降级） */
    public enum Strategy { HEADING, PSEUDO, FALLBACK }

    /**
     * 单块：序号（1 起，断点续跑笔记文件 chunk-NN 对齐）、展示标题（HEADING/PSEUDO
     * 为标题行提取，FALLBACK 为 null——事件行退化为「分块提炼 k/N」）、块全文
     * （原文精确子串）。
     */
    public record Chunk(int index, String title, String text) {}

    /** 切分结果：策略 + 有序块列表（空原文返回空列表）。 */
    public record ChunkResult(Strategy strategy, List<Chunk> chunks) {
        public int size() {
            return chunks.size();
        }
    }

    /** ATX 一级标题行（`# ` 开头；fence 状态由调用方逐行维护） */
    private static final Pattern HEADING_LINE = Pattern.compile("^#\\s+.+|^#$");

    /**
     * 伪章标题行：中文「第X章/卷/节/回/部/集/篇」（数字或汉字数词，可带副标题
     * 分隔）、「序章/楔子/引子/尾声/终章/番外/后记/前言」，英文「Chapter N」。
     * 白名单模式保守匹配 + 行长上限（防正文叙述里恰好以「第三章」开头的长句误判）。
     */
    private static final Pattern PSEUDO_LINE = Pattern.compile(
            "^(第[0-9零一二三四五六七八九十百千万两]{1,12}[章卷节回部集篇][\\s：:、.．!！?？—-]*\\S{0,30})$"
                    + "|^(序章|楔子|引子|尾声|终章|番外|后记|前言)([\\s：:、.．—-]+\\S{0,30})?$"
                    + "|^(?i)(Chapter\\s+\\d+.{0,60})$");

    /** 段内硬切的回退锚点（换行或句末标点，避免句中腰斩；找不到才纯字数切） */
    private static final Pattern BREAK_POINT = Pattern.compile("[\\n。！？；!?;.]");

    /** 围栏行（``` / ~~~ 三字符以上开关；缩进容忍） */
    private static final Pattern FENCE_LINE = Pattern.compile("^\\s{0,3}(```|~~~).*$");

    private SourceChunker() {
    }

    /**
     * 切分原文为有序块列表（确定性纯函数）。
     *
     * @param source       原文全文（null 视为空）
     * @param chunkMaxChars 单块字数上限（章超限二次切分与兜底切块粒度；≤0 用 1 防死循环）
     */
    public static ChunkResult split(String source, int chunkMaxChars) {
        int maxChars = Math.max(chunkMaxChars, 1);
        if (source == null || source.isBlank()) {
            return new ChunkResult(Strategy.FALLBACK, List.of());
        }

        List<int[]> headingBounds = boundarySpans(source, true);
        Strategy strategy;
        List<int[]> titleLines;
        if (!headingBounds.isEmpty()) {
            strategy = Strategy.HEADING;
            titleLines = headingBounds;
        } else {
            List<int[]> pseudoBounds = boundarySpans(source, false);
            if (!pseudoBounds.isEmpty()) {
                strategy = Strategy.PSEUDO;
                titleLines = pseudoBounds;
            } else {
                return new ChunkResult(Strategy.FALLBACK,
                        fallbackChunks(source, maxChars));
            }
        }
        return new ChunkResult(strategy, sectionChunks(source, titleLines, strategy, maxChars));
    }

    // ==================== 章节边界扫描（HEADING / PSEUDO 共用） ====================

    /**
     * 章节边界行区间列表：每个元素 [标题行 start, 标题行 end)。章节内容区间由
     * sectionChunks 按相邻边界行推导（章节 i = [边界 i 行 start, 边界 i+1 行
     * start)，首边界前置内容并入第 1 章 start=0，拼接无损）。
     * <p>
     * fence-aware：围栏内的标题行不算边界（逐行维护围栏开关状态，围栏配对后恢复）。
     */
    private static List<int[]> boundarySpans(String source, boolean headingMode) {
        List<int[]> boundaries = new ArrayList<>();
        boolean inFence = false;
        int offset = 0;
        int length = source.length();
        while (offset < length) {
            int lineEnd = source.indexOf('\n', offset);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            String line = source.substring(offset, lineEnd);
            if (FENCE_LINE.matcher(line).matches()) {
                inFence = !inFence;
            } else if (!inFence && isBoundaryLine(line, headingMode)) {
                boundaries.add(new int[]{offset, lineEnd});
            }
            offset = lineEnd + 1;
        }
        return boundaries;
    }

    /** 边界行判定：HEADING 模式认 ATX 一级标题；PSEUDO 模式认章节模式独立行。 */
    private static boolean isBoundaryLine(String line, boolean headingMode) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        if (headingMode) {
            return HEADING_LINE.matcher(stripped).matches();
        }
        Matcher matcher = PSEUDO_LINE.matcher(stripped);
        return matcher.matches();
    }

    /** 章节标题提取（展示用；HEADING 剥 `#` 前缀，PSEUDO 取行原文，截 40 字）。 */
    private static String titleOf(String source, int lineStart, int lineEnd, Strategy strategy) {
        String line = source.substring(lineStart, lineEnd).strip();
        if (strategy == Strategy.HEADING && line.startsWith("#")) {
            line = line.replaceFirst("^#+\\s*", "");
        }
        return line.length() > 40 ? line.substring(0, 40) + "…" : line;
    }

    // ==================== 章节切块（含超限二次切分） ====================

    /**
     * 章节标题行区间 → 块列表：章 i 内容区间 = [边界 i 行 start, 边界 i+1 行
     * start)（首边界前置内容并入第 1 章，start=0），每章一块（块文本含标题行，
     * 供 LLM 读块时自带上文语境）；章超 maxChars 时章内二次切分（子块标题复用
     * 章标题，事件行以序号区分），子块拼接等于原章文本（无损传递：子块同为
     * 原文精确子串）。块序号全局从 1 递增（断点续跑 chunk-NN 文件对齐）。
     */
    private static List<Chunk> sectionChunks(String source, List<int[]> titleLines, Strategy strategy, int maxChars) {
        List<Chunk> chunks = new ArrayList<>();
        int length = source.length();
        for (int i = 0; i < titleLines.size(); i++) {
            int[] titleLine = titleLines.get(i);
            String title = titleOf(source, titleLine[0], titleLine[1], strategy);
            int sectionStart = i == 0 ? 0 : titleLine[0];
            int sectionEnd = i + 1 < titleLines.size() ? titleLines.get(i + 1)[0] : length;
            String sectionText = source.substring(sectionStart, sectionEnd);
            if (sectionText.length() <= maxChars) {
                chunks.add(new Chunk(chunks.size() + 1, title, sectionText));
            } else {
                for (String piece : splitOversize(sectionText, maxChars)) {
                    chunks.add(new Chunk(chunks.size() + 1, title, piece));
                }
            }
        }
        return chunks;
    }

    // ==================== 兜底切块（FALLBACK） ====================

    /**
     * 兜底切块：全文按段落边界（空行）累积成 ≤maxChars 的块，单段超限段内硬切。
     * 块 title 为 null（无结构标题可提取，事件行退化为「分块提炼 k/N」），
     * 块序号从 1 递增，各块拼接恒等于原文（无损）。
     */
    private static List<Chunk> fallbackChunks(String source, int maxChars) {
        List<Chunk> chunks = new ArrayList<>();
        for (String piece : splitOversize(source, maxChars)) {
            chunks.add(new Chunk(chunks.size() + 1, null, piece));
        }
        return chunks;
    }

    // ==================== 超限二次切分（章内 / 兜底共用） ====================

    /**
     * 超限文本二次切分：段落边界（空行）优先累积成 ≤maxChars 的块；单段超限再
     * 段内硬切（{@link #hardSplit}）。返回各 piece 均为 text 的精确子串且顺序
     * 拼接恒等于 text（拼接无损）。
     */
    private static List<String> splitOversize(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        List<int[]> paragraphs = paragraphSpans(text);
        int blockStart = -1;
        int blockEnd = -1;
        for (int[] paragraph : paragraphs) {
            String paragraphText = text.substring(paragraph[0], paragraph[1]);
            if (paragraphText.length() > maxChars) {
                // 先冲刷已累积块，再对超限段段内硬切（独立成块序列）
                if (blockStart >= 0) {
                    pieces.add(text.substring(blockStart, blockEnd));
                    blockStart = -1;
                }
                pieces.addAll(hardSplit(paragraphText, maxChars));
                continue;
            }
            if (blockStart < 0) {
                blockStart = paragraph[0];
                blockEnd = paragraph[1];
            } else if (paragraph[1] - blockStart <= maxChars) {
                blockEnd = paragraph[1];
            } else {
                // 段落并入将超限：封口当前块，当前段起新块
                pieces.add(text.substring(blockStart, blockEnd));
                blockStart = paragraph[0];
                blockEnd = paragraph[1];
            }
        }
        if (blockStart >= 0) {
            pieces.add(text.substring(blockStart, blockEnd));
        }
        return pieces;
    }

    /**
     * 段落区间列表：[start, end) 连续覆盖 [0, text.length())（拼接无损前提）。
     * 段落 = 空行分隔的行组；分隔空行归前段尾部、开头空白并入第一段、结尾
     * 空白归末段——保证各段子串拼接恒等于原文。
     */
    private static List<int[]> paragraphSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int segmentStart = -1;
        int pendingEnd = -1;
        int offset = 0;
        int length = text.length();
        while (offset < length) {
            int lineEnd = text.indexOf('\n', offset);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            String line = text.substring(offset, lineEnd);
            if (line.isBlank()) {
                if (segmentStart >= 0) {
                    // 空行（含连续空行）归前段尾部
                    pendingEnd = lineEnd + 1;
                }
            } else if (segmentStart < 0) {
                segmentStart = 0;
            } else if (pendingEnd >= 0) {
                spans.add(new int[]{segmentStart, pendingEnd});
                segmentStart = offset;
                pendingEnd = -1;
            }
            offset = lineEnd + 1;
        }
        if (segmentStart >= 0) {
            spans.add(new int[]{segmentStart, length});
        }
        return spans;
    }

    /**
     * 单段超限的段内硬切：每步从剩余头部取 ≤maxChars 的子串，切点优先回退到
     * 窗口内最后一个换行/句末标点（BREAK_POINT，避免句中腰斩）；窗口内无锚点
     * 才纯字数切。返回子串顺序拼接恒等于 text。
     */
    private static List<String> hardSplit(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        int length = text.length();
        while (start < length) {
            if (length - start <= maxChars) {
                pieces.add(text.substring(start, length));
                break;
            }
            int windowEnd = start + maxChars;
            Matcher matcher = BREAK_POINT.matcher(text).region(start, windowEnd);
            int cut = -1;
            while (matcher.find()) {
                cut = matcher.end();
            }
            if (cut <= start) {
                cut = windowEnd;
            }
            pieces.add(text.substring(start, cut));
            start = cut;
        }
        return pieces;
    }
}
