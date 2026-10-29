package com.luyu.agent.rpg.story.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.EventLog;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryRun;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.RpgStoryRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * StoryRunEngine 开局物化 + 边结算 + 物化缓存 + 选项混排 + 轨道闸 + 图上回溯 + 多周目测试（story-graph-runtime
 * tasks 4.1/4.2/4.4/4.5/4.7/4.8/4.9，spec「开局图快照与运行副本隔离」「边结算确定性」
 * 「节点正文物化与变体优先级链」缓存段、「选项混排与即兴三档」
 * 「轨道闸（工程性收束）」）。
 * <p>
 * 钉住：graphId 缺省零增量（无图 world 行为与现状一致）、图不存在/未整稿
 * IAE（控制层 400）、入口节点 = 首幕最小序号（非列表首个）、story_run
 * 落行字段完整（玩家游走只写运行副本、模板层只读不写）、on_enter 效果
 * 结算语义（数值叠加/缺失从 0 起算/非数值覆盖/无效果不动）；
 * 边结算：守卫不满足的边不出选项（边 guard + 目标 entry_guard 双过滤）、
 * 选边防御复验、边 effects + 目标 on_enter 结算推进运行副本、跨幕拼接边同权；
 * 物化缓存：同路径命中复用、路径不一致强制重物化（walkedEdges 序列比对）、
 * 存档级强制重物化配置恒不命中、幂等覆盖、坏 JSON 防御恒不命中不抛；
 * 选项混排三档：off 仅图边、锚点不被即兴绕过、free 图退化为建议、
 * 节点级 improvise 覆盖作品级、mode 缺省归 bounded、未挂图零增量 null；
 * 轨道闸：跨幕结算后源幕轨道表求值（命中静默记事件、发散暂存收敛指令
 * 指向最近轨道、幕内边与源幕无轨道表不闸、求值用结算后状态视图）；
 * 图上回溯：确定性重放恢复分叉点状态（基线+入口 on_enter+保留边链）、
 * 幂等一致、环取最近到达、目标未走拒绝、物化缓存保留、分叉点列表；
 * 多周目：通关判定（当前节点非结局拒绝）、运行副本重置回模板初始态、
 * 缓存复用/强制重物化配置、开局基线跨周目不变。
 */
@ExtendWith(MockitoExtension.class)
class StoryRunEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 首幕节点故意乱序（n1_03 在前）：钉「入口 = 幕内最小序号而非列表首个」 */
    private static final String ONE_ACT_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "convergeTo": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
               "nodes": [
                 {"id": "n1_03", "type": "scene", "beat": "中段场景"},
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸",
                  "onEnter": {"trust_信长": 10, "信长初印象": "警惕"}},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_03", "label": "深入"},
                 {"id": "e1_02", "from": "n1_03", "to": "n1_07", "label": "收束"}]}
            ]
            """;

    /** 入口节点无 on_enter（缺省路径：不动 playerStates） */
    private static final String PLAIN_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "convergeTo": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_07", "label": "收束"}]}
            ]
            """;

    /** 负值效果（数值语义：缺失从 0 起算 → -5） */
    private static final String NEGATIVE_EFFECT_JSON = ONE_ACT_JSON
            .replace("\"onEnter\": {\"trust_信长\": 10, \"信长初印象\": \"警惕\"}",
                     "\"onEnter\": {\"trust_信长\": -5}");

    /** 边结算靶（4.2）：e1_01 带边 guard+effects，e1_03 靠目标节点 entry_guard 过滤 */
    private static final String GUARDED_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "convergeTo": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                 {"id": "n1_03", "type": "scene", "beat": "密谈",
                  "entryGuard": {"key": "trust_信长", "op": ">=", "value": 60},
                  "onEnter": {"信长初印象": "动摇"}},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_03", "label": "示好",
                  "quote": "在下愿为织田家效力",
                  "guard": {"key": "trust_信长", "op": ">=", "value": 60},
                  "effects": {"trust_信长": 5}},
                 {"id": "e1_02", "from": "n1_01", "to": "n1_07", "label": "潜入",
                  "quote": "我不动声色"},
                 {"id": "e1_03", "from": "n1_01", "to": "n1_03", "label": "试探",
                  "quote": "敢问殿下下一步"}]}
            ]
            """;

    /** 跨幕拼接边（4.2）：x1_01 由引擎拼接挂在源幕 edges，目标在邻幕 */
    private static final String CROSS_ACT_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "convergeTo": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_07", "label": "收束",
                  "quote": "按计划行事"},
                 {"id": "x1_01", "from": "n1_07", "to": "n2_01", "label": "入幕二",
                  "quote": "夜色渐深"}]},
              {"id": "act2", "theme": "终局",
               "convergeTo": [{"orbit": "终局", "guard": {"key": "trust_信长", "op": ">=", "value": 30}}],
               "nodes": [
                 {"id": "n2_01", "type": "scene", "beat": "清洲密议",
                  "onEnter": {"trust_信长": -5}},
                 {"id": "n2_05", "type": "ending", "beat": "终局"}],
               "edges": [
                 {"id": "e2_01", "from": "n2_01", "to": "n2_05", "label": "摊牌"}]}
            ]
            """;

    /** 选项混排三档（4.5）：n1_03 节点级 improvise=false；n1_07 锚点带出边（锚点用例有边可出） */
    private static final String OPTIONS_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田郎"},
                 {"id": "n1_03", "type": "scene", "beat": "密谈", "improvise": false},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_03", "label": "示好",
                  "quote": "在下愿为织田家效力"},
                 {"id": "e1_02", "from": "n1_01", "to": "n1_07", "label": "潜入",
                  "quote": "我不动声色"},
                 {"id": "e1_03", "from": "n1_07", "to": "n1_01", "label": "回环",
                  "quote": "事后再访"}]}
            ]
            """;

    @Mock
    private RpgStoryGraphRepository graphRepo;

    @Mock
    private RpgStoryRunRepository runRepo;

    @Mock
    private RpgGameStateRepository stateRepo;

    @Mock
    private RpgEventLogRepository eventRepo;

    private StoryRunEngine engine;

    @BeforeEach
    void setUp() {
        // entryNode 依赖真实确定性规则（最小序号）：validator 用真实例；
        // guard 求值用真实求值器（纯函数）
        engine = new StoryRunEngine(graphRepo, runRepo, stateRepo, eventRepo,
                new StoryGraphValidator(), new GuardEvaluator());
    }

    private static GameState gs(String playerStates) {
        GameState state = new GameState();
        state.setId("gs-1");
        state.setPlayerStates(playerStates);
        return state;
    }

    private static StoryGraph graph(String graphJson) {
        StoryGraph g = new StoryGraph("sg-1", "t-a", "w-1", "夜雾纪行");
        g.setGraphJson(graphJson);
        return g;
    }

    /** 选项混排用：带即兴档位的图（缺省构造已归 bounded，显式传便于三档切换） */
    private static StoryGraph graph(String graphJson, String improviseMode) {
        StoryGraph g = graph(graphJson);
        g.setImproviseMode(improviseMode);
        return g;
    }

    private Map<String, Object> readStates(GameState state) throws Exception {
        return MAPPER.readValue(state.getPlayerStates(),
                MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    // ==================== 零增量（spec「无故事图的 world 行为零增量」） ====================

    @Test
    void graphId缺省返回null零交互无图world行为与现状一致() {
        assertThat(engine.bootstrapForGame(gs("{}"), null)).isNull();
        assertThat(engine.bootstrapForGame(gs("{}"), "   ")).isNull();
        verifyNoInteractions(graphRepo, runRepo, stateRepo);
    }

    // ==================== 拒绝路径（控制层映射 400） ====================

    @Test
    void 图不存在或跨租户拒绝开局且零写入() {
        when(graphRepo.findById("sg-404")).thenReturn(null);

        assertThatThrownBy(() -> engine.bootstrapForGame(gs("{}"), "sg-404"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        verifyNoInteractions(runRepo, stateRepo);
    }

    @Test
    void 空图未整稿拒绝开局() {
        when(graphRepo.findById("sg-1")).thenReturn(graph("[]"));

        assertThatThrownBy(() -> engine.bootstrapForGame(gs("{}"), "sg-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入口");
        verifyNoInteractions(runRepo, stateRepo);
    }

    @Test
    void 首幕无节点拒绝开局() {
        when(graphRepo.findById("sg-1"))
                .thenReturn(graph("[{\"id\": \"act1\", \"nodes\": [], \"edges\": []}]"));

        assertThatThrownBy(() -> engine.bootstrapForGame(gs("{}"), "sg-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入口");
        verifyNoInteractions(runRepo, stateRepo);
    }

    // ==================== 正常开局（spec「开局图快照与运行副本隔离」） ====================

    @Test
    void 正常开局落运行副本入口取首幕最小序号节点() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(ONE_ACT_JSON));

        StoryRun run = engine.bootstrapForGame(gs("{}"), "sg-1");

        assertThat(run).isNotNull();
        assertThat(run.getCurrentNode()).isEqualTo("n1_01");
        ArgumentCaptor<StoryRun> captor = ArgumentCaptor.forClass(StoryRun.class);
        verify(runRepo).save(captor.capture());
        StoryRun saved = captor.getValue();
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getGameStateId()).isEqualTo("gs-1");
        assertThat(saved.getGraphId()).isEqualTo("sg-1");
        assertThat(saved.getCurrentNode()).isEqualTo("n1_01");
        assertThat(saved.getWalkedEdges()).isEqualTo("[]");
        assertThat(saved.getMaterializedBodies()).isEqualTo("{}");
        assertThat(saved.getImproviseDepth()).isZero();
        assertThat(saved.getRematerialize()).isFalse();
        assertThat(saved.getInitialPlayerStates()).isEqualTo("{}"); // 开局基线 = 挂图结算前原值（4.8）
        // 开局只读模板层，永不写模板（spec「游走不影响模板图」的结构性保证）
        verify(graphRepo).findById("sg-1");
        verifyNoMoreInteractions(graphRepo);
    }

    /** spec Scenario「游走不影响模板图」后半钉子：一局游走只写运行副本，第二次开局获得的图与首次开局时点一致（模板层只读） */
    @Test
    void 第二次开局获得的图与首次开局时点一致() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(ONE_ACT_JSON));
        StoryRun first = engine.bootstrapForGame(gs("{}"), "sg-1");
        String firstEntry = first.getCurrentNode();
        String firstWalked = first.getWalkedEdges();
        String firstBodies = first.getMaterializedBodies();

        // 一局游走痕迹（内联模拟：只写运行副本 run1，模板层不碰）
        first.setCurrentNode("n1_07");
        first.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        runRepo.update(first);

        // 第二次开局（新存档）：同一模板图，副本初始态与首次开局时点一致
        GameState gs2 = new GameState();
        gs2.setId("gs-2");
        gs2.setPlayerStates("{}");
        StoryRun second = engine.bootstrapForGame(gs2, "sg-1");

        assertThat(second.getCurrentNode()).isEqualTo(firstEntry);
        assertThat(second.getWalkedEdges()).isEqualTo(firstWalked);
        assertThat(second.getMaterializedBodies()).isEqualTo(firstBodies);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        // 模板层只读：全程仅两次 findById，无任何写交互（游走不污染模板的结构性保证）
        verify(graphRepo, times(2)).findById("sg-1");
        verifyNoMoreInteractions(graphRepo);
    }

    @Test
    void 入口onEnter数值效果叠加现值字符串覆盖并落库() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(ONE_ACT_JSON));
        GameState state = gs("{\"trust_信长\": 45, \"信长初印象\": \"中立\"}");

        engine.bootstrapForGame(state, "sg-1");

        // 数值加减：45 + 10 = 55；非数值覆盖：「中立」→「警惕」
        Map<String, Object> states = readStates(state);
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(55L);
        assertThat(states.get("信长初印象")).isEqualTo("警惕");
        verify(stateRepo).updatePlayerStates(eq("gs-1"), eq(state.getPlayerStates()));
        // 开局基线 = 结算前原始值（4.8 重放起点），而非结算后的 55/警惕
        ArgumentCaptor<StoryRun> saveCaptor = ArgumentCaptor.forClass(StoryRun.class);
        verify(runRepo).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().getInitialPlayerStates())
                .isEqualTo("{\"trust_信长\": 45, \"信长初印象\": \"中立\"}");
    }

    @Test
    void 数值效果现值缺失从零起算支持负值() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(NEGATIVE_EFFECT_JSON));
        GameState state = gs("{}");

        engine.bootstrapForGame(state, "sg-1");

        Map<String, Object> states = readStates(state);
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(-5L);
    }

    @Test
    void 现值为非数值时数值效果从零起算覆盖() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(ONE_ACT_JSON));
        GameState state = gs("{\"trust_信长\": \"未知\"}");

        engine.bootstrapForGame(state, "sg-1");

        // 现值非数值（无法数值叠加）：base 0 + delta 10，覆盖原字符串值
        Map<String, Object> states = readStates(state);
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(10L);
    }

    @Test
    void playerStates为null时onEnter从空态结算() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(ONE_ACT_JSON));
        GameState state = gs(null);

        engine.bootstrapForGame(state, "sg-1");

        Map<String, Object> states = readStates(state);
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(10L);
        assertThat(states.get("信长初印象")).isEqualTo("警惕");
    }

    @Test
    void 入口无onEnter不动playerStates不落库() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(PLAIN_JSON));
        GameState state = gs("{\"money\": 70}");

        StoryRun run = engine.bootstrapForGame(state, "sg-1");

        assertThat(run).isNotNull();
        assertThat(state.getPlayerStates()).isEqualTo("{\"money\": 70}");
        verify(stateRepo, never()).updatePlayerStates(any(), any());
        verify(runRepo).save(any(StoryRun.class));
    }

    // ==================== 边结算（tasks 4.2，spec「边结算确定性」） ====================

    private static StoryRun runAt(String graphId, String currentNode) {
        StoryRun run = new StoryRun();
        run.setId("run-1");
        run.setGameStateId("gs-1");
        run.setGraphId(graphId);
        run.setCurrentNode(currentNode);
        run.setWalkedEdges("[]");
        return run;
    }

    private static List<String> edgeIds(List<StoryEdge> options) {
        return options.stream().map(StoryEdge::getId).toList();
    }

    /** spec 钉子：守卫不满足的边不出现（trust 45 vs >= 60；目标入口守卫同滤） */
    @Test
    void 守卫不满足的边不出选项() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");

        List<StoryEdge> options = engine.outgoingOptions(run, gs("{\"trust_信长\": 45}"));

        // e1_01（边 guard >=60）与 e1_03（目标 n1_03 entryGuard >=60）均被滤，仅剩无守卫 e1_02
        assertThat(edgeIds(options)).containsExactly("e1_02");
    }

    @Test
    void 守卫满足时守卫边与目标入口守卫边均出现() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");

        List<StoryEdge> options = engine.outgoingOptions(run, gs("{\"trust_信长\": 65}"));

        assertThat(edgeIds(options)).containsExactly("e1_01", "e1_02", "e1_03");
    }

    /** 防御：图不存在/未开图 → 空列表（GM 即兴兑底，不抛） */
    @Test
    void 出选项图不存在或未开图返回空() {
        when(graphRepo.findById("sg-1")).thenReturn(null);
        assertThat(engine.outgoingOptions(runAt("sg-1", "n1_01"), gs("{}"))).isEmpty();
        assertThat(engine.outgoingOptions(runAt(null, null), gs("{}"))).isEmpty();
        assertThat(engine.outgoingOptions(null, gs("{}"))).isEmpty();
    }

    /** spec 钉子：效果结算与 GM 叙述解耦——选边后数值确定性生效（GM 是否提及无关） */
    @Test
    void 选边结算边效果与目标onEnter推进运行副本() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");
        GameState state = gs("{\"trust_信长\": 65}");

        StoryNode target = engine.settleEdge(run, state, "e1_01");

        // 边 effects（trust +5）+ 目标 on_enter（覆盖信长初印象）：65 → 70
        assertThat(target.getId()).isEqualTo("n1_03");
        Map<String, Object> states = readStates(state);
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(70L);
        assertThat(states.get("信长初印象")).isEqualTo("动摇");
        // 运行副本前进：当前节点 + 已走边追加
        assertThat(run.getCurrentNode()).isEqualTo("n1_03");
        assertThat(run.getWalkedEdges()).isEqualTo("[\"e1_01\"]");
        verify(runRepo).update(run);
        verify(stateRepo).updatePlayerStates(eq("gs-1"), eq(state.getPlayerStates()));
    }

    @Test
    void 无效果的边不动playerStates但推进运行副本() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");
        GameState state = gs("{\"trust_信长\": 45}");

        StoryNode target = engine.settleEdge(run, state, "e1_02");

        assertThat(target.getId()).isEqualTo("n1_07");
        assertThat(state.getPlayerStates()).isEqualTo("{\"trust_信长\": 45}");
        assertThat(run.getCurrentNode()).isEqualTo("n1_07");
        verify(runRepo).update(run);
        verify(stateRepo, never()).updatePlayerStates(any(), any());
    }

    @Test
    void 边不从当前节点出发拒绝不可跳选或重放他边() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");

        assertThatThrownBy(() -> engine.settleEdge(run, gs("{\"trust_信长\": 65}"), "e1_01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不从当前节点");
        verifyNoInteractions(runRepo, stateRepo);
    }

    @Test
    void 边不存在拒绝结算() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");

        assertThatThrownBy(() -> engine.settleEdge(run, gs("{}"), "e9_99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
        verifyNoInteractions(runRepo, stateRepo);
    }

    /** 竞态防御：选项生成后状态变化（65 → 45），结算时守卫复验拒绝 */
    @Test
    void 选边时守卫不满足拒绝结算() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");

        assertThatThrownBy(() -> engine.settleEdge(run, gs("{\"trust_信长\": 45}"), "e1_01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("守卫不满足");
        verifyNoInteractions(runRepo, stateRepo);
    }

    /** 目标节点入口守卫不满足：无守卫的边也进不去（model spec「不可经任何边进入」） */
    @Test
    void 目标入口守卫不满足拒绝结算() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");

        // e1_03 无边守卫，但目标 n1_03 entryGuard >=60 不满足
        assertThatThrownBy(() -> engine.settleEdge(run, gs("{\"trust_信长\": 45}"), "e1_03"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("入口守卫");
        verifyNoInteractions(runRepo, stateRepo);
    }

    /** 跨幕拼接边同权：结算跨幕边 → 目标节点在邻幕，其 on_enter 生效 */
    @Test
    void 跨幕拼接边结算目标节点在邻幕onEnter生效() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(CROSS_ACT_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");
        GameState state = gs("{\"trust_信长\": 65}");

        StoryNode target = engine.settleEdge(run, state, "x1_01");

        assertThat(target.getId()).isEqualTo("n2_01");
        assertThat(target.getBeat()).isEqualTo("清洲密议");
        assertThat(((Number) readStates(state).get("trust_信长")).longValue()).isEqualTo(60L);
        assertThat(run.getCurrentNode()).isEqualTo("n2_01");
        assertThat(run.getWalkedEdges()).isEqualTo("[\"x1_01\"]");
    }

    /** 已走边按游走顺序有序追加（确定性重放的结算序列基础） */
    @Test
    void 连续游走已走边按序追加() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(CROSS_ACT_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");
        GameState state = gs("{}");

        engine.settleEdge(run, state, "e1_01");
        assertThat(run.getWalkedEdges()).isEqualTo("[\"e1_01\"]");

        engine.settleEdge(run, state, "x1_01");
        assertThat(run.getWalkedEdges()).isEqualTo("[\"e1_01\",\"x1_01\"]");
        assertThat(run.getCurrentNode()).isEqualTo("n2_01");
    }

    /** 存档未开图（currentNode null）选边硬拒 */
    @Test
    void 未开图选边拒绝() {
        StoryRun run = runAt("sg-1", null);

        assertThatThrownBy(() -> engine.settleEdge(run, gs("{}"), "e1_01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能选边");
    }

    // ==================== 物化缓存（tasks 4.4，spec 缓存段） ====================

    /** 同路径到达：缓存命中直接复用（省 GM 调用） */
    @Test
    void 同路径到达缓存命中返回正文() {
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\"]");

        engine.storeBody(run, "n1_07", "烛火摇曳，殿中对酌……");

        // walkedEdges 未变（同一路径再次到达）：命中
        assertThat(engine.cachedBody(run, "n1_07")).isEqualTo("烛火摇曳，殿中对酌……");
    }

    /** spec Scenario「不同来路强制重新物化」：回溯后经另一条边重到同节点，路径序列必变 → 不命中 */
    @Test
    void 路径不一致强制重新物化() {
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\"]");
        engine.storeBody(run, "n1_07", "正文A");

        // 回溯重进：walkedEdges 变为另一条路径
        run.setWalkedEdges("[\"e1_02\"]");

        assertThat(engine.cachedBody(run, "n1_07")).isNull();
    }

    /** 开局首节点（空 walkedEdges）同样按路径一致性判定：序列一旦非空即不命中 */
    @Test
    void 开局空路径缓存后路径增长不命中() {
        StoryRun run = runAt("sg-1", "n1_01"); // walkedEdges "[]"
        engine.storeBody(run, "n1_01", "开局正文");

        assertThat(engine.cachedBody(run, "n1_01")).isEqualTo("开局正文");

        run.setWalkedEdges("[\"e1_01\"]");
        assertThat(engine.cachedBody(run, "n1_01")).isNull();
    }

    /** 未缓存节点恒不命中 */
    @Test
    void 未缓存节点返回null() {
        assertThat(engine.cachedBody(runAt("sg-1", "n1_07"), "n1_07")).isNull();
        assertThat(engine.cachedBody(null, "n1_07")).isNull();
        assertThat(engine.cachedBody(runAt("sg-1", "n1_07"), null)).isNull();
    }

    /** 4.9 存档级强制重物化配置：即便路径一致也恒不复用缓存 */
    @Test
    void 强制重物化配置恒不命中() {
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\"]");
        engine.storeBody(run, "n1_07", "正文A");

        run.setRematerialize(true);

        assertThat(engine.cachedBody(run, "n1_07")).isNull();
    }

    /** 写缓存落库运行副本，JSON 结构含节点键、正文与 16 位 hex 路径指纹 */
    @Test
    void 写缓存落库且JSON结构完整() {
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\"]");

        engine.storeBody(run, "n1_07", "正文A");

        verify(runRepo).update(run);
        assertThat(run.getMaterializedBodies())
                .contains("\"n1_07\"")
                .contains("\"body\":\"正文A\"")
                .containsPattern("\"pathHash\":\"[0-9a-f]{16}\"");
    }

    /** 幂等覆盖：同节点重物化即覆盖旧条目（旧正文不再命中） */
    @Test
    void 同节点重物化覆盖旧条目() {
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\"]");

        engine.storeBody(run, "n1_07", "正文A");
        engine.storeBody(run, "n1_07", "正文B");

        verify(runRepo, times(2)).update(run);
        assertThat(engine.cachedBody(run, "n1_07")).isEqualTo("正文B");
    }

    /** 坏 materialized_bodies 防御：恒不命中不抛；写入则从空重建 */
    @Test
    void 坏JSON防御恒不命中且重建() {
        StoryRun run = runAt("sg-1", "n1_07");
        run.setMaterializedBodies("not-json");

        assertThat(engine.cachedBody(run, "n1_07")).isNull();

        engine.storeBody(run, "n1_07", "重建后的正文");
        assertThat(engine.cachedBody(run, "n1_07")).isEqualTo("重建后的正文");
    }

    /** 空正文不写入（空物化无缓存价值） */
    @Test
    void 空白正文不写入零落库() {
        StoryRun run = runAt("sg-1", "n1_07");

        engine.storeBody(run, "n1_07", "  ");

        verifyNoInteractions(runRepo);
        assertThat(run.getMaterializedBodies()).isNull();
    }

    // ==================== 选项混排三档（tasks 4.5，spec「选项混排与即兴三档」） ====================

    /** spec Scenario「off 模式仅图边」：即兴关闭、图边照常出（双守卫过滤） */
    @Test
    void off模式即兴关闭图边照常() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(runAt("sg-1", "n1_01"));
        when(stateRepo.findById("gs-1")).thenReturn(gs("{}"));
        when(graphRepo.findById("sg-1")).thenReturn(graph(OPTIONS_GRAPH_JSON, "off"));

        StoryRunEngine.StoryOptionsView view = engine.storyOptions("gs-1");

        assertThat(view.mode()).isEqualTo("off");
        assertThat(view.nodeId()).isEqualTo("n1_01");
        assertThat(view.improviseAllowed()).isFalse();
        assertThat(edgeIds(view.edges())).containsExactly("e1_01", "e1_02");
    }

    /** spec Scenario「锚点不被即兴绕过」：bounded 允许即兴，锚点强制图边（其出边恒为图边） */
    @Test
    void 锚点强制图边不被即兴绕过() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(runAt("sg-1", "n1_07"));
        when(stateRepo.findById("gs-1")).thenReturn(gs("{}"));
        when(graphRepo.findById("sg-1")).thenReturn(graph(OPTIONS_GRAPH_JSON, "bounded"));

        StoryRunEngine.StoryOptionsView view = engine.storyOptions("gs-1");

        assertThat(view.improviseAllowed()).isFalse();
        assertThat(edgeIds(view.edges())).containsExactly("e1_03");
    }

    /** spec Scenario「free 模式图退化为建议」：不出边按钮（状态/回溯不受影响），即兴照常 */
    @Test
    void free模式图退化为建议不出边() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(runAt("sg-1", "n1_01"));
        when(stateRepo.findById("gs-1")).thenReturn(gs("{}"));
        when(graphRepo.findById("sg-1")).thenReturn(graph(OPTIONS_GRAPH_JSON, "free"));

        StoryRunEngine.StoryOptionsView view = engine.storyOptions("gs-1");

        assertThat(view.mode()).isEqualTo("free");
        assertThat(view.edges()).isEmpty();
        assertThat(view.improviseAllowed()).isTrue();
    }

    /** 节点级 improvise 覆盖作品级；mode 缺省归 bounded（模型默认档） */
    @Test
    void 节点级improvise覆盖作品级且mode缺省归bounded() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(runAt("sg-1", "n1_03"));
        when(stateRepo.findById("gs-1")).thenReturn(gs("{}"));
        when(graphRepo.findById("sg-1")).thenReturn(graph(OPTIONS_GRAPH_JSON));

        StoryRunEngine.StoryOptionsView view = engine.storyOptions("gs-1");

        assertThat(view.mode()).isEqualTo("bounded");
        assertThat(view.improviseAllowed()).isFalse(); // n1_03 节点级 false 覆盖
    }

    /** 零增量：未挂图 → null（无图 world 选项区与现状完全一致） */
    @Test
    void storyOptions未挂图零增量null() {
        assertThat(engine.storyOptions("gs-1")).isNull();
        verifyNoInteractions(stateRepo, graphRepo);
    }

    /** 零增量：存档缺失防御 → null */
    @Test
    void storyOptions存档缺失零增量null() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(runAt("sg-1", "n1_01"));
        when(stateRepo.findById("gs-1")).thenReturn(null);

        assertThat(engine.storyOptions("gs-1")).isNull();
        verifyNoInteractions(graphRepo);
    }

    /** 选边入口（gameTurn 接线）：存档缺失 IAE（控制层 SSE error） */
    @Test
    void 选边入口存档缺失拒绝() {
        when(stateRepo.findById("gs-404")).thenReturn(null);

        assertThatThrownBy(() -> engine.settleEdgeForGame("gs-404", "e1_01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游戏状态未找到");
        verifyNoInteractions(runRepo);
    }

    /** 选边入口按存档定位 run 后结算推进（gameTurn 传 gameStateId 不传 run） */
    @Test
    void 选边入口按存档定位run后结算() {
        when(stateRepo.findById("gs-1")).thenReturn(gs("{\"trust_信长\": 65}"));
        when(runRepo.findByGameStateId("gs-1")).thenReturn(runAt("sg-1", "n1_01"));
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));

        StoryNode target = engine.settleEdgeForGame("gs-1", "e1_01");

        assertThat(target.getId()).isEqualTo("n1_03");
        verify(runRepo).update(any(StoryRun.class));
    }

    // ==================== 锚点引力计数（tasks 4.6，spec「锚点引力与即兴深度上限」） ====================

    /** spec Scenario「走图边即重置计数」：选边结算同步清零（第 3 轮走图边 → 计数 0） */
    @Test
    void 走图边即重置即兴深度计数() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GUARDED_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");
        run.setImproviseDepth(3); // 前三轮纯即兴累计

        engine.settleEdge(run, gs("{\"trust_信长\": 65}"), "e1_01");

        assertThat(run.getImproviseDepth()).isZero();
        verify(runRepo).update(run);
    }

    /** 纯即兴轮 bounded 计数 +1（spec「bounded 模式下 SHALL 计数」） */
    @Test
    void 纯即兴轮bounded计数递增() {
        StoryRun run = runAt("sg-1", "n1_01");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(graphRepo.findById("sg-1")).thenReturn(graph(OPTIONS_GRAPH_JSON, "bounded"));

        engine.recordTurnOutcome("gs-1", false);

        assertThat(run.getImproviseDepth()).isEqualTo(1);
        verify(runRepo).update(run);
    }

    /** 连续纯即兴计数可超过上限（第 6 轮完成 depth=6：第 7 轮物化注入收敛指令） */
    @Test
    void 连续纯即兴计数可超过上限() {
        StoryRun run = runAt("sg-1", "n1_01");
        run.setImproviseDepth(5);
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(graphRepo.findById("sg-1")).thenReturn(graph(OPTIONS_GRAPH_JSON, "bounded"));

        engine.recordTurnOutcome("gs-1", false);

        assertThat(run.getImproviseDepth()).isEqualTo(6);
        verify(runRepo).update(run);
    }

    /** off/free 模式即兴轮不计数（置 0） */
    @Test
    void 非bounded模式即兴轮不计数() {
        StoryRun run = runAt("sg-1", "n1_01");
        run.setImproviseDepth(2);
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(graphRepo.findById("sg-1")).thenReturn(graph(OPTIONS_GRAPH_JSON, "free"));

        engine.recordTurnOutcome("gs-1", false);

        assertThat(run.getImproviseDepth()).isZero();
        verify(runRepo).update(run);
    }

    /** 走图边轮记账幂等不写库（settleEdge 已同步置 0；仅历史数据非零时补写） */
    @Test
    void 走图边轮记账幂等不写库() {
        StoryRun run = runAt("sg-1", "n1_01");
        run.setImproviseDepth(0); // settleEdge 推进时已同步置 0
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);

        engine.recordTurnOutcome("gs-1", true);

        assertThat(run.getImproviseDepth()).isZero();
        verify(runRepo, never()).update(any());
        verifyNoInteractions(graphRepo);
    }

    /** 零增量：未挂图（无 run）不计数不落库 */
    @Test
    void 记账未挂图零增量() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(null);

        engine.recordTurnOutcome("gs-1", false);

        verify(runRepo, never()).update(any());
        verifyNoInteractions(graphRepo);
    }

    // ==================== 轨道闸（tasks 4.7，spec「轨道闸（工程性收束）」） ====================

    /**
     * 轨道闸靶图：act1 双档出口轨道（信任线 >=60 / 怀疑线 <=40），跨幕边
     * e1_09 n1_07 → n2_01。trust 65 命中信任线；trust 55 两档均不中 → 发散
     * （最近轨道 = 信任线：55 距 60 差 5 < 距 40 差 15）。
     */
    private static final String GATE_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "convergeTo": [
                 {"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}},
                 {"orbit": "怀疑线", "guard": {"key": "trust_信长", "op": "<=", "value": 40}}],
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_07", "label": "示好",
                  "quote": "在下愿为织田家效力"},
                 {"id": "e1_09", "from": "n1_07", "to": "n2_01", "label": "入幕二",
                  "quote": "夜色渐深"}]},
              {"id": "act2", "theme": "终局",
               "convergeTo": [{"orbit": "终局", "guard": {"key": "trust_信长", "op": ">=", "value": 30}}],
               "nodes": [
                 {"id": "n2_01", "type": "scene", "beat": "清洲密议"},
                 {"id": "n2_05", "type": "ending", "beat": "终局"}],
               "edges": [
                 {"id": "e2_01", "from": "n2_01", "to": "n2_05", "label": "摊牌"}]}
            ]
            """;

    /** 源幕无出口轨道表（convergeTo 缺省）：跨幕边也不闸 */
    private static final String NO_ORBIT_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_07", "label": "示好",
                  "quote": "在下愿为织田家效力"},
                 {"id": "e1_09", "from": "n1_07", "to": "n2_01", "label": "入幕二",
                  "quote": "夜色渐深"}]},
              {"id": "act2", "theme": "终局",
               "nodes": [
                 {"id": "n2_01", "type": "scene", "beat": "清洲密议"},
                 {"id": "n2_05", "type": "ending", "beat": "终局"}],
               "edges": [
                 {"id": "e2_01", "from": "n2_01", "to": "n2_05", "label": "摊牌"}]}
            ]
            """;

    /** spec Scenario「轨道命中静默通过」：trust 65 命中信任线 → 无指令 + 事件日志记命中轨道 */
    @Test
    void 跨幕轨道命中静默通过并记录事件() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GATE_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");
        GameState state = gs("{\"trust_信长\": 65}");
        state.setTurnCount(3);

        engine.settleEdge(run, state, "e1_09");

        assertThat(run.getPendingGate()).isNull();
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventRepo).insert(captor.capture());
        assertThat(captor.getValue().getGameStateId()).isEqualTo("gs-1");
        assertThat(captor.getValue().getTurn()).isEqualTo(3);
        assertThat(captor.getValue().getEventType()).isEqualTo("story_orbit_hit");
        assertThat(captor.getValue().getContent())
                .contains("act1")
                .contains("信任线");
    }

    /** spec Scenario「状态发散被收敛指令拉回」：trust 55 两档均不中 → 暂存指令（最近轨道=信任线）+ 发散事件 */
    @Test
    void 跨幕状态发散暂存收敛指令并记录事件() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GATE_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");

        engine.settleEdge(run, gs("{\"trust_信长\": 55}"), "e1_09");

        // 55 距信任线（>=60）差 5、距怀疑线（<=40）差 15 → 最近轨道 = 信任线
        assertThat(run.getPendingGate())
                .contains("【轨道收敛】")
                .contains("act1")
                .contains("信任线")
                .contains("trust_信长 >= 60");
        verify(runRepo).update(run); // 指令随结算 update 落库（一次性，物化消费即清）
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventRepo).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("story_orbit_diverge");
        assertThat(captor.getValue().getContent())
                .contains("act1")
                .contains("信任线");
    }

    /** 幕内边（e1_01 同在 act1）不闸：轨道闸只在跨幕结算点求值 */
    @Test
    void 幕内边不触发轨道闸() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GATE_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_01");

        engine.settleEdge(run, gs("{\"trust_信长\": 55}"), "e1_01");

        assertThat(run.getPendingGate()).isNull();
        verify(eventRepo, never()).insert(any());
    }

    /** 源幕无出口轨道表（convergeTo 缺省）：跨幕边静默通过零事件 */
    @Test
    void 源幕无轨道表不闸() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(NO_ORBIT_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");

        engine.settleEdge(run, gs("{\"trust_信长\": 55}"), "e1_09");

        assertThat(run.getPendingGate()).isNull();
        verify(eventRepo, never()).insert(any());
    }

    /** 求值时点 = 结算后状态视图：e1_09 带边 effects trust+10（55+10=65 命中；结算前 55 本发散） */
    @Test
    void 轨道求值用结算后状态() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(GATE_GRAPH_JSON
                .replace("\"label\": \"入幕二\"", "\"label\": \"入幕二\", \"effects\": {\"trust_信长\": 10}")));
        StoryRun run = runAt("sg-1", "n1_07");

        engine.settleEdge(run, gs("{\"trust_信长\": 55}"), "e1_09");

        assertThat(run.getPendingGate()).isNull();
        ArgumentCaptor<EventLog> captor = ArgumentCaptor.forClass(EventLog.class);
        verify(eventRepo).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("story_orbit_hit");
    }

    // ==================== 图上回溯（tasks 4.8，spec「图上回溯与确定性重放」） ====================

    /**
     * 回溯靶图：入口 n1_01（onEnter trust+10）→ e1_01(+5) → n1_03（onEnter
     * 初印象=动摇）→ e1_02(+3) → n1_07 锚点；e1_04(n1_03→n1_07, -2) 平行边；
     * e1_05(n1_07→n1_03, +7) 回环（钉「重复到达取最近一次」）。
     */
    private static final String BACKTRACK_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸",
                  "onEnter": {"trust_信长": 10}},
                 {"id": "n1_03", "type": "scene", "beat": "密谈",
                  "onEnter": {"信长初印象": "动摇"}},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_03", "label": "示好",
                  "effects": {"trust_信长": 5}},
                 {"id": "e1_02", "from": "n1_03", "to": "n1_07", "label": "收束",
                  "effects": {"trust_信长": 3}},
                 {"id": "e1_04", "from": "n1_03", "to": "n1_07", "label": "潜入",
                  "effects": {"trust_信长": -2}},
                 {"id": "e1_05", "from": "n1_07", "to": "n1_03", "label": "回环",
                  "effects": {"trust_信长": 7}}]}
            ]
            """;

    /** spec Scenario「回退到分叉点状态正确重建」：确定性重放 = 基线+入口 on_enter+保留边链，被回退边效果不再生效 */
    @Test
    void 回退到分叉点状态正确重建() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(BACKTRACK_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        run.setInitialPlayerStates("{\"trust_信长\": 50}");
        run.setMaterializedBodies("{\"n1_07\":{\"pathHash\":\"h9\",\"narration\":\"夜谈\"}}");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(stateRepo.findById("gs-1")).thenReturn(gs("{\"trust_信长\": 68}")); // 旧路径态（50+10+5+3）

        StoryRunEngine.BacktrackResult result = engine.backtrackToNode("gs-1", "n1_03");

        // 重放：基线 50 → 入口 onEnter +10 → e1_01 +5 → n1_03 onEnter 覆盖初印象；
        // e1_02 的 +3 属被回退路径，不再生效（spec「先前路径的效果不再生效」）
        Map<String, Object> states = readStates(gs(result.playerStates()));
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(65L);
        assertThat(states.get("信长初印象")).isEqualTo("动摇");
        // 被回退边移出已走集合 + 位置回退 + 重放态覆盖落库
        assertThat(run.getCurrentNode()).isEqualTo("n1_03");
        assertThat(run.getWalkedEdges()).isEqualTo("[\"e1_01\"]");
        assertThat(result.node().getId()).isEqualTo("n1_03");
        verify(stateRepo).updatePlayerStates(eq("gs-1"), eq(result.playerStates()));
        verify(runRepo).update(run);
        // 物化正文缓存保留不清（spec：按到达路径 hash 规则自然失效，4.4）
        assertThat(run.getMaterializedBodies())
                .isEqualTo("{\"n1_07\":{\"pathHash\":\"h9\",\"narration\":\"夜谈\"}}");
    }

    /** spec Scenario「重放结果与逐步结算一致」：同存档两次相同回退 → 重放态完全一致（确定性链路） */
    @Test
    void 重放结果与逐步结算一致() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(BACKTRACK_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        run.setInitialPlayerStates("{\"trust_信长\": 50}");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(stateRepo.findById("gs-1")).thenReturn(gs("{\"trust_信长\": 68}"));

        String first = engine.backtrackToNode("gs-1", "n1_03").playerStates();
        String second = engine.backtrackToNode("gs-1", "n1_03").playerStates();

        assertThat(second).isEqualTo(first);
        verify(runRepo, times(2)).update(run);
    }

    /** 回退到入口节点：空前缀重放 = 基线 + 入口 on_enter（walkedEdges 清空） */
    @Test
    void 回退到入口节点空路径重放() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(BACKTRACK_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        run.setInitialPlayerStates("{\"trust_信长\": 50}");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(stateRepo.findById("gs-1")).thenReturn(gs("{\"trust_信长\": 68}"));

        engine.backtrackToNode("gs-1", "n1_01");

        Map<String, Object> states = readStates(gs(engine.backtrackToNode("gs-1", "n1_01").playerStates()));
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(60L);
        assertThat(run.getCurrentNode()).isEqualTo("n1_01");
        assertThat(run.getWalkedEdges()).isEqualTo("[]");
    }

    /** 环上重复到达同节点：截断取最近一次到达（e1_05 回环后回退 n1_03 不早截） */
    @Test
    void 环上重复到达取最近一次() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(BACKTRACK_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_03");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\",\"e1_05\"]");
        run.setInitialPlayerStates("{\"trust_信长\": 50}");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(stateRepo.findById("gs-1")).thenReturn(gs("{}"));

        Map<String, Object> states = readStates(gs(engine.backtrackToNode("gs-1", "n1_03").playerStates()));

        // cut = 2（e1_05 最近到达 n1_03）：已走集合不截断，重放含回环边 50+10+5+3+7 = 75
        assertThat(run.getWalkedEdges()).isEqualTo("[\"e1_01\",\"e1_02\",\"e1_05\"]");
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(75L);
    }

    /** 防御：目标不在已走路径上（不可跳选未走过节点）→ IAE 控制层映射 400 */
    @Test
    void 回退目标不在已走路径上拒绝() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(BACKTRACK_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);

        assertThatThrownBy(() -> engine.backtrackToNode("gs-1", "n1_99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在已走路径");
        verify(runRepo, never()).update(any());
    }

    /** 防御：未挂图（无 run）→ IAE（对齐选边归控形态） */
    @Test
    void 未挂图回退拒绝() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(null);

        assertThatThrownBy(() -> engine.backtrackToNode("gs-1", "n1_01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未挂故事图");
    }

    /** 分叉点列表（回退 UI 数据源）：入口 + 已走边 to 按游走序，带拍子与来路边 label */
    @Test
    void 分叉点列表含入口与已走节点() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(BACKTRACK_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_07");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);

        List<StoryRunEngine.StoryForkView> forks = engine.walkedForks("gs-1");

        assertThat(forks).extracting(StoryRunEngine.StoryForkView::nodeId)
                .containsExactly("n1_01", "n1_03", "n1_07");
        assertThat(forks.get(1).beat()).isEqualTo("密谈");
        assertThat(forks.get(1).arrivedViaLabel()).isEqualTo("示好");
        assertThat(forks.get(0).nodeType()).isEqualTo("scene");
        assertThat(forks.get(2).nodeType()).isEqualTo("anchor");
    }

    /** 环重复到达：去重保留最近到达（重插游走尾序，来路 = 最近到达边） */
    @Test
    void 分叉点列表环上去重() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(BACKTRACK_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_03");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\",\"e1_05\"]");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);

        List<StoryRunEngine.StoryForkView> forks = engine.walkedForks("gs-1");

        assertThat(forks).extracting(StoryRunEngine.StoryForkView::nodeId)
                .containsExactly("n1_01", "n1_07", "n1_03");
        assertThat(forks.get(2).arrivedViaLabel()).isEqualTo("回环");
    }

    /** 未挂图 → 空列表（无回退点零增量） */
    @Test
    void 未挂图分叉点空列表() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(null);

        assertThat(engine.walkedForks("gs-1")).isEmpty();
    }

    // ==================== 多周目（tasks 4.9，spec「多周目同骨架不同游走」） ====================

    /**
     * 多周目靶图：入口 n1_01（onEnter trust+10）→ e1_01(+5) → n1_03 →
     * e1_02(+3) → n1_09 结局（通关判定靶）。
     */
    private static final String CYCLE_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸",
                  "onEnter": {"trust_信长": 10}},
                 {"id": "n1_03", "type": "scene", "beat": "密谈"},
                 {"id": "n1_09", "type": "ending", "beat": "终局"}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_03", "label": "示好",
                  "effects": {"trust_信长": 5}},
                 {"id": "e1_02", "from": "n1_03", "to": "n1_09", "label": "终局",
                  "effects": {"trust_信长": 3}}]}
            ]
            """;

    /** spec Scenario「二周目获得干净图」：已走边空、回入口、缓存按配置处理、一局游走痕迹不带入 */
    @Test
    void 通关存档开新周目干净开局() throws Exception {
        when(graphRepo.findById("sg-1")).thenReturn(graph(CYCLE_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_09");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        run.setInitialPlayerStates("{\"trust_信长\": 50}");
        run.setMaterializedBodies("{\"n1_03\":{\"pathHash\":\"h3\",\"narration\":\"密谈\"}}");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(stateRepo.findById("gs-1")).thenReturn(gs("{\"trust_信长\": 68}")); // 一局终态（50+10+5+3）

        StoryRunEngine.BacktrackResult result = engine.startNewCycle("gs-1", null);

        // 干净重放：基线 50 + 入口 onEnter +10 = 60（一局路径效果 +5/+3 不带入）
        Map<String, Object> states = readStates(gs(result.playerStates()));
        assertThat(((Number) states.get("trust_信长")).longValue()).isEqualTo(60L);
        assertThat(result.node().getId()).isEqualTo("n1_01");
        // 新运行副本 = 模板初始态（非上次运行副本游走痕迹）
        assertThat(run.getCurrentNode()).isEqualTo("n1_01");
        assertThat(run.getWalkedEdges()).isEqualTo("[]");
        assertThat(run.getImproviseDepth()).isZero();
        assertThat(run.getRematerialize()).isFalse();
        // 复用配置（缺省）：一局物化缓存保留（同路径重走命中省 GM 调用）
        assertThat(run.getMaterializedBodies())
                .isEqualTo("{\"n1_03\":{\"pathHash\":\"h3\",\"narration\":\"密谈\"}}");
        // 同模板图的开局基线跨周目不变（UPDATE 不携带该列的 4.8 语义）
        assertThat(run.getInitialPlayerStates()).isEqualTo("{\"trust_信长\": 50}");
        verify(stateRepo).updatePlayerStates(eq("gs-1"), eq(result.playerStates()));
        verify(runRepo).update(run);
    }

    /** spec Scenario「强制重物化配置生效」：true → 一局缓存清空 + rematerialize 恒不命中双保险 */
    @Test
    void 强制重物化配置生效() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(CYCLE_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_09");
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        run.setInitialPlayerStates("{\"trust_信长\": 50}");
        run.setMaterializedBodies("{\"n1_09\":{\"pathHash\":\"h9\",\"narration\":\"终局\"}}");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(stateRepo.findById("gs-1")).thenReturn(gs("{}"));

        engine.startNewCycle("gs-1", true);

        // 缓存清空（不复用一局缓存）+ 恒不命中开关
        assertThat(run.getMaterializedBodies()).isEqualTo("{}");
        assertThat(run.getRematerialize()).isTrue();
        assertThat(run.getCurrentNode()).isEqualTo("n1_01");
    }

    /** 复用（缺省）配置：二周目重走同路径 → 一局物化缓存命中（省 GM 调用）；强制周目恒不命中 */
    @Test
    void 复用配置二周目同路径缓存命中() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(CYCLE_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_03");
        run.setWalkedEdges("[\"e1_01\"]"); // 一局到达 n1_03 的路径
        run.setInitialPlayerStates("{\"trust_信长\": 50}");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);
        when(stateRepo.findById("gs-1")).thenReturn(gs("{}"));
        engine.storeBody(run, "n1_03", "密谈夜谈正文"); // 一局物化（真链路，pathHash=hash(["e1_01"])）

        run.setCurrentNode("n1_09"); // 通关
        run.setWalkedEdges("[\"e1_01\",\"e1_02\"]");
        engine.startNewCycle("gs-1", null); // 二周目：复用配置
        run.setWalkedEdges("[\"e1_01\"]"); // 二周目重走同路径

        assertThat(engine.cachedBody(run, "n1_03")).isEqualTo("密谈夜谈正文"); // 命中省调用

        run.setCurrentNode("n1_09"); // 模拟二周目通关
        engine.startNewCycle("gs-1", true); // 强制重物化周目
        run.setWalkedEdges("[\"e1_01\"]");

        assertThat(engine.cachedBody(run, "n1_03")).isNull(); // 缓存已清 + 开关恒不命中
    }

    /** 防御：未通关存档（当前节点非结局）拒绝开新周目 → IAE 控制层映射 400 */
    @Test
    void 未通关存档拒绝开新周目() {
        when(graphRepo.findById("sg-1")).thenReturn(graph(CYCLE_GRAPH_JSON));
        StoryRun run = runAt("sg-1", "n1_03"); // 中途存档（scene 非结局）
        run.setWalkedEdges("[\"e1_01\"]");
        when(runRepo.findByGameStateId("gs-1")).thenReturn(run);

        assertThatThrownBy(() -> engine.startNewCycle("gs-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未通关");
        verify(runRepo, never()).update(any());
        verify(stateRepo, never()).updatePlayerStates(any(), any());
    }

    /** 防御：未挂图（无 run）→ IAE（对齐 backtrack 归控形态） */
    @Test
    void 未挂图开新周目拒绝() {
        when(runRepo.findByGameStateId("gs-1")).thenReturn(null);

        assertThatThrownBy(() -> engine.startNewCycle("gs-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未挂故事图");
    }
}
