package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.config.RpgMemoryProperties;
import com.luyu.agent.rpg.model.EventLog;
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

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GameLoopService#finalizeTurn} 回传 {@link GameLoopService.TurnOutcome} 的单元测试
 * （rpg-hud-bubble-components design D3 / tasks 5.1）。
 * <p>
 * 回传契约：stateDeltaJson 为 6b 校验/修复后的序列化原文，与 rpg_event_log 落库的
 * state_change 快照同源同值（尾包 state 与落库所见即所存）；6g 插入时对齐键
 * session_event_id 为 NULL（由 turn_persisted 尾包侧回填，D4——finalizeTurn 执行时
 * assistant 事件尚未可靠落会话）；无 state_delta / 存档缺失时整体回传 null 容忍。
 */
class GameLoopServiceTurnOutcomeTest {

    private static final String GS_ID = "gs-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RpgGameStateRepository stateRepo;
    private RpgEventLogRepository eventRepo;
    private RpgGameStateSnapshotRepository snapshotRepo;
    private StateDeltaExtractor stateDeltaExtractor;
    private StateDeltaSanitizer stateDeltaSanitizer;
    private NpcKeyRepairService npcKeyRepairService;
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
        service = newService(new MemorySnapshotStore(tempDir.resolve("saves"), tempDir.resolve("snapshots")));
        TenantContext.set("u-test", "t-test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 通过反射按既有（唯一）构造器装配，避免本测试随依赖演进频繁改构造行（同 SnapshotTest 模式）。 */
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
                else if (types[i] == StarLorebookScanner.class) {
                    StarLorebookScanner scanner = mock(StarLorebookScanner.class);
                    when(scanner.scanAndCollect(anyString(), anyString(), anyInt()))
                            .thenReturn(StarLorebookScanner.ScanOutcome.empty());
                    args[i] = scanner;
                }
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
        gs.setNpcStates("{\"苏枕雪\":{\"status\":\"戒备\"}}");
        return gs;
    }

    private GameLoopService.TurnContext turn(int newTurn) {
        return new GameLoopService.TurnContext(
                null, "userPrompt", Map.of(), List.of(), false, newTurn, Set.of("苏枕雪"));
    }

    private JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /** 捕获落库的 state_change 事件 */
    private EventLog capturedStateEvent() {
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventRepo, times(1)).insert(captor.capture());
        return captor.getAllValues().stream()
                .filter(e -> "state_change".equals(e.getEventType()))
                .findFirst().orElseThrow();
    }

    @Test
    void 有state_delta轮回传修复后原文且与落库快照一致() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(2));
        // 含非法 key：6b 校验修复链生效后回传修复版本
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"location_change": "桃溪渡口",
                 "npc_states": {"gray_robed_servant": {"status": "引路"},
                                "苏枕雪": {"status": "提议合作"}},
                 "choices": [{"label": "跟上去", "quote": "我跟你走", "hint": null}]}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("灰袍仆人提灯引路。");
        when(npcKeyRepairService.repair(any(), any(), any(), any()))
                .thenReturn(json("{\"灰袍仆人\": {\"status\": \"引路\"}, \"苏枕雪\": {\"status\": \"提议合作\"}}"));

        GameLoopService.TurnOutcome outcome =
                service.finalizeTurn(GS_ID, "gm-output", turn(3));

        assertThat(outcome).isNotNull();
        assertThat(outcome.turn()).isEqualTo(3);
        // 回传修复后原文：非法 key 已被替换，展示字段（choices）原样透传
        assertThat(outcome.stateDeltaJson())
                .contains("灰袍仆人").contains("提议合作")
                .doesNotContain("gray_robed_servant");
        // 与落库快照同源同值（D3 单一真源：尾包 state == EventLog state_change）
        EventLog stateEvent = capturedStateEvent();
        assertThat(stateEvent.getStateDelta()).isEqualTo(outcome.stateDeltaJson());
        assertThat(stateEvent.getTurn()).isEqualTo(3);
        // 6g 插入时对齐键 NULL（D4：session_event_id 由尾包侧回填，此处不可达）
        assertThat(stateEvent.getSessionEventId()).isNull();
    }

    @Test
    void 无state_delta轮回传null容忍且不落状态事件() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(2));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(null);
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述。");

        GameLoopService.TurnOutcome outcome =
                service.finalizeTurn(GS_ID, "gm-output", turn(3));

        // 容忍：turn 照常回传，stateDeltaJson 为 null（尾包省略 state 字段，D8 降级）
        assertThat(outcome).isNotNull();
        assertThat(outcome.turn()).isEqualTo(3);
        assertThat(outcome.stateDeltaJson()).isNull();
        // 不落 state_change 事件（无 delta 可存）
        verify(eventRepo, times(0)).insert(any(EventLog.class));
    }

    @Test
    void 存档缺失回传null() {
        when(stateRepo.findById(GS_ID)).thenReturn(null);

        GameLoopService.TurnOutcome outcome =
                service.finalizeTurn(GS_ID, "gm-output", turn(3));

        assertThat(outcome).isNull();
        verify(eventRepo, times(0)).insert(any(EventLog.class));
        verify(stateRepo, times(0)).updateState(anyString(), any(), anyInt(), any());
    }

    @Test
    void 全合法轮回传原文不经修复服务() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(2));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json("""
                {"location_change": "渡口",
                 "npc_states": {"苏枕雪": {"status": "合作", "mind": "此人可靠。"}}}
                """));
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述。");

        GameLoopService.TurnOutcome outcome =
                service.finalizeTurn(GS_ID, "gm-output", turn(3));

        verify(npcKeyRepairService, times(0)).repair(any(), any(), any(), any());
        // 展示字段（mind）随 delta 原样透传：引擎不读不校验（spec 处理边界）
        assertThat(outcome.stateDeltaJson()).contains("渡口").contains("合作").contains("此人可靠");
        assertThat(capturedStateEvent().getStateDelta()).isEqualTo(outcome.stateDeltaJson());
    }
}
