package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.config.RpgMemoryProperties;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgGameStateSnapshotRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GameLoopService#prepareTurn} 联合快照与 {@link GameLoopService#finalizeTurn}
 * npc_states key 校验/修复链的单元测试。
 * <p>
 * prepareTurn 部分（回溯正确性的根基约定）：
 * <ol>
 *   <li>首个回合产生 turn=0 初始快照（存量存档依赖此点获得回溯能力）；</li>
 *   <li>快照先于本轮 player_action 事件日志（快照 = GM 即将看到的世界，不含本轮事件）；</li>
 *   <li>连续两次 prepareTurn 同 turn 只调一份快照（重新生成场景，覆盖语义由仓储层保证）；</li>
 *   <li>每次快照后触发保留窗口清理（KEEP_COUNT=4）；</li>
 *   <li>快照失败仅降级告警，不阻断回合。</li>
 * </ol>
 * finalizeTurn 部分（npc_states key 硬校验）：全合法轮不触发修复；
 * 非法 key 轮修复成功后以汉字 key 入库；修复失败轮非法条目确定性丢弃且主流程照常。
 */
class GameLoopServiceSnapshotTest {

    private static final String GS_ID = "gs-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RpgGameStateRepository stateRepo;
    private RpgEventLogRepository eventRepo;
    private RpgGameStateSnapshotRepository snapshotRepo;
    private StateDeltaExtractor stateDeltaExtractor;
    private StateDeltaSanitizer stateDeltaSanitizer;
    private NpcKeyRepairService npcKeyRepairService;
    private TurnGuardService turnGuardService;
    private GameLoopService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        stateRepo = mock(RpgGameStateRepository.class);
        eventRepo = mock(RpgEventLogRepository.class);
        snapshotRepo = mock(RpgGameStateSnapshotRepository.class);
        stateDeltaExtractor = mock(StateDeltaExtractor.class);
        stateDeltaSanitizer = mock(StateDeltaSanitizer.class);
        npcKeyRepairService = mock(NpcKeyRepairService.class);
        turnGuardService = new TurnGuardService();
        service = newService(new MemorySnapshotStore(tempDir.resolve("saves"), tempDir.resolve("snapshots")));
        // prepareTurn 经 ToolContext 携带租户身份（同生产环境 JwtTenantFilter 建立路径）
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 通过反射按既有（唯一）构造器装配，避免本测试随依赖演进频繁改构造行。 */
    @SuppressWarnings("unchecked")
    private GameLoopService newService(MemorySnapshotStore memoryStore) {
        try {
            Constructor<GameLoopService> ctor =
                    (Constructor<GameLoopService>) GameLoopService.class.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] == RpgGameStateRepository.class) args[i] = stateRepo;
                else if (types[i] == RpgEventLogRepository.class) args[i] = eventRepo;
                else if (types[i] == RpgGameStateSnapshotRepository.class) args[i] = snapshotRepo;
                else if (types[i] == MemorySnapshotStore.class) args[i] = memoryStore;
                else if (types[i] == RpgMemoryProperties.class) args[i] = new RpgMemoryProperties();
                else if (types[i] == StateDeltaExtractor.class) args[i] = stateDeltaExtractor;
                else if (types[i] == StateDeltaSanitizer.class) args[i] = stateDeltaSanitizer;
                else if (types[i] == NpcKeyRepairService.class) args[i] = npcKeyRepairService;
                else if (types[i] == TurnGuardService.class) args[i] = turnGuardService;
                else args[i] = mock(types[i]);
            }
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new IllegalStateException("装配 GameLoopService 失败", e);
        }
    }

    private GameState gameState(int turnCount) {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setWorldId("world-1");
        gs.setPlayerCharId("char-1");
        gs.setTurnCount(turnCount);
        gs.setCurrentLocation("清州城");
        gs.setFlags("{}");
        gs.setNpcStates("{}");
        return gs;
    }

    @Test
    void 首个回合产生turn0初始快照() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(0));

        GameLoopService.TurnContext ctx = service.prepareTurn(GS_ID, "我走进酒馆");

        // 租户身份随 ToolContext 显式传播（SSE/异步链路的租户载体，tenancy spec）
        assertThat(ctx.toolContext())
                .containsEntry("gameStateId", GS_ID)
                .containsEntry(TenantContext.TOOL_CONTEXT_KEY, "t-test");

        // N=1，快照 turn = N-1 = 0（GameState 创建后的初始态）
        verify(snapshotRepo).upsert(any(GameState.class), eq(0));
    }

    @Test
    void 快照先于本轮playerAction事件日志() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(2));

        service.prepareTurn(GS_ID, "我走进酒馆");

        InOrder inOrder = inOrder(snapshotRepo, eventRepo);
        inOrder.verify(snapshotRepo).upsert(any(GameState.class), eq(2));
        inOrder.verify(eventRepo).insert(any());
    }

    @Test
    void 后续轮快照turn为N减一() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(5));

        service.prepareTurn(GS_ID, "继续赶路");

        // N=6，快照 turn = 5（第 5 轮末态）
        verify(snapshotRepo).upsert(any(GameState.class), eq(5));
    }

    @Test
    void 连续两次prepareTurn同turn重复调用同一快照() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(3));

        service.prepareTurn(GS_ID, "第一次行动");
        // 生产环境中守卫由回合 SSE 流终止（Controller doFinally）释放；
        // 此处模拟上一回合流终止后再重发
        turnGuardService.release(GS_ID);
        service.prepareTurn(GS_ID, "重新生成的重发");

        // 重新生成场景：turnCount 未推进，两次都写 turn=3 同一份快照（仓储层 DELETE+INSERT 覆盖）
        verify(snapshotRepo, times(2)).upsert(any(GameState.class), eq(3));
    }

    @Test
    void 每次快照后触发保留窗口清理() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(1));

        service.prepareTurn(GS_ID, "行动");

        verify(snapshotRepo).prune(GS_ID, RpgGameStateSnapshotRepository.KEEP_COUNT);
        assertThat(RpgGameStateSnapshotRepository.KEEP_COUNT).isEqualTo(4);
    }

    @Test
    void 快照失败降级告警不阻断回合() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(1));
        doThrow(new RuntimeException("磁盘故障")).when(snapshotRepo).upsert(any(GameState.class), anyInt());

        assertThatCode(() -> service.prepareTurn(GS_ID, "行动"))
                .doesNotThrowAnyException();

        // 回合主流程照常推进
        verify(eventRepo).insert(any());
        verify(stateRepo, times(0)).updateState(anyString(), any(), anyInt(), any());
    }

    // ==================== prepareTurn 回合互斥守卫（design D11） ====================

    @Test
    void 守卫下prepareTurn重复调用被拒() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(1));

        service.prepareTurn(GS_ID, "第一次行动");

        // 第一个回合持有守卫期间：重复调用被拒（双开标签/连点场景）
        assertThatThrownBy(() -> service.prepareTurn(GS_ID, "第二次行动"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前有回合正在进行");

        // 守卫释放（生产环境由回合流终止 doFinally 触发）后可再次发起
        turnGuardService.release(GS_ID);
        assertThatCode(() -> service.prepareTurn(GS_ID, "重发行动")).doesNotThrowAnyException();
    }

    @Test
    void prepareTurn异常时守卫自释放() {
        when(stateRepo.findById(GS_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.prepareTurn(GS_ID, "行动"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游戏状态未找到");

        // 调用方未拿到回合：守卫不得残留
        assertThat(turnGuardService.isInTurn(GS_ID)).isFalse();
    }

    // ==================== finalizeTurn: npc_states key 校验/修复 ====================

    private GameState stateWithNpcStates(int turnCount, String npcStates) {
        GameState gs = gameState(turnCount);
        gs.setNpcStates(npcStates);
        return gs;
    }

    private GameLoopService.TurnContext turn(int newTurn, Set<String> validNpcKeys) {
        return new GameLoopService.TurnContext(
                null, "userPrompt", Map.of(), List.of(), false, newTurn, validNpcKeys);
    }

    private JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /** 捕获 updateState 落库的 npcStates JSON */
    private String savedNpcStates() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stateRepo).updateState(eq(GS_ID), captor.capture(), anyInt(), anyString());
        return captor.getValue();
    }

    @Test
    void 全合法轮不触发修复调用() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithNpcStates(2, "{\"苏枕雪\":{\"status\":\"戒备\"}}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"location_change": "桃溪渡口",
                 "npc_states": {"苏枕雪": {"status": "提议合作"}}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述");

        service.finalizeTurn(GS_ID, "gm-output",
                turn(3, Set.of("苏枕雪")));

        verify(npcKeyRepairService, never()).repair(any(), any(), any(), any());
        String saved = savedNpcStates();
        assertThat(saved).contains("苏枕雪").contains("提议合作").doesNotContain("gray_robed_servant");
        verify(stateRepo).updateState(eq(GS_ID), any(), eq(3), eq("桃溪渡口"));
    }

    @Test
    void 非法key轮修复成功后以汉字key入库() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithNpcStates(2, "{\"苏枕雪\":{\"status\":\"戒备\"}}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"location_change": "渡口",
                 "npc_states": {"gray_robed_servant": {"status": "引路"},
                                "苏枕雪": {"status": "合作"}}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("灰袍仆人提灯引路。");
        when(npcKeyRepairService.repair(any(), any(), any(), any()))
                .thenReturn(json("{\"灰袍仆人\": {\"status\": \"引路\"}}"));

        service.finalizeTurn(GS_ID, "gm-output",
                turn(3, Set.of("苏枕雪")));

        String saved = savedNpcStates();
        assertThat(saved)
                .contains("灰袍仆人")
                .contains("引路")
                .contains("苏枕雪")
                .doesNotContain("gray_robed_servant");
        // state_change 事件存修复后的 state_delta，content 附注修复标记
        ArgumentCaptor<com.luyu.agent.rpg.model.EventLog> eventCaptor =
                ArgumentCaptor.forClass(com.luyu.agent.rpg.model.EventLog.class);
        verify(eventRepo, times(1)).insert(eventCaptor.capture());
        var stateEvent = eventCaptor.getAllValues().stream()
                .filter(e -> "state_change".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(stateEvent.getContent()).contains("修复");
        assertThat(stateEvent.getStateDelta()).contains("灰袍仆人").doesNotContain("gray_robed_servant");
    }

    @Test
    void 修复失败轮非法条目被丢弃且主流程照常() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithNpcStates(2, "{\"苏枕雪\":{\"status\":\"戒备\"}}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"location_change": "渡口",
                 "npc_states": {"gray_robed_servant": {"status": "引路"},
                                "苏枕雪": {"status": "合作"}}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("灰袍仆人提灯引路。");
        when(npcKeyRepairService.repair(any(), any(), any(), any())).thenReturn(null);

        service.finalizeTurn(GS_ID, "gm-output",
                turn(3, Set.of("苏枕雪")));

        // 非法条目丢弃，合法条目照常合并；位置/轮次/事件照常
        String saved = savedNpcStates();
        assertThat(saved).contains("苏枕雪").doesNotContain("gray_robed_servant");
        verify(stateRepo).updateState(eq(GS_ID), any(), eq(3), eq("渡口"));
        ArgumentCaptor<com.luyu.agent.rpg.model.EventLog> eventCaptor =
                ArgumentCaptor.forClass(com.luyu.agent.rpg.model.EventLog.class);
        verify(eventRepo, times(1)).insert(eventCaptor.capture());
        var stateEvent = eventCaptor.getAllValues().stream()
                .filter(e -> "state_change".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(stateEvent.getContent()).contains("丢弃");
    }

    // ==================== finalizeTurn: player 节点合并（rpg-player-memory 设计 D2） ====================

    /** 捕获 updatePlayerStates 落库的 player_states JSON */
    private String savedPlayerStates() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stateRepo).updatePlayerStates(eq(GS_ID), captor.capture());
        return captor.getValue();
    }

    private GameState stateWithPlayerStates(int turnCount, String playerStates) {
        GameState gs = gameState(turnCount);
        gs.setPlayerStates(playerStates);
        return gs;
    }

    @Test
    void 金钱按增量累加() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithPlayerStates(2, "{\"money\": 100, \"titles\": [\"旧称号\"]}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"location_change": "渡口",
                 "player": {"money": -30}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("你花 30 两买了匹马。");

        service.finalizeTurn(GS_ID, "gm-output", turn(3, Set.of()));

        String saved = savedPlayerStates();
        assertThat(saved).contains("\"money\":70").contains("旧称号");
        // state_change 事件 content 附 player 变更摘要
        ArgumentCaptor<com.luyu.agent.rpg.model.EventLog> eventCaptor =
                ArgumentCaptor.forClass(com.luyu.agent.rpg.model.EventLog.class);
        verify(eventRepo, times(1)).insert(eventCaptor.capture());
        var stateEvent = eventCaptor.getAllValues().stream()
                .filter(e -> "state_change".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(stateEvent.getContent()).contains("player变更").contains("money");
    }

    @Test
    void 驼峰键playerStates同样生效() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithPlayerStates(2, "{\"money\": 100}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"playerStates": {"titles": [\"燕云小剑客\"]}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述。");

        service.finalizeTurn(GS_ID, "gm-output", turn(3, Set.of()));

        assertThat(savedPlayerStates())
                .contains("燕云小剑客").contains("\"money\":100");
    }

    @Test
    void 无player节点时状态不变且不落库() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithPlayerStates(2, "{\"money\": 100}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"location_change": "渡口"}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述。");

        service.finalizeTurn(GS_ID, "gm-output", turn(3, Set.of()));

        verify(stateRepo, never()).updatePlayerStates(anyString(), any());
    }

    @Test
    void titles数组整体替换与自由键覆盖合并() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithPlayerStates(2,
                        "{\"titles\": [\"旧称号\"], \"封地\": \"旧庄\", \"abilities\": {\"轻功\": \"娴熟\"}}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"player": {"titles": [\"燕云小剑客\"],
                            "封地": "新庄",
                            "abilities": {"剑阵": "入门"}}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述。");

        service.finalizeTurn(GS_ID, "gm-output", turn(3, Set.of()));

        String saved = savedPlayerStates();
        // titles 整体替换：旧称号不再保留
        assertThat(saved).contains("燕云小剑客").doesNotContain("旧称号");
        // 自由键覆盖：封地新值替换旧值；abilities 同键覆盖且未提及键保留
        assertThat(saved).contains("新庄").doesNotContain("旧庄");
        assertThat(saved).contains("剑阵").contains("轻功");
    }

    @Test
    void 无现值时金钱以零为基数累加() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithPlayerStates(2, null));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"player": {"money": 50}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述。");

        service.finalizeTurn(GS_ID, "gm-output", turn(3, Set.of()));

        assertThat(savedPlayerStates()).contains("\"money\":50");
    }
}
