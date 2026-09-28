package com.luyu.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.luyu.agent.config.AuthBootstrapProperties;
import com.luyu.agent.service.AuthService;

/**
 * default 租户引导账号测试：补齐 multi-tenant-column-isolation 部署缺口
 * （design L116「为原使用者注册 default 租户账号（或提供一次性引导）」）。
 * <p>
 * 验证链：引导创建 → AuthService.login 用引导密码登录得 tenantId='default'
 * → 存量数据归属租户自此可登录访问（spec 场景「升级后原会话可用」的登录前提）。
 */
@JdbcTest
class DefaultTenantBootstrapTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private DefaultTenantBootstrap bootstrap;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        bootstrap = new DefaultTenantBootstrap(jdbcTemplate, new AuthBootstrapProperties());
        authService = new AuthService(jdbcTemplate, transactionManager);
    }

    @Test
    void 首次引导创建default租户账号且可用引导密码登录() {
        bootstrap.bootstrap();

        Integer tenantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id = 'default' AND status = 'ACTIVE'", Integer.class);
        assertThat(tenantCount).isEqualTo(1);

        AuthService.AuthUser result = authService.login("admin@default.local", "123456");
        assertThat(result).as("引导密码应可登录").isNotNull();
        assertThat(result.tenantId()).isEqualTo("default");
        // 错误密码抛出无效凭据异常（login 契约：失败抛异常而非返回 null）
        assertThatThrownBy(() -> authService.login("admin@default.local", "wrong-pass"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class);
    }

    @Test
    void 引导幂等重跑账号唯一() {
        bootstrap.bootstrap();
        bootstrap.bootstrap();
        bootstrap.bootstrap();

        assertThat(countUsersOfDefault()).isEqualTo(1);
        // 重跑后引导密码仍可登录（未重复插入、未改密）
        assertThat(authService.login("admin@default.local", "123456")).isNotNull();
    }

    @Test
    void 已有default账号时跳过不覆盖既有凭据() {
        bootstrap.bootstrap();
        bootstrap.bootstrap();

        assertThat(countUsersOfDefault()).isEqualTo(1);
    }

    @Test
    void 引导邮箱被其他租户占用时跳过创建() {
        authService.register("admin@default.local", "password1");

        bootstrap.bootstrap();

        assertThat(countUsersOfDefault())
                .as("邮箱已被注册租户占用时不得抢建 default 账号")
                .isEqualTo(0);
        // 占用者仍归属自己的注册租户
        AuthService.AuthUser result = authService.login("admin@default.local", "password1");
        assertThat(result).isNotNull();
        assertThat(result.tenantId()).startsWith("t-");
    }

    private int countUsersOfDefault() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE tenant_id = 'default'", Integer.class);
        return count == null ? 0 : count;
    }
}
