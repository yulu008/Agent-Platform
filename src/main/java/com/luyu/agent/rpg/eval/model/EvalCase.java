package com.luyu.agent.rpg.eval.model;

import java.util.List;
import java.util.Map;

/**
 * 评估单元数据契约（content-eval tasks 3.1/3.2，design D6/D7/D9）。
 * <p>
 * 一个 EvalCase = 一个被评人物的一次评估输入：
 * <ul>
 *   <li><b>静态层</b>（layer=static）：设定包即被评产物本身（档案自洽评估），
 *       窗口/状态/指纹字段为 null；</li>
 *   <li><b>动态层</b>（layer=dynamic）：设定包 + 该存档的叙述窗（早/晚滑窗对）
 *       + npc_states/playerStates 快照 + 版本指纹——评「表现 vs 设定」的漂移。</li>
 * </ul>
 * 三源归一（sourceType）：命定人物档案（destined，subject=figureKey 稳定跨物化键）、
 * NPC 人物卡（npcCard，subject=卡 id）、冥星武将名录条目（starLore，subject=条目 id）。
 * <p>
 * {@link #aliases} 指称词表 = displayName + aliases 拆分（含汉字名/称号），
 * 供动态层筛出场轮与 L1 汉字指称检查复用。
 */
public record EvalCase(
        /** static / dynamic（对齐 EvalRun.LAYER_*） */
        String layer,
        /** destined / npcCard / starLore */
        String sourceType,
        /** figureKey / NPC 卡 id / 名录条目 id */
        String subject,
        /** 展示名（列表免回查） */
        String subjectLabel,
        /** JSON: 评估单元（静态=subject+worldId；动态=sessionId+gameStateId+窗范围） */
        String unitJson,
        /** JSON: 设定包（{@link SettingPack} 序列化） */
        String settingPackJson,
        /** 指称词表（displayName 头位 + aliases 拆分），静态/动态共用 */
        List<String> aliases,
        /** 动态层早窗（最早 N 出场轮；静态层 null） */
        List<TurnText> earlyWindow,
        /** 动态层晚窗（最近 N 出场轮；静态层 null） */
        List<TurnText> lateWindow,
        /** 动态层 npc_states 快照 JSON（静态层 null） */
        String npcStatesJson,
        /** 动态层 playerStates 快照 JSON（静态层 null） */
        String playerStatesJson,
        /**
         * 动态层版本指纹：prompt_hash（真实）或 "approx:&lt;model&gt;"（存量降级，
         * 报告标注近似）；静态层 null。
         */
        String targetFingerprint,
        /**
         * 动态层逐轮状态增量轨迹（state_delta 数值扁平化，断崖检测输入）；
         * 静态层无会话 → null。
         */
        List<TurnState> stateTrajectory) {

    /** sourceType：命定人物档案（rpg_destined_figure，真源 preset-adventure.md） */
    public static final String SOURCE_DESTINED = "destined";
    /** sourceType：NPC 人物卡（rpg_character_card type=npc，工坊手作） */
    public static final String SOURCE_NPC_CARD = "npcCard";
    /** sourceType：冥星武将名录条目（rpg_star_incarnation 世界模板行） */
    public static final String SOURCE_STAR_LORE = "starLore";

    /**
     * 叙述窗行：一轮的 user 行动或 assistant 叙述（state_delta 已剥离）。
     * eventId 仅 assistant 行携带（prompt_hash 对齐键；渲染/判分时忽略）。
     */
    public record TurnText(int turn, String role, String text, String eventId) {
        public static final String ROLE_USER = "user";
        public static final String ROLE_ASSISTANT = "assistant";
    }

    /**
     * 一轮的状态增量（state_delta 数值扁平化，design D5 断崖检测输入）：
     * key 形如 {@code npc:<npcId>:<motive>}（motives 增量）或
     * {@code player:<field>}（money/cosmo 等变化量）。
     */
    public record TurnState(int turn, Map<String, Double> deltas) {
    }

    /**
     * 设定包：三源归一的档案载体（judge prompt 渲染与 L1 硬事实核对的公共输入）。
     * <ul>
     *   <li>destined：structured = faction/constellation/starRank/eraNote；三件套齐；</li>
     *   <li>npcCard：structured = motivation/speechStyle/knowledge；三件套齐（结构不同源）；</li>
     *   <li>starLore：lore = 条目叙事正文（身份三要素融于叙事）；其余 null。</li>
     * </ul>
     */
    public record SettingPack(
            /** JSON: 结构化字段（各源字段集不同，见类注释） */
            String structuredJson,
            String identity,
            String personality,
            String background,
            /** starLore 条目叙事正文（destined/npcCard 为 null） */
            String lore,
            List<String> aliases) {
    }
}
