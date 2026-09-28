package com.luyu.agent.rpg.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.tenancy.TenantContext;
import com.luyu.agent.tenancy.TenantPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/**
 * 存档作用域记忆工具包装器。
 * <p>
 * 承担两件事：
 * <ol>
 *   <li><b>改名</b>：对外暴露 {@code GmMemory*} 名称与瘦身后的中文描述，
 *       与主聊天的 {@code Memory*} 工具彻底区分，避免
 *       {@code AutoMemoryToolsAdvisor} 的按名过滤与 {@code StaticToolCallbackResolver}
 *       的按名索引冲突。</li>
 *   <li><b>路径注入（双重前缀：租户 + 存档）</b>：在调用期把入参 JSON 的 {@code path}
 *       改写为 {@code <tenantId>/rpg-saves/<gameStateId>/<原 path>}，使绑定固定租户总根
 *       {@code ~/.agent/tenants} 的 {@code AutoMemoryTools} 按
 *       「租户 → 存档」两级隔离落盘（design D6 / tasks 4.4）。</li>
 * </ol>
 * <p>
 * {@code gameStateId} 与 {@code tenantId} 均取自 {@link ToolContext}，由
 * {@code GameLoopService.prepareTurn} 在请求线程放入（tenantId 来自验签后的 JWT）、
 * {@code RpgGameController} 透传。GM 无需知道前缀，也无从指定其他租户或存档。
 * <p>
 * <b>为什么穿越校验放在本层而不依赖底层</b>：{@code AutoMemoryTools.resolveSafePath}
 * 只保证最终路径不逃出记忆根目录，而 {@code gs-001/../gs-002/x.md} 归一化后是
 * {@code <root>/gs-002/x.md}，仍在根目录内 —— 底层会放行，等于跨存档越狱。
 * 故本层直接拒绝任何含上跳段的相对路径。
 *
 * @see com.luyu.agent.config.ResilientToolCallback 外层容错包装
 */
public class SaveScopedMemoryCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SaveScopedMemoryCallback.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** ToolContext 中存档标识的 key，与 GameLoopService.prepareTurn 放入的键一致 */
    public static final String GAME_STATE_ID_KEY = "gameStateId";

    /** AutoMemoryTools 各方法统一的路径参数名（已由 logs/toolcall.log 实测确认） */
    private static final String PATH_FIELD = "path";

    private static final String MISSING_SAVE_ERROR =
            "工具调用失败：当前上下文缺少存档标识（gameStateId）或租户标识（tenantId），"
                    + "无法定位记忆目录。请通过正常的游戏回合流程调用本工具。";

    private final ToolCallback delegate;
    private final ToolDefinition toolDefinition;

    /**
     * @param delegate    被包装的 AutoMemoryTools 方法级回调（绑定 RPG 存档根目录）
     * @param toolName    对外暴露的改名后工具名，如 {@code GmMemoryView}
     * @param description 瘦身后的中文描述；类型分类学不在此复述，单一真源为
     *                    {@code resources/rpg/gm-memory-prompt.md}
     */
    public SaveScopedMemoryCallback(ToolCallback delegate, String toolName, String description) {
        this.delegate = delegate;
        // inputSchema 直接复用底层，参数名与类型保持一致，避免手写 JSON Schema 出错
        this.toolDefinition = DefaultToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema(delegate.getToolDefinition().inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /**
     * 无 ToolContext 的调用路径一律拒绝：拿不到 gameStateId / tenantId 就无法定位
     * 「租户 + 存档」双层级目录，回落到总根会产生无归属的孤儿记忆文件。
     */
    @Override
    public String call(String toolInput) {
        log.warn("工具[{}] 在无 ToolContext 情况下被调用，已拒绝执行。input={}",
                toolDefinition.name(), toolInput);
        return MISSING_SAVE_ERROR;
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String gameStateId = resolveFromContext(toolContext, GAME_STATE_ID_KEY);
        String tenantId = resolveFromContext(toolContext, TenantContext.TOOL_CONTEXT_KEY);
        if (isBlank(gameStateId) || isBlank(tenantId)) {
            log.warn("工具[{}] 调用缺少 {} / {}，已拒绝执行。input={}",
                    toolDefinition.name(), GAME_STATE_ID_KEY, TenantContext.TOOL_CONTEXT_KEY, toolInput);
            return MISSING_SAVE_ERROR;
        }

        String rewritten;
        try {
            rewritten = injectSavePrefix(toolInput, tenantId, gameStateId);
        } catch (IllegalArgumentException e) {
            // 入参非法（缺 path / 穿越 / 非 JSON 对象）：回可读错误给模型，不中断游戏循环
            log.warn("工具[{}] 入参被拒绝: {}", toolDefinition.name(), e.getMessage());
            return "工具调用失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("工具[{}] 入参改写异常: {}", toolDefinition.name(), e.getMessage());
            return "工具调用失败：参数 JSON 无法解析，请重新生成完整的 JSON 参数后重试。";
        }

        return delegate.call(rewritten, toolContext);
    }

    /**
     * 把入参 JSON 的 path 改写为 {@code <tenantId>/rpg-saves/<gameStateId>/<规范化后的原 path>}。
     * 两个 ID 均先过白名单（安全单段），杜绝拼入穿越序列。
     */
    private String injectSavePrefix(String toolInput, String tenantId, String gameStateId)
            throws Exception {
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
        String safeTenantId = TenantPaths.requireSafeTenantId(tenantId);
        String safeGameStateId = RpgSavePaths.requireSafeGameStateId(gameStateId);
        obj.put(PATH_FIELD, safeTenantId + "/rpg-saves/" + safeGameStateId + "/" + relative);
        return mapper.writeValueAsString(obj);
    }

    /**
     * 规范化 GM 书写的相对路径，并拒绝任何可能逃出本存档的形式。
     *
     * @throws IllegalArgumentException 路径含上跳段或为绝对路径
     */
    private String normalizeRelativePath(String rawPath) {
        String path = rawPath.trim().replace('\\', '/');
        if (path.startsWith("/")) {
            throw new IllegalArgumentException("path 必须是相对当前存档根目录的相对路径，不能以 / 开头。");
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        // 逐段校验：任何 ".." 都意味着试图离开本存档目录
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("path 不允许包含 \"..\"，只能访问当前存档内的记忆文件。");
            }
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空。");
        }
        return path;
    }

    private String resolveFromContext(@Nullable ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
