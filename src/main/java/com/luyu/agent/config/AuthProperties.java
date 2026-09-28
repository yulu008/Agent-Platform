package com.luyu.agent.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证配置属性，绑定 application.yml 中 auth.jwt 结构。
 * <p>
 * HS256 密钥生产环境必须经环境变量 {@code AUTH_JWT_SECRET} 覆盖，
 * 本地默认值仅供开发期使用。
 */
@ConfigurationProperties(prefix = "auth.jwt")
public class AuthProperties {

    /** HS256 签名密钥（至少 32 字节） */
    private String secret = "dev-only-secret-change-me-in-production-0123456789abcdef";

    /** token 有效期 */
    private Duration ttl = Duration.ofDays(7);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }
}
