package com.luyu.agent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户注册与登录 REST API。
 * <p>
 * 路径前缀：{@code /api/auth}
 * <ul>
 *   <li>{@code POST /register} → 201 + token 或 409</li>
 *   <li>{@code POST /login} → 200 + token 或 401</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 用户注册。
     *
     * @param body {@code { username, password }}
     * @return 201 + {@code { token, userId, username }} 或 409
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        try {
            UserService.AuthResult result = userService.register(username, password);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "token", result.token(),
                    "userId", result.userId(),
                    "username", result.username()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 用户登录。
     *
     * @param body {@code { username, password }}
     * @return 200 + {@code { token, userId, username }} 或 401
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        try {
            UserService.AuthResult result = userService.login(username, password);
            return ResponseEntity.ok(Map.of(
                    "token", result.token(),
                    "userId", result.userId(),
                    "username", result.username()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
    }
}
