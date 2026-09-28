package com.luyu.agent.controller;

import com.luyu.agent.tenancy.SessionTenantGuard;
import com.luyu.agent.tenancy.TenantContext;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 会话管理端点
 * 提供会话列表查询、新建会话、清空会话消息的 REST API
 * 
 * 数据源：Spring AI Session API（SessionService + SessionRepository）
 * 
 * 会话归属跟随请求租户（第 5 层，session-isolation spec）：AI_SESSION.user_id 装
 * {@link TenantContext} 租户 ID——列表只含本租户，新建归属本租户，
 * 删除/清空跨租户会话统一 404 且零副作用。
 */
@RestController
public class SessionController {

    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private final SessionTenantGuard sessionTenantGuard;

    public SessionController(SessionService sessionService,
                             SessionRepository sessionRepository,
                             SessionTenantGuard sessionTenantGuard) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.sessionTenantGuard = sessionTenantGuard;
    }

    /**
     * 获取所有会话列表，按创建时间降序
     *
     * @return 会话摘要列表 [{id, title, updatedAt}]
     */
    @GetMapping("/chat/sessions")
    public List<Map<String, Object>> listSessions() {
        return sessionRepository.findByUserId(TenantContext.requireTenantId())
                .stream()
                .sorted(Comparator.comparing(Session::createdAt).reversed())
                .map(s -> {
                    String title = s.metadata() != null
                            ? (String) s.metadata().getOrDefault("title", "新对话")
                            : "新对话";
                    return Map.<String, Object>of(
                            "id", s.id(),
                            "title", title,
                            "updatedAt", s.createdAt().toString()
                    );
                })
                .toList();
    }

    /**
     * 创建新会话
     *
     * @return 新会话的 id 和 title
     */
    @PostMapping("/chat/sessions")
    public Map<String, String> createSession() {
        Session session = sessionService.create(
                CreateSessionRequest.builder()
                        .userId(TenantContext.requireTenantId())
                        .metadata("title", "新对话")
                        .build()
        );
        return Map.of("id", session.id(), "title", "新对话");
    }

    /**
     * 清空指定会话的所有消息，保留会话壳
     * 
     * 注意：Session API 采用事件溯源，清空消息 = 删除会话后重建
     *
     * @param sessionId 会话 ID
     * @return 204 No Content
     */
    @DeleteMapping("/chat/messages")
    public ResponseEntity<Void> clearMessages(@RequestParam String sessionId) {
        // 跨租户会话表现为不存在（session-isolation spec）：404 且零副作用
        if (sessionTenantGuard.isForeign(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        // Session API 不支持单独清空事件，删除整个会话
        // 前端会在清空后重新创建会话
        sessionService.delete(sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除指定会话及其所有消息
     *
     * @param sessionId 会话 ID
     * @return 204 No Content
     */
    @DeleteMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        // 跨租户会话表现为不存在（session-isolation spec）：404 且零副作用
        if (sessionTenantGuard.isForeign(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        sessionService.delete(sessionId);
        return ResponseEntity.noContent().build();
    }
}
