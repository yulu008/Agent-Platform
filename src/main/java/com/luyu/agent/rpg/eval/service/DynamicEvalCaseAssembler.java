package com.luyu.agent.rpg.eval.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.luyu.agent.rpg.engine.StateDeltaSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.luyu.agent.rpg.eval.config.EvalProperties;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.EvalRun;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 动态层 EvalCase 装配器（content-eval tasks 3.2，design D9）。
 * <p>
 * 按存档（gameStateId）回捞存量会话：人物池取静态层三源设定包，逐人物按指称
 * 词表（displayName + aliases）筛出场轮 → 早窗/晚窗滑窗对（默认各
 * {@code rpg.eval.dynamic-window-turns} 轮，超字符预算缩窗：早窗保最早、
 * 晚窗保最近）+ npc_states/playerStates 快照 + 版本指纹。
 * <p>
 * 指纹解析（design D8）：出场轮 assistant 事件的 prompt_hash 取窗内<b>最新非空</b>
 * 值（跨版本混跑时锚定「生成晚窗内容的那一版」）；全空（存量会话）按 metering
 * token_usage_log 的 model 字段拼 {@code approx:<model>} 降级指纹（报告标注
 * 「近似」）；近似来源也缺失时 {@code approx:unknown}。
 * <p>
 * 出场轮不足 {@link #MIN_APPEARANCE_TURNS} 的人物跳过（无证据不评）；
 * 存档/会话/事件读取异常返回空列表（批跑报「无可用单元」而非中断）。
 */
@Service
public class DynamicEvalCaseAssembler {

    private static final Logger log = LoggerFactory.getLogger(DynamicEvalCaseAssembler.class);

    /** 出场轮下限：不足视为无证据，跳过该人物（不产生 record） */
    static final int MIN_APPEARANCE_TURNS = 2;

    /** 单窗字符预算（叙述+行动；超预算缩窗） */
    static final int WINDOW_CHAR_BUDGET = 12000;

    /** 近似指纹前缀（报告据此标注「近似」） */
    public static final String APPROX_PREFIX = "approx:";

    private final RpgGameStateRepository stateRepo;
    private final StaticEvalCaseAssembler staticAssembler;
    private final SessionService sessionService;
    private final StateDeltaSanitizer stateDeltaSanitizer;
    private final JdbcTemplate jdbcTemplate;
    private final EvalProperties evalProperties;

    @Autowired
    public DynamicEvalCaseAssembler(RpgGameStateRepository stateRepo,
                                    StaticEvalCaseAssembler staticAssembler,
                                    SessionService sessionService,
                                    StateDeltaSanitizer stateDeltaSanitizer,
                                    JdbcTemplate jdbcTemplate,
                                    EvalProperties evalProperties) {
        this.stateRepo = stateRepo;
        this.staticAssembler = staticAssembler;
        this.sessionService = sessionService;
        this.stateDeltaSanitizer = stateDeltaSanitizer;
        this.jdbcTemplate = jdbcTemplate;
        this.evalProperties = evalProperties;
    }

    /**
     * 装配某存档的全部动态层评估单元（人物 × 滑窗对，窗轮数取配置默认）。
     * 存档不存在/无会话/事件读取失败返回空列表。
     */
    public List<EvalCase> assemble(String gameStateId) {
        return assemble(gameStateId, null);
    }

    /**
     * 装配某存档的全部动态层评估单元（人物 × 滑窗对），窗轮数可请求级覆盖：
     * {@code windowTurnsOverride} 为 null/非正数时回落
     * {@code rpg.eval.dynamic-window-turns}（批跑端点 {@code windows?} 参数，design D12）。
     */
    public List<EvalCase> assemble(String gameStateId, Integer windowTurnsOverride) {
        try {
            GameState gs = stateRepo.findById(gameStateId);
            if (gs == null || gs.getSessionId() == null || gs.getSessionId().isBlank()) {
                log.warn("动态层装配跳过：存档不存在或无会话: gameStateId={}", gameStateId);
                return List.of();
            }
            String sessionId = gs.getSessionId();
            List<TurnSequence> turns = extractTurns(sessionId);
            if (turns.isEmpty()) {
                log.warn("动态层装配跳过：会话无可评轮: sessionId={}", sessionId);
                return List.of();
            }
            Map<String, String> hashByEventId = loadPromptHashes(sessionId);
            String approxModel = loadApproxModel(sessionId);
            int windowTurns = effectiveWindowTurns(windowTurnsOverride);

            List<EvalCase> cases = new ArrayList<>();
            for (EvalCase setting : staticAssembler.assemble(gs.getWorldId())) {
                List<TurnSequence> appeared = new ArrayList<>();
                for (TurnSequence t : turns) {
                    if (mentionsAny(t, setting.aliases())) {
                        appeared.add(t);
                    }
                }
                if (appeared.size() < MIN_APPEARANCE_TURNS) {
                    continue; // 无证据不评
                }
                List<EvalCase.TurnText> early = sliceWindow(appeared, true, windowTurns);
                List<EvalCase.TurnText> late = sliceWindow(appeared, false, windowTurns);
                List<String> assistantEventIds = appeared.stream()
                        .map(TurnSequence::assistantEventId).toList();
                String fingerprint = resolveFingerprint(assistantEventIds, hashByEventId, approxModel);
                List<EvalCase.TurnState> trajectory = appeared.stream()
                        .map(t -> new EvalCase.TurnState(t.turn(), t.stateDelta()))
                        .toList();
                cases.add(new EvalCase(EvalRun.LAYER_DYNAMIC, setting.sourceType(),
                        setting.subject(), setting.subjectLabel(),
                        dynamicUnitJson(setting, sessionId, gameStateId, early, late),
                        setting.settingPackJson(), setting.aliases(),
                        early, late, gs.getNpcStates(), gs.getPlayerStates(), fingerprint,
                        trajectory));
            }
            return cases;
        } catch (Exception e) {
            log.warn("动态层装配失败（返回空）: gameStateId={}", gameStateId, e);
            return List.of();
        }
    }

    /** 生效窗轮数：请求级覆盖优先（正数），否则配置默认（下限 1） */
    private int effectiveWindowTurns(Integer override) {
        if (override != null && override > 0) {
            return override;
        }
        return Math.max(1, evalProperties.getDynamicWindowTurns());
    }

    /** 一轮的原文行（user 行动 + assistant 纯叙述）与状态增量（state_delta 数值扁平化） */
    private record TurnSequence(int turn, List<EvalCase.TurnText> rows, String assistantEventId,
                                Map<String, Double> stateDelta) {
    }

    private List<TurnSequence> extractTurns(String sessionId) {
        List<SessionEvent> events = sessionService.getEvents(sessionId);
        List<TurnSequence> turns = new ArrayList<>();
        int turn = 0;
        String pendingUser = null;
        for (SessionEvent e : events) {
            if (e.isSynthetic()) {
                continue;
            }
            if (e.getMessage() instanceof UserMessage um) {
                pendingUser = um.getText();
            } else if (e.getMessage() instanceof AssistantMessage am && !am.hasToolCalls()) {
                // 工具调用轮非叙述轮（对齐 StarLorebookScanner.lastGmNarration 先例）
                turn++;
                List<EvalCase.TurnText> rows = new ArrayList<>(2);
                if (pendingUser != null && !pendingUser.isBlank()) {
                    rows.add(new EvalCase.TurnText(turn, EvalCase.TurnText.ROLE_USER,
                            pendingUser, null));
                }
                rows.add(new EvalCase.TurnText(turn, EvalCase.TurnText.ROLE_ASSISTANT,
                        stateDeltaSanitizer.extractNarration(am.getText()), e.getId()));
                turns.add(new TurnSequence(turn, List.copyOf(rows), e.getId(),
                        flattenStateDelta(stateDeltaSanitizer.tryExtractStateDelta(am.getText()))));
                pendingUser = null;
            }
        }
        return turns;
    }

    /**
     * state_delta 数值扁平化（design D5 断崖检测输入）：
     * {@code npc_states.<id>.motives.<key>} → {@code npc:<id>:<key>}、
     * {@code player.<money/cosmo>} → {@code player:<field>}；
     * 非数值/缺失/解析失败 → 空 Map（轨迹缺失不阻断评估）。
     */
    private static Map<String, Double> flattenStateDelta(JsonNode delta) {
        Map<String, Double> flat = new LinkedHashMap<>();
        if (delta == null || !delta.isObject()) {
            return flat;
        }
        JsonNode npcStates = delta.path("npc_states");
        if (npcStates.isObject()) {
            npcStates.fields().forEachRemaining(npc -> {
                JsonNode motives = npc.getValue().path("motives");
                if (motives.isObject()) {
                    motives.fields().forEachRemaining(m -> {
                        JsonNode v = m.getValue();
                        if (v.isNumber()) {
                            flat.put("npc:" + npc.getKey() + ":" + m.getKey(), v.asDouble());
                        }
                    });
                }
            });
        }
        JsonNode player = delta.path("player");
        if (player.isObject()) {
            player.fields().forEachRemaining(p -> {
                JsonNode v = p.getValue();
                if (v.isNumber()) {
                    flat.put("player:" + p.getKey(), v.asDouble());
                }
            });
        }
        return flat;
    }

    /** 该轮任一行含任一指称 → 出场 */
    private static boolean mentionsAny(TurnSequence t, List<String> aliases) {
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            for (EvalCase.TurnText row : t.rows()) {
                if (row.text() != null && row.text().contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 滑窗切片（超字符预算缩窗）：早窗取最早 N 轮并从尾部收缩；晚窗取最近 N 轮
     * 并从头收缩（「最近轮优先」，design D9）。
     */
    private static List<EvalCase.TurnText> sliceWindow(List<TurnSequence> appeared,
                                                       boolean early, int windowTurns) {
        List<TurnSequence> selected = early
                ? appeared.subList(0, Math.min(windowTurns, appeared.size()))
                : appeared.subList(Math.max(0, appeared.size() - windowTurns), appeared.size());
        List<EvalCase.TurnText> rows = new ArrayList<>();
        for (TurnSequence t : selected) {
            rows.addAll(t.rows());
        }
        // 字符预算缩窗：保留锚定端（早窗=最早、晚窗=最近）逐行裁掉另一端
        int total = rows.stream().mapToInt(r -> r.text() == null ? 0 : r.text().length()).sum();
        if (total > WINDOW_CHAR_BUDGET) {
            List<EvalCase.TurnText> budgeted = new ArrayList<>();
            int used = 0;
            Iterable<EvalCase.TurnText> ordered = early ? rows : List.copyOf(rows).reversed();
            for (EvalCase.TurnText row : ordered) {
                int len = row.text() == null ? 0 : row.text().length();
                if (used + len > WINDOW_CHAR_BUDGET) {
                    break;
                }
                budgeted.add(row);
                used += len;
            }
            if (!early) {
                budgeted = budgeted.reversed();
            }
            rows = budgeted;
        }
        return rows;
    }

    /** prompt_hash 事件对齐表（AI_SESSION_EVENT 直查；列缺失/异常降级空表） */
    private Map<String, String> loadPromptHashes(String sessionId) {
        Map<String, String> hashes = new HashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT id, prompt_hash FROM AI_SESSION_EVENT WHERE session_id = ?",
                    rs -> {
                        String hash = rs.getString("prompt_hash");
                        if (hash != null) {
                            hashes.put(rs.getString("id"), hash);
                        }
                    }, sessionId);
        } catch (Exception e) {
            log.warn("prompt_hash 读取失败（该会话全部按近似指纹降级）: sessionId={}", sessionId, e);
        }
        return hashes;
    }

    /** metering 明细的 model 字段（近似指纹来源；无明细/异常返回 null） */
    private String loadApproxModel(String sessionId) {
        try {
            List<String> models = jdbcTemplate.queryForList(
                    "SELECT DISTINCT model FROM token_usage_log WHERE tenant_id = ? AND session_id = ?",
                    String.class, TenantContext.requireTenantId(), sessionId);
            return models.isEmpty() ? null : String.join("/", models);
        } catch (Exception e) {
            log.warn("近似指纹来源读取失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 版本指纹：出场轮 assistant 事件 hash 取最新非空（跨版本锚定晚窗生成版本）；
     * 全空 → {@code approx:<model>}（model 缺失 → {@code approx:unknown}）。
     */
    static String resolveFingerprint(List<String> assistantEventIds, Map<String, String> hashByEventId,
                                     String approxModel) {
        String latest = null;
        for (String eventId : assistantEventIds) {
            String hash = eventId == null ? null : hashByEventId.get(eventId);
            if (hash != null) {
                latest = hash;
            }
        }
        if (latest != null) {
            return latest;
        }
        return APPROX_PREFIX + (approxModel == null || approxModel.isBlank() ? "unknown" : approxModel);
    }

    private static String dynamicUnitJson(EvalCase setting, String sessionId, String gameStateId,
                                          List<EvalCase.TurnText> early, List<EvalCase.TurnText> late) {
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("source", setting.sourceType());
        unit.put("subject", setting.subject());
        unit.put("worldId", null); // 动态层以存档为锚，worldId 从设定包 unitJson 取
        unit.put("sessionId", sessionId);
        unit.put("gameStateId", gameStateId);
        if (!early.isEmpty() && !late.isEmpty()) {
            unit.put("earlyWindow", List.of(early.get(0).turn(), early.get(early.size() - 1).turn()));
            unit.put("lateWindow", List.of(late.get(0).turn(), late.get(late.size() - 1).turn()));
        }
        return StaticEvalCaseAssembler.toJson(unit);
    }
}
