package com.luyu.agent.rpg.eval.engine;

import java.util.ArrayList;
import java.util.List;

import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.JudgeJudgment;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 证据 span 核验器（content-eval tasks 5.5，design D3）。
 * <p>
 * 落库前核验 judge 引用的证据可定位（防「煞有介事引用不存在的原文」污染
 * 统计）：
 * <ul>
 *   <li><b>轮次定位</b>：动态层 turn 必须是窗内真实轮次（该轮 user 行动 +
 *       GM 叙述合并为被检索文本）；turn = -1（对齐 {@link L1Warning#TURN_NONE}）
 *       与静态层全部证据 → 档案文本（identity/personality/background/lore +
 *       structuredJson）为被检索文本；</li>
 *   <li><b>子串匹配</b>：直接 contains，失败再防御性模糊匹配（双方剔除全部
 *       空白字符后 contains——容换行/空格差异）；</li>
 *   <li><b>无证据作废</b>：evidence 空 = 无证据判分作废（D3 核心场景）。</li>
 * </ul>
 * 核验按子维度条目粒度：失败的条目挂 {@code invalidReason}（该 record 作废，
 * 不计入统计），同 case 其余条目不受牵连。
 */
@Component
public class EvidenceVerifier {

    private static final Logger log = LoggerFactory.getLogger(EvidenceVerifier.class);

    /**
     * 核验整组判分；返回挂核验结论的副本（原 judgments 不修改）。
     */
    public List<JudgeJudgment> verify(List<JudgeJudgment> judgments, EvalCase c) {
        List<JudgeJudgment> verified = new ArrayList<>(judgments.size());
        for (JudgeJudgment j : judgments) {
            String reason = verifyOne(j, c);
            verified.add(reason == null
                    ? j
                    : new JudgeJudgment(j.subDimension(), j.verdict(), j.rationale(),
                            j.evidence(), reason));
        }
        return verified;
    }

    /** 单条核验：null = 通过；非空 = 作废原因 */
    private String verifyOne(JudgeJudgment j, EvalCase c) {
        if (j.evidence() == null || j.evidence().isEmpty()) {
            return "无证据判分作废（每条 verdict 必须附可定位证据）";
        }
        for (JudgeJudgment.Evidence e : j.evidence()) {
            String corpus = locateCorpus(e.turn(), c);
            if (corpus == null) {
                log.warn("证据轮次定位失败: subject={}, turn={}", c.subject(), e.turn());
                return "证据轮次 " + e.turn() + " 不在叙述窗内";
            }
            if (!matches(corpus, e.span())) {
                log.warn("证据 span 无法定位: subject={}, turn={}", c.subject(), e.turn());
                return "证据 span 无法定位（turn=" + e.turn() + "）: " + clip(e.span());
            }
        }
        return null;
    }

    /**
     * 轮次定位被检索文本：
     * 动态层真实轮次 → 该轮全部行合并；turn=-1 或静态层 → 档案文本。
     * turn 为窗外轮次返回 null（轮次幻觉信号）。
     */
    private String locateCorpus(int turn, EvalCase c) {
        boolean dynamic = c.earlyWindow() != null || c.lateWindow() != null;
        if (!dynamic || turn == L1Warning.TURN_NONE) {
            return settingCorpus(c);
        }
        List<EvalCase.TurnText> rows = L1Texts.rowsOfTurn(c, turn);
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (EvalCase.TurnText row : rows) {
            if (row.text() != null && !row.text().isBlank()) {
                sb.append(row.text().trim()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 档案被检索文本：三件套/lore 文本段 + structuredJson 原文 */
    private static String settingCorpus(EvalCase c) {
        StringBuilder sb = new StringBuilder();
        for (String text : L1Texts.settingTexts(c)) {
            sb.append(text).append('\n');
        }
        if (c.settingPackJson() != null) {
            sb.append(c.settingPackJson());
        }
        return sb.toString();
    }

    /** 直接子串；失败走容空白模糊匹配（剔除全部空白后 contains） */
    static boolean matches(String corpus, String span) {
        if (corpus == null || span == null) {
            return false;
        }
        if (corpus.contains(span)) {
            return true;
        }
        return stripWhitespace(corpus).contains(stripWhitespace(span));
    }

    /** 剔除全部空白（含全角空格）——容 judge 引用时的换行/空格差异 */
    static String stripWhitespace(String text) {
        return text.replaceAll("[\\s\\u3000]+", "");
    }

    private static String clip(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
