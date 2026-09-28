package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StateDeltaKeyValidator} 的单元测试。
 * <p>
 * 覆盖 spec 四场景（卡 ID 合法 / 既有 key 合法含旧档拼音 / 新增纯汉字合法 /
 * 新增拉丁 key 非法），外加混合批量判定、空/非对象节点与合法集合为 null 的边界。
 */
class StateDeltaKeyValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CARD_ID = "3f9c2a1e-0000-4000-8000-abc123def456";

    /** 合法 key 集合：角色卡 ID ∪ 存档既有 key（含旧档拼音） */
    private static final Set<String> VALID_KEYS = Set.of(CARD_ID, "sanwensuke", "苏枕雪");

    private JsonNode npcStates(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void 角色卡ID判合法() throws Exception {
        var result = StateDeltaKeyValidator.validate(
                npcStates("{\"" + CARD_ID + "\": {\"status\": \"警惕\"}}"), VALID_KEYS);

        assertThat(result.invalidKeys()).isEmpty();
        assertThat(result.validEntries().has(CARD_ID)).isTrue();
        assertThat(result.validEntries().get(CARD_ID).get("status").asText()).isEqualTo("警惕");
    }

    @Test
    void 存档既有key判合法含旧档拼音() throws Exception {
        var result = StateDeltaKeyValidator.validate(
                npcStates("{\"sanwensuke\": {\"status\": \"idle\"}}"), VALID_KEYS);

        assertThat(result.invalidKeys()).isEmpty();
        assertThat(result.validEntries().has("sanwensuke")).isTrue();
    }

    @Test
    void 新增纯汉字key判合法() throws Exception {
        var result = StateDeltaKeyValidator.validate(
                npcStates("{\"灰袍仆人\": {\"status\": \"引路\"}, \"疤脸汉子·3\": {\"status\": \"戒备\"}}"),
                VALID_KEYS);

        assertThat(result.invalidKeys()).isEmpty();
        assertThat(result.validEntries().size()).isEqualTo(2);
    }

    @Test
    void 新增含拉丁字母key判非法() throws Exception {
        var result = StateDeltaKeyValidator.validate(
                npcStates("{\"gray_robed_servant\": {\"status\": \"引路\"}, \"su_zhenxue\": {}}"),
                VALID_KEYS);

        assertThat(result.invalidKeys()).containsExactly("gray_robed_servant", "su_zhenxue");
        assertThat(result.validEntries().isEmpty()).isTrue();
    }

    @Test
    void 混合合法与非法的批量判定() throws Exception {
        var result = StateDeltaKeyValidator.validate(npcStates("""
                {"gray_robed_servant": {"status": "引路"},
                 "灰袍仆人": {"status": "提灯"},
                 "sanwensuke": {"status": "idle"},
                 "su_zhenxue": {}}
                """), VALID_KEYS);

        assertThat(result.invalidKeys()).containsExactly("gray_robed_servant", "su_zhenxue");
        assertThat(result.validEntries().size()).isEqualTo(2);
        assertThat(result.validEntries().has("灰袍仆人")).isTrue();
        assertThat(result.validEntries().has("sanwensuke")).isTrue();
        assertThat(result.hasInvalid()).isTrue();
    }

    @Test
    void 空节点与非对象节点返回空结果() throws Exception {
        var empty = StateDeltaKeyValidator.validate(npcStates("{}"), VALID_KEYS);
        assertThat(empty.invalidKeys()).isEmpty();
        assertThat(empty.validEntries().isEmpty()).isTrue();

        var notObject = StateDeltaKeyValidator.validate(npcStates("[]"), VALID_KEYS);
        assertThat(notObject.invalidKeys()).isEmpty();
        assertThat(notObject.validEntries().isEmpty()).isTrue();

        var nullNode = StateDeltaKeyValidator.validate(null, VALID_KEYS);
        assertThat(nullNode.invalidKeys()).isEmpty();
        assertThat(nullNode.validEntries().isEmpty()).isTrue();
    }

    @Test
    void 合法集合为null时仅按拉丁字母判定() throws Exception {
        // null 集合下：拼音既有 key 也判非法（防御语义），纯汉字仍合法
        var result = StateDeltaKeyValidator.validate(
                npcStates("{\"sanwensuke\": {}, \"灰袍仆人\": {}}"), null);

        assertThat(result.invalidKeys()).containsExactly("sanwensuke");
        assertThat(result.validEntries().has("灰袍仆人")).isTrue();
    }

    @Test
    void 空白key判非法() {
        assertThat(StateDeltaKeyValidator.isKeyValid("", VALID_KEYS)).isFalse();
        assertThat(StateDeltaKeyValidator.isKeyValid(null, VALID_KEYS)).isFalse();
    }

    @Test
    void 含乱码替换符的key判非法() {
        // 上游坏字节经 UTF-8 解码残留的 U+FFFD：即使无拉丁字母也判非法，交由修复链处理
        assertThat(StateDeltaKeyValidator.isKeyValid("灰袍仆人\uFFFD", VALID_KEYS)).isFalse();
        assertThat(StateDeltaKeyValidator.isKeyValid("\uFFFD", VALID_KEYS)).isFalse();
    }
}
