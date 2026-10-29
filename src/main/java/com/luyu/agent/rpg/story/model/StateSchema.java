package com.luyu.agent.rpg.story.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 状态词汇表（story-graph-model spec：层0，作者预定义）。
 * <p>
 * 本故事关心的状态谓词键清单与类型——guard/effects/variants/converge_to
 * 引用的状态键 SHALL 存在于本表（白名单，防 LLM 造键）。值为扁平状态空间
 * （如 {@code trust_信长}），运行侧负责把它桥接到 GameState（任务 4.2）。
 * <ul>
 *   <li>{@code number}：数值键（可选区间 min/max；guard 允许全部 op）</li>
 *   <li>{@code string}：字符串键（可选枚举 values；guard 允许 == / in）</li>
 *   <li>{@code flag}：布尔键（guard 仅允许 ==）</li>
 * </ul>
 * JSON 形态支持完整 {@code {"key": {"type": "number", "min": 0, "max": 100}}}
 * 与简化 {@code {"key": "number"}} 两种（宽容归一，解析在 StateSchemaValidator）。
 */
public class StateSchema {

    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_FLAG = "flag";
    public static final Set<String> KNOWN_TYPES = Set.of(TYPE_NUMBER, TYPE_STRING, TYPE_FLAG);

    /** 单键类型定义 */
    public static class KeyDef {

        private String type;
        /** number 可选区间下界 */
        private Double min;
        /** number 可选区间上界 */
        private Double max;
        /** string 可选枚举值域 */
        private List<String> values = new ArrayList<>();

        public KeyDef() {
        }

        public KeyDef(String type) {
            this.type = type;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Double getMin() { return min; }
        public void setMin(Double min) { this.min = min; }
        public Double getMax() { return max; }
        public void setMax(Double max) { this.max = max; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
    }

    private final Map<String, KeyDef> keys = new LinkedHashMap<>();

    public void put(String key, KeyDef def) {
        keys.put(key, def);
    }

    public boolean contains(String key) {
        return key != null && keys.containsKey(key);
    }

    public KeyDef get(String key) {
        return key == null ? null : keys.get(key);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public Set<String> keySet() {
        return keys.keySet();
    }

    public Map<String, KeyDef> getKeys() {
        return keys;
    }
}
