package com.luyu.agent.rpg.story.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.story.model.ConvergeOrbit;
import com.luyu.agent.rpg.story.model.GuardClause;
import com.luyu.agent.rpg.story.model.StateSchema;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * state_schema 词汇表校验器（story-graph-model 任务 1.4）。
 * <p>
 * 全图引用键白名单检查：{@code guard}（幕/节点/边/变体/轨道表）、
 * {@code effects}（边）、{@code on_enter}（节点）引用的状态键 SHALL
 * 存在于 {@code state_schema}；违规报出具体节点/边 ID 与非法键名（spec
 * Scenario 格式："边 e1_03 引用词汇表外键 loyalty_家康"）。
 * <p>
 * 附加两类结构性检查（引用键合法但求值必然失效的作者侧早失败）：
 * <ul>
 *   <li>谓词自身语法（复用 {@link GuardEvaluator#validate}）</li>
 *   <li>类型-op/值匹配：flag 仅 ==；string 不允许数值比较；number 效果值
 *       必须可数值化、flag 效果值必须布尔、string 效果值必须字符串</li>
 * </ul>
 * 本类只做白名单与匹配检查，不改数据（修复是 StoryGraphValidator 的事）；
 * 纯遍历确定性，同输入同输出。
 */
@Component
public class StateSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(StateSchemaValidator.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final GuardEvaluator guardEvaluator;

    public StateSchemaValidator(GuardEvaluator guardEvaluator) {
        this.guardEvaluator = guardEvaluator;
    }

    /**
     * 词汇表校验入口（acts 列表 + state_schema JSON）。
     *
     * @return 错误清单（空 = 通过）
     */
    public List<String> validate(List<StoryAct> acts, String stateSchemaJson) {
        List<String> errors = new ArrayList<>();
        if (stateSchemaJson == null || stateSchemaJson.isBlank()) {
            errors.add("state_schema 词汇表缺失（guard/effects 引用键无白名单依据）");
            return errors;
        }
        StateSchema schema = parseSchema(stateSchemaJson, errors);
        if (schema.isEmpty()) {
            return errors;
        }
        List<StoryAct> safeActs = acts == null ? List.of() : acts;
        for (StoryAct act : safeActs) {
            String actLoc = "幕 " + act.getId();
            checkGuard(act.getEntryGuard(), schema, actLoc + " entry_guard", errors);
            if (act.getConvergeTo() != null) {
                for (ConvergeOrbit orbit : act.getConvergeTo()) {
                    checkGuard(orbit.getGuard(), schema,
                            actLoc + " converge_to[" + orbit.getOrbit() + "]", errors);
                }
            }
            if (act.getNodes() != null) {
                for (StoryNode node : act.getNodes()) {
                    String nodeLoc = "节点 " + node.getId();
                    checkGuard(node.getEntryGuard(), schema, nodeLoc + " entry_guard", errors);
                    checkEffects(node.getOnEnter(), schema, nodeLoc + " on_enter", errors);
                    if (node.getVariants() != null) {
                        for (int i = 0; i < node.getVariants().size(); i++) {
                            StoryVariant variant = node.getVariants().get(i);
                            checkGuard(variant.getGuard(), schema,
                                    nodeLoc + " variant[" + (i + 1) + "]", errors);
                        }
                    }
                }
            }
            if (act.getEdges() != null) {
                for (StoryEdge edge : act.getEdges()) {
                    String edgeLoc = "边 " + edge.getId() + "（" + edge.getFrom() + "->" + edge.getTo() + "）";
                    checkGuard(edge.getGuard(), schema, edgeLoc, errors);
                    checkEffects(edge.getEffects(), schema, edgeLoc + " effects", errors);
                }
            }
        }
        return errors;
    }

    /** StoryGraph 组合入口（graphJson 反序列化 + 词汇表校验；坏 JSON 记 error 不抛） */
    public List<String> validateGraph(StoryGraph graph) {
        if (graph == null) {
            return List.of("图为空");
        }
        List<StoryAct> acts;
        try {
            acts = graph.acts(mapper);
        } catch (Exception e) {
            return List.of("graph_json 反序列化失败：" + e.getMessage());
        }
        return validate(acts, graph.getStateSchema());
    }

    // ==================== schema 解析 ====================

    /**
     * 宽容解析 state_schema：完整形态 {@code {"key": {"type": "number", "min": 0}}}
     * 或简化形态 {@code {"key": "number"}}；解析问题记入 errors（部分成功继续）。
     */
    public StateSchema parseSchema(String json, List<String> errors) {
        StateSchema schema = new StateSchema();
        if (json == null || json.isBlank()) {
            return schema;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            errors.add("state_schema JSON 解析失败：" + e.getMessage());
            return schema;
        }
        if (!root.isObject()) {
            errors.add("state_schema 顶层不是对象");
            return schema;
        }
        root.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode def = entry.getValue();
            StateSchema.KeyDef kd = new StateSchema.KeyDef();
            if (def.isTextual()) {
                kd.setType(def.asText().trim().toLowerCase());
            } else if (def.isObject()) {
                JsonNode type = def.path("type");
                kd.setType(type.isTextual() ? type.asText().trim().toLowerCase() : null);
                JsonNode min = def.path("min");
                if (min.isNumber()) {
                    kd.setMin(min.asDouble());
                }
                JsonNode max = def.path("max");
                if (max.isNumber()) {
                    kd.setMax(max.asDouble());
                }
                JsonNode values = def.path("values");
                if (values.isArray()) {
                    List<String> vs = new ArrayList<>();
                    for (JsonNode v : values) {
                        if (v.isTextual()) {
                            vs.add(v.asText());
                        }
                    }
                    kd.setValues(vs);
                }
            } else {
                errors.add("state_schema 键 " + key + " 的定义既非类型字符串也非对象");
                return;
            }
            if (kd.getType() == null || !StateSchema.KNOWN_TYPES.contains(kd.getType())) {
                errors.add("state_schema 键 " + key + " 类型非法（应为 number/string/flag）：" + kd.getType());
                return;
            }
            schema.put(key, kd);
        });
        return schema;
    }

    // ==================== 引用检查 ====================

    private void checkGuard(GuardClause guard, StateSchema schema, String location, List<String> errors) {
        if (guard == null) {
            return;
        }
        // 谓词自身语法（op/value 结构）复用求值器校验
        errors.addAll(guardEvaluator.validate(guard, location));
        String key = guard.getKey();
        if (key == null || key.isBlank()) {
            return; // 语法校验已报
        }
        if (!schema.contains(key)) {
            errors.add(location + " 引用 state_schema 词汇表外键：" + key);
            return;
        }
        // 类型-op 匹配（错配 = 求值恒 false 的死守卫，作者侧早失败）
        String type = schema.get(key).getType();
        String op = guard.getOp();
        if (op == null) {
            return;
        }
        switch (type) {
            case StateSchema.TYPE_FLAG:
                if (!"==".equals(op)) {
                    errors.add(location + " 类型错配：flag 键仅允许 ==（当前 op " + op + "）");
                }
                break;
            case StateSchema.TYPE_STRING:
                if (">=".equals(op) || "<=".equals(op) || ">".equals(op) || "<".equals(op)) {
                    errors.add(location + " 类型错配：string 键不允许数值比较 op（当前 op " + op + "）");
                }
                break;
            default:
                // number 允许全部 op
        }
    }

    private void checkEffects(Map<String, Object> effects, StateSchema schema, String location, List<String> errors) {
        if (effects == null || effects.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : effects.entrySet()) {
            String key = e.getKey();
            if (!schema.contains(key)) {
                errors.add(location + " 写入 state_schema 词汇表外键：" + key);
                continue;
            }
            Object value = e.getValue();
            String type = schema.get(key).getType();
            switch (type) {
                case StateSchema.TYPE_NUMBER:
                    if (toNumber(value) == null) {
                        errors.add(location + " 键 " + key + "（number）的效果值必须为数字：" + value);
                    }
                    break;
                case StateSchema.TYPE_FLAG:
                    if (!(value instanceof Boolean)
                            && !("true".equalsIgnoreCase(String.valueOf(value))
                            || "false".equalsIgnoreCase(String.valueOf(value)))) {
                        errors.add(location + " 键 " + key + "（flag）的效果值必须为布尔：" + value);
                    }
                    break;
                case StateSchema.TYPE_STRING:
                    if (value != null && !(value instanceof String)) {
                        errors.add(location + " 键 " + key + "（string）的效果值必须为字符串：" + value);
                    }
                    break;
                default:
                    log.debug("未知类型跳过效果值检查：{} {}", key, type);
            }
        }
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
