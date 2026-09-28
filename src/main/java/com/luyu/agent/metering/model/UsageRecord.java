package com.luyu.agent.metering.model;

/**
 * 单次 LLM 调用的归一化用量（计量切面产出，计费与落库的输入）。
 *
 * @param tenantId         归属租户；缺失时为占位 {@code <missing>}（可观测的漏传信号，非静默兜底）
 * @param model            实际使用的模型名（优先取响应 metadata，用于判档）
 * @param callType         调用类型：chat / rpg / workshop / compaction / state_delta / title / subagent / unknown
 * @param sessionId        会话 ID（可空，内部调用可能无会话）
 * @param promptTokens     输入 token 总数（含缓存命中部分）
 * @param completionTokens 输出 token 数
 * @param cachedTokens     缓存命中输入 token；{@code null} 表示该次不可得（计费降级为不区分缓存）
 */
public record UsageRecord(
        String tenantId,
        String model,
        String callType,
        String sessionId,
        long promptTokens,
        long completionTokens,
        Long cachedTokens) {

    /** 缓存 token 是否不可得（需降级计费并在明细标记）。 */
    public boolean cacheUnavailable() {
        return cachedTokens == null;
    }

    /** 缓存命中 token，不可得时按 0 计（配合 cacheUnavailable 标记降级）。 */
    public long cachedOrZero() {
        return cachedTokens == null ? 0L : cachedTokens;
    }
}
