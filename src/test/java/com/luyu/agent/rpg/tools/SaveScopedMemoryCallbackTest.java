package com.luyu.agent.rpg.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SaveScopedMemoryCallback} 单元测试。
 * <p>
 * 该包装器是存档隔离的<b>唯一</b>强制点：GM 看到的工具签名里没有任何存档信息，
 * 全靠本层在调用期注入 {@code <gameStateId>/} 前缀。因此路径改写与越狱拦截必须有测试钉住 ——
 * 尤其 {@code gs-001/../gs-002/x.md} 这种形式：归一化后是 {@code <root>/gs-002/x.md}，
 * 仍在 {@code AutoMemoryTools} 的根目录内，底层防护会放行，只有本层的逐段校验能拦住。
 */
class SaveScopedMemoryCallbackTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";

    /** 记录被委托调用的入参，用于断言改写结果 */
    private static final class RecordingCallback implements ToolCallback {

        private final ToolDefinition definition = DefaultToolDefinition.builder()
                .name("MemoryView")
                .description("底层原始描述")
                .inputSchema(INPUT_SCHEMA)
                .build();

        String lastInput;
        ToolContext lastContext;
        int callCount;

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            this.lastInput = toolInput;
            this.lastContext = toolContext;
            this.callCount++;
            return "底层执行成功";
        }
    }

    private final RecordingCallback delegate = new RecordingCallback();

    private final SaveScopedMemoryCallback callback =
            new SaveScopedMemoryCallback(delegate, "GmMemoryView", "读取当前存档的记忆文件");

    private static ToolContext saveContext(String gameStateId) {
        return new ToolContext(Map.of(SaveScopedMemoryCallback.GAME_STATE_ID_KEY, gameStateId));
    }

    private static String pathOf(String rewrittenInput) throws Exception {
        JsonNode node = mapper.readTree(rewrittenInput).get("path");
        return node == null ? null : node.asText();
    }

    // ==================== 路径改写 ====================

    @Test
    void 相对路径被加上存档前缀() throws Exception {
        String result = callback.call("{\"path\":\"MEMORY.md\"}", saveContext("gs-001"));

        assertThat(result).isEqualTo("底层执行成功");
        assertThat(pathOf(delegate.lastInput)).isEqualTo("gs-001/MEMORY.md");
    }

    @Test
    void 反斜杠与点斜杠前缀被归一化() throws Exception {
        callback.call("{\"path\":\".\\\\npc\\\\npc_lin.md\"}", saveContext("gs-002"));
        assertThat(pathOf(delegate.lastInput)).isEqualTo("gs-002/npc/npc_lin.md");

        callback.call("{\"path\":\"./npc_lin.md\"}", saveContext("gs-002"));
        assertThat(pathOf(delegate.lastInput)).isEqualTo("gs-002/npc_lin.md");
    }

    @Test
    void path以外的参数原样透传() throws Exception {
        callback.call("{\"path\":\"npc_lin.md\",\"content\":\"# 林掌柜\\n他记得那枚玉佩\"}",
                saveContext("gs-001"));

        JsonNode root = mapper.readTree(delegate.lastInput);
        assertThat(root.get("path").asText()).isEqualTo("gs-001/npc_lin.md");
        assertThat(root.get("content").asText()).isEqualTo("# 林掌柜\n他记得那枚玉佩");
        assertThat(root.size()).isEqualTo(2);
    }

    @Test
    void ToolContext原样传给底层() {
        ToolContext context = saveContext("gs-001");

        callback.call("{\"path\":\"MEMORY.md\"}", context);

        assertThat(delegate.lastContext).isSameAs(context);
    }

    // ==================== 越狱拦截 ====================

    @Test
    void 上跳到父目录被拒且不调用底层() {
        String result = callback.call("{\"path\":\"../gs-002/MEMORY.md\"}", saveContext("gs-001"));

        assertThat(result).contains("工具调用失败").contains("..");
        assertThat(delegate.callCount).isZero();
    }

    @Test
    void 归一化后仍在根目录内的跨存档越狱也被拒() {
        // 关键用例：gs-001/../gs-002/x.md 归一化后是 <root>/gs-002/x.md，
        // AutoMemoryTools.resolveSafePath 只校验「不逸出根目录」，会放行 → 必须在本层拦住
        String result = callback.call("{\"path\":\"gs-001/../gs-002/x.md\"}", saveContext("gs-001"));

        assertThat(result).contains("工具调用失败").contains("..");
        assertThat(delegate.callCount).isZero();
    }

    @Test
    void 绝对路径被拒() {
        String result = callback.call("{\"path\":\"/etc/passwd\"}", saveContext("gs-001"));

        assertThat(result).contains("工具调用失败").contains("不能以 / 开头");
        assertThat(delegate.callCount).isZero();
    }

    // ==================== 缺失上下文与非法入参 ====================

    @Test
    void 缺少gameStateId时拒绝执行不回落根目录() {
        String result = callback.call("{\"path\":\"MEMORY.md\"}", new ToolContext(Map.of()));

        assertThat(result).contains("缺少存档标识");
        assertThat(delegate.callCount).isZero();
    }

    @Test
    void 无ToolContext的重载同样拒绝() {
        // 回落到根目录会产生无归属的孤儿记忆文件
        String result = callback.call("{\"path\":\"MEMORY.md\"}");

        assertThat(result).contains("缺少存档标识");
        assertThat(delegate.callCount).isZero();
    }

    @Test
    void 缺少path参数时返回可读错误() {
        assertThat(callback.call("{}", saveContext("gs-001"))).contains("缺少 path 参数");
        assertThat(callback.call("{\"path\":\"  \"}", saveContext("gs-001")))
                .contains("缺少 path 参数");
        assertThat(delegate.callCount).isZero();
    }

    @Test
    void 非法JSON不抛异常而是返回可读错误() {
        // 模型偶发产出截断 JSON，必须回错误文本让它重试，而不是中断游戏循环
        assertThat(callback.call("{\"path\":", saveContext("gs-001")))
                .contains("工具调用失败")
                .contains("重新生成完整的 JSON 参数");
        assertThat(callback.call("[1,2,3]", saveContext("gs-001"))).contains("必须是 JSON 对象");
        assertThat(callback.call("", saveContext("gs-001"))).contains("至少需要提供 path");
        assertThat(delegate.callCount).isZero();
    }

    // ==================== 工具定义 ====================

    @Test
    void 工具定义改名并复用底层inputSchema() {
        ToolDefinition definition = callback.getToolDefinition();

        assertThat(definition.name()).isEqualTo("GmMemoryView");
        assertThat(definition.description()).isEqualTo("读取当前存档的记忆文件");
        // inputSchema 直接复用底层，避免手写 JSON Schema 出错
        assertThat(definition.inputSchema()).isEqualTo(INPUT_SCHEMA);
    }
}
