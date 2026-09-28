package com.luyu.agent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT 签发与验证工具。
 * <p>
 * HS256 对称签名，载荷 {@code { sub: userId, username, exp }}，
 * 有效期由 {@code jwt.expiration-hours} 配置（默认 24 小时）。
 * 密钥从 {@code jwt.secret} 读取，生产环境必须通过环境变量覆盖。
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final SecretKey key;
    private final Duration expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration-hours:24}") int expirationHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofHours(expirationHours);
    }

    /**
     * 签发 JWT。
     *
     * @param userId   用户 ID（作为 subject）
     * @param username 用户名（作为自定义 claim）
     * @return JWT 字符串
     */
    public String generateToken(String userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration.toMillis());
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 验证并解析 JWT。
     *
     * @param token JWT 字符串（不含 "Bearer " 前缀）
     * @return 解析后的 Claims，验证失败返回 {@code null}
     */
    public Claims verify(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.debug("JWT 验证失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 Claims 中提取用户 ID。
     *
     * @param claims 已验证的 JWT 载荷
     * @return 用户 ID（即 subject）
     */
    public String getUserId(Claims claims) {
        return claims.getSubject();
    }
}
