package com.luyu.agent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 用户注册与登录服务。
 * <p>
 * 注册：BCrypt 哈希密码 + UUID 用户 ID，存入 H2 users 表。
 * 登录：BCrypt 匹配验证，成功则签发 JWT。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 注册新用户。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return JWT token 与用户信息
     * @throws IllegalArgumentException 用户名已存在
     */
    public AuthResult register(String username, String password) {
        if (username == null || username.trim().length() < 2) {
            throw new IllegalArgumentException("用户名至少 2 个字符");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 个字符");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已存在");
        }

        String userId = UUID.randomUUID().toString();
        String hash = passwordEncoder.encode(password);
        userRepository.save(new User(userId, username, hash));

        String token = jwtUtil.generateToken(userId, username);
        log.info("用户注册成功: username={}, userId={}", username, userId);
        return new AuthResult(token, userId, username);
    }

    /**
     * 用户登录。
     *
     * @param username 用户名
     * @param password 明文密码
     * @return JWT token 与用户信息
     * @throws IllegalArgumentException 用户名不存在或密码错误
     */
    public AuthResult login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        log.info("用户登录成功: username={}, userId={}", username, user.getId());
        return new AuthResult(token, user.getId(), user.getUsername());
    }

    /**
     * 认证结果。
     *
     * @param token   JWT token
     * @param userId  用户 ID
     * @param username 用户名
     */
    public record AuthResult(String token, String userId, String username) {
    }
}
