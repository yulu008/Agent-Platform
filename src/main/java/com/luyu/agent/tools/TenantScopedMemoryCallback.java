package com.luyu.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/**
 * 多租户作用域记忆工具包装器。
 * <p>
 * 在调用期把入参 JSON 的 {@code path} 改写为
 * {@code <userId>/<subDirectory>/<规范化后的原 path>}，
 * 使绑定固定根目录（{@code ~/.agent}）的 {@code AutoMemoryTools}
 * 能按用户隔离落盘。
 * <p>
 * {@code userId} 取自 {@link ToolContext}，由 Controller 在请求入口
 * 从 {@link com.luyu.agent.tenant.TenantContext} 获取后注入。
 * LLM 无需知道前缀，也无从指定其他用户目录。
 * <p>
 * <b>包装链</b>（由外到内）：
 * <ul>
 *   <li>主聊天：{@code ResilientToolCallback → TenantScopedMemoryCallback → AutoMemoryTools}</li>
 *   <li>RPG：{@code ResilientToolCallback → SaveScopedMemoryCallback → TenantScopedMemoryCallback → AutoMemoryTools}</li>
 * </ul>
 * <p>
 * <b>路径穿越防护</b>：与 {@code SaveScopedMemoryCallback} 一致，
 * 拒绝任何含 {@code ".."} 的相对路径或以 {@code /} 开头的绝对路径。
 *
 * @see com.luyu.agent.rpg.tools.SaveScopedMemoryCallback 存档级路径注入
 */
public class TenantScopedMemoryCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(TenantScopedMemoryCallback.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** ToolContext 中用户 ID 的 key */
    public static final String USER_ID_KEY = "userId";

    /** AutoMemoryTools 各方法统一的路径参数名 */
    private static final String PATH_FIELD = "path";

    private static final String MISSING_USER_ERROR =
            "工具调用失败：当前上下文缺少用户标识（userId），无法定位记忆目录。"
                    + "请通过正常的对话流程调用本工具。";

    private final ToolCallback delegate;
    private final String subDirectory;

    /**
     * @param delegate     被包装的 AutoMemoryTools 方法级回调（根目录为 {@code ~/.agent}）
     * @param subDirectory 记忆子目录名，如 {@code "memories"}（主聊天）或 {@code "rpg-saves"}（RPG）
     */
    public TenantScopedMemoryCallback(ToolCallback delegate, String subDirectory) {
        this.delegate = delegate;
        this.subDirectory = subDirectory;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 无 ToolContext 的调用路径一律拒绝：拿不到 userId 就无法确定用户目录，
     * 回落到根目录会产生无归属的孤儿记忆文件。
     */
    @Override
    public String call(String toolInput) {
        log.warn("工具[{}] 在无 ToolContext 情况下被调用，已拒绝执行。input={}",
                delegate.getToolDefinition().name(), toolInput);
        return MISSING_USER_ERROR;
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String userId = resolveUserId(toolContext);
        if (userId == null || userId.isBlank()) {
            log.warn("工具[{}] 调用缺少 {}，已拒绝执行。input={}",
                    delegate.getToolDefinition().name(), USER_ID_KEY, toolInput);
            return MISSING_USER_ERROR;
        }

        String rewritten;
        try {
            rewritten = injectTenantPrefix(toolInput, userId);
        } catch (IllegalArgumentException e) {
            log.warn("工具[{}] 入参被拒绝: {}", delegate.getToolDefinition().name(), e.getMessage());
            return "工具调用失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("工具[{}] 入参改写异常: {}", delegate.getToolDefinition().name(), e.getMessage());
            return "工具调用失败：参数 JSON 无法解析，请重新生成完整的 JSON 参数后重试。";
        }

        return delegate.call(rewritten, toolContext);
    }

    /**
     * 把入参 JSON 的 path 改写为 {@code <userId>/<subDirectory>/<规范化后的原 path>}。
     */
    private String injectTenantPrefix(String toolInput, String userId) throws Exception {
        if (toolInput == null || toolInput.isBlank()) {
            throw new IllegalArgumentException("缺少入参，至少需要提供 path。");
        }
        JsonNode root = mapper.readTree(toolInput);
        if (!(root instanceof ObjectNode obj)) {
            throw new IllegalArgumentException("入参必须是 JSON 对象。");
        }
        JsonNode pathNode = obj.get(PATH_FIELD);
        if (pathNode == null || pathNode.isNull() || pathNode.asText().isBlank()) {
            throw new IllegalArgumentException("缺少 path 参数。");
        }
        String relative = normalizeRelativePath(pathNode.asText());
        obj.put(PATH_FIELD, userId + "/" + subDirectory + "/" + relative);
        return mapper.writeValueAsString(obj);
    }

    /**
     * 规范化路径并拒绝任何可能逃出本用户目录的形式。
     *
     * @throws IllegalArgumentException 路径含上跳段或为绝对路径
     */
    private String normalizeRelativePath(String rawPath) {
        String path = rawPath.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("path 必须是相对记忆根目录的相对路径，不能以 / 开头。");
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("path 不允许包含 \"..\"，只能访问当前用户目录内的记忆文件。");
            }
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空。");
        }
        return path;
    }

    private String resolveUserId(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(USER_ID_KEY);
        return value == null ? null : value.toString();
    }
}
