package com.luyu.agent.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 存量会话归属回填（tasks 5.4 / session-isolation spec「存量会话归入保留租户」）。
 * <p>
 * 升级前既有会话的 {@code AI_SESSION.user_id} 为写死的 {@code default-user}；
 * 多租户化后租户 ID 来自 JWT（保留租户为 {@link TenantPaths#DEFAULT_TENANT}），
 * 回填把旧值统一改写为 {@code default}，default 租户登录后既有会话可见且可继续对话。
 * <p>
 * 幂等：{@code WHERE user_id = 'default-user'} 天然可重跑；监听
 * {@link ApplicationReadyEvent} 保证在 AI_SESSION 建表（spring-ai-session
 * initialize-schema）与全部 bean 就绪之后执行；失败仅记 error 不阻断启动
 * （H2 单文件库可备份重试，与 schema-h2.sql 幂等段同一惯例）。
 */
@Component
public class AiSessionTenantBackfill {

    private static final Logger log = LoggerFactory.getLogger(AiSessionTenantBackfill.class);

    /** 升级前的写死用户标识（SessionController / SessionMemoryAdvisor 的旧 defaultUserId） */
    static final String LEGACY_USER_ID = "default-user";

    private final JdbcTemplate jdbcTemplate;

    public AiSessionTenantBackfill(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE AI_SESSION SET user_id = ? WHERE user_id = ?",
                    TenantPaths.DEFAULT_TENANT, LEGACY_USER_ID);
            if (updated > 0) {
                log.info("存量会话归属回填完成: {} 条会话归入 '{}' 租户", updated, TenantPaths.DEFAULT_TENANT);
            } else {
                log.debug("无待回填的存量会话（user_id='{}' 不存在或已回填）", LEGACY_USER_ID);
            }
        } catch (Exception e) {
            log.error("AI_SESSION 存量会话回填失败（不影响启动，可重启重试）", e);
        }
    }
}
