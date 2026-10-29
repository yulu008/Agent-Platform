package com.luyu.agent.rpg.eval.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.engine.StateDeltaSanitizer;
import com.luyu.agent.rpg.eval.config.EvalProperties;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态层装配器单测（content-eval tasks 3.3）：指称筛轮、滑窗切分与缩窗、
 * 工具调用/合成消息过滤、指纹解析降级路径。
 */
class DynamicEvalCaseAssemblerTest {

    private static final String GS_ID = "gs-1";
    private static final String SESSION_ID = "sess-1";
    private static final String WORLD_ID = "preset-adventure:t1";
    private static final String NOBUNAGA = "织田信长";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RpgGameStateRepository stateRepo;
    private StaticEvalCaseAssembler staticAssembler;
    private SessionService sessionService;
    private StateDeltaSanitizer stateDeltaSanitizer;
    private JdbcTemplate jdbcTemplate;
    private EvalProperties evalProperties;
    private DynamicEvalCaseAssembler assembler;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-test", "t-test");
        stateRepo = mock(RpgGameStateRepository.class);
        staticAssembler = mock(StaticEvalCaseAssembler.class);
        sessionService = mock(SessionService.class);
        stateDeltaSanitizer = mock(StateDeltaSanitizer.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        evalProperties = new EvalProperties();
        // sanitizer 默认行为：原样返回（清洗逻辑自身已有独立测试）
        when(stateDeltaSanitizer.extractNarration(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        // metering 无明细（近似指纹来源为空 → approx:unknown；用例按需覆写）
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of());
        assembler = new DynamicEvalCaseAssembler(stateRepo, staticAssembler,
                sessionService, stateDeltaSanitizer, jdbcTemplate, evalProperties);

        // 基线：存档绑定会话、人物池一个静态单元
        when(stateRepo.findById(GS_ID)).thenReturn(gameState());
        when(staticAssembler.assemble(WORLD_ID)).thenReturn(List.of(staticCase()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void 出场轮筛选_不足两轮跳过_单元结构含窗口状态指纹() throws Exception {
        // 4 轮：前 2 轮含织田信长（出场）、第 3 轮无指称、第 4 轮只有路人甲（1 轮出场不足）
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我去清州城。"),
                gmEvent("a1", "织田信长正在校场练兵。"),
                userEvent("u2", "我上前搭话。"),
                gmEvent("a2", "织田信长回头打量你。"),
                userEvent("u3", "我离开。"),
                gmEvent("a3", "城门外的商人正在收摊。"),
                userEvent("u4", "打听路人。"),
                gmEvent("a4", "路人甲指了指方向。")));
        // 人物池补一个只有 1 轮出场的人物
        when(staticAssembler.assemble(WORLD_ID)).thenReturn(List.of(
                staticCase(), staticCase("passerby", "路人甲", List.of("路人甲"))));
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("glm-5-1"));

        List<EvalCase> cases = assembler.assemble(GS_ID);

        // 路人甲仅 1 轮出场（< MIN_APPEARANCE_TURNS=2），无证据不评
        assertThat(cases).hasSize(1);
        EvalCase c = cases.get(0);
        assertThat(c.layer()).isEqualTo("dynamic");
        assertThat(c.sourceType()).isEqualTo(EvalCase.SOURCE_DESTINED);
        assertThat(c.subject()).isEqualTo("nobunaga");
        assertThat(c.subjectLabel()).isEqualTo(NOBUNAGA);
        assertThat(c.settingPackJson()).isEqualTo(staticCase().settingPackJson());
        assertThat(c.npcStatesJson()).isEqualTo("{\"hp\":100}");
        assertThat(c.playerStatesJson()).isEqualTo("{\"gold\":50}");
        // prompt_hash 表为空 + metering 有 model → 存量降级近似指纹
        assertThat(c.targetFingerprint()).isEqualTo("approx:glm-5-1");
        // 出场恰 2 轮（< windowTurns=15）：早窗 = 晚窗 = 轮1-2 全行
        assertThat(c.earlyWindow()).extracting(EvalCase.TurnText::turn)
                .containsExactly(1, 1, 2, 2);
        assertThat(c.lateWindow()).extracting(EvalCase.TurnText::turn)
                .containsExactly(1, 1, 2, 2);
        // eventId 仅 assistant 行携带（prompt_hash 对齐键）
        assertThat(c.earlyWindow().get(0).eventId()).isNull();
        assertThat(c.earlyWindow().get(1).eventId()).isEqualTo("a1");

        // prompt_hash 事件对齐表直查 AI_SESSION_EVENT
        verify(jdbcTemplate).query(contains("prompt_hash"),
                any(RowCallbackHandler.class), eq(SESSION_ID));

        JsonNode unit = JSON.readTree(c.unitJson());
        assertThat(unit.get("sessionId").asText()).isEqualTo(SESSION_ID);
        assertThat(unit.get("gameStateId").asText()).isEqualTo(GS_ID);
        assertThat(unit.get("earlyWindow").toString()).isEqualTo("[1,2]");
        assertThat(unit.get("lateWindow").toString()).isEqualTo("[1,2]");
    }

    @Test
    void 叙述剥离_state_delta不进入窗口() {
        when(stateDeltaSanitizer.extractNarration(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace("【状态】", ""));
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我去清州城。"),
                gmEvent("a1", "【状态】hp=90 织田信长正在校场练兵。"),
                userEvent("u2", "我上前搭话。"),
                gmEvent("a2", "【状态】hp=85 织田信长回头打量你。")));

        List<EvalCase> cases = assembler.assemble(GS_ID);

        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).earlyWindow())
                .extracting(EvalCase.TurnText::text)
                .noneMatch(t -> t.contains("【状态】"));
    }

    @Test
    void 滑窗切分_早窗保最早_晚窗保最近() {
        evalProperties.setDynamicWindowTurns(2);
        when(sessionService.getEvents(SESSION_ID)).thenReturn(
                turns(turn(1), turn(2), turn(3), turn(4)));

        List<EvalCase> cases = assembler.assemble(GS_ID);

        assertThat(cases).hasSize(1);
        EvalCase c = cases.get(0);
        assertThat(c.earlyWindow()).extracting(EvalCase.TurnText::turn)
                .containsExactly(1, 1, 2, 2);
        assertThat(c.lateWindow()).extracting(EvalCase.TurnText::turn)
                .containsExactly(3, 3, 4, 4);
    }

    @Test
    void 字符预算缩窗_锚定端保留() {
        // 4 出场轮、每轮 assistant 4000 字 → 两窗各超 12000 预算触发缩窗
        when(sessionService.getEvents(SESSION_ID)).thenReturn(
                turns(bigTurn(1), bigTurn(2), bigTurn(3), bigTurn(4)));

        List<EvalCase> cases = assembler.assemble(GS_ID);

        assertThat(cases).hasSize(1);
        EvalCase c = cases.get(0);
        // 早窗保最早：首行 turn=1，裁掉最晚端（不含 turn=4）
        assertThat(c.earlyWindow().get(0).turn()).isEqualTo(1);
        assertThat(c.earlyWindow()).extracting(EvalCase.TurnText::turn)
                .noneMatch(t -> t >= 4);
        // 晚窗保最近：末行 turn=4，裁掉最早端（不含 turn=1）
        assertThat(c.lateWindow().get(c.lateWindow().size() - 1).turn()).isEqualTo(4);
        assertThat(c.lateWindow()).extracting(EvalCase.TurnText::turn)
                .noneMatch(t -> t <= 1);
        // 两窗各自不超字符预算
        assertThat(c.earlyWindow()).allSatisfy(
                row -> assertThat(row.text().length()).isLessThanOrEqualTo(
                        DynamicEvalCaseAssembler.WINDOW_CHAR_BUDGET));
        assertThat(sumChars(c.earlyWindow()))
                .isLessThanOrEqualTo(DynamicEvalCaseAssembler.WINDOW_CHAR_BUDGET);
        assertThat(sumChars(c.lateWindow()))
                .isLessThanOrEqualTo(DynamicEvalCaseAssembler.WINDOW_CHAR_BUDGET);
    }

    @Test
    void 工具调用轮与合成消息_不参与轮提取() {
        // 工具调用轮非叙述轮（对齐 StarLorebookScanner 先例）、合成摘要跳过
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我去清州城。"),
                toolCallEvent("t1", "调用 update_game_state"),
                syntheticEvent("syn1", "（前情摘要：织田信长的旧事）"),
                gmEvent("a1", "织田信长正在校场练兵。"),
                userEvent("u2", "我上前搭话。"),
                toolCallEvent("t2", "调用 roll_dice"),
                gmEvent("a2", "织田信长回头打量你。")));

        List<EvalCase> cases = assembler.assemble(GS_ID);

        assertThat(cases).hasSize(1);
        List<EvalCase.TurnText> rows = cases.get(0).earlyWindow();
        // 仅 2 个叙述轮（工具/合成不计轮号），轮序 1、2
        assertThat(rows).extracting(EvalCase.TurnText::turn).containsExactly(1, 1, 2, 2);
        assertThat(rows).extracting(EvalCase.TurnText::text)
                .noneMatch(t -> t.contains("update_game_state")
                        || t.contains("roll_dice")
                        || t.contains("前情摘要"));
    }

    @Test
    void 指纹解析_最新非空优先_近似降级() {
        // 出场轮 assistant hash 取最新非空（跨版本锚定晚窗生成版本）
        assertThat(DynamicEvalCaseAssembler.resolveFingerprint(
                List.of("e1", "e2", "e3"),
                Map.of("e1", "h1", "e3", "h3"), "m"))
                .isEqualTo("h3");
        // 中间空值不阻断，向后取最新
        assertThat(DynamicEvalCaseAssembler.resolveFingerprint(
                List.of("e1", "e2"), Map.of("e2", "h2"), "m"))
                .isEqualTo("h2");
        // null eventId 防御（Arrays.asList 容纳 null 元素）
        assertThat(DynamicEvalCaseAssembler.resolveFingerprint(
                Arrays.asList(null, "e2"), Map.of("e2", "h2"), null))
                .isEqualTo("h2");
        // 全空 → 近似指纹：model 存在
        assertThat(DynamicEvalCaseAssembler.resolveFingerprint(
                List.of("e1"), Map.of(), "glm-5-1"))
                .isEqualTo("approx:glm-5-1");
        // 全空 + model 也缺失 → unknown
        assertThat(DynamicEvalCaseAssembler.resolveFingerprint(
                List.of(), Map.of(), null))
                .isEqualTo("approx:unknown");
        assertThat(DynamicEvalCaseAssembler.resolveFingerprint(
                List.of(), Map.of(), " "))
                .isEqualTo("approx:unknown");
    }

    @Test
    void 存档缺失或无会话_返回空列表() {
        when(stateRepo.findById("gs-none")).thenReturn(null);
        assertThat(assembler.assemble("gs-none")).isEmpty();

        GameState noSession = gameState();
        noSession.setSessionId("  ");
        when(stateRepo.findById(GS_ID)).thenReturn(noSession);
        assertThat(assembler.assemble(GS_ID)).isEmpty();
    }

    @Test
    void 会话事件读取异常_防御返回空列表() {
        when(sessionService.getEvents(SESSION_ID))
                .thenThrow(new RuntimeException("会话存储不可用"));

        assertThat(assembler.assemble(GS_ID)).isEmpty();
    }

    // ==================== 构造辅助 ====================

    private static GameState gameState() {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setWorldId(WORLD_ID);
        gs.setSessionId(SESSION_ID);
        gs.setNpcStates("{\"hp\":100}");
        gs.setPlayerStates("{\"gold\":50}");
        return gs;
    }

    /** 织田信长静态单元（动态层人物池模板） */
    private static EvalCase staticCase() {
        return staticCase("nobunaga", NOBUNAGA, List.of(NOBUNAGA));
    }

    private static EvalCase staticCase(String subject, String label, List<String> aliases) {
        return new EvalCase("static", EvalCase.SOURCE_DESTINED, subject, label,
                "{\"source\":\"destined\",\"subject\":\"" + subject + "\"}",
                "{\"identity\":\"尾张大名\"}", aliases, null, null, null, null, null, null);
    }

    /** 多轮拼接为事件流（保持轮序） */
    private static List<SessionEvent> turns(SessionEvent[]... pairs) {
        List<SessionEvent> events = new ArrayList<>();
        for (SessionEvent[] pair : pairs) {
            events.addAll(Arrays.asList(pair));
        }
        return events;
    }

    /** 一轮完整叙述（user + GM，均含指称 → 保证出场） */
    private static SessionEvent[] turn(int i) {
        return new SessionEvent[] {
                userEvent("u" + i, "第" + i + "轮行动，找织田信长。"),
                gmEvent("a" + i, "第" + i + "轮：织田信长有所动作。")
        };
    }

    /** 一轮超长叙述（assistant 4000 字，触发字符预算缩窗） */
    private static SessionEvent[] bigTurn(int i) {
        return new SessionEvent[] {
                userEvent("u" + i, "第" + i + "轮行动。"),
                gmEvent("a" + i, "叙".repeat(4000) + "织田信长。")
        };
    }

    private static SessionEvent userEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new UserMessage(text)).timestamp(Instant.now()).build();
    }

    private static SessionEvent gmEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new AssistantMessage(text)).timestamp(Instant.now()).build();
    }

    private static SessionEvent syntheticEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new AssistantMessage(text))
                .metadata(SessionEvent.METADATA_SYNTHETIC, true)
                .timestamp(Instant.now()).build();
    }

    private static SessionEvent toolCallEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(AssistantMessage.builder()
                        .content(text)
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-" + id, "function", "update_game_state", "{}")))
                        .build())
                .timestamp(Instant.now()).build();
    }

    private static int sumChars(List<EvalCase.TurnText> rows) {
        return rows.stream().mapToInt(r -> r.text() == null ? 0 : r.text().length()).sum();
    }
}
