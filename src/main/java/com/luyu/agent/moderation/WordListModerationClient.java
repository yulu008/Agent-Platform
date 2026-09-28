package com.luyu.agent.moderation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地敏感词库审查实现（design D2 / spec「审查类别与引擎」）。
 * <p>
 * 基于 {@link AhoCorasick} 一次扫描匹配全部词条，零网络依赖、零显著延迟。
 * 词库按类别分节从资源文件加载（见 {@link #parse(Iterable)}），加载失败或空词库时
 * 等价于「无命中放行」（design D5）。
 */
public class WordListModerationClient implements ContentModerationClient {

    private final AhoCorasick matcher;

    public WordListModerationClient(Map<ModerationCategory, List<String>> wordsByCategory) {
        this.matcher = new AhoCorasick(wordsByCategory);
    }

    @Override
    public ModerationResult check(String text) {
        if (text == null || text.isEmpty()) {
            return ModerationResult.pass();
        }
        Set<ModerationCategory> hits = matcher.matchCategories(text);
        return hits.isEmpty() ? ModerationResult.pass() : ModerationResult.blocked(hits);
    }

    /** 词库是否为空（用于启动日志提示）。 */
    public boolean isEmpty() {
        return matcher.isEmpty();
    }

    /**
     * 解析词库文本行。格式：
     * <pre>
     * # 注释行
     * [profanity]        分节标记，切换当前类别
     * 词条1
     * 词条2
     * [discrimination]
     * ...
     * </pre>
     * 空行与 {@code #} 注释忽略；分节标记之前的词条忽略；未知分节标记忽略其后的词条直到下一个有效标记。
     *
     * @param lines 词库文件的行序列
     * @return 类别 -> 词条列表（不含空类别）
     */
    public static Map<ModerationCategory, List<String>> parse(Iterable<String> lines) {
        Map<ModerationCategory, List<String>> result = new EnumMap<>(ModerationCategory.class);
        if (lines == null) {
            return result;
        }
        ModerationCategory current = null;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = ModerationCategory.fromKey(line.substring(1, line.length() - 1));
                continue;
            }
            if (current == null) {
                // 无有效分节，忽略
                continue;
            }
            result.computeIfAbsent(current, k -> new ArrayList<>()).add(line);
        }
        return result;
    }
}
