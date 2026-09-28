package com.luyu.agent.rpg.config;

import com.luyu.agent.tenancy.TenantPaths;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * RPG 存档记忆路径的单一真源（多租户版，design D6 / tasks 4.4）：
 * <pre>
 * ~/.agent/tenants/&lt;tenantId&gt;/rpg-saves/&lt;gameStateId&gt;/   ← 存档记忆（原 ~/.agent/rpg-saves/&lt;gsId&gt;/）
 * </pre>
 * <p>
 * 与主聊天侧同构的两种接入方式：
 * <ul>
 *   <li><b>构建期固定 root 的工具底座</b>（{@code RpgToolConfiguration} 的
 *       {@code AutoMemoryTools}）：root 指向 {@link #TOOL_ROOT}（租户总根），
 *       {@code <tenantId>/rpg-saves/} 路径段由 {@code SaveScopedMemoryCallback}
 *       在调用期从 ToolContext 注入（双重前缀：租户 + 存档）。</li>
 *   <li><b>调用期构造 root 的组件</b>（快照 / 删档 / 工坊 / 存档记忆 REST 端点）：
 *       直接用 {@link #saveDir}，按当前请求租户解析，跨租户自然互不可达。</li>
 * </ul>
 * <p>
 * 此前路径字面量分散在三处各写一份易漂移，故收拢到本类；
 * {@code gameStateId} 拼路径前必须过白名单校验（与
 * {@code RpgSaveMemoryController}/{@code SaveDeletionService} 同标准）。
 */
public final class RpgSavePaths {

    /**
     * GmMemory* 工具底座（{@code AutoMemoryTools}）的构建期固定根：租户总根
     * {@code ~/.agent/tenants}。{@code <tenantId>/rpg-saves/} 段由
     * {@code SaveScopedMemoryCallback} 调用期注入，底座本身无法随租户变化。
     */
    public static final String TOOL_ROOT = TenantPaths.ROOT;

    /** 存量 RPG 存档记忆根目录（迁移源，tasks 4.5；单一真源在 {@link TenantPaths}） */
    public static final String LEGACY_ROOT = TenantPaths.LEGACY_RPG_SAVES;

    /** 索引文件名（与 {@code AutoMemoryTools} 约定一致，不作为记忆条目） */
    public static final String INDEX_FILE = "MEMORY.md";

    /** gameStateId 白名单：单段安全字符（与租户 ID 同标准） */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private RpgSavePaths() {
    }

    /**
     * 某存档的记忆目录（不保证存在），调用期按当前请求租户解析：
     * {@code ~/.agent/tenants/<tenantId>/rpg-saves/<gameStateId>}。
     * <p>
     * 调用方须处于租户上下文（HTTP 请求线程，或经 TurnContext 恢复的异步链路），
     * 否则 {@link com.luyu.agent.tenancy.TenantContextMissingException} 快速失败。
     *
     * @param gameStateId 游戏状态 ID（须过白名单）
     */
    public static Path saveDir(String gameStateId) {
        return TenantPaths.rpgSavesDir().resolve(requireSafeGameStateId(gameStateId));
    }

    /** 校验 gameStateId 是否为安全的单段路径成分。 */
    public static boolean isSafeGameStateId(String gameStateId) {
        return gameStateId != null && SAFE_ID.matcher(gameStateId).matches();
    }

    /**
     * 校验并返回安全的 gameStateId；非法（含穿越序列、空、超长）即抛
     * {@link IllegalArgumentException}——绝不带着可疑值去拼路径。
     */
    public static String requireSafeGameStateId(String gameStateId) {
        if (!isSafeGameStateId(gameStateId)) {
            throw new IllegalArgumentException("非法的存档标识: " + gameStateId);
        }
        return gameStateId;
    }
}
