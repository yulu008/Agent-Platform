package com.luyu.agent.auth;

/**
 * 用户实体。
 * <p>
 * 对应 H2 {@code users} 表，密码以 BCrypt 哈希存储。
 */
public class User {

    private final String id;
    private final String username;
    private final String passwordHash;

    public User(String id, String username, String passwordHash) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }
}
