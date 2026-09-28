package com.luyu.agent.tenancy;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.luyu.agent.config.AuthBootstrapProperties;

/**
 * default 租户引导账号（一次性，幂等）。
 * <p>
 * 补齐 multi-tenant-column-isolation 的部署缺口（design L116/L122）：存量数据
 * （AI_SESSION 回填、rpg_* 回填、文件搬迁）全部归入 {@link TenantPaths#DEFAULT_TENANT}，
 * 而注册接口生成的租户 ID 是 {@code t-<uuid>}，常规注册无法获得 default 租户的登录账号，
 * 没有引导账号则存量数据对任何人都不可见。
 * <p>
 * default 是与注册租户完全同级的独立租户：数据隔离规则（列级过滤、守卫、文件目录）
 * 对它没有任何特殊豁免，本组件只是提前创建 tenant 行与登录账号。
 * <p>
 * 时机与幂等：监听 {@link ApplicationReadyEvent}（schema-h2.sql 控制表建立之后）；
 * tenant 行与 app_user 行分别判存，可任意次重跑；失败仅记 error 不阻断启动
 * （与 {@link AiSessionTenantBackfill} 同一惯例）。
 */
@Component
@EnableConfigurationProperties(AuthBootstrapProperties.class)
public class DefaultTenantBootstrap {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantBootstrap.class);

    private final JdbcTemplate jdbcTemplate;
    private final AuthBootstrapProperties properties;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public DefaultTenantBootstrap(JdbcTemplate jdbcTemplate, AuthBootstrapProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        try {
            Integer tenantCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tenant WHERE id = ?",
                    Integer.class, TenantPaths.DEFAULT_TENANT);
            if (tenantCount == null || tenantCount == 0) {
                jdbcTemplate.update(
                        "INSERT INTO tenant (id, display_name, status) VALUES (?, ?, 'ACTIVE')",
                        TenantPaths.DEFAULT_TENANT, "默认租户（存量数据保留租户）");
                log.info("default 租户控制行已创建");
            }

            Integer userCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE tenant_id = ?",
                    Integer.class, TenantPaths.DEFAULT_TENANT);
            if (userCount != null && userCount > 0) {
                // 幂等升级：将 default 租户引导账号角色提升为 platform_admin（老库可能为 'admin'）。
                // 以 tenant_id='default' + 引导邮箱双重定位，不会误伤其他租户的同名邮箱账号。
                int upgraded = jdbcTemplate.update(
                        "UPDATE app_user SET user_role = 'platform_admin' "
                                + "WHERE tenant_id = ? AND email = ? AND user_role <> 'platform_admin'",
                        TenantPaths.DEFAULT_TENANT, properties.getEmail());
                if (upgraded > 0) {
                    log.info("default 租户引导账号角色已幂等升级为 platform_admin");
                } else {
                    log.debug("default 租户账号已存在且角色正确，跳过引导");
                }
                return;
            }
            String email = properties.getEmail();
            Integer emailCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, email);
            if (emailCount != null && emailCount > 0) {
                log.warn("引导邮箱 {} 已被其他租户占用，跳过创建 default 租户账号", email);
                return;
            }
            jdbcTemplate.update(
                    "INSERT INTO app_user (id, tenant_id, email, password_hash, user_role) VALUES (?, ?, ?, ?, 'platform_admin')",
                    "u-" + UUID.randomUUID(), TenantPaths.DEFAULT_TENANT, email,
                    passwordEncoder.encode(properties.getPassword()));
            log.info("default 租户引导账号已创建（platform_admin）: {}（请登录后尽快修改密码）", email);
        } catch (Exception e) {
            log.error("default 租户引导账号创建失败（不阻断启动）", e);
        }
    }
}
