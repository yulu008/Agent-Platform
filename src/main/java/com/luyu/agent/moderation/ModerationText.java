package com.luyu.agent.moderation;

/**
 * 审查文本归一化工具（design D2）。
 * <p>
 * 匹配前对词库词条与用户输入做同一套基础归一，降低大小写与零宽字符插入绕过：
 * <ul>
 *   <li>转小写（{@link String#toLowerCase()}）</li>
 *   <li>剔除零宽字符与软连字符（U+200B/200C/200D/FEFF/00AD）</li>
 * </ul>
 * 仅做无损于中文语义的最小归一，不做拼音/谐音还原（靠词库维护覆盖）。
 */
final class ModerationText {

    private ModerationText() {
    }

    /**
     * 归一化文本；null 返回空串。
     */
    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isStrippable(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isStrippable(char c) {
        return c == '\u200B'   // ZERO WIDTH SPACE
                || c == '\u200C' // ZERO WIDTH NON-JOINER
                || c == '\u200D' // ZERO WIDTH JOINER
                || c == '\uFEFF' // ZERO WIDTH NO-BREAK SPACE / BOM
                || c == '\u00AD'; // SOFT HYPHEN
    }
}
