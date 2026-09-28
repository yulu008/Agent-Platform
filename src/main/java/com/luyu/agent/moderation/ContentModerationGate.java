package com.luyu.agent.moderation;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.luyu.agent.tenancy.TenantContext;

/**
 * 内容审查闸门（design D1/D3/D5，spec「命中处置」「词库加载与启用开关」）。
 * <p>
 * 三条用户输入线（主聊天 / RPG / 工坊）在调用模型前统一经此闸门：
 * <ul>
 *   <li>{@code moderation.enabled=false}：直接放行（整体旁路，支持一键回滚）</li>
 *   <li>命中：返回 blocked 结果并记审计日志（仅命中类别，<b>不落被审原文</b>，与「不落库」铁律一致）</li>
 *   <li>引擎异常：本地词库匹配不应抛异常，兜底按放行处理并告警（等价于无命中）</li>
 * </ul>
 * 拒答话术由 {@link #refusalMessage()} 统一提供，供各端点复用。
 */
@Component
public class ContentModerationGate {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationGate.class);

    private final ModerationProperties properties;
    private final ContentModerationClient client;

    public ContentModerationGate(ModerationProperties properties, ContentModerationClient client) {
        this.properties = properties;
        this.client = client;
    }

    /**
     * 审查一段用户输入。
     *
     * @param text 用户输入原文（可为 null / 空白，视为通过）
     * @return 审查结果，永不返回 null
     */
    public ModerationResult check(String text) {
        if (!properties.isEnabled()) {
            return ModerationResult.pass();
        }
        if (text == null || text.isBlank()) {
            return ModerationResult.pass();
        }
        ModerationResult result;
        try {
            result = client.check(text);
        } catch (Exception e) {
            // 本地词库匹配理论上不抛异常；兜底放行，避免因审查组件故障阻断全部对话
            log.error("内容审查引擎异常，按放行处理: tenant={}", safeTenant(), e);
            return ModerationResult.pass();
        }
        if (result.blocked()) {
            log.warn("输入命中内容审查，已拒答: tenant={}, categories={}",
                    safeTenant(), categoryLabels(result));
        }
        return result;
    }

    /** 命中拒答时返回给用户的统一话术。 */
    public String refusalMessage() {
        return properties.getRefusalMessage();
    }

    /** 审查是否启用（供端点/测试探测）。 */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private String safeTenant() {
        String tenantId = TenantContext.getTenantId();
        return tenantId == null ? "unknown" : tenantId;
    }

    private String categoryLabels(ModerationResult result) {
        return result.categories().stream()
                .map(ModerationCategory::label)
                .collect(Collectors.joining(","));
    }
}
