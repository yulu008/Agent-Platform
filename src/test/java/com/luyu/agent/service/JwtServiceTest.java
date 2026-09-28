package com.luyu.agent.service;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.luyu.agent.config.AuthProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} 单元测试：签发/验签回路、篡改拒绝、过期拒绝、非法标识拒绝。
 */
class JwtServiceTest {

    private JwtService service(Duration ttl) {
        AuthProperties properties = new AuthProperties();
        properties.setSecret("test-secret-key-0123456789abcdef0123456789");
        properties.setTtl(ttl);
        return new JwtService(properties);
    }

    @Test
    void 签发后验签返回正确声明() {
        JwtService jwt = service(Duration.ofHours(1));
        String token = jwt.issue("u-123", "t-456");

        JwtService.Claims claims = jwt.verify(token);

        assertThat(claims).isNotNull();
        assertThat(claims.userId()).isEqualTo("u-123");
        assertThat(claims.tenantId()).isEqualTo("t-456");
    }

    @Test
    void 篡改签名的token验签失败() {
        JwtService jwt = service(Duration.ofHours(1));
        String token = jwt.issue("u-123", "t-456");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + ("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

        assertThat(jwt.verify(tampered)).isNull();
    }

    @Test
    void 篡改payload的token验签失败() {
        JwtService jwt = service(Duration.ofHours(1));
        String token = jwt.issue("u-123", "t-456");
        // 换一个合法 token 的 payload（伪造 tid），签名不匹配
        String other = jwt.issue("u-123", "t-999");
        String[] original = token.split("\\.");
        String[] forged = other.split("\\.");

        assertThat(jwt.verify(original[0] + "." + forged[1] + "." + original[2])).isNull();
    }

    @Test
    void 过期token验签失败() {
        // ttl=0 → exp == now，立即过期
        JwtService jwt = service(Duration.ZERO);
        String token = jwt.issue("u-123", "t-456");

        assertThat(jwt.verify(token)).isNull();
    }

    @Test
    void 非法输入拒绝() {
        JwtService jwt = service(Duration.ofHours(1));

        assertThatThrownBy(() -> jwt.issue("bad id with space", "t-456"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwt.issue("u-123", "../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 格式残缺的token验签失败() {
        JwtService jwt = service(Duration.ofHours(1));

        assertThat(jwt.verify(null)).isNull();
        assertThat(jwt.verify("")).isNull();
        assertThat(jwt.verify("only.one")).isNull();
        assertThat(jwt.verify("not-a-jwt")).isNull();
    }

    @Test
    void 不同密钥签发的token互相拒绝() {
        AuthProperties a = new AuthProperties();
        a.setSecret("secret-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        AuthProperties b = new AuthProperties();
        b.setSecret("secret-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        String token = new JwtService(a).issue("u-123", "t-456");

        assertThat(new JwtService(b).verify(token)).isNull();
    }
}
