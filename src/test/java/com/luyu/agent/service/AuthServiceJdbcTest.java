package com.luyu.agent.service;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.luyu.agent.config.AuthProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AuthService} 切片测试（@JdbcTest + 内存 H2）。
 * <p>
 * 控制表 DDL 经由 {@code schema.sql} 类路径根脚本建表（内容即
 * {@code rpg/schema-h2.sql} 的控制表段，独立维护避免引入 RPG 表 FK 依赖）。
 * <p>
 * 钉住的 spec 场景：注册创建租户、邮箱重复 409、登录错误不泄露邮箱存在性。
 */
@JdbcTest
@Import({AuthService.class})
@TestPropertySource(properties = "auth.jwt.secret=test-secret-key-0123456789abcdef0123456789")
class AuthServiceJdbcTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void 注册成功创建租户与用户() {
        AuthService.AuthUser user = authService.register("alice@example.com", "password123");

        assertThat(user.userId()).startsWith("u-");
        assertThat(user.tenantId()).startsWith("t-");
        assertThat(user.email()).isEqualTo("alice@example.com");

        Integer tenants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE id = ?", Integer.class, user.tenantId());
        Integer users = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, "alice@example.com");
        assertThat(tenants).isEqualTo(1);
        assertThat(users).isEqualTo(1);
    }

    @Test
    void 密码以bcrypt哈希存储而非明文() {
        authService.register("bob@example.com", "password123");

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE email = ?", String.class, "bob@example.com");
        assertThat(hash).startsWith("$2");
        assertThat(hash).doesNotContain("password123");
    }

    @Test
    void 重复注册抛邮箱已存在() {
        authService.register("carol@example.com", "password123");

        assertThatThrownBy(() -> authService.register("carol@example.com", "another-pass-456"))
                .isInstanceOf(AuthService.EmailExistsException.class);
    }

    @Test
    void 登录成功返回用户与租户() {
        AuthService.AuthUser registered = authService.register("dave@example.com", "password123");

        AuthService.AuthUser logged = authService.login("dave@example.com", "password123");

        assertThat(logged.userId()).isEqualTo(registered.userId());
        assertThat(logged.tenantId()).isEqualTo(registered.tenantId());
    }

    @Test
    void 错误密码抛统一凭据异常() {
        authService.register("erin@example.com", "password123");

        assertThatThrownBy(() -> authService.login("erin@example.com", "wrong-password"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class)
                .hasMessageContaining("邮箱或密码错误");
    }

    @Test
    void 不存在的邮箱抛同一种异常不泄露存在性() {
        // 两类失败必须同类型同文案，前端无法区分"用户不存在"与"密码错误"
        assertThatThrownBy(() -> authService.login("ghost@example.com", "whatever123"))
                .isInstanceOf(AuthService.InvalidCredentialsException.class)
                .hasMessageContaining("邮箱或密码错误");
    }

    @Test
    void 非法邮箱格式与弱密码被拒绝() {
        assertThatThrownBy(() -> authService.register("not-an-email", "password123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authService.register("valid@example.com", "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
