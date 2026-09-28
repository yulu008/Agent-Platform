package com.luyu.agent.rpg.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSE 流式 state_delta 过滤器（有状态，每轮请求一个实例）。
 * <p>
 * 在 token 流上抑制 {@code <state_delta>...</state_delta>} 块：开标签一旦开始成型
 * （含跨 chunk 的部分前缀，如 {@code "<stat"}，及 {@code "< state_delta >"} 空白变体）
 * 即扣留不下发，闭合标签消费后才恢复放行；流结束时仍未闭合的块整体丢弃。
 * 避免流式期间 JSON 状态数据闪现在前端。
 * <p>
 * 本类只做展示层过滤，不解析 JSON；state_delta 提取仍由 {@link StateDeltaSanitizer} 完成。
 * <p>
 * 状态机：
 * <ul>
 *   <li>块外：放行开标签之前的文本；末尾可能是开标签部分前缀的字符扣留等待下一 chunk</li>
 *   <li>块内：全部抑制；仅保留可能是闭合标签部分前缀的尾部，其余直接丢弃（防止缓冲区无界增长）</li>
 * </ul>
 */
public class StateDeltaStreamFilter {

    private static final String TAG_BODY = "state_delta";
    /** 完整开/闭标签（容忍标签内空白变体） */
    private static final Pattern OPEN_COMPLETE =
            Pattern.compile("<\\s*state_delta\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE_COMPLETE =
            Pattern.compile("<\\s*/\\s*state_delta\\s*>", Pattern.CASE_INSENSITIVE);
    /** 向前扫描部分标签前缀的窗口大小（最长合法标签约 20 字符，留余量） */
    private static final int HOLD_WINDOW = 32;

    private final StringBuilder pending = new StringBuilder();
    private boolean insideBlock = false;

    /**
     * 接收一个流式 token，返回可安全下发给前端的部分（可能为空串）。
     */
    public String accept(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        pending.append(token);
        return drain(false);
    }

    /**
     * 流结束：未闭合块丢弃，其余扣留内容放行。
     */
    public String finish() {
        return drain(true);
    }

    private String drain(boolean eos) {
        StringBuilder out = new StringBuilder();
        while (pending.length() > 0) {
            if (insideBlock) {
                Matcher closeM = CLOSE_COMPLETE.matcher(pending);
                if (closeM.find()) {
                    pending.delete(0, closeM.end());
                    insideBlock = false;
                    continue;
                }
                // 闭合标签未出现：仅保留可能是其部分前缀的尾部，其余丢弃（块内容不下发）
                if (!eos) {
                    int keep = pending.length() - holdStart(true);
                    if (keep < pending.length()) {
                        pending.delete(0, pending.length() - keep);
                    }
                } else {
                    // 流结束仍未闭合：整体丢弃
                    pending.setLength(0);
                }
                break;
            }

            Matcher openM = OPEN_COMPLETE.matcher(pending);
            if (openM.find()) {
                // 完整开标签出现：放行标签前的文本，进入块内抑制态
                if (openM.start() > 0) {
                    out.append(pending, 0, openM.start());
                    pending.delete(0, openM.start());
                }
                pending.delete(0, openM.end());
                insideBlock = true;
                continue;
            }

            // 无完整开标签：扣留可能是其部分前缀的尾部（如 "<stat"、"< state_delta" 等 '>'），
            // 其余放行
            int hold = holdStart(false);
            if (hold > 0) {
                out.append(pending, 0, hold);
                pending.delete(0, hold);
            }
            if (eos && pending.length() > 0) {
                // 流结束：残留的部分开标签前缀整体丢弃（未闭合块不给前端）
                pending.setLength(0);
            }
            break;
        }
        return out.toString();
    }

    /**
     * 在 pending 尾部窗口内查找「开/闭标签的部分前缀」的起点。
     * <p>
     * 前缀形如 {@code '<' ['/']? 空白* "state_delta" 的前缀 空白*}（差一个 {@code '>'}），
     * 取窗口内满足条件的最靠后的 {@code '<'}，使扣留最短。
     *
     * @param closing true 查找闭合标签前缀（{@code "</state_delta"}），false 查找开标签前缀
     * @return 起点下标；无需扣留时返回 pending 长度
     */
    private int holdStart(boolean closing) {
        int limit = Math.max(0, pending.length() - HOLD_WINDOW);
        for (int p = pending.length() - 1; p >= limit; p--) {
            if (pending.charAt(p) != '<') {
                continue;
            }
            if (isTagPrefix(p, closing)) {
                return p;
            }
        }
        return pending.length();
    }

    /**
     * 判断 pending 从 start 到末尾是否是标签前缀（见 {@link #holdStart}）。
     */
    private boolean isTagPrefix(int start, boolean closing) {
        int i = start + 1;
        if (i >= pending.length()) {
            return true; // 只有 "<"
        }
        if (closing) {
            i = skipWhitespace(i);
            if (i >= pending.length()) {
                return true; // "< "：可能是 "< / ..." 的一部分
            }
            if (pending.charAt(i) != '/') {
                return false;
            }
            i++;
            if (i >= pending.length()) {
                return true; // "</"
            }
        }
        i = skipWhitespace(i);
        int matched = 0;
        while (i < pending.length() && matched < TAG_BODY.length()
                && Character.toLowerCase(pending.charAt(i)) == TAG_BODY.charAt(matched)) {
            i++;
            matched++;
        }
        if (i < pending.length() && matched < TAG_BODY.length()) {
            return false; // 出现既非空白也拼不成标签名的字符
        }
        i = skipWhitespace(i);
        return i == pending.length();
    }

    private int skipWhitespace(int i) {
        while (i < pending.length() && Character.isWhitespace(pending.charAt(i))) {
            i++;
        }
        return i;
    }
}
