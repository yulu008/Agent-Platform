package com.luyu.agent.rpg.eval.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.springframework.stereotype.Component;

/**
 * 汉字指称检查器（content-eval tasks 4.3，design D5；世界规则
 * gm-narrative-rules.md「纯中文输出，禁止夹杂英文单词或罗马音」）。
 * <p>
 * 被检文本源：动态层 = GM 叙述行（user 行是玩家输入，不是被评产物）；
 * 静态层 = 设定包文本段（档案自身也不该夹罗马音）。两字符以上拉丁序列
 * 即检出（单字符/数字不检）；L1 只筛不判——专有名词汉字译名的例外由
 * judge 裁决。
 */
@Component
public class KanjiReferenceChecker {

    /** 2+ 字符拉丁词（含连字符/撇号，覆盖 Oda Nobunaga / Kagetora 等形态） */
    private static final Pattern LATIN_WORD =
            Pattern.compile("[A-Za-z][A-Za-z\\-']{1,}");

    /** 单 case 检出上限（防长叙述刷屏；超限截断并在末条预警注明） */
    private static final int MAX_HITS_PER_CASE = 10;

    public List<L1Warning> check(EvalCase c) {
        List<L1Warning> warnings = new ArrayList<>();
        int hits = 0;
        boolean truncated = false;
        // 动态层：GM 叙述行（轮次可定位）；静态层：设定包文本段（TURN_NONE）
        for (EvalCase.TurnText row : L1Texts.narrationRows(c)) {
            hits = scan(row.turn(), row.text(), hits, warnings);
            if (hits >= MAX_HITS_PER_CASE) {
                truncated = true;
                break;
            }
        }
        if (hits < MAX_HITS_PER_CASE) {
            for (String text : L1Texts.settingTexts(c)) {
                hits = scan(L1Warning.TURN_NONE, text, hits, warnings);
                if (hits >= MAX_HITS_PER_CASE) {
                    truncated = true;
                    break;
                }
            }
        }
        if (truncated) {
            warnings.add(new L1Warning(L1Warning.CHECKER_KANJI_REFERENCE,
                    L1Warning.TURN_NONE, "检出条目过多已截断（原文疑似大面积夹罗马音/英文）", ""));
        }
        return warnings;
    }

    private int scan(int turn, String text, int hits, List<L1Warning> warnings) {
        Matcher m = LATIN_WORD.matcher(text);
        while (m.find() && hits < MAX_HITS_PER_CASE) {
            String word = m.group();
            warnings.add(new L1Warning(L1Warning.CHECKER_KANJI_REFERENCE, turn,
                    "叙述含罗马音/纯英文指称（世界规则要求纯中文）：" + word,
                    snippet(text, m.start(), m.end())));
            hits++;
        }
        return hits;
    }

    /** span 摘录：命中词前后各 12 字上下文（judge 核对定位用） */
    private static String snippet(String text, int start, int end) {
        int from = Math.max(0, start - 12);
        int to = Math.min(text.length(), end + 12);
        return text.substring(from, to);
    }
}
