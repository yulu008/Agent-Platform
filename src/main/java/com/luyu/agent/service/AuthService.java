package com.luyu.agent.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 注册与登录服务（多租户身份基建）。
 * <p>
 * 控制表 tenant / app_user 落在主 H2 实例（schema-h2.sql 幂等建表）。
 * 注册 = 建 tenant + 建管理员用户（租户=个人用户 1:1），同一事务保证
 * 不产生无主的 tenant 行；密码 bcrypt 哈希，登录错误统一
 * {@link InvalidCredentialsException}，不泄露邮箱是否存在。
 */
@Service
public class AuthService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final BCryptPasswordEncoder passwordEncoder;

    @Autowired
    public AuthService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /** 认证成功的用户信息（供 Controller 签发 JWT） */
    public record AuthUser(String userId, String tenantId, String email) {
    }

    /** 注册：邮箱已被占用 */
    public static class EmailExistsException extends RuntimeException {
        public EmailExistsException(String email) {
            super("邮箱已被注册: " + email);
        }
    }

    /** 登录：凭据无效（不区分用户不存在与密码错误） */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("邮箱或密码错误");
        }
    }

    /**
     * 注册新租户与管理员用户。
     *
     * @return 创建成功的用户信息
     * @throws IllegalArgumentException 邮箱格式或密码强度不合规
     * @throws EmailExistsException     邮箱已被注册
     */
    public AuthUser register(String email, String password) {
        String normalized = normalizeEmail(email);
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("邮箱格式不合法");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }

        String tenantId = "t-" + UUID.randomUUID();
        String userId = "u-" + UUID.randomUUID();
        String hash = passwordEncoder.encode(password);

        transactionTemplate.executeWithoutResult(tx -> {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE email = ?", Integer.class, normalized);
            if (count != null && count > 0) {
                throw new EmailExistsException(normalized);
            }
            jdbcTemplate.update(
                    "INSERT INTO tenant (id, display_name, status) VALUES (?, ?, 'ACTIVE')",
                    tenantId, normalized);
            jdbcTemplate.update(
                    "INSERT INTO app_user (id, tenant_id, email, password_hash, user_role) VALUES (?, ?, ?, ?, 'admin')",
                    userId, tenantId, normalized, hash);
        });
        return new AuthUser(userId, tenantId, normalized);
    }

    /**
     * 登录校验。
     *
     * @return 用户信息（含租户 ID，供签发 JWT）
     * @throws InvalidCredentialsException 邮箱不存在或密码错误
     */
    public AuthUser login(String email, String password) {
        String normalized = normalizeEmail(email);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, password_hash FROM app_user WHERE email = ?", normalized);
        if (rows.isEmpty()) {
            // 与密码错误路径保持一致的计算量，避免时序侧信道探测邮箱存在性
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new InvalidCredentialsException();
        }
        Map<String, Object> row = rows.get(0);
        String hash = (String) row.get("PASSWORD_HASH");
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            throw new InvalidCredentialsException();
        }
        return new AuthUser((String) row.get("ID"), (String) row.get("TENANT_ID"), normalized);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** 无效 bcrypt 哈希：登录用户不存在时用它保持恒定计算路径 */
    private static final String DUMMY_HASH =
            "$2a$10$abcdefghijklmnopqrstuv012345678901234567890123456789012345678901";
}
