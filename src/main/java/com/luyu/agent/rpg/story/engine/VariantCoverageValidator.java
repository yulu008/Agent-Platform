package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.GuardClause;
import com.luyu.agent.rpg.story.model.StateSchema;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryVariant;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变体覆盖性校验器（story-graph-model 任务 1.6）。
 * <p>
 * 两项检查，全部仅产出 warning（不阻断——即兴兜底始终可用）：
 * <ul>
 *   <li><b>幕级授权</b>：节点声明 {@code variants[]} 但所在幕
 *       {@code allow_explicit_variants} 非 true → 变体被忽略（物化走即兴），
 *       报告提示授权缺失。数据保留不清空（开关切换不应丢作者数据；运行侧
 *       物化器按授权判定生效）</li>
 *   <li><b>guard 覆盖缺口</b>：变体 guard 联合覆盖 vs 可达区间（spec
 *       Scenario：trust &gt;= 60 之外的低信任轨道无变体命中 → 警告，运行期
 *       低信任到达走即兴物化）。逐键一维近似：number 键区间代数（schema
 *       min/max 为可达区间，未定义视为无界哨兵域）；flag 键检查 true/false
 *       双值覆盖；string 键有 values 枚举时检查枚举覆盖，无枚举跳过（无法
 *       判定不警告）</li>
 * </ul>
 * 变体 guard 的状态键合法性与谓词语法归 StateSchemaValidator（任务 1.4），
 * 本类不重复。覆盖检查为警告级近似（开闭边界/多键组合缺口不精确判定）。
 */
@Component
public class VariantCoverageValidator {

    /** 无界哨兵（±1e9）：数值键未定义 min/max 时的可达区间近似边界 */
    private static final double SENTINEL = 1.0e9;

    private final StateSchemaValidator stateSchemaValidator;

    public VariantCoverageValidator(StateSchemaValidator stateSchemaValidator) {
        this.stateSchemaValidator = stateSchemaValidator;
    }

    /**
     * 变体覆盖性校验入口。
     *
     * @return warning 清单（本检查永不阻断，返回可全部作警告呈现）
     */
    public List<String> validate(List<StoryAct> acts, String stateSchemaJson) {
        List<String> warnings = new ArrayList<>();
        if (acts == null || acts.isEmpty()) {
            return warnings;
        }
        // schema 解析失败/为空时跳过覆盖检查（结构问题已由 1.4 报错）
        StateSchema schema = stateSchemaValidator.parseSchema(stateSchemaJson, new ArrayList<>());
        for (StoryAct act : acts) {
            if (act.getNodes() == null) {
                continue;
            }
            boolean authorized = Boolean.TRUE.equals(act.getAllowExplicitVariants());
            for (StoryNode node : act.getNodes()) {
                if (node.getVariants() == null || node.getVariants().isEmpty()) {
                    continue;
                }
                if (!authorized) {
                    warnings.add("幕 " + act.getId() + " 未授权显式变体（allow_explicit_variants 非 true），节点 "
                            + node.getId() + " 的 " + node.getVariants().size()
                            + " 个变体将被忽略（物化走即兴）");
                    continue;
                }
                checkCoverage(node, schema, warnings);
            }
        }
        return warnings;
    }

    /** StoryGraph 组合入口（graphJson 反序列化；坏 JSON 静默跳过——结构错误已由其他链报） */
    public List<String> validateGraph(StoryGraph graph) {
        if (graph == null) {
            return List.of();
        }
        try {
            return validate(graph.acts(new com.fasterxml.jackson.databind.ObjectMapper()),
                    graph.getStateSchema());
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 覆盖缺口检查 ====================

    private void checkCoverage(StoryNode node, StateSchema schema, List<String> warnings) {
        List<StoryVariant> variants = node.getVariants();

        // 无 guard 变体 = 无条件命中（兜底变体）→ 逻辑上无缺口
        for (StoryVariant variant : variants) {
            if (variant.getGuard() == null) {
                return;
            }
        }

        // 按键分组（逐键独立近似；多键组合缺口不判定）
        Map<String, List<GuardClause>> byKey = new LinkedHashMap<>();
        for (StoryVariant variant : variants) {
            GuardClause guard = variant.getGuard();
            if (guard.getKey() == null) {
                continue;
            }
            byKey.computeIfAbsent(guard.getKey(), k -> new ArrayList<>()).add(guard);
        }

        for (Map.Entry<String, List<GuardClause>> entry : byKey.entrySet()) {
            String key = entry.getKey();
            StateSchema.KeyDef def = schema.get(key);
            if (def == null) {
                continue; // 词汇表外键（1.4 已报）
            }
            switch (def.getType()) {
                case StateSchema.TYPE_NUMBER:
                    checkNumberCoverage(node, key, entry.getValue(), def, warnings);
                    break;
                case StateSchema.TYPE_FLAG:
                    checkFlagCoverage(node, key, entry.getValue(), warnings);
                    break;
                case StateSchema.TYPE_STRING:
                    if (!def.getValues().isEmpty()) {
                        checkEnumCoverage(node, key, entry.getValue(), def, warnings);
                    }
                    break;
                default:
                    // 未知类型跳过
            }
        }
    }

    /** number 键：变体 guard 区间并集 vs 可达区间（schema min/max 或哨兵域）的差集 */
    private void checkNumberCoverage(StoryNode node, String key, List<GuardClause> guards,
                                     StateSchema.KeyDef def, List<String> warnings) {
        List<double[]> intervals = new ArrayList<>();
        for (GuardClause guard : guards) {
            Double v = toNumber(guard.getValue());
            if (v == null) {
                continue; // 值非数值（1.4 类型错配已报）
            }
            switch (guard.getOp()) {
                case ">=", ">" -> intervals.add(new double[]{v, SENTINEL});
                case "<=", "<" -> intervals.add(new double[]{-SENTINEL, v});
                case "==" -> intervals.add(new double[]{v, v});
                case "in" -> {
                    if (guard.getValue() instanceof List<?> list) {
                        for (Object item : list) {
                            Double d = toNumber(item);
                            if (d != null) {
                                intervals.add(new double[]{d, d});
                            }
                        }
                    }
                }
                default -> { /* op 非法已由语法校验报 */ }
            }
        }
        if (intervals.isEmpty()) {
            return;
        }
        // 可达区间（未定义 min/max 用哨兵近似无界）
        double lo = def.getMin() == null ? -SENTINEL : def.getMin();
        double hi = def.getMax() == null ? SENTINEL : def.getMax();

        // 区间排序合并
        intervals.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> merged = new ArrayList<>();
        for (double[] interval : intervals) {
            if (!merged.isEmpty() && interval[0] <= merged.get(merged.size() - 1)[1]) {
                double[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new double[]{interval[0], interval[1]});
            }
        }

        // 差集：可达 [lo, hi] − 合并并集，边界采样近似（警告级）
        List<String> gaps = new ArrayList<>();
        double coveredStart = lo;
        for (double[] interval : merged) {
            if (interval[0] > coveredStart) {
                gaps.add(intervalGapText(coveredStart, interval[0], lo, hi));
            }
            coveredStart = Math.max(coveredStart, interval[1]);
            if (coveredStart >= hi) {
                break;
            }
        }
        if (coveredStart < hi) {
            gaps.add(intervalGapText(coveredStart, hi, lo, hi));
        }
        if (!gaps.isEmpty()) {
            warnings.add("节点 " + node.getId() + " 变体 guard 对键 " + key + " 存在覆盖缺口："
                    + String.join("、", gaps) + "（未被任何变体命中，运行期走即兴物化）");
        }
    }

    /** 缺口区间文本（哨兵边界转无界表述） */
    private String intervalGapText(double gapLo, double gapHi, double domainLo, double domainHi) {
        String loText = gapLo <= domainLo ? "(-∞" : String.valueOf(gapLo);
        String hiText = gapHi >= domainHi ? "+∞)" : gapHi + "]";
        return "[" + loText + ", " + hiText;
    }

    /** flag 键：true/false 双值覆盖检查 */
    private void checkFlagCoverage(StoryNode node, String key, List<GuardClause> guards,
                                    List<String> warnings) {
        boolean coversTrue = false;
        boolean coversFalse = false;
        for (GuardClause guard : guards) {
            if (!"==".equals(guard.getOp())) {
                continue; // flag 仅 ==（1.4 类型错配已报）
            }
            Boolean value = toBoolean(guard.getValue());
            if (Boolean.TRUE.equals(value)) {
                coversTrue = true;
            } else if (Boolean.FALSE.equals(value)) {
                coversFalse = true;
            }
        }
        if (!coversTrue || !coversFalse) {
            warnings.add("节点 " + node.getId() + " 变体 guard 对 flag 键 " + key + " 存在覆盖缺口："
                    + (!coversTrue ? "true " : "") + (!coversFalse ? "false" : "")
                    + "（未被任何变体命中，运行期走即兴物化）");
        }
    }

    /** string 键（有 values 枚举）：枚举值覆盖检查 */
    private void checkEnumCoverage(StoryNode node, String key, List<GuardClause> guards,
                                   StateSchema.KeyDef def, List<String> warnings) {
        List<String> uncovered = new ArrayList<>();
        for (String value : def.getValues()) {
            boolean covered = false;
            for (GuardClause guard : guards) {
                if ("==".equals(guard.getOp()) && value.equals(String.valueOf(guard.getValue()))) {
                    covered = true;
                    break;
                }
                if ("in".equals(guard.getOp()) && guard.getValue() instanceof List<?> list
                        && list.contains(value)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                uncovered.add(value);
            }
        }
        if (!uncovered.isEmpty()) {
            warnings.add("节点 " + node.getId() + " 变体 guard 对 string 键 " + key
                    + " 存在枚举覆盖缺口：" + uncovered + "（未被任何变体命中，运行期走即兴物化）");
        }
    }

    // ==================== 类型转换 ====================

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

    private Boolean toBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
