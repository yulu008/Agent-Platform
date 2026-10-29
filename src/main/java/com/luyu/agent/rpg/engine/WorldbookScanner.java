package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.service.LorebookSetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用世界书关键词扫描器（add-rpg-worldbook design D4，SillyTavern lorebook 风格）。
 * <p>
 * 每个后续回合在组装增量 prompt 前执行（仅非首轮，调用方 {@code GameLoopService}）：
 * <ol>
 *   <li>扫描文本 = 本轮玩家行动 + GM 上一轮叙事（仅扫玩家行动会因 GM 先提到名词而漏激活）；</li>
 *   <li>匹配集 = 每条启用条目的 {@code title + keywords}（子串匹配，中文无需分词）；</li>
 *   <li>返回<b>本轮命中</b>的启用条目——瞬时注入：仅命中轮返回，下一轮不再命中即不返回。</li>
 * </ol>
 * 与冥星武将录（{@link StarLorebookScanner}，sticky 双表）的关键区别：本扫描器<b>无状态</b>，
 * 不写任何 runtime 行，故删档/回溯无需清理。任何异常降级为空列表（世界书是增强注入，
 * 绝不阻断回合主流程）；{@code rpg.worldbook.enabled=false} 时直接返回空。
 */
@Component
public class WorldbookScanner {

    private static final Logger log = LoggerFactory.getLogger(WorldbookScanner.class);

    private final RpgGameStateRepository stateRepo;
    private final SessionService sessionService;
    private final StateDeltaSanitizer stateDeltaSanitizer;
    private final RpgWorldbookProperties worldbookProperties;
    private final LorebookSetService lorebookSetService;

    public WorldbookScanner(RpgGameStateRepository stateRepo,
                            SessionService sessionService,
                            StateDeltaSanitizer stateDeltaSanitizer,
                            RpgWorldbookProperties worldbookProperties,
                            LorebookSetService lorebookSetService) {
        this.stateRepo = stateRepo;
        this.sessionService = sessionService;
        this.stateDeltaSanitizer = stateDeltaSanitizer;
        this.worldbookProperties = worldbookProperties;
        this.lorebookSetService = lorebookSetService;
    }

    /**
     * 扫描并返回本轮命中的世界书条目（瞬时，不持久化激活状态）。
     *
     * @param gameStateId  存档 ID
     * @param playerAction 本轮玩家行动文本（扫描文本之一）
     * @return 本轮命中的启用条目（按仓储排序）；未命中/无条目/开关关闭/异常时为空列表
     */
    public List<WorldbookEntry> scan(String gameStateId, String playerAction) {
        if (!worldbookProperties.isEnabled()) {
            return List.of();
        }
        try {
            GameState gs = stateRepo.findById(gameStateId);
            if (gs == null) {
                return List.of();
            }
            // 读取源（add-rpg-lorebook-sets D2）：存档 set 条目优先，老存档回退模板行
            LorebookSetService.ResolvedView view = lorebookSetService.resolve(gameStateId);
            List<WorldbookEntry> entries = view == null ? List.of() : view.entries();
            if (entries.isEmpty()) {
                return List.of();
            }
            // 扫描文本：玩家行动 + GM 上一轮叙事
            String scanText = (playerAction == null ? "" : playerAction) + "\n" + lastGmNarration(gs);
            if (scanText.isBlank()) {
                return List.of();
            }
            List<WorldbookEntry> hits = new ArrayList<>();
            for (WorldbookEntry entry : entries) {
                // 停用条目跳过（enabled=null 视为启用，与建表默认 TRUE 一致）
                if (Boolean.FALSE.equals(entry.getEnabled())) {
                    continue;
                }
                if (matchesAnyKeyword(scanText, entry)) {
                    hits.add(entry);
                }
            }
            if (!hits.isEmpty()) {
                log.info("世界书命中: gameStateId={}, 命中 {} 条", gameStateId, hits.size());
            }
            return hits;
        } catch (Exception e) {
            log.warn("世界书扫描失败，本轮降级为不注入: gameStateId={}", gameStateId, e);
            return List.of();
        }
    }

    /**
     * GM 上一轮叙事（清洗 state_delta 后的纯叙述正文）。
     * <p>
     * 从存档绑定的 session 倒序找最近的非合成 assistant 消息（跳过工具调用事件）；
     * 找不到（如换 session 读档后尚未推进）返回空串，扫描仅覆盖玩家行动。
     */
    private String lastGmNarration(GameState gs) {
        String sessionId = gs.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        List<SessionEvent> events = sessionService.getEvents(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            SessionEvent event = events.get(i);
            if (event.isSynthetic()) {
                continue;
            }
            if (event.getMessage() instanceof AssistantMessage am && !am.hasToolCalls()) {
                return stateDeltaSanitizer.extractNarration(am.getText());
            }
        }
        return "";
    }

    /** 关键词匹配：标题 / 逗号分隔关键词项，任一子串命中即算命中 */
    private static boolean matchesAnyKeyword(String scanText, WorldbookEntry entry) {
        if (contains(scanText, entry.getTitle())) {
            return true;
        }
        String keywords = entry.getKeywords();
        if (keywords != null && !keywords.isBlank()) {
            for (String keyword : keywords.split(",")) {
                if (contains(scanText, keyword.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String text, String keyword) {
        return keyword != null && !keyword.isBlank() && text.contains(keyword);
    }
}
