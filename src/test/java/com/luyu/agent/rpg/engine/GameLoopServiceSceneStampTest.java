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

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code stampScenePresence}（rpg-scene-cast 步骤6d'）的单元测试。
 * <p>
 * 钉住场景在场盖戳的根基约定（design D3/D4/D5）：
 * <ol>
 *   <li>在场 = 校验后的 {@code scene_npcs} ∪ 本轮 {@code npc_states} 触及 key，其余全量置 false（全量替换）；</li>
 *   <li>{@code scene_npcs} 的非法 key（不在合法集合且无条目）确定性丢弃，不走 LLM 修复；</li>
 *   <li>声明缺失时的降级链：地点变 → 仅 touched；地点未变 → 沿用旧戳 + touched；</li>
 *   <li>GM 回声进 npc_states 条目的 in_scene 被后端盖戳覆盖（惰性）；</li>
 *   <li>npc_states 非法 JSON 等任何异常不抛出、不中断回合。</li>
 * </ol>
 */
class GameLoopServiceSceneStampTest {

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
                else args[i] = mock(types[i]);
            }
            return ctor.newInstance(args);
        } catch (Exception e) {
            throw new IllegalStateException("装配 GameLoopService 失败", e);
        }
    }

    private GameState stateWithNpcStates(String npcStates) {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setWorldId("world-1");
        gs.setPlayerCharId("char-1");
        gs.setTurnCount(2);
        gs.setCurrentLocation("清州城");
        gs.setNpcStates(npcStates);
        return gs;
    }

    private GameLoopService.TurnContext turn(Set<String> validNpcKeys) {
        return new GameLoopService.TurnContext(
                null, "userPrompt", Map.of(), List.of(), false, 3, validNpcKeys);
    }

    private JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /** 捕获 updateState 落库的 npcStates 并解析为树，便于断言 in_scene 布尔值 */
    private JsonNode savedNpcTree() throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stateRepo).updateState(eq(GS_ID), captor.capture(), anyInt(), anyString());
        return MAPPER.readTree(captor.getValue());
    }

    private void stub(String gmOutput, JsonNode stateDelta) {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithNpcStates("{\"张猛\":{\"status\":\"对峙\",\"in_scene\":true},"
                        + "\"慕雨眠\":{\"status\":\"旁观\",\"in_scene\":true},"
                        + "\"老铁匠\":{\"status\":\"打铁\",\"in_scene\":false},"
                        + "\"鬼门人\":{\"status\":\"离场\",\"in_scene\":false}}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(stateDelta);
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述");
    }

    @Test
    void 声明与触及的并集在场_其余全量置false() throws Exception {
        stub("gm-output", json("""
                {"location_change": "清州城",
                 "scene_npcs": ["张猛", "慕雨眠"],
                 "npc_states": {"慕雨眠": {"status": "旁观"}, "老铁匠": {"status": "打铁"}}}
                """));

        service.finalizeTurn(GS_ID, "gm-output", turn(Set.of("张猛", "慕雨眠", "老铁匠", "鬼门人")));

        JsonNode saved = savedNpcTree();
        // declared（张猛/慕雨眠）∪ touched（慕雨眠/老铁匠）
        assertThat(saved.path("张猛").path("in_scene").asBoolean()).isTrue();
        assertThat(saved.path("慕雨眠").path("in_scene").asBoolean()).isTrue();
        assertThat(saved.path("老铁匠").path("in_scene").asBoolean()).isTrue();
        assertThat(saved.path("鬼门人").path("in_scene").asBoolean()).isFalse();
        // 盖戳不破坏条目其余字段
        assertThat(saved.path("张猛").path("status").asText()).isEqualTo("对峙");
    }

    @Test
    void scene_npcs非法key确定性丢弃() throws Exception {
        stub("gm-output", json("""
                {"location_change": "清州城",
                 "scene_npcs": ["张猛", "su_zhenxue"],
                 "npc_states": {"张猛": {"status": "对峙"}}}
                """));

        service.finalizeTurn(GS_ID, "gm-output", turn(Set.of("张猛", "慕雨眠", "老铁匠", "鬼门人")));

        JsonNode saved = savedNpcTree();
        assertThat(saved.path("张猛").path("in_scene").asBoolean()).isTrue();
        assertThat(saved.has("su_zhenxue")).isFalse();
        // 未声明未触及的既有条目全量置 false
        assertThat(saved.path("慕雨眠").path("in_scene").asBoolean()).isFalse();
    }

    @Test
    void 声明缺失且换地点_在场降级为仅touched() throws Exception {
        stub("gm-output", json("""
                {"location_change": "桃溪渡口",
                 "npc_states": {"老铁匠": {"status": "打铁"}}}
                """));

        service.finalizeTurn(GS_ID, "gm-output", turn(Set.of()));

        JsonNode saved = savedNpcTree();
        assertThat(saved.path("老铁匠").path("in_scene").asBoolean()).isTrue();
        // 上一幕在场者随换幕全部离场
        assertThat(saved.path("张猛").path("in_scene").asBoolean()).isFalse();
        assertThat(saved.path("慕雨眠").path("in_scene").asBoolean()).isFalse();
        assertThat(saved.path("鬼门人").path("in_scene").asBoolean()).isFalse();
    }

    @Test
    void 声明缺失且未换地点_沿用旧戳加touched() throws Exception {
        stub("gm-output", json("""
                {"location_change": "清州城",
                 "npc_states": {"老铁匠": {"status": "打铁"}}}
                """));

        service.finalizeTurn(GS_ID, "gm-output", turn(Set.of()));

        JsonNode saved = savedNpcTree();
        // 旧在场者保持在场
        assertThat(saved.path("张猛").path("in_scene").asBoolean()).isTrue();
        assertThat(saved.path("慕雨眠").path("in_scene").asBoolean()).isTrue();
        // 本轮触及者追加为在场；旧不在场者保持不在场
        assertThat(saved.path("老铁匠").path("in_scene").asBoolean()).isTrue();
        assertThat(saved.path("鬼门人").path("in_scene").asBoolean()).isFalse();
    }

    @Test
    void GM回声的in_scene被后端盖戳覆盖() throws Exception {
        // GM 在 npc_states 条目里回声 in_scene:false，但 scene_npcs 声明其在场
        stub("gm-output", json("""
                {"location_change": "清州城",
                 "scene_npcs": ["张猛"],
                 "npc_states": {"张猛": {"status": "对峙", "in_scene": false}}}
                """));

        service.finalizeTurn(GS_ID, "gm-output", turn(Set.of("张猛")));

        assertThat(savedNpcTree().path("张猛").path("in_scene").asBoolean()).isTrue();
    }

    @Test
    void scene_npcs驼峰命名同样生效() throws Exception {
        stub("gm-output", json("""
                {"location_change": "清州城",
                 "sceneNpcs": ["张猛"],
                 "npc_states": {}}
                """));

        service.finalizeTurn(GS_ID, "gm-output", turn(Set.of("张猛")));

        JsonNode saved = savedNpcTree();
        assertThat(saved.path("张猛").path("in_scene").asBoolean()).isTrue();
        assertThat(saved.path("慕雨眠").path("in_scene").asBoolean()).isFalse();
    }

    @Test
    void npc_states非法JSON不抛异常主流程照常() {
        when(stateRepo.findById(GS_ID)).thenReturn(stateWithNpcStates("not-json"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(null);
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述");

        assertThatCode(() -> service.finalizeTurn(GS_ID, "gm-output", turn(Set.of())))
                .doesNotThrowAnyException();
        // 主流程照常：位置/轮次照常落库（npc_states 原样保持非法原文）
        verify(stateRepo).updateState(eq(GS_ID), any(), anyInt(), anyString());
    }

    @Test
    void state_delta缺失时沿用旧戳_不中断回合() throws Exception {
        when(stateRepo.findById(GS_ID)).thenReturn(
                stateWithNpcStates("{\"张猛\":{\"status\":\"对峙\",\"in_scene\":true}}"));
        when(stateDeltaExtractor.extract(anyString())).thenReturn(null);
        when(stateDeltaSanitizer.extractNarration(anyString())).thenReturn("叙述");

        assertThatCode(() -> service.finalizeTurn(GS_ID, "gm-output", turn(Set.of())))
                .doesNotThrowAnyException();
        // 无 delta：无 touched、地点未变 → 旧戳原样保留
        assertThat(savedNpcTree().path("张猛").path("in_scene").asBoolean()).isTrue();
    }
}
