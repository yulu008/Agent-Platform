package com.luyu.agent.rpg.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 存档招式的宽松读取工具（rpg-technique-tree-data D6）。
 * <p>
 * GM 上下文渲染与 {@code rpg-technique-gate} 共用的读取入口，兼容规则：
 * <ul>
 *   <li>缺 {@code learned} → 视为 {@code true}（旧档玩家已在全量发放下正常游戏，不追溯性没收）</li>
 *   <li>缺 {@code proficiency} → 视为 400（小成）</li>
 *   <li>缺 {@code tier} / {@code prerequisites} → 从 {@link TechniqueTreeMap} 现算，<b>不回写存档</b></li>
 *   <li>{@code playerStates} 无 {@code techniques} 键（非星命世界存档，如都市大逃杀）→ 返回空集</li>
 * </ul>
 * 非法 JSON 一律降级为空集而非抛异常：读取失败只该让招式段消失，不该让整轮对话 500。
 */
public final class TechniqueStateReader {

    /** 旧档缺 {@code proficiency} 时的默认值：400 = 小成 */
    public static final int LEGACY_PROFICIENCY = 400;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TechniqueStateReader() {
    }

    /**
     * 从 {@code playerStates} JSON 串读取招式状态列表。
     *
     * @return 招式状态列表；无 {@code techniques} 键 / 非法 JSON / null 时为空集
     */
    public static List<TechniqueState> read(String playerStatesJson) {
        if (playerStatesJson == null || playerStatesJson.isBlank()) {
            return List.of();
        }
        try {
            return read(MAPPER.readTree(playerStatesJson));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 从已解析的 {@code playerStates} 根节点读取招式状态列表。
     * <p>
     * 星座/阵营从根节点的 {@code constellation} / {@code faction} 键取，用于现算缺省的
     * {@code tier} / {@code prerequisites}（作用域锁在同星座同表内）。
     */
    public static List<TechniqueState> read(JsonNode root) {
        if (root == null || !root.isObject()) {
            return List.of();
        }
        JsonNode techs = root.path("techniques");
        if (!techs.isArray()) {
            return List.of();
        }
        String constellation = root.path("constellation").asText("");
        String faction = root.path("faction").asText("");
        List<TechniqueState> out = new ArrayList<>();
        for (JsonNode node : techs) {
            String name = node.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            boolean learned = node.has("learned") ? node.path("learned").asBoolean(true) : true;
            int proficiency = node.has("proficiency")
                    ? node.path("proficiency").asInt(LEGACY_PROFICIENCY) : LEGACY_PROFICIENCY;
            int tier = node.has("tier") ? node.path("tier").asInt(1) : computedTier(constellation, faction, name);
            List<String> prereqs = node.has("prerequisites")
                    ? parsePrereqs(node.path("prerequisites"))
                    : TechniqueTreeMap.getPrerequisites(constellation, faction, name);
            out.add(new TechniqueState(name, node.path("description").asText(""),
                    node.path("source").asText(""), tier, prereqs, learned, proficiency));
        }
        return List.copyOf(out);
    }

    /** 树表现算 tier；未建树 / 不在树中（单技能条目、兜底招式）视为单节点树 tier=1 */
    private static int computedTier(String constellation, String faction, String name) {
        int tier = TechniqueTreeMap.getTier(constellation, faction, name);
        return tier == TechniqueTreeMap.TIER_UNKNOWN ? 1 : tier;
    }

    private static List<String> parsePrereqs(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> prereqs = new ArrayList<>();
        node.forEach(p -> prereqs.add(p.asText("")));
        prereqs.removeIf(String::isBlank);
        return prereqs;
    }
}
