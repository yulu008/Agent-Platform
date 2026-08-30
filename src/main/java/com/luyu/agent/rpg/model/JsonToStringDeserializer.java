package com.luyu.agent.rpg.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * 宽字符串反序列化器：兼容 JSON 字符串与数组/对象两种输入，统一归一化为字符串。
 * <p>
 * 用于 knowledge / rules / locations 等「JSON 数组字符串」字段：
 * LLM 输出不稳定，有时返回真数组、有时返回字符串化数组，
 * 不做兼容时 Jackson 直接抛 MismatchedInputException 导致生成接口 500。
 */
public class JsonToStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}
