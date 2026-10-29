package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 版本行级 diff（add-planner-pipeline tasks 4.6，spec planner-confirm-gates
 * 「版本树与 diff 呈现」/ design D5——返工整轮重生成后，让人核对未被反馈
 * 要求改动的部分是否被意外修改）。
 * <p>
 * 纯函数无状态：公共前后缀掐头去尾（O(n)），中段行级 LCS（O(n·m)，阶段
 * 稿行数量级下毫秒级）。输出 context / removed / added 三类行——removed
 * 与 added 相邻成对即一处修改，供前端高亮渲染；行按 {@code \R} 切分
 * （兼容 LF / CRLF），尾部换行不计行（对齐编辑器行数语义）。
 */
public final class StageVersionDiffer {

    /** 单行 diff 条目：type ∈ context / removed / added，text 为整行内容（不含换行）。 */
    public record DiffLine(String type, String text) {}

    private StageVersionDiffer() {
    }

    /**
     * 计算两版全文的行级 diff。
     *
     * @param oldText 旧版全文（v from，null 视为空文本）
     * @param newText 新版全文（v to，null 视为空文本）
     */
    public static List<DiffLine> diff(String oldText, String newText) {
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);

        // 掐公共前后缀：两端一致部分直接 context，缩小中段 LCS 表
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix
                && oldLines.get(oldLines.size() - 1 - suffix)
                        .equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }

        List<String> oldMid = oldLines.subList(prefix, oldLines.size() - suffix);
        List<String> newMid = newLines.subList(prefix, newLines.size() - suffix);

        List<DiffLine> result = new ArrayList<>(oldLines.size() + newLines.size());
        for (int i = 0; i < prefix; i++) {
            result.add(new DiffLine("context", oldLines.get(i)));
        }
        appendLcs(oldMid, newMid, result);
        for (int i = oldLines.size() - suffix; i < oldLines.size(); i++) {
            result.add(new DiffLine("context", oldLines.get(i)));
        }
        return result;
    }

    /** 中段 LCS：逆序建表正序回溯；removed 排在 added 前（同处修改的删旧/加新相邻呈现）。 */
    private static void appendLcs(List<String> oldMid, List<String> newMid, List<DiffLine> out) {
        int n = oldMid.size();
        int m = newMid.size();
        int[][] table = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                table[i][j] = oldMid.get(i).equals(newMid.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldMid.get(i).equals(newMid.get(j))) {
                out.add(new DiffLine("context", oldMid.get(i)));
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                out.add(new DiffLine("removed", oldMid.get(i)));
                i++;
            } else {
                out.add(new DiffLine("added", newMid.get(j)));
                j++;
            }
        }
        while (i < n) {
            out.add(new DiffLine("removed", oldMid.get(i)));
            i++;
        }
        while (j < m) {
            out.add(new DiffLine("added", newMid.get(j)));
            j++;
        }
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
