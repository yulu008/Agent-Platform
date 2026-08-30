package com.luyu.agent.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话事件查询接口（调试用）
 *
 * 直接查询 H2 数据库中的会话事件表，
 * 可查看压缩后的归档记录和当前活跃事件。
 */
@RestController
@RequestMapping("/api/debug")
public class SessionDebugController {

    private final JdbcTemplate jdbcTemplate;

    public SessionDebugController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 列出所有表名（帮助了解数据库结构）
     */
    @GetMapping("/tables")
    public List<Map<String, Object>> listTables() {
        return jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME");
    }

    /**
     * 列出所有会话及其事件数量
     */
    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions() {
        return jdbcTemplate.queryForList(
                "SELECT s.id, s.user_id, s.created_at, s.metadata, " +
                "(SELECT COUNT(*) FROM spring_ai_session_events e WHERE e.session_id = s.id) AS event_count " +
                "FROM spring_ai_sessions s ORDER BY s.created_at DESC");
    }

    /**
     * 查看指定会话的全部事件（含归档）
     */
    @GetMapping("/sessions/{sessionId}/events")
    public List<Map<String, Object>> listSessionEvents(@PathVariable String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT id, session_id, event_type, message_role, message_content, " +
                "synthetic, created_at FROM spring_ai_session_events " +
                "WHERE session_id = ? ORDER BY created_at ASC, id ASC",
                sessionId);
    }

    /**
     * 查看指定会话的归档事件（synthetic = true 的摘要记录）
     */
    @GetMapping("/sessions/{sessionId}/archived")
    public List<Map<String, Object>> listArchivedEvents(@PathVariable String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT id, session_id, event_type, message_role, message_content, " +
                "synthetic, created_at FROM spring_ai_session_events " +
                "WHERE session_id = ? AND synthetic = true ORDER BY created_at ASC",
                sessionId);
    }
}
