package com.luyu.agent.rpg.eval.engine;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.L1Warning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 钩子断言核对器（content-eval tasks 4.4，design D5）。
 * <p>
 * 断言清单 = classpath 资源 {@code rpg/eval-character-assertions.md}
 * （人工维护，行格式：{@code 匹配键 | 断言文本 | 反例触发词}）。匹配键与
 * EvalCase 的 subject / subjectLabel / aliases 精确匹配取断言集；GM 叙述
 * 中命中反例触发词且同句含该人物指称 → 预警（断言文本 + span）。
 * <p>
 * 仅检动态层叙述（静态层设定包自身包含断言原句，自匹配属噪音）；资源缺失
 * /解析失败 → 空清单降级（warn 不阻断，对齐 PromptHashCalculator 防御惯例）。
 */
@Component
public class HookAssertionChecker {

    private static final Logger log = LoggerFactory.getLogger(HookAssertionChecker.class);

    private static final String RESOURCE_PATH = "rpg/eval-character-assertions.md";

    /** 一条人工断言：匹配键 + 断言文本 + 反例触发词集 */
    record Assertion(String matchKey, String text, List<String> triggers) {
    }

    private volatile List<Assertion> assertions;

    public List<L1Warning> check(EvalCase c) {
        List<EvalCase.TurnText> narration = L1Texts.narrationRows(c);
        if (narration.isEmpty()) {
            return List.of();
        }
        List<Assertion> matched = matchAssertions(c);
        if (matched.isEmpty()) {
            return List.of();
        }
        List<L1Warning> warnings = new ArrayList<>();
        for (EvalCase.TurnText row : narration) {
            for (String sentence : L1Texts.sentences(row.text())) {
                for (Assertion a : matched) {
                    for (String trigger : a.triggers()) {
                        if (sentence.contains(trigger) && mentions(c, sentence)) {
                            warnings.add(new L1Warning(L1Warning.CHECKER_HOOK_ASSERTIONS,
                                    row.turn(),
                                    "疑似违反断言「" + a.text() + "」（触发词：" + trigger + "）",
                                    clip(sentence)));
                            break; // 同句同断言一预警即可
                        }
                    }
                }
            }
        }
        return warnings;
    }

    /** 该 case 命中的断言集（匹配键对 subject/subjectLabel/aliases 精确匹配） */
    private List<Assertion> matchAssertions(EvalCase c) {
        List<Assertion> all = assertions();
        List<Assertion> matched = new ArrayList<>();
        for (Assertion a : all) {
            if (a.matchKey().equals(c.subject()) || a.matchKey().equals(c.subjectLabel())
                    || (c.aliases() != null && c.aliases().contains(a.matchKey()))) {
                matched.add(a);
            }
        }
        return matched;
    }

    /** 同句含指称（触发词出现在他人叙事句中不挂到被评人物） */
    private boolean mentions(EvalCase c, String sentence) {
        if (c.aliases() == null) {
            return false;
        }
        for (String alias : c.aliases()) {
            if (alias != null && sentence.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    /** 断言清单（惰性加载一次；资源缺失/行非法跳过该行，warn 降级） */
    private List<Assertion> assertions() {
        List<Assertion> cached = assertions;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (assertions != null) {
                return assertions;
            }
            List<Assertion> parsed = new ArrayList<>();
            try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : content.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    String[] parts = trimmed.split("\\|");
                    if (parts.length == 3) {
                        List<String> triggers = new ArrayList<>();
                        for (String t : parts[2].split("[，,、]")) {
                            String trigger = t.trim();
                            if (!trigger.isEmpty()) {
                                triggers.add(trigger);
                            }
                        }
                        if (!triggers.isEmpty()) {
                            parsed.add(new Assertion(parts[0].trim(), parts[1].trim(), triggers));
                        }
                    } else {
                        log.warn("断言清单行格式非法（跳过）: {}", trimmed);
                    }
                }
            } catch (Exception e) {
                log.warn("断言清单资源加载失败（钩子断言核对降级为空清单）: {}", RESOURCE_PATH, e);
            }
            assertions = parsed;
            return parsed;
        }
    }

    private static String clip(String sentence) {
        return sentence.length() <= 40 ? sentence : sentence.substring(0, 40) + "…";
    }
}
