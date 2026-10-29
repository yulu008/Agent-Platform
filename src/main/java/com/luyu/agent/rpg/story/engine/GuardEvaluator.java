package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.GuardClause;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * guard 谓词求值器（story-graph-model 任务 1.3，design D4/Risks：限简单谓词保确定性）。
 * <p>
 * 纯函数语义：同输入同输出，无状态无副作用，LLM 不参与判定。求值入口
 * {@link #evaluate} 接受调用方构建的状态视图（state_schema 键 → 当前值；
 * GameState → 状态视图的映射是运行侧的事，本层不感知存档结构）。
 * <p>
 * 求值防御语义：缺键/类型不可比/op 非法一律 {@code false}——守卫是白名单
 * 过滤（不满足不出选项/不可入），宁可不放行。语法问题由 {@link #validate}
 * 在校验链报出，运行期静默 false 即可。
 * <ul>
 *   <li>{@code >= <= > <}：数值比较（两侧均可数值化，否则 false）</li>
 *   <li>{@code ==}：宽松等值（可数值化按数值等，否则字符串/布尔严格等）</li>
 *   <li>{@code in}：value 为数组，成员命中判定（成员等值同 == 语义）</li>
 * </ul>
 */
@Component
public class GuardEvaluator {

    /** 契约允许的算子全集（spec：op ∈ >= <= > < == in） */
    public static final Set<String> SUPPORTED_OPS = Set.of(">=", "<=", ">", "<", "==", "in");

    /**
     * 确定性求值。
     *
     * @param guard 谓词（null 直接 false）
     * @param state 状态视图（state_schema 键 → 当前值；null / 缺键 → false）
     * @return 是否满足
     */
    public boolean evaluate(GuardClause guard, Map<String, Object> state) {
        if (guard == null || guard.getKey() == null || guard.getKey().isBlank()
                || guard.getOp() == null || guard.getValue() == null) {
            return false;
        }
        if (state == null || !state.containsKey(guard.getKey())) {
            return false;
        }
        Object actual = state.get(guard.getKey());
        return switch (guard.getOp()) {
            case ">=", "<=", ">", "<" -> numericCompare(guard.getOp(), actual, guard.getValue());
            case "==" -> equalsLoose(actual, guard.getValue());
            case "in" -> inSet(actual, guard.getValue());
            default -> false;
        };
    }

    /**
     * 谓词语法校验（词汇表键白名单归 StateSchemaValidator，此处只管谓词自身结构）。
     *
     * @param guard    待校验谓词
     * @param location 报错定位（如 "节点 n1_03 entry_guard"）
     * @return 错误清单（空 = 语法合法）
     */
    public List<String> validate(GuardClause guard, String location) {
        List<String> errors = new ArrayList<>();
        if (guard == null) {
            errors.add(location + "：guard 为空");
            return errors;
        }
        if (guard.getKey() == null || guard.getKey().isBlank()) {
            errors.add(location + "：guard 缺少 key");
        }
        String op = guard.getOp();
        if (op == null || !SUPPORTED_OPS.contains(op)) {
            errors.add(location + "：guard op 非法（应为 >= <= > < == in）：" + op);
        } else {
            Object value = guard.getValue();
            if (value == null) {
                errors.add(location + "：guard 缺少 value");
            } else if (isNumericOp(op) && toNumber(value) == null) {
                errors.add(location + "：数值比较 op 的 value 必须为数字：" + value);
            } else if ("in".equals(op) && !(value instanceof List)) {
                errors.add(location + "：in 的 value 必须为数组：" + value);
            } else if ("==".equals(op) && value instanceof List) {
                errors.add(location + "：== 的 value 必须为标量（数字/字符串/布尔）：" + value);
            }
        }
        return errors;
    }

    // ==================== 求值实现 ====================

    private boolean numericCompare(String op, Object actual, Object expected) {
        Double a = toNumber(actual);
        Double e = toNumber(expected);
        if (a == null || e == null) {
            return false;
        }
        return switch (op) {
            case ">=" -> a >= e;
            case "<=" -> a <= e;
            case ">" -> a > e;
            case "<" -> a < e;
            default -> false;
        };
    }

    /** 宽松等值：双侧可数值化按数值等（Long 5 == "5"）；布尔严格等；其余按字符串等 */
    private boolean equalsLoose(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        Double a = toNumber(actual);
        Double e = toNumber(expected);
        if (a != null && e != null) {
            return a.doubleValue() == e.doubleValue();
        }
        return String.valueOf(actual).equals(String.valueOf(expected));
    }

    private boolean inSet(Object actual, Object expected) {
        if (!(expected instanceof List<?> list)) {
            return false;
        }
        for (Object item : list) {
            if (equalsLoose(actual, item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNumericOp(String op) {
        return ">=".equals(op) || "<=".equals(op) || ">".equals(op) || "<".equals(op);
    }

    /** 数值化（Number 直转；字符串可解析则解析；其余 null） */
    private Double toNumber(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
