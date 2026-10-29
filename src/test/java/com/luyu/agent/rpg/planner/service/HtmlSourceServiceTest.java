package com.luyu.agent.rpg.planner.service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HtmlSourceService} 的 charset 嗅探与 jsoup 清洗单测
 * （planner-html-source tasks 2.4，design D3/D4）。
 * <p>
 * 纯函数式清洗（无状态、无工作区副作用），脱离 Spring 容器直接 new
 * （对齐 {@code WorldCoverStorageServiceTest} 风格）。钉住五组契约：
 * <ol>
 *   <li><b>charset 嗅探（D3）</b>：GBK 页面 meta charset 正确解码（裸
 *       {@code <meta charset>} 与 http-equiv 两种声明形态）、UTF-8 BOM
 *       识别剥离、无声明非法 UTF-8 走兜底解码——替换字符（U+FFFD）比例
 *       超阈值记 warn 但不 400 拒绝（乱码页面清洗后 LLM 自然判废，用户
 *       看拆解稿质量可自行换源重试）；</li>
 *   <li><b>标题层级（D4）</b>：h1-h3 → {@code #/##/###} 前缀独立行
 *       （链式提炼的 md 结构分级切块在 HTML 资料上仍然生效）；</li>
 *   <li><b>非正文剥离（D4）</b>：script/style/noscript/svg/iframe 节点
 *       移除（head 与 body 双位置）；</li>
 *   <li><b>块级转换行</b>：p 边界换行、br 换行、行内空白压单空格；</li>
 *   <li><b>空入参防御</b>：null/空字节返回空串恒非 null。</li>
 * </ol>
 */
class HtmlSourceServiceTest {

    private final HtmlSourceService service = new HtmlSourceService();

    // ===== charset 嗅探（D3） =====

    @Test
    void gbk页面metaCharset嗅探正确解码并清洗() {
        // 百科历史页面主流形态：GBK 字节 + 裸 <meta charset> 声明（嗅探窗口 ASCII 区可见）
        String html = "<html><head><meta charset=\"GBK\"></head><body>"
                + "<h1>三体</h1><p>面壁计划</p></body></html>";
        byte[] bytes = html.getBytes(Charset.forName("GBK"));

        assertThat(service.clean(bytes)).isEqualTo("# 三体\n\n面壁计划");
    }

    @Test
    void httpEquiv形态meta声明同样命中GBK解码() {
        // content="text/html; charset=GBK" 是老页面更常见的声明形态
        String html = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=GBK\"></head>"
                + "<body><h2>词条名</h2><p>词条正文</p></body></html>";
        byte[] bytes = html.getBytes(Charset.forName("GBK"));

        assertThat(service.clean(bytes)).isEqualTo("## 词条名\n\n词条正文");
    }

    @Test
    void utf8Bom识别剥离不残留零宽字符() {
        // UTF-8 编码 U+FEFF 即 EF BB BF 字节头：嗅探命中后解码首字符剥离，正文清洗无残留
        String html = "\uFEFF<html><body><p>正文</p></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        assertThat(service.clean(bytes)).isEqualTo("正文");
    }

    @Test
    void 非法UTF8无声明兜底解码记warn仍尽力清洗() {
        // 0xC3 0x28（截断的多字节序列）×10：无 BOM 无 meta → UTF-8 兜底 → 每对产
        // 1 个 U+FFFD（比例约 43% 远超 5% 阈值）——warn 感知但不 400 拒绝（D3）
        byte[] prefix = "<p>".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "</p>".getBytes(StandardCharsets.UTF_8);
        byte[] bad = new byte[prefix.length + 20 + suffix.length];
        System.arraycopy(prefix, 0, bad, 0, prefix.length);
        for (int i = 0; i < 10; i++) {
            bad[prefix.length + 2 * i] = (byte) 0xC3;
            bad[prefix.length + 2 * i + 1] = 0x28;
        }
        System.arraycopy(suffix, 0, bad, prefix.length + 20, suffix.length);

        Logger logger = (Logger) LoggerFactory.getLogger(HtmlSourceService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // 尽力清洗不拒绝：恒非 null，替换字符进入清洗文本（乱码证据可见）
            assertThat(service.clean(bad)).isNotNull().contains("\uFFFD");
            assertThat(appender.list).anySatisfy(event ->
                    assertThat(event.getFormattedMessage()).contains("替换字符比例"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ===== jsoup 清洗（D4） =====

    @Test
    void 标题层级h1到h3保留井号前缀独立行() {
        String html = "<html><body><h1>大标题</h1><h2>二级标题</h2>"
                + "<h3>三级标题</h3><p>正文段落</p></body></html>";

        assertThat(service.clean(html.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("# 大标题\n\n## 二级标题\n\n### 三级标题\n\n正文段落");
    }

    @Test
    void scriptStyleNoscriptSvgIframe节点全部剥离() {
        // head 侧本就不进 body 遍历；body 侧靠 STRIP_SELECTOR 移除（script 内容不出现在正文）
        String html = "<html><head><style>.x{color:red}</style><script>alert(1)</script></head>"
                + "<body><svg><circle r=\"1\"/></svg><iframe src=\"evil\"></iframe>"
                + "<p>正文</p><noscript>替代文案</noscript><script>var x=1</script></body></html>";

        assertThat(service.clean(html.getBytes(StandardCharsets.UTF_8))).isEqualTo("正文");
    }

    @Test
    void 块级元素转换行br换行行内空白压单() {
        String html = "<html><body><p>行一<br>行二</p><p>词一   词二</p></body></html>";

        assertThat(service.clean(html.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("行一\n行二\n\n词一 词二");
    }

    // ===== 空入参防御 =====

    @Test
    void 空入参返回空串恒非null() {
        assertThat(service.clean(null)).isEqualTo("");
        assertThat(service.clean(new byte[0])).isEqualTo("");
    }
}
