package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.config.RpgMemoryProperties;
import com.luyu.agent.rpg.model.CosmoSense;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GameLoopService} 小宇宙成长结算旁路测试（rpg-cosmo-sense tasks 3.4）。
 * <p>
 * 经完整 finalizeTurn 流程钉住五场景：+30 原样累加 / +80 回落∈[1,50] / +450 先截断再回落 /
 * -9999 clamp 主角下界 1 / 非星命存档（base 无 cosmo 键）误写按自由键透传。
 * 反射装配与 turn/json 助手沿用 {@code GameLoopServiceSnapshotTest}。
 */
class GameLoopServiceCosmoTest {

    private static final String GS_ID = "gs-cosmo";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RpgGameStateRepository stateRepo;
    private StateDeltaExtractor stateDeltaExtractor;
    private StateDeltaSanitizer stateDeltaSanitizer;
    private GameLoopService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        stateRepo = mock(RpgGameStateRepository.class);
        RpgEventLogRepository eventRepo = mock(RpgEventLogRepository.class);
        RpgGameStateSnapshotRepository snapshotRepo = mock(RpgGameStateSnapshotRepository.class);
        stateDeltaExtractor = mock(StateDeltaExtractor.class);
        stateDeltaSanitizer = mock(StateDeltaSanitizer.class);
        NpcKeyRepairService npcKeyRepairService = mock(NpcKeyRepairService.class);
        TurnGuardService turnGuardService = new TurnGuardService();
        service = newService(new MemorySnapshotStore(tempDir.resolve("saves"), tempDir.resolve("snapshots")),
                eventRepo, snapshotRepo, npcKeyRepairService, turnGuardService);
        TenantContext.set("u-test", "t-test");
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述。");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private GameLoopService newService(MemorySnapshotStore memoryStore,
                                       RpgEventLogRepository eventRepo,
                                       RpgGameStateSnapshotRepository snapshotRepo,
                                       NpcKeyRepairService npcKeyRepairService,
                                       TurnGuardService turnGuardService) {
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

    private GameLoopService.TurnContext turn(int newTurn) {
        return new GameLoopService.TurnContext(
                null, "userPrompt", Map.of(), List.of(), false, newTurn, Set.of());
    }

    private JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private GameState stateWithPlayerStates(String playerStates) {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setWorldId("world-1");
        gs.setPlayerCharId("char-1");
        gs.setTurnCount(2);
        gs.setCurrentLocation("圣域");
        gs.setFlags("{}");
        gs.setNpcStates("{}");
        gs.setPlayerStates(playerStates);
        return gs;
    }

    private String savedPlayerStates() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stateRepo).updatePlayerStates(eq(GS_ID), captor.capture());
        return captor.getValue();
    }

    private int savedCosmo() throws Exception {
        return MAPPER.readTree(savedPlayerStates()).path("cosmo").asInt();
    }

    @Test
    void 小额正增量原样累加() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(stateWithPlayerStates("{\"cosmo\": 268}"));
        when(stateDeltaExtractor.extract(anyString()))
                .thenReturn(json("{\"location_change\":\"圣域\",\"player\":{\"cosmo\":30}}"));

        service.finalizeTurn(GS_ID, "gm-output", turn(3));

        assertThat(savedCosmo()).isEqualTo(298);
    }

    @Test
    void 超50正增量回落留账在1到50() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(stateWithPlayerStates("{\"cosmo\": 268}"));
        when(stateDeltaExtractor.extract(anyString()))
                .thenReturn(json("{\"location_change\":\"圣域\",\"player\":{\"cosmo\":80}}"));

        service.finalizeTurn(GS_ID, "gm-output", turn(3));

        // 落账 = 268 + [1,50] → [269, 318]
        assertThat(savedCosmo()).isBetween(269, 318);
    }

    @Test
    void 超400先截断再回落() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(stateWithPlayerStates("{\"cosmo\": 268}"));
        when(stateDeltaExtractor.extract(anyString()))
                .thenReturn(json("{\"location_change\":\"圣域\",\"player\":{\"cosmo\":450}}"));

        service.finalizeTurn(GS_ID, "gm-output", turn(3));

        // 450 截断为 400，仍 >50 触发回落 → 268 + [1,50] → [269, 318]
        assertThat(savedCosmo()).isBetween(269, 318);
    }

    @Test
    void 巨额负增量clamp主角下界1() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(stateWithPlayerStates("{\"cosmo\": 120}"));
        when(stateDeltaExtractor.extract(anyString()))
                .thenReturn(json("{\"location_change\":\"圣域\",\"player\":{\"cosmo\":-9999}}"));

        service.finalizeTurn(GS_ID, "gm-output", turn(3));

        assertThat(savedCosmo()).isEqualTo(CosmoSense.PLAYER_MIN);
    }

    @Test
    void 非星命存档误写cosmo按自由键透传() throws Exception {
        // base 无 cosmo 键（非星命存档）：GM 误写 cosmo 应原样落档，不累加不 clamp 不回落
        when(stateRepo.findById(GS_ID)).thenReturn(stateWithPlayerStates("{\"money\": 100}"));
        when(stateDeltaExtractor.extract(anyString()))
                .thenReturn(json("{\"location_change\":\"都市\",\"player\":{\"cosmo\":50}}"));

        service.finalizeTurn(GS_ID, "gm-output", turn(3));

        String saved = savedPlayerStates();
        assertThat(MAPPER.readTree(saved).path("cosmo").asInt()).isEqualTo(50);
        assertThat(saved).contains("\"money\":100");
    }

    @Test
    void cosmo结算不污染同轮其余player键合并() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithPlayerStates("{\"cosmo\": 268, \"money\": 100, \"titles\": [\"旧称号\"]}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(json(
                "{\"location_change\":\"圣域\",\"player\":{\"cosmo\":30,\"money\":-30,\"titles\":[\"新称号\"]}}"));

        service.finalizeTurn(GS_ID, "gm-output", turn(3));

        String saved = savedPlayerStates();
        assertThat(MAPPER.readTree(saved).path("cosmo").asInt()).isEqualTo(298);
        assertThat(saved).contains("\"money\":70").contains("新称号").doesNotContain("旧称号");
    }
}
