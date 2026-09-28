package com.luyu.agent.moderation;

import java.util.Collections;
import java.util.Set;

/**
 * 审查结果（input-content-moderation spec）。
 * <p>
 * 只暴露「是否命中 + 命中类别」，屏蔽底层引擎（本地词库 / 未来外部 API）细节。
 * 命中类别用于审计日志，不含被审原文。
 *
 * @param blocked    是否命中受限内容（true = 审查未通过，需拒答）
 * @param categories 命中的类别集合；通过时为空集
 */
public record ModerationResult(boolean blocked, Set<ModerationCategory> categories) {

    private static final ModerationResult PASS = new ModerationResult(false, Collections.emptySet());

    /** 审查通过（未命中任何受限类别）。 */
    public static ModerationResult pass() {
        return PASS;
    }

    /** 审查未通过，携带命中类别。 */
    public static ModerationResult blocked(Set<ModerationCategory> categories) {
        return new ModerationResult(true,
                categories == null || categories.isEmpty()
                        ? Collections.emptySet()
                        : Collections.unmodifiableSet(categories));
    }
}
