package com.luyu.agent.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.stereotype.Component;

/**
 * 会话租户归属守卫（第 5 层，session-isolation spec）。
 * <p>
 * AI_SESSION 复用既有 {@code user_id} 列装租户 ID（design D4）：会话创建时写入
 * {@link TenantContext#requireTenantId()}，归属校验统一收敛到本组件，Controller
 * 各端点不再各自手写 findById 比对。
 * <p>
 * 语义（跨租户与"不存在"不区分，统一表现为会话不存在，杜绝资源枚举）：
 * <ul>
 *   <li>{@link #isForeign(String)}：会话<b>存在且</b>归属他租户 → true。
 *       供追加式流式端点（发消息 / 开局 / 回合）使用——会话不存在是合法态
 *       （SessionMemoryAdvisor 首轮自动建会话，归属当前租户），不得拦截。</li>
 *   <li>{@link #isOwned(String)}：会话存在且归属当前租户 → true。
 *       供读 / 删 / 压缩 / 回溯等操作语义端点按需选用。</li>
 * </ul>
 */
@Component
public class SessionTenantGuard {

    private static final Logger log = LoggerFactory.getLogger(SessionTenantGuard.class);

    private final SessionRepository sessionRepository;

    public SessionTenantGuard(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 会话是否存在且归属当前请求租户。
     * <p>
     * 不存在 / 归属他租户 / sessionId 为空均为 false。
     */
    public boolean isOwned(String sessionId) {
        Session session = find(sessionId);
        return session != null && currentTenantId().equals(session.userId());
    }

    /**
     * 会话是否存在且归属<b>其他</b>租户（跨租户访问必须拒绝的唯一真源）。
     * <p>
     * 会话不存在返回 false——新会话由 advisor 首轮自动创建，属合法路径；
     * 查询失败同样返回 false 并告警，由后续 sessionService 调用自然报错，
     * 守卫本身不做静默放行决策之外的兜底。
     */
    public boolean isForeign(String sessionId) {
        Session session = find(sessionId);
        return session != null && !currentTenantId().equals(session.userId());
    }

    private Session find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return sessionRepository.findById(sessionId);
        } catch (Exception e) {
            log.warn("会话归属查询失败，按不存在处理: sessionId={}", sessionId, e);
            return null;
        }
    }

    private String currentTenantId() {
        return TenantContext.requireTenantId();
    }
}
