package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TriggerEngine#scanHardConditions} 的 player.* 硬条件单元测试（rpg-player-memory）。
 * <p>
 * 覆盖：player_states JSON 字段读取（money 数值阈值、abilities 嵌套路径）、
 * 字段不存在/null/非法 JSON 时条件不通过（比较行为不变）、既有 player.location 分支不受影响。
 */
class TriggerEngineTest {

    private static final String GS_ID = "gs-1";
    private static final String WORLD_ID = "world-1";

    private RpgTriggerRepository triggerRepo;
    private RpgTriggerRuntimeRepository runtimeRepo;
    private TriggerEngine engine;

    @BeforeEach
    void setUp() {
        triggerRepo = mock(RpgTriggerRepository.class);
        runtimeRepo = mock(RpgTriggerRuntimeRepository.class);
        engine = new TriggerEngine(triggerRepo, runtimeRepo);
        when(runtimeRepo.findByGameStateId(GS_ID)).thenReturn(List.of());
    }

    private GameState gameState(String playerStates) {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setWorldId(WORLD_ID);
        gs.setPlayerCharId("char-1");
        gs.setTurnCount(3);
        gs.setCurrentLocation("清州城");
        gs.setPlayerStates(playerStates);
        return gs;
    }

    private Trigger trigger(String hardConditions) {
        Trigger t = new Trigger();
        t.setId("t-1");
        t.setWorldId(WORLD_ID);
        t.setType("event");
        t.setHardConditions(hardConditions);
        t.setAction("rob");
        return t;
    }

    private List<Trigger> scan(String hardConditions, GameState gs) {
        when(triggerRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(trigger(hardConditions)));
        return engine.scanHardConditions(gs);
    }

    @Test
    void 金钱阈值命中时硬条件通过() {
        // player.money >= 100，当前 150 → 通过
        var passed = scan("[{\"field\": \"player.money\", \"op\": \">=\", \"value\": 100}]",
                gameState("{\"money\": 150, \"titles\": [\"燕云小剑客\"]}"));

        assertThat(passed).hasSize(1);
    }

    @Test
    void 金钱低于阈值时硬条件不通过() {
        var passed = scan("[{\"field\": \"player.money\", \"op\": \">=\", \"value\": 100}]",
                gameState("{\"money\": 70}"));

        assertThat(passed).isEmpty();
    }

    @Test
    void 嵌套路径读取能力字段() {
        // player.abilities.剑阵 == 入门
        var passed = scan("[{\"field\": \"player.abilities.剑阵\", \"op\": \"==\", \"value\": \"入门\"}]",
                gameState("{\"abilities\": {\"剑阵\": \"入门\", \"轻功\": \"娴熟\"}}"));

        assertThat(passed).hasSize(1);
    }

    @Test
    void 字段不存在时条件不通过() {
        // player_states 无 money 键 → 值为 null → null 不等于任何期望值
        var passed = scan("[{\"field\": \"player.money\", \"op\": \">=\", \"value\": 100}]",
                gameState("{\"titles\": []}"));

        assertThat(passed).isEmpty();
    }

    @Test
    void playerStates为null或非法JSON时条件不通过() {
        assertThat(scan("[{\"field\": \"player.money\", \"op\": \">=\", \"value\": 100}]",
                gameState(null))).isEmpty();
        assertThat(scan("[{\"field\": \"player.money\", \"op\": \">=\", \"value\": 100}]",
                gameState("不是JSON"))).isEmpty();
    }

    @Test
    void 既有playerLocation分支不受影响() {
        var passed = scan("[{\"field\": \"player.location\", \"op\": \"==\", \"value\": \"清州城\"}]",
                gameState(null));

        assertThat(passed).hasSize(1);
    }

    @Test
    void 数值相等比较走字符串语义() {
        // jsonNodeToObject 把 70 解析为 Integer，actual 为 Integer 70 → toString 相等
        var passed = scan("[{\"field\": \"player.money\", \"op\": \"==\", \"value\": 70}]",
                gameState("{\"money\": 70}"));

        assertThat(passed).hasSize(1);
    }
}
