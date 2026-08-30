package com.luyu.agent.service;

/**
 * Token 数量估算工具（字符近似法）
 * <p>
 * 采用字符近似法估算 token 数量，精度约 ±20%：
 * <ul>
 *   <li>中文字符（CJK 统一汉字）：× 1.5 token/字</li>
 *   <li>英文单词：× 0.75 token/word</li>
 *   <li>标点/符号/数字：× 1 token/字符</li>
 * </ul>
 * 适用于 UI 展示场景，不替代精确 tokenizer。
 */
public final class TokenEstimator {

    /** 统一上下文窗口上限（token 数） */
    public static final int CONTEXT_WINDOW_SIZE = 128_000;

    /** 自动压缩触发阈值（70% 窗口） */
    public static final int COMPACTION_THRESHOLD = (int) (CONTEXT_WINDOW_SIZE * 0.7);

    private TokenEstimator() {
        // 工具类不可实例化
    }

    /**
     * 估算文本的 token 数量
     *
     * @param text 输入文本
     * @return 估算 token 数（整数，向上取整）
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        double tokens = 0;
        int i = 0;
        boolean inEnglishWord = false;
        int englishWordChars = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            if (isCjk(c)) {
                // 结束当前英文单词（如果有）
                if (inEnglishWord) {
                    tokens += Math.max(1, englishWordChars * 0.75);
                    inEnglishWord = false;
                    englishWordChars = 0;
                }
                tokens += 1.5;
            } else if (Character.isLetter(c) && c < 128) {
                // ASCII 字母：属于英文单词
                inEnglishWord = true;
                englishWordChars++;
            } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                // 空白字符：结束英文单词
                if (inEnglishWord) {
                    tokens += Math.max(1, englishWordChars * 0.75);
                    inEnglishWord = false;
                    englishWordChars = 0;
                }
                // 空白本身不计 token
            } else {
                // 标点、数字、符号：结束英文单词，自身计 1 token
                if (inEnglishWord) {
                    tokens += Math.max(1, englishWordChars * 0.75);
                    inEnglishWord = false;
                    englishWordChars = 0;
                }
                tokens += 1;
            }
            i++;
        }

        // 处理末尾残留的英文单词
        if (inEnglishWord) {
            tokens += Math.max(1, englishWordChars * 0.75);
        }

        return (int) Math.ceil(tokens);
    }

    /**
     * 判断是否为 CJK 统一汉字（包括基本汉字和扩展区）
     */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
    }
}
