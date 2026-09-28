package com.luyu.agent.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户数据访问层。
 * <p>
 * 基于 {@link JdbcTemplate} 操作 H2 {@code users} 表。
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<User> ROW_MAPPER = (rs, rowNum) ->
            new User(rs.getString("id"), rs.getString("username"),
                    rs.getString("password_hash"));

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 用户实体，不存在时返回 {@link Optional#empty()}
     */
    public Optional<User> findByUsername(String username) {
        try {
            User user = jdbcTemplate.queryForObject(
                    "SELECT id, username, password_hash FROM users WHERE username = ?",
                    ROW_MAPPER, username);
            return Optional.ofNullable(user);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 检查用户名是否已存在。
     *
     * @param username 用户名
     * @return 存在返回 true
     */
    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Integer.class, username);
        return count != null && count > 0;
    }

    /**
     * 保存用户。
     *
     * @param user 用户实体
     */
    public void save(User user) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)",
                user.getId(), user.getUsername(), user.getPasswordHash());
    }
}
