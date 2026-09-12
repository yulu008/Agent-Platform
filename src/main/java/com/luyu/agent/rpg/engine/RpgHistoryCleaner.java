package com.luyu.agent.rpg.engine;

import org.springframework.stereotype.Component;

/**
 * RPG 会话历史 read-time 清洗器。
 * <p>
 * 用于 {@code GET /rpg/game/history}：把 session 中"存进去的"原始事件文本，还原成
 * "想显示的"干净对话文本。清洗只发生在读取返回时，<b>不改写 session 存储</b>，
 * 因此对模型记忆（SessionMemoryAdvisor 每轮回喂的上下文）零影响。
 * <p>
 * 清洗规则与 {@code rpg/gm-incremental-prompt.md} 的固定模板标记强绑定：
 * <ul>
 *   <li>user 消息：若含固定标记 {@code ## 玩家行动}，抽取该标记与下一个 {@code ##} 之间的文本；
 *       否则（首轮未包裹）原样返回。</li>
 *   <li>assistant 消息：移除 {@code <state_delta>...</state_delta>} 块，仅保留叙述正文
 *       （复用 {@link StateDeltaSanitizer#extractNarration}）。</li>
 * </ul>
 */
@Component
public class RpgHistoryCleaner {

    /** gm-incremental-prompt.md 中写死的玩家行动段标记 */
    private static final String PLAYER_ACTION_MARKER = "## 玩家行动";

    /** 下一个二级标题前缀，用于界定玩家行动段的结束位置 */
    private static final String NEXT_SECTION_PREFIX = "\n## ";

    private final StateDeltaSanitizer stateDeltaSanitizer;

    public RpgHistoryCleaner(StateDeltaSanitizer stateDeltaSanitizer) {
        this.stateDeltaSanitizer = stateDeltaSanitizer;
    }

    /**
     * 清洗 user 消息：从包裹 prompt 中抽取玩家行动原文。
     *
     * @param raw session 中存储的原始 user 文本（后续轮为包裹 prompt，首轮为原始行动）
     * @return 干净的玩家行动文本；无标记时原样返回（trim）
     */
    public String cleanUserMessage(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int idx = raw.indexOf(PLAYER_ACTION_MARKER);
        if (idx < 0) {
            // 首轮未包裹，原样返回
            return raw.trim();
        }
        int start = idx + PLAYER_ACTION_MARKER.length();
        int end = raw.indexOf(NEXT_SECTION_PREFIX, start);
        String action = (end >= 0) ? raw.substring(start, end) : raw.substring(start);
        return action.trim();
    }

    /**
     * 清洗 assistant 消息：移除 state_delta 块，仅保留叙述正文。
     *
     * @param raw session 中存储的原始 assistant 文本
     * @return 纯叙述正文
     */
    public String cleanAssistantMessage(String raw) {
        return stateDeltaSanitizer.extractNarration(raw);
    }
}
