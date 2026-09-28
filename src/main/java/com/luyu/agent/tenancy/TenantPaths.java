package com.luyu.agent.tenancy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 租户文件目录层的单一真源（design D6）：
 * <pre>
 * ~/.agent/tenants/&lt;tenantId&gt;/memories/          ← 全局记忆（原 ~/.agent/memories）
 * ~/.agent/tenants/&lt;tenantId&gt;/rpg-saves/&lt;gsId&gt;/  ← 存档记忆（原 ~/.agent/rpg-saves/&lt;gsId&gt;/）
 * </pre>
 * <p>
 * 目录层级 = 数据库过滤边界：tenantId 拼路径前必须过与 gameStateId 相同的白名单校验
 * （{@code [A-Za-z0-9_-]{1,64}}，参照 {@code RpgSaveMemoryController.SAFE_ID}），
 * 杜绝 {@code ../} 形式的租户 ID 逃逸出租户总根。
 * <p>
 * 与构建期固定 root 的既有模式配合：所有固定 root（MemoryService、记忆工具、快照组件）
 * 一律指向 {@link #ROOT}（租户总根），{@code <tenantId>/} 路径段由各调用点在请求期拼入——
 * 与 SaveScopedMemoryCallback 注入 {@code <gameStateId>/} 前缀同构。
 */
public final class TenantPaths {

    /** 租户文件总根 */
    public static final String ROOT = System.getProperty("user.home") + "/.agent/tenants";

    /** 存量全局记忆目录（迁移源） */
    public static final String LEGACY_MEMORIES =
            System.getProperty("user.home") + "/.agent/memories";

    /** 存量 RPG 存档记忆目录（迁移源） */
    public static final String LEGACY_RPG_SAVES =
            System.getProperty("user.home") + "/.agent/rpg-saves";

    /** 保留租户（存量数据迁移目标，与 DB tenant_id 回填值一致） */
    public static final String DEFAULT_TENANT = "default";

    /** tenantId 白名单：单段安全字符（与 gameStateId 同标准） */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private TenantPaths() {
    }

    /** 校验 tenantId 是否为安全的单段路径成分。 */
    public static boolean isSafeTenantId(String tenantId) {
        return tenantId != null && SAFE_ID.matcher(tenantId).matches();
    }

    /**
     * 校验并返回安全的 tenantId；非法（含穿越序列、空、超长）即抛
     * {@link IllegalArgumentException}——绝不带着可疑值去拼路径。
     */
    public static String requireSafeTenantId(String tenantId) {
        if (!isSafeTenantId(tenantId)) {
            throw new IllegalArgumentException("非法的租户标识: " + tenantId);
        }
        return tenantId;
    }

    /** 当前请求租户的目录（来自 {@link TenantContext}）。 */
    public static Path tenantDir() {
        return tenantDir(requireSafeTenantId(TenantContext.requireTenantId()));
    }

    /** 指定租户的目录：{@code ~/.agent/tenants/<tenantId>}。 */
    public static Path tenantDir(String tenantId) {
        return Paths.get(ROOT, requireSafeTenantId(tenantId));
    }

    /** 当前请求租户的全局记忆目录：{@code ~/.agent/tenants/<tid>/memories}。 */
    public static Path memoriesDir() {
        return tenantDir().resolve("memories");
    }

    /** 指定租户的全局记忆目录。 */
    public static Path memoriesDir(String tenantId) {
        return tenantDir(tenantId).resolve("memories");
    }

    /** 当前请求租户的 RPG 存档记忆根：{@code ~/.agent/tenants/<tid>/rpg-saves}。 */
    public static Path rpgSavesDir() {
        return tenantDir().resolve("rpg-saves");
    }

    /** 指定租户的 RPG 存档记忆根。 */
    public static Path rpgSavesDir(String tenantId) {
        return tenantDir(tenantId).resolve("rpg-saves");
    }
}
