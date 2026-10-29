package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S4 剧情结构稿「## 分幕结构」小节的确定性解析（story-graph-authoring tasks 2.2）。
 * <p>
 * 幕划分真源是 S4 确认稿的 Markdown 而非 LLM 自由发挥——S4.5 的逐幕生成、
 * 幕总数、每幕主题/幕末拍子/出口轨道草案全部从这里切出（design D2/D9：
 * 结构确定性归引擎）。行格式（PlotGenerator 模板钉住）：
 * <pre>
 * ## 分幕结构
 * - 第 1 幕｜主题：一句幕主题｜幕末拍子：该幕收束锚点的剧情拍子｜出口轨道：状态键 op 数值
 * - 第 2 幕｜主题：…｜幕末拍子：…｜出口轨道：…
 * </pre>
 * <p>
 * 解析规则（宽进严出）：非{@code ## 分幕结构}小节的行不扫；小节内非幕行
 * （空行/说明行）跳过；幕号必须从 1 连续递增（断档/重复/回退报 error——
 * 幕命名空间 {@code n<act>_<seq>} 与跨幕拼接都以幕号连续为前提）。
 * 允许段内出现半角竖线 {@code |}（模型抄写漂移的宽容归一，同双 key 惯例）。
 */
public final class StoryActOutlineParser {

    /** 幕行模式：- 第 N 幕｜主题：…｜幕末拍子：…｜出口轨道：…（竖线全/半角兼容） */
    private static final Pattern ACT_LINE = Pattern.compile(
            "^[-*]\\s*第\\s*(\\d+)\\s*幕\\s*[|｜]\\s*主题\\s*[:：]\\s*(.+?)\\s*[|｜]\\s*"
                    + "幕末拍子\\s*[:：]\\s*(.+?)\\s*[|｜]\\s*出口轨道\\s*[:：]\\s*(.+)$");

    /** 小节标题模式（## 分幕结构，宽容前后空白） */
    private static final Pattern SECTION_HEADER = Pattern.compile("^#{2,3}\\s*分幕结构\\s*$");

    private StoryActOutlineParser() {
    }

    /**
     * 幕大纲行（值对象）：S4.5 单幕生成的定位素材。
     *
     * @param actNumber 幕号（1 起连续）
     * @param theme 幕主题（一句）
     * @param endingBeat 幕末收束拍子（该幕 anchor 的剧情拍子）
     * @param orbitDraft 出口轨道草案原文（如 {@code trust_信长 >= 60; 已获刀 == true}）
     */
    public record ActOutline(int actNumber, String theme, String endingBeat, String orbitDraft) {
    }

    /**
     * 从 S4 确认稿解析幕大纲列表。
     * <p>
     * 小节缺失或幕行为空返回空列表（调用方判「S4 稿缺少分幕结构」拒绝进入
     * S4.5 生成，宽进严出：解析器不替作者补默认幕）。
     *
     * @throws IllegalArgumentException 幕号非从 1 连续递增（报具体行）
     */
    public static List<ActOutline> parse(String plotDraft) {
        List<ActOutline> acts = new ArrayList<>();
        if (plotDraft == null || plotDraft.isBlank()) {
            return acts;
        }
        boolean inSection = false;
        int lineNumber = 0;
        for (String rawLine : plotDraft.split("\n", -1)) {
            lineNumber++;
            String line = rawLine.strip();
            if (SECTION_HEADER.matcher(line).matches()) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("#")) {
                // 下一个小节开始，分幕结构结束
                break;
            }
            if (!inSection) {
                continue;
            }
            Matcher matcher = ACT_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;   // 空行/说明行跳过
            }
            int actNumber = Integer.parseInt(matcher.group(1));
            if (actNumber != acts.size() + 1) {
                throw new IllegalArgumentException("分幕结构幕号不连续：第 " + lineNumber
                        + " 行幕号 " + actNumber + "，期望 " + (acts.size() + 1)
                        + "（幕号必须从 1 连续递增）");
            }
            acts.add(new ActOutline(actNumber, matcher.group(2).strip(),
                    matcher.group(3).strip(), matcher.group(4).strip()));
        }
        return acts;
    }
}
