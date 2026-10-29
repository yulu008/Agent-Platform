package com.luyu.agent.tenancy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存量会话 kind 回填（separate-rpg-chat-sessions design D5）。
 * <p>
 * kind 打标只对打标代码上线后的新开局生效；升级前的老数据（无 kind 的 RPG 会话、
 * 删档残留的幽灵空壳）由本组件启动时治理，让主会话列表过滤（kind != rpg）对存量
 * 同样成立。判定特征（已代码验证）：chat 壳创建必写 {@code metadata.title}
 * （POST /chat/sessions / SessionTitleGenerator），RPG 壳从未写过 title。
 * <ul>
 *   <li>已带 {@code kind="rpg"} → 跳过（幂等，重跑零变更）</li>
 *   <li>有 title → chat 会话，不动</li>
 *   <li>无 title 且被 rpg_game_state.session_id 引用 → merge 补写 {@code kind="rpg"}</li>
 *   <li>无 title、不被引用、事件数为 0 → 删档残留幽灵壳，删除（对齐删档删壳语义）</li>
 * </ul>
 * 三条件叠加限定误删面趋零：开局未走首回合的 RPG 会话被 GameState 引用
 * （startGame 先建 GameState），不会误入幽灵分类。
 * <p>
 * 与 {@link AiSessionTenantBackfill} 同款模式：原生 SQL 直操作 AI_SESSION
 * （启动上下文无 TenantContext，不走 repository 租户过滤查询）；监听
 * {@link ApplicationReadyEvent} 保证在 AI_SESSION 建表（spring-ai-session
 * initialize-schema）与 RpgSchemaInitializer 建表之后执行；失败仅记
 * error 不阻断启动，重启自动重试。
 * <p>
 * 引用集读取涉及 rpg_game_state（TenantGuardedJdbcTemplate 守卫名单）：
 * 启动期全租户治理与 DDL 迁移同性质（不属于任何租户上下文），自建裸
 * {@link JdbcTemplate} 直连绕过守卫——对齐 RpgSchemaInitializer 的
 * ResourceDatabasePopulator 直连豁免通道。
 */
@Component
public class AiSessionKindBackfill {

    private static final Logger log = LoggerFactory.getLogger(AiSessionKindBackfill.class);

    private final JdbcTemplate jdbcTemplate;
    // jackson2 ObjectMapper 无 Spring bean（Boot 4 上下文为 Jackson 3）：
    // 对齐 WorkshopService 同款字段直 new 惯例，仅启动单线程使用
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiSessionKindBackfill(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void backfill() {
        try {
            // RPG 存档引用集合（全租户，tenant_guard_whitelist: 启动期治理豁免——与 DDL 迁移
            // 同性质的全租户只读，自建裸模板直连，见类注释豁免通道）：被 GameState 引用 = 活跃/待补标 RPG 会话
            Set<String> rpgSessionIds = new HashSet<>(jdbcTemplate.queryForList(
                    "SELECT DISTINCT session_id FROM rpg_game_state "
                            + "WHERE session_id IS NOT NULL AND TRIM(session_id) <> ''",
                    String.class));

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, metadata FROM AI_SESSION");

            int tagged = 0;
            int ghostRemoved = 0;
            int chatUntouched = 0;
            int alreadyTagged = 0;
            int invalidMeta = 0;
            int defensiveUntouched = 0;
            for (Map<String, Object> row : rows) {
                String id = asText(row.get("id"));
                if (id == null || id.isBlank()) {
                    continue;
                }
                String metaText = asText(row.get("metadata"));
                ObjectNode meta = parseMetadata(metaText);
                if (meta == null) {
                    // 非对象/非法 JSON：不猜、不动（理论不存在，留日志人工排查）
                    invalidMeta++;
                    continue;
                }
                if ("rpg".equals(meta.path("kind").asText(null))) {
                    alreadyTagged++;
                    continue;
                }
                if (meta.hasNonNull("title")) {
                    // chat 会话：壳创建必写 title
                    chatUntouched++;
                    continue;
                }
                if (rpgSessionIds.contains(id)) {
                    // 老 RPG 会话：merge 补 kind（保留既有键）
                    meta.put("kind", "rpg");
                    jdbcTemplate.update("UPDATE AI_SESSION SET metadata = ? WHERE id = ?",
                            objectMapper.writeValueAsString(meta), id);
                    tagged++;
                } else if (eventCount(id) == 0) {
                    // 幽灵壳：无 title + 不被引用 + 无事件 = 删档残留
                    jdbcTemplate.update("DELETE FROM AI_SESSION WHERE id = ?", id);
                    ghostRemoved++;
                } else {
                    // 无 title 有事件且不被引用：理论不存在（RPG 局在引用集合内、
                    // chat 会话有 title），防御性不动，交由日志排查
                    defensiveUntouched++;
                }
            }
            log.info("存量会话 kind 回填完成: 总数={}, 补标={}, 幽灵壳删除={}, chat不动={}, "
                            + "已打标跳过={}, 非法metadata={}, 防御不动={}",
                    rows.size(), tagged, ghostRemoved, chatUntouched,
                    alreadyTagged, invalidMeta, defensiveUntouched);
        } catch (Exception e) {
            log.error("AI_SESSION 存量 kind 回填失败（不影响启动，可重启重试）", e);
        }
    }

    /**
     * 该会话的现存事件数（幽灵壳判定的第三条件：删档时事件已被清空）。
     */
    private int eventCount(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_SESSION_EVENT WHERE session_id = ?",
                Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    /**
     * metadata 列文本解析为可写对象节点：null/空白归一化为空对象；非 JSON 对象
     * （数组/标量/非法文本）返回 null（调用方按非法跳过，绝不猜写）。
     */
    private ObjectNode parseMetadata(String text) {
        if (text == null || text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            return node instanceof ObjectNode obj ? obj : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 列值归一化为文本（H2 CLOB 列经 queryForList 可能返回 Clob 而非 String）。
     */
    private static String asText(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof java.sql.Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (Exception e) {
                return null;
            }
        }
        return raw.toString();
    }
}
