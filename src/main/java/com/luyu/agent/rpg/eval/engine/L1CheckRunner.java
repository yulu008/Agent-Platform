package com.luyu.agent.rpg.eval.engine;

import java.util.ArrayList;
import java.util.List;

import com.luyu.agent.rpg.eval.config.EvalProperties;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L1 确定性检查聚合入口（content-eval tasks 4.x，design D5）。
 * <p>
 * judge 评估前对单个 EvalCase 依次执行四个检查器（硬事实 / 状态轨迹断崖 /
 * 汉字指称 / 钩子断言）。全程零 LLM 调用零 metering 计量（检查器为纯规则，
 * 不依赖 ChatClient 与计量组件——spec「L1 无 LLM 调用」由构造依赖即保证）。
 * <p>
 * 防御：单检查器异常跳过不影响其余（对齐三源独立降级惯例）；总预警数以
 * {@code rpg.eval.max-l1-warnings-per-case} 为上限（防预警过长挤占 judge
 * prompt 的叙述窗预算，超限截断并注明）。
 */
@Component
public class L1CheckRunner {

    private static final Logger log = LoggerFactory.getLogger(L1CheckRunner.class);

    private final HardFactChecker hardFactChecker;
    private final StateTrajectoryChecker stateTrajectoryChecker;
    private final KanjiReferenceChecker kanjiReferenceChecker;
    private final HookAssertionChecker hookAssertionChecker;
    private final EvalProperties evalProperties;

    public L1CheckRunner(HardFactChecker hardFactChecker,
                         StateTrajectoryChecker stateTrajectoryChecker,
                         KanjiReferenceChecker kanjiReferenceChecker,
                         HookAssertionChecker hookAssertionChecker,
                         EvalProperties evalProperties) {
        this.hardFactChecker = hardFactChecker;
        this.stateTrajectoryChecker = stateTrajectoryChecker;
        this.kanjiReferenceChecker = kanjiReferenceChecker;
        this.hookAssertionChecker = hookAssertionChecker;
        this.evalProperties = evalProperties;
    }

    public List<L1Warning> run(EvalCase evalCase) {
        List<L1Warning> warnings = new ArrayList<>();
        runChecker("hard-facts", () -> hardFactChecker.check(evalCase), warnings);
        runChecker("state-trajectory", () -> stateTrajectoryChecker.check(evalCase), warnings);
        runChecker("kanji-reference", () -> kanjiReferenceChecker.check(evalCase), warnings);
        runChecker("hook-assertions", () -> hookAssertionChecker.check(evalCase), warnings);
        int limit = Math.max(1, evalProperties.getMaxL1WarningsPerCase());
        if (warnings.size() > limit) {
            List<L1Warning> trimmed = new ArrayList<>(warnings.subList(0, limit));
            trimmed.add(new L1Warning("l1-runner", L1Warning.TURN_NONE,
                    "L1 预警超上限已截断（共 " + warnings.size() + " 条，限 " + limit + " 条）", ""));
            return trimmed;
        }
        return warnings;
    }

    private void runChecker(String name, CheckerSupplier checker, List<L1Warning> warnings) {
        // 单检查器异常跳过不影响其余（L1 是预警增强，绝不阻断评估主流程）
        try {
            warnings.addAll(checker.get());
        } catch (Exception e) {
            log.warn("L1 检查器执行失败（跳过）: checker={}", name, e);
        }
    }

    @FunctionalInterface
    private interface CheckerSupplier {
        List<L1Warning> get();
    }
}
