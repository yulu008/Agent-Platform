package com.luyu.agent.admin;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 平台管理员身份回查（tasks 8.1b / design D9）。
 * <p>
 * <b>不改 JWT 结构</b>（不加 role claim）：管理端访问控制用 JWT 里的 userId 回查
 * {@code app_user.user_role}，仅 {@code platform_admin} 放行。角色即时读库，
 * 故 {@code DefaultTenantBootstrap} 幂等升级角色后无需重签 token 即生效。
 * <p>
 * {@code app_user} 不在 {@code TenantGuardedJdbcTemplate} 守卫名单（仅 rpg_* 受守卫），
 * 按主键 id 查询不受租户过滤影响。
 */
@Component
public class PlatformAdminGuard {

    /** 平台管理员角色值（仅 default 租户引导账号拥有）。 */
    public static final String ROLE = "platform_admin";

    private final JdbcTemplate jdbc;

    public PlatformAdminGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判断用户是否为平台管理员。
     *
     * @param userId JWT 解析出的用户 ID（可能为 null）
     * @return 角色为 {@code platform_admin} 返回 true；用户不存在/角色不符/入参空 均返回 false
     */
    public boolean isPlatformAdmin(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        List<String> roles = jdbc.queryForList(
                "SELECT user_role FROM app_user WHERE id = ?", String.class, userId);
        return !roles.isEmpty() && ROLE.equals(roles.get(0));
    }
}
