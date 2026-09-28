package com.luyu.agent.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

/**
 * 租户作用域记忆工具包装器（design D6，tasks 4.3）：主聊天 {@code Memory*} 工具的
 * 租户目录前缀注入层，模式与 RPG 的 {@code SaveScopedMemoryCallback} 同构。
 * <ol>
 *   <li>不改名：toolDefinition 原样透传 delegate，主聊天对 {@code Memory*} 名称零感知。</li>
 *   <li>路径注入：调用期把入参 JSON 的 {@code path} 改写为
 *       {@code <tenantId>/memories/<原 path>}，使绑定固定租户总根
 *       {@code ~/.agent/tenants} 的 {@code AutoMemoryTools} 按租户隔离落盘到
 *       {@code ~/.agent/tenants/<tid>/memories/}。</li>
 * </ol>
 * <p>
 * {@code tenantId} 取自 {@link ToolContext}（{@link TenantContext#TOOL_CONTEXT_KEY}），
 * 由 ChatStreamController 在请求发起时放入。取不到即拒绝执行（file-isolation spec：
 * MUST NOT 回落到无租户的根目录产生孤儿文件），并以可读错误回给模型。
 * <p>
 * 穿越校验在本层逐段拒绝 {@code ..} 与绝对路径：tenantId 前缀由本层拼入且先过
 * {@link TenantPaths#requireSafeTenantId} 白名单，模型无法通过 path 指定其他租户。
 *
 * @see com.luyu.agent.config.ResilientToolCallback 外层容错包装
 */
public class TenantScopedMemoryCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(TenantScopedMemoryCallback.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** AutoMemoryTools 各方法统一的路径参数名 */
    private static final String PATH_FIELD = "path";

    private static final String MISSING_TENANT_ERROR =
            "工具调用失败：当前上下文缺少租户标识（tenantId），无法定位记忆目录。"
                    + "请通过正常对话流程调用本工具。";

    private final ToolCallback delegate;

    public TenantScopedMemoryCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 无 ToolContext 的调用路径一律拒绝：拿不到 tenantId 就无法确定租户目录，
     * 回落到总根会产生无归属的孤儿记忆文件（file-isolation spec）。
     */
    @Override
    public String call(String toolInput) {
        log.warn("工具[{}] 在无 ToolContext 情况下被调用，已拒绝执行。input={}",
                delegate.getToolDefinition().name(), toolInput);
        return MISSING_TENANT_ERROR;
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String tenantId = resolveTenantId(toolContext);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("工具[{}] 调用缺少 {}，已拒绝执行。input={}",
                    delegate.getToolDefinition().name(), TenantContext.TOOL_CONTEXT_KEY, toolInput);
            return MISSING_TENANT_ERROR;
        }
        String safeTenantId;
        try {
            safeTenantId = TenantPaths.requireSafeTenantId(tenantId);
        } catch (IllegalArgumentException e) {
            log.warn("工具[{}] 收到非法租户标识，已拒绝执行。", delegate.getToolDefinition().name());
            return MISSING_TENANT_ERROR;
        }

        String rewritten;
        try {
            rewritten = injectTenantPrefix(toolInput, safeTenantId);
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
     * 把入参 JSON 的 path 改写为 {@code <tenantId>/memories/<规范化后的原 path>}：
     * delegate 的固定 root 是租户总根 {@code ~/.agent/tenants}，故前缀需补齐
     * {@code memories/} 段，最终落到 {@code ~/.agent/tenants/<tid>/memories/}（design D6）。
     */
    private String injectTenantPrefix(String toolInput, String tenantId) throws Exception {
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
        obj.put(PATH_FIELD, tenantId + "/memories/" + relative);
        return mapper.writeValueAsString(obj);
    }

    /**
     * 规范化相对路径并拒绝任何可能逃出本租户目录的形式。
     */
    private String normalizeRelativePath(String rawPath) {
        String path = rawPath.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("path 必须是相对当前租户记忆目录的相对路径，不能以 / 开头。");
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("path 不允许包含 \"..\"，只能访问当前租户内的记忆文件。");
            }
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空。");
        }
        return path;
    }

    private String resolveTenantId(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(TenantContext.TOOL_CONTEXT_KEY);
        return value == null ? null : value.toString();
    }
}
