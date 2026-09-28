package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * state_delta 中 npc_states key 的合法性校验器（纯函数组件，无 IO、无状态）。
 * <p>
 * 判定顺序（短路）：
 * <ol>
 *   <li>key 在合法 key 集合内（该世界角色卡 ID ∪ 存档 npc_states 既有 key）→ 合法</li>
 *   <li>key 含乱码替换符 U+FFFD（上游模型坏字节残留）→ 非法</li>
 *   <li>key 不含拉丁字母（纯汉字及汉字加标点/数字等非拉丁命名）→ 合法</li>
 *   <li>其余（新增且含拉丁字母、且非任何角色卡 ID）→ 非法</li>
 * </ol>
 * 刻意放行「既有 key」：旧档遗留的拼音 key 必须允许 GM 沿用，否则会被误杀。
 *
 * @see com.luyu.agent.rpg.service.WorkshopService#localizeNames 工坊侧的存量清理入口
 */
public final class StateDeltaKeyValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
    private static final char REPLACEMENT_CHAR = '\uFFFD';

    private StateDeltaKeyValidator() {
    }

    /**
     * 校验结果：合法条目节点 + 非法 key 清单。
     *
     * @param validEntries 仅含合法 key 的 npc_states 节点（新建对象，不修改入参）
     * @param invalidKeys   非法 key 清单（保持原顺序）
     */
    public record Result(ObjectNode validEntries, List<String> invalidKeys) {

        public boolean hasInvalid() {
            return !invalidKeys.isEmpty();
        }
    }

    /**
     * 单 key 合法性判定。
     *
     * @param key       npc_states 的 key
     * @param validKeys 合法 key 集合（卡 ID ∪ 存档既有 key），可为 null（视为空集合）
     * @return true 表示合法
     */
    public static boolean isKeyValid(String key, Set<String> validKeys) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (validKeys != null && validKeys.contains(key)) {
            return true;
        }
        if (key.indexOf(REPLACEMENT_CHAR) >= 0) {
            return false;
        }
        return !LATIN.matcher(key).find();
    }

    /**
     * 批量校验 npc_states 节点的全部 key。
     *
     * @param npcStates state_delta 的 npc_states（或 npcStates）节点；null/非对象视为空
     * @param validKeys 合法 key 集合（卡 ID ∪ 存档既有 key），可为 null
     * @return 校验结果（空节点返回空合法节点 + 空非法清单）
     */
    public static Result validate(JsonNode npcStates, Set<String> validKeys) {
        ObjectNode valid = MAPPER.createObjectNode();
        List<String> invalid = new ArrayList<>();
        if (npcStates != null && npcStates.isObject()) {
            npcStates.fields().forEachRemaining(entry -> {
                if (isKeyValid(entry.getKey(), validKeys)) {
                    valid.set(entry.getKey(), entry.getValue());
                } else {
                    invalid.add(entry.getKey());
                }
            });
        }
        return new Result(valid, invalid);
    }
}
