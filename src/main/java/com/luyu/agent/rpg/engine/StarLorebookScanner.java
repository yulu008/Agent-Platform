package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.service.LoreArchiveService;
import com.luyu.agent.rpg.service.LorebookSetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 冥星武将录关键词扫描器（star-lorebook design D3，SillyTavern lorebook 风格）。
 * <p>
 * 每个后续回合在组装增量 prompt 前执行（仅非首轮，调用方 {@code GameLoopService}）：
 * <ol>
 *   <li>扫描文本 = 本轮玩家行动 + GM 上一轮叙事（仅扫玩家行动会因 GM 先提到武将名而漏激活）；</li>
 *   <li>匹配集 = 每条名录的 {@code star_name + warlord_name + aliases}（子串匹配，中文无需分词）；</li>
 *   <li>命中未激活条目 → 写 runtime 行（{@code activated_turn=当前轮}、{@code claimed=false}）
 *       + 向 MEMORY.md 追加档案索引行（rpg-lore-archival-loading D4：索引可见性 = 激活边界，
 *       激活即入目，索引失败降级告警不阻断）；</li>
 *   <li>返回本存档全部已激活条目（含开局抢星的 claimed 行）+ 本轮新命中子集——
 *       sticky 状态归 runtime 双表，注入层只消费「新命中」打一行式提示。</li>
 * </ol>
 * 任何异常降级为空结果（世界书是增强注入，绝不阻断回合主流程）；
 * {@code rpg.lorebook.enabled=false} 时直接返回空。
 */
@Component
public class StarLorebookScanner {

    private static final Logger log = LoggerFactory.getLogger(StarLorebookScanner.class);

    /** playerStates 解析用（人物局标记，只读、线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 已揭示条目（注入块渲染的数据载体）：名录静态行 + 该存档的抢星状态。
     *
     * @param incarnation    名录条目（星名/武将名/叙事/别名）
     * @param claimed        此局星归玩家（开局抢星判定命中）
     * @param playerIsFigure claimed 行的人物扮演语义（preset-destined-figures D3）：人物局
     *                       撞星（figureKey 非空）时渲染层切换为「人即玩家」——玩家=武将本人；
     *                       缺省 false 维持旧「星归玩家」语义（无名新人/信胜撞星，武将失衣）。
     */
    public record RevealedEntry(StarIncarnation incarnation, boolean claimed,
                                boolean playerIsFigure) {

        /** 兼容构造：激活路径（claimed 恒 false）用不到人物信号，缺省 false */
        public RevealedEntry(StarIncarnation incarnation, boolean claimed) {
            this(incarnation, claimed, false);
        }
    }

    /**
     * 扫描结果（rpg-lore-archival-loading tasks 2.4）：{@code revealed} 为本存档全部
     * 已激活条目（sticky，供状态面消费），{@code newlyRevealed} 为本轮新写入 runtime 行的
     * 子集——注入块仅渲染新命中打一行式提示（命中轮滑窗残留层），既有激活依赖索引兜底。
     */
    public record ScanOutcome(List<RevealedEntry> revealed, List<RevealedEntry> newlyRevealed) {

        public static ScanOutcome empty() {
            return new ScanOutcome(List.of(), List.of());
        }
    }

    private final RpgGameStateRepository stateRepo;
    private final RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private final SessionService sessionService;
    private final StateDeltaSanitizer stateDeltaSanitizer;
    private final RpgLorebookProperties lorebookProperties;
    private final LorebookSetService lorebookSetService;
    private final LoreArchiveService loreArchiveService;

    public StarLorebookScanner(RpgGameStateRepository stateRepo,
                               RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                               SessionService sessionService,
                               StateDeltaSanitizer stateDeltaSanitizer,
                               RpgLorebookProperties lorebookProperties,
                               LorebookSetService lorebookSetService,
                               LoreArchiveService loreArchiveService) {
        this.stateRepo = stateRepo;
        this.starRuntimeRepo = starRuntimeRepo;
        this.sessionService = sessionService;
        this.stateDeltaSanitizer = stateDeltaSanitizer;
        this.lorebookProperties = lorebookProperties;
        this.lorebookSetService = lorebookSetService;
        this.loreArchiveService = loreArchiveService;
    }

    /**
     * 扫描并汇总已揭示条目（激活即同步追加档案索引行）。
     *
     * @param gameStateId  存档 ID
     * @param playerAction 本轮玩家行动文本（扫描文本之一）
     * @param turn         本轮轮次号（命中时记入 activated_turn）
     * @return 扫描结果（全量已激活 + 本轮新命中）；未激活/名录为空/开关关闭/异常时为空结果
     */
    public ScanOutcome scanAndCollect(String gameStateId, String playerAction, int turn) {
        if (!lorebookProperties.isEnabled()) {
            return ScanOutcome.empty();
        }
        try {
            GameState gs = stateRepo.findById(gameStateId);
            if (gs == null) {
                return ScanOutcome.empty();
            }
            // 读取源（add-rpg-lorebook-sets D2）：存档 set 名录优先，老存档回退模板行
            LorebookSetService.ResolvedView view = lorebookSetService.resolve(gameStateId);
            List<StarIncarnation> incarnations = view == null ? List.of() : view.stars();
            if (incarnations.isEmpty()) {
                return ScanOutcome.empty();
            }

            // 人物扮演局标记（preset-destined-figures D2）：figure_key 非空 = 玩家即命定
            // 人物本人——claimed 行携带该信号供渲染层区分双语义
            boolean playerIsFigure = playerIsFigure(gs);

            // 已激活（含开局 claimed）：star_name 为跨物化稳定键（design 风险缓解）
            Map<String, StarIncarnationRuntime> activeByName = new HashMap<>();
            for (StarIncarnationRuntime rt : starRuntimeRepo.findByGameStateId(gameStateId)) {
                if (rt.getStarName() != null) {
                    activeByName.put(rt.getStarName(), rt);
                }
            }

            // 扫描文本：玩家行动 + GM 上一轮叙事
            String scanText = (playerAction == null ? "" : playerAction) + "\n" + lastGmNarration(gs);
            List<RevealedEntry> newlyRevealed = new ArrayList<>();
            if (!scanText.isBlank()) {
                for (StarIncarnation incarnation : incarnations) {
                    if (activeByName.containsKey(incarnation.getStarName())) {
                        continue;
                    }
                    if (matchesAnyKeyword(scanText, incarnation)) {
                        StarIncarnationRuntime rt = new StarIncarnationRuntime();
                        rt.setId(UUID.randomUUID().toString());
                        rt.setGameStateId(gameStateId);
                        rt.setIncarnationId(incarnation.getId());
                        rt.setStarName(incarnation.getStarName());
                        rt.setActivatedTurn(turn);
                        rt.setClaimedByPlayer(false);
                        starRuntimeRepo.save(rt);
                        activeByName.put(incarnation.getStarName(), rt);
                        newlyRevealed.add(new RevealedEntry(incarnation, false));
                        // 索引行随激活追加（rpg-lore-archival-loading D4）：服务内部幂等与降级，
                        // 此处再包一层防御服务自身缺陷——索引失败仅缺索引，
                        // 不拖垮 runtime 行（sticky）与本轮返回值
                        try {
                            loreArchiveService.appendRevealedIndexLine(gameStateId,
                                    incarnation.getStarName(), incarnation.getWarlordName(), false);
                        } catch (Exception e) {
                            log.warn("档案索引行追加异常，降级跳过: gameStateId={}, star={}",
                                    gameStateId, incarnation.getStarName(), e);
                        }
                        log.info("世界书条目激活: gameStateId={}, turn={}, star={}, warlord={}",
                                gameStateId, turn, incarnation.getStarName(), incarnation.getWarlordName());
                    }
                }
            }

            // 按名录顺序汇总已揭示条目（sticky：不随本轮是否再次命中而消失）
            List<RevealedEntry> revealed = new ArrayList<>();
            for (StarIncarnation incarnation : incarnations) {
                StarIncarnationRuntime rt = activeByName.get(incarnation.getStarName());
                if (rt != null) {
                    boolean claimed = Boolean.TRUE.equals(rt.getClaimedByPlayer());
                    // 人物信号仅对 claimed 行有意义（激活路径 claimed=false，恒用不到）
                    revealed.add(new RevealedEntry(incarnation, claimed, claimed && playerIsFigure));
                }
            }
            return new ScanOutcome(List.copyOf(revealed), List.copyOf(newlyRevealed));
        } catch (Exception e) {
            log.warn("世界书扫描失败，本轮降级为不注入: gameStateId={}", gameStateId, e);
            return ScanOutcome.empty();
        }
    }

    /**
     * 人物扮演局标记（preset-destined-figures D2）：playerStates 的 figure_key 非空
     * 即人物局（玩家=命定人物本人）。解析失败/无键按 false 降级（旧档兼容）。
     */
    private static boolean playerIsFigure(GameState gs) {
        String playerStates = gs.getPlayerStates();
        if (playerStates == null || playerStates.isBlank()) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(playerStates);
            return !root.path("figure_key").asText("").isBlank();
        } catch (Exception e) {
            return false;
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

    /** 关键词匹配：星名 / 武将全名 / 受控别名（md 显式声明），任一子串命中即激活 */
    private static boolean matchesAnyKeyword(String scanText, StarIncarnation incarnation) {
        if (contains(scanText, incarnation.getStarName())) {
            return true;
        }
        if (contains(scanText, incarnation.getWarlordName())) {
            return true;
        }
        String aliases = incarnation.getAliases();
        if (aliases != null && !aliases.isBlank()) {
            for (String alias : aliases.split(",")) {
                if (contains(scanText, alias.trim())) {
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
