package com.luyu.agent.moderation;

/**
 * 内容审查引擎抽象（design D2）。
 * <p>
 * 本轮唯一实现为本地敏感词库 {@link WordListModerationClient}；接口保留以便未来
 * 无侵入替换为外部内容安全 API 实现。
 */
public interface ContentModerationClient {

    /**
     * 审查一段文本，返回是否命中受限类别。
     *
     * @param text 待审文本（可为 null / 空，视为通过）
     * @return 审查结果，永不返回 null
     */
    ModerationResult check(String text);
}
