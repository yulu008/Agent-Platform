package com.luyu.agent.rpg.eval.engine;

import java.util.List;
import java.util.Map;

import com.luyu.agent.rpg.eval.config.EvalProperties;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.springframework.stereotype.Component;

/**
 * 状态轨迹断崖检测器（content-eval tasks 4.2，design D5）。
 * <p>
 * 输入 = EvalCase.stateTrajectory（逐轮 state_delta 数值增量扁平化，
 * {@code npc:<id>:<motive>} / {@code player:<field>}）。单轮
 * |Δ| ≥ 阈值（rpg.eval.cliff-threshold，默认 40——对齐 player.cosmo
 * 单轮 50 落账上限的量级）且该轮叙述无对应语义词命中 → 预警。
 * <p>
 * 语义词表按键名末段映射（cosmo/money/greed/fear/...）；未映射键
 * 保守预警（注明「无语义词表」）。轨迹缺失（存量会话 state_delta
 * 未解析）自然零检出。
 */
@Component
public class StateTrajectoryChecker {

    /** 状态键末段 → 语义词表（断崖值应有叙事支撑；命中任一词即视为有支撑） */
    private static final Map<String, List<String>> FIELD_KEYWORDS = Map.of(
            "cosmo", List.of("爆发", "觉醒", "燃烧", "小宇宙", "第七感", "气势", "斗气", "灵压", "封印"),
            "money", List.of("金", "银", "钱", "贯", "两", "赏", "买", "购", "卖", "赎", "军费", "军粮"),
            "greed", List.of("贪", "财", "利", "欲", "赏金", "贿"),
            "fear", List.of("惧", "怕", "惊", "惶", "颤", "恐", "退"),
            "curiosity", List.of("好奇", "疑问", "探究", "打量", "奇"),
            "trust", List.of("信任", "信", "托", "托付", "结盟"),
            "anger", List.of("怒", "愤", "恼", "叱"),
            "joy", List.of("喜", "悦", "笑"));

    private final EvalProperties evalProperties;

    public StateTrajectoryChecker(EvalProperties evalProperties) {
        this.evalProperties = evalProperties;
    }

    public List<L1Warning> check(EvalCase c) {
        if (c.stateTrajectory() == null || c.stateTrajectory().isEmpty()) {
            return List.of();
        }
        int threshold = Math.max(1, evalProperties.getCliffThreshold());
        return c.stateTrajectory().stream()
                .<L1Warning>mapMulti((ts, consumer) -> {
                    for (Map.Entry<String, Double> e : ts.deltas().entrySet()) {
                        double delta = Math.abs(e.getValue());
                        if (delta < threshold) {
                            continue;
                        }
                        if (!narrationHasKeyword(c, ts.turn(), e.getKey())) {
                            consumer.accept(new L1Warning(
                                    L1Warning.CHECKER_STATE_TRAJECTORY, ts.turn(),
                                    cliffMessage(e.getKey(), e.getValue()), ""));
                        }
                    }
                })
                .toList();
    }

    /** 断崖轮的叙述（user 行动 + GM 叙述）是否含该状态键的任一语义词 */
    private boolean narrationHasKeyword(EvalCase c, int turn, String stateKey) {
        List<String> keywords = FIELD_KEYWORDS.get(fieldSuffix(stateKey));
        if (keywords == null || keywords.isEmpty()) {
            return false; // 未映射键：无语义词表可校验（保守走预警）
        }
        String narration = L1Texts.rowsOfTurn(c, turn).stream()
                .map(EvalCase.TurnText::text)
                .filter(t -> t != null)
                .reduce("", (a, b) -> a + b);
        for (String kw : keywords) {
            if (narration.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String cliffMessage(String key, double value) {
        String sign = value > 0 ? "+" : "";
        String detail = key + " " + sign + value;
        List<String> keywords = FIELD_KEYWORDS.get(fieldSuffix(key));
        if (keywords == null || keywords.isEmpty()) {
            return "状态轨迹断崖（" + detail + "，未知状态键无语义词表，请人工核对）";
        }
        return "状态轨迹断崖且该轮叙述无对应语义支撑（" + detail + "）";
    }

    /** 键名末段（npc:ID:greed → greed；player:cosmo → cosmo） */
    private static String fieldSuffix(String stateKey) {
        int idx = stateKey.lastIndexOf(':');
        return idx < 0 ? stateKey : stateKey.substring(idx + 1);
    }
}
