package com.luyu.agent.rpg.planner.service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ip_name 模式 HTML 资料页清洗（planner-html-source design D3/D4）。
 * <p>
 * 输入是用户浏览器另存的 HTML 原始字节（用户资产，原始字节由
 * {@link PlannerWorkspaceStore#writeSourcePage} 落盘），本类负责
 * 「字节 → 正确编码字符串 → jsoup 清洗正文文本」一步到位：
 * <ul>
 *   <li><b>charset 嗅探</b>（D3）：UTF-8/UTF-16 BOM → 字节流前 2KB 正则抓
 *       {@code <meta charset=...>} / {@code <meta http-equiv="Content-Type"
 *       content="...charset=..."> }（ASCII 区大小写不敏感）→ UTF-8 兜底。
 *       中文网页 GBK/GB2312 高发（百科等历史页面），嗅探失败按 UTF-8 解出的
 *       替换字符（U+FFFD）比例超阈值时记 warn 仍尽力清洗（不 400 拒绝——
 *       乱码页面清洗后 LLM 自然判废，用户看拆解稿质量可自行换源重试）</li>
 *   <li><b>jsoup 清洗</b>（D4）：{@code Jsoup.parse}（禁脚本执行天然安全）→
 *       移除 {@code script/style/noscript/svg/iframe} 节点 → 块级元素转换行
 *       （{@code h1-h6} 前缀加对应数量 {@code #} 保留标题层级独立行——链式
 *       提炼的 md 结构分级切块在 HTML 资料上仍然生效，百科词条标题层级 =
 *       结构化红利）→ 压缩连续空白</li>
 * </ul>
 * <p>
 * 纯函数式清洗（无状态、无工作区副作用），单测可脱离 Spring 容器
 * （对齐 {@code WorldCoverStorageServiceTest} 风格，tasks 2.4）。
 */
@Service
public class HtmlSourceService {

    private static final Logger log = LoggerFactory.getLogger(HtmlSourceService.class);

    /** 嗅探扫描的字节窗口（meta charset 声明在真实页面头部 2KB 内必达） */
    private static final int SNIFF_WINDOW = 2048;

    /** 解码替换字符（U+FFFD）占比超过该值记 warn（D3 乱码感知） */
    private static final double REPLACEMENT_RATIO_WARN = 0.05;

    /** meta charset 两种声明形态（ASCII 区大小写不敏感；引号兼容单双与裸值） */
    private static final Pattern META_CHARSET =
            Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    /** 清洗时移除的非正文节点（D4） */
    private static final String STRIP_SELECTOR = "script, style, noscript, svg, iframe";

    /** 块级标签（转换行边界；D4 约定集合） */
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "^(p|div|li|tr|table|thead|tbody|ul|ol|dl|dt|dd|section|article|aside|header|footer|nav"
                    + "|blockquote|pre|figure|figcaption|form|fieldset|hr)$");

    /** 标题标签（h1-h6 → `#` 前缀独立行） */
    private static final Pattern HEADING_TAG = Pattern.compile("^h([1-6])$");

    /**
     * 清洗一份 HTML 资料页（字节 → 正文文本）。
     *
     * @param htmlBytes 原始字节（用户浏览器另存的 HTML）
     * @return 清洗文本（标题带 {@code #} 前缀独立行、块级转换行、空白压缩；恒非 null）
     */
    public String clean(byte[] htmlBytes) {
        String html = decode(htmlBytes);
        return strip(html);
    }

    // ==================== charset 嗅探（D3） ====================

    /**
     * 字节 → 字符串：BOM → meta charset 正则 → UTF-8 兜底。
     * 解码后统计替换字符（U+FFFD）比例，超阈值记 warn（乱码感知，不拒绝）。
     */
    String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        Charset charset = sniffCharset(bytes);
        String decoded = new String(bytes, charset);
        // BOM 头剥离（嗅探命中 BOM 时解码器保留 U+FEFF，正文清洗前剥掉）
        if (!decoded.isEmpty() && decoded.charAt(0) == '\uFEFF') {
            decoded = decoded.substring(1);
        }
        double ratio = replacementRatio(decoded);
        if (ratio > REPLACEMENT_RATIO_WARN) {
            log.warn("HTML 解码替换字符比例 {}（charset 嗅探可能失准，清洗文本或乱码）: charset={}",
                    Math.round(ratio * 1000) / 10.0 + "%", charset);
        }
        return decoded;
    }

    /** 嗅探序：UTF-8/UTF-16LE/UTF-16BE BOM → 前 2KB meta charset 声明 → UTF-8 兜底。 */
    private Charset sniffCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2) {
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
            if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
        }
        // meta 声明扫描（窗口内 ASCII 区匹配；GBK/GB2312/UTF-8 等常见名由 Charset.forName 解析）
        int window = Math.min(bytes.length, SNIFF_WINDOW);
        String head = new String(bytes, 0, window, StandardCharsets.ISO_8859_1);
        Matcher matcher = META_CHARSET.matcher(head);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (IllegalArgumentException e) {
                log.warn("meta charset 声明不可识别（按 UTF-8 兜底）: {}", matcher.group(1));
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** 替换字符（U+FFFD）占比（解码失准的量化信号）。 */
    private static double replacementRatio(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') {
                count++;
            }
        }
        return (double) count / text.length();
    }

    // ==================== jsoup 清洗（D4） ====================

    /** jsoup 解析 + 非正文节点剥离 + 块级转换行 + 空白压缩。 */
    String strip(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html == null ? "" : html);
        doc.select(STRIP_SELECTOR).remove();
        StringBuilder out = new StringBuilder(Math.max(64, html == null ? 64 : html.length() / 4));
        traverse(doc.body(), out);
        return compress(out.toString());
    }

    /** 深度优先遍历：文本节点原样（空白压单空格）、标题行加 `#` 前缀、块级元素换行。 */
    private void traverse(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            String value = text.text();
            if (!value.isBlank()) {
                out.append(value.replaceAll("\\s+", " "));
            }
            return;
        }
        if (!(node instanceof Element el)) {
            return;   // 注释、数据节点等跳过
        }
        String tag = el.tagName();
        Matcher heading = HEADING_TAG.matcher(tag);
        if (heading.matches()) {
            // 标题独立行：`##` 前缀 + 标题文本（内部换行压平）+ 行尾
            out.append('\n').append("#".repeat(Integer.parseInt(heading.group(1)))).append(' ');
            traverseChildren(el, out);
            out.append('\n');
            return;
        }
        if ("br".equals(tag)) {
            out.append('\n');
            return;
        }
        if (BLOCK_TAG.matcher(tag).matches()) {
            out.append('\n');
            traverseChildren(el, out);
            out.append('\n');
            return;
        }
        traverseChildren(el, out);
    }

    private void traverseChildren(Element el, StringBuilder out) {
        for (Node child : el.childNodes()) {
            traverse(child, out);
        }
    }

    /** 空白压缩：行内多空格压一、3+ 连续空行压双空行、首尾剥离。 */
    private static String compress(String raw) {
        return raw.replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    /**
     * 一份 HTML 资料页的清洗产物（原始字节 + 清洗文本，planner-html-source D5
     * 双文件落盘载体）：controller 校验 + moderation 前置后组装，经
     * {@code PipelineRunService.createRun} 进入
     * {@link PlannerWorkspaceStore#writeSourcePage}。
     */
    public record SourcePage(byte[] htmlBytes, String cleanedText) {
    }
}
