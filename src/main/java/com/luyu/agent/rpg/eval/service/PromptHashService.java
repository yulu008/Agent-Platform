package com.luyu.agent.rpg.eval.service;

import com.luyu.agent.rpg.eval.engine.PromptHashCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 版本指纹附着服务（content-eval tasks 2.2，design D8）。
 * <p>
 * GM assistant 事件由 spring-ai-session 的 SessionMemoryAdvisor 落库（AI_SESSION_
 * EVENT），本服务在回合尾包阶段（{@code RpgGameController#doTurnPersistedEvent}，
 * 该处已取到本轮 assistantEventId）把 {@link PromptHashCalculator} 的指纹回填到
 * 事件行——「落事件时附带」的实现载体：独立列 UPDATE，幂等可重跑。
 * <p>
 * 防御语义（全链不抛不阻断尾包）：
 * <ul>
 *   <li>指纹不可用（资源缺失）→ 跳过附着，事件行保持 NULL（与存量事件同语义，
 *       动态层评估按近似指纹降级路径处理）；</li>
 *   <li>UPDATE 影响 0 行（事件不存在/已回溯删除）→ warn 即过；</li>
 *   <li>SQL 异常（列未建/库故障）→ warn 即过，重启后 EvalSchemaInitializer
 *       幂等补列自愈。</li>
 * </ul>
 */
@Service
public class PromptHashService {

    private static final Logger log = LoggerFactory.getLogger(PromptHashService.class);

    private final PromptHashCalculator calculator;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public PromptHashService(PromptHashCalculator calculator, JdbcTemplate jdbcTemplate) {
        this.calculator = calculator;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 把当前提示词版本指纹附着到指定 assistant 事件行（prompt_hash 列）。
     * eventId 为 null 或指纹不可用时静默跳过；任何失败仅 warn 不抛。
     */
    public void attachToEvent(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        String hash = calculator.currentHash();
        if (hash == null) {
            return; // 资源缺失：防御跳过，事件行保持 NULL
        }
        try {
            int affected = jdbcTemplate.update(
                    "UPDATE AI_SESSION_EVENT SET prompt_hash = ? WHERE id = ?", hash, eventId);
            if (affected == 0) {
                log.warn("prompt_hash 附着未命中事件行（可能已回溯删除）: eventId={}", eventId);
            }
        } catch (Exception e) {
            log.warn("prompt_hash 附着失败（不影响尾包；重启后幂等补列自愈）: eventId={}", eventId, e);
        }
    }
}
