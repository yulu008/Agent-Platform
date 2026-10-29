package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.model.StoryRun;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.RpgStoryRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * StoryNodeMaterializer 节点物化器测试（story-graph-runtime tasks 4.3，spec
 * 「节点正文物化与变体优先级链」）。
 * <p>
 * 钉住：未挂图/未开图/图不可解析 → null 零增量（软增强不阻塞回合，无图 world
 * 行为与现状一致）；正常块含节点拍子 + 实际来路边 label/quote + 叙述要求
 * （解耦钉子：结构效果已结算、勿在 state_delta 重复加减）；开局无来路省略
 * 来路行；变体优先级链（幕授权 && 第一个 guard 命中用种子 → 全不命中纯即兴；
 * 幕未授权 variants 整体忽略；缺键/坏 playerStates 防御走空视图全不命中）；
 * 来路边终点与当前节点不符的防御省略。
 * <p>
 * guard 求值用真实求值器（纯函数），仓储全 mock。
 */
@ExtendWith(MockitoExtension.class)
class StoryNodeMaterializerTest {

    private static final String GAME_ID = "gs-1";

    /**
     * 变体链靶图：act1 授权变体；n1_07 声明两个变体——
     * 变体1 guard trust>=60（嘉许）、变体2 guard trust>=0（例行公事）。
     * trust 72：两者都命中 → 第一个（变体1）生效；trust 50：仅变体2 命中（回退链）。
     */
    private static final String VARIANT_GRAPH_JSON = """
            [
              {"id": "act1", "theme": "初遇",
               "allowExplicitVariants": true,
               "nodes": [
                 {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                 {"id": "n1_07", "type": "anchor", "beat": "幕末锚点",
                  "variants": [
                    {"guard": {"key": "trust_信长", "op": ">=", "value": 60},
                     "beatVariant": "信长语带嘉许地召见", "bodySeed": "烛火通明，殿内回暖"},
                    {"guard": {"key": "trust_信长", "op": ">=", "value": 0},
                     "beatVariant": "信长例行公事地接见", "bodySeed": "烛影摇曳，气氛平淡"}]}],
               "edges": [
                 {"id": "e1_01", "from": "n1_01", "to": "n1_07", "label": "示好",
                  "quote": "在下愿为织田家效力"}]}
            ]
            """;

    /** 幕未授权：同图但 allowExplicitVariants=false（变体整体被忽略，物化走纯即兴） */
    private static final String UNAUTHORIZED_GRAPH_JSON = VARIANT_GRAPH_JSON
            .replace("\"allowExplicitVariants\": true", "\"allowExplicitVariants\": false");

    @Mock
    private RpgStoryRunRepository runRepo;

    @Mock
    private RpgStoryGraphRepository graphRepo;

    private StoryNodeMaterializer materializer;

    @BeforeEach
    void setUp() {
        materializer = new StoryNodeMaterializer(runRepo, graphRepo, new GuardEvaluator());
    }

    private static GameState gs(String playerStates) {
        GameState state = new GameState();
        state.setId(GAME_ID);
        state.setPlayerStates(playerStates);
        return state;
    }

    private static StoryGraph graph(String graphJson) {
        StoryGraph g = new StoryGraph("sg-1", "t-a", "w-1", "夜雾纪行");
        g.setGraphJson(graphJson);
        return g;
    }

    private static StoryRun runAt(String currentNode, String walkedEdges) {
        StoryRun run = new StoryRun();
        run.setGameStateId(GAME_ID);
        run.setGraphId("sg-1");
        run.setCurrentNode(currentNode);
        run.setWalkedEdges(walkedEdges);
        return run;
    }

    /** 惯用桩：run 存在且已开图（graphId/currentNode 就绪） */
    private void stubRun(StoryRun run, StoryGraph g) {
        when(runRepo.findByGameStateId(GAME_ID)).thenReturn(run);
        when(graphRepo.findById("sg-1")).thenReturn(g);
    }

    // ==================== 零增量（spec「无故事图的 world 行为零增量」） ====================

    @Test
    void 未挂图返回null零交互() {
        when(runRepo.findByGameStateId(GAME_ID)).thenReturn(null);

        assertThat(materializer.buildStoryNodeBlock(GAME_ID, gs("{}"))).isNull();
        verifyNoInteractions(graphRepo);
    }

    @Test
    void 未开图currentNode空返回null() {
        when(runRepo.findByGameStateId(GAME_ID)).thenReturn(runAt(null, "[]"));

        assertThat(materializer.buildStoryNodeBlock(GAME_ID, gs("{}"))).isNull();
        verifyNoInteractions(graphRepo);
    }

    @Test
    void 图不存在返回null软降级不抛() {
        stubRun(runAt("n1_07", "[\"e1_01\"]"), null);

        // 物化是软增强：图被删等异常态降级省略注入，不阻塞游戏回合
        assertThat(materializer.buildStoryNodeBlock(GAME_ID, gs("{}"))).isNull();
    }

    @Test
    void 图数据损坏返回null不抛() {
        stubRun(runAt("n1_07", "[]"), graph("not-json"));

        assertThat(materializer.buildStoryNodeBlock(GAME_ID, gs("{}"))).isNull();
    }

    // ==================== 正常块（spec「节点正文物化与变体优先级链」） ====================

    @Test
    void 正常块含节点拍子来路与叙述要求() {
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 72}"));

        assertThat(block)
                .startsWith("\n\n## 故事图当前位置（结构指示）")
                .contains("【当前节点】n1_07（锚点·必经拍子）")
                .contains("【节点拍子】幕末锚点")
                .contains("【实际来路】")
                .contains("「示好」")
                .contains("「在下愿为织田家效力」")
                // 解耦钉子（spec「效果结算与 GM 叙述解耦」反向要求）：明示勿重复结算
                .contains("勿在 state_delta 中重复加减")
                .contains("state_delta 与 choices 的输出照旧");
    }

    @Test
    void 开局无来路省略来路行() {
        stubRun(runAt("n1_01", "[]"), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 50}"));

        assertThat(block)
                .contains("【节点拍子】夜探织田邸")
                .contains("【当前节点】n1_01（自由场景）")
                .doesNotContain("【实际来路】");
    }

    @Test
    void 来路边终点与当前节点不符时防御省略来路() {
        // walkedEdges 末条 e1_01 的 to 是 n1_07，但当前节点是 n1_01（数据不一致态）
        stubRun(runAt("n1_01", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 50}"));

        assertThat(block).contains("夜探织田邸").doesNotContain("【实际来路】");
    }

    // ==================== 变体优先级链（spec 钉子） ====================

    @Test
    void 变体命中注入种子锁定基调() {
        // trust 72 ≥ 60：变体1 命中（spec Scenario「变体种子锁定基调」）
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 72}"));

        assertThat(block)
                .contains("【变体基调】信长语带嘉许地召见")
                .contains("【正文种子】烛火通明，殿内回暖")
                .doesNotContain("例行公事");
    }

    @Test
    void 第一个命中变体优先回退链用后续命中者() {
        // trust 50：变体1（>=60）不命中、变体2（>=0）命中 → 链回退到变体2
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 50}"));

        assertThat(block)
                .contains("【变体基调】信长例行公事地接见")
                .doesNotContain("嘉许");
    }

    @Test
    void 变体全不命中纯即兴块仍注入位置() {
        // 缺键 trust_信长：两变体 guard 均 false（白名单哲学）→ 纯即兴
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{}"));

        assertThat(block)
                .contains("【节点拍子】幕末锚点")
                .doesNotContain("【变体基调】")
                .doesNotContain("【正文种子】");
    }

    @Test
    void 幕未授权变体整体忽略走纯即兴() {
        // 幕 allowExplicitVariants=false：即便 trust 72 命中变体1 也不出种子（对齐 1.6 校验语义）
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(UNAUTHORIZED_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 72}"));

        assertThat(block)
                .contains("【节点拍子】幕末锚点")
                .doesNotContain("【变体基调】")
                .doesNotContain("嘉许");
    }

    @Test
    void playerStates解析失败防御走空视图变体全不命中() {
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("not-json"));

        assertThat(block)
                .contains("【节点拍子】幕末锚点")
                .doesNotContain("【变体基调】");
    }

    // ==================== 模板层只读（spec「游走不影响模板图」的结构性保证） ====================

    @Test
    void 物化只读模板层零写入() {
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 72}"));

        // 只读两仓储：run 查副本、graph 查模板，无任何写方法调用
        verifyNoMoreInteractions(runRepo, graphRepo);
    }

    // ==================== 锚点引力与深度上限（tasks 4.6，spec「锚点引力与即兴深度上限」） ====================

    /** 引力靶图同 VARIANT_GRAPH_JSON（n1_01 自由场景 + n1_07 锚点带拍）；run 带即兴深度 */
    private static StoryRun runAtDepth(String currentNode, int depth) {
        StoryRun run = runAt(currentNode, "[]");
        run.setImproviseDepth(depth);
        return run;
    }

    /** depth=0（开局/走图边后）无引力行 */
    @Test
    void 即兴深度为零无引力行() {
        stubRun(runAtDepth("n1_01", 0), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{}"));

        assertThat(block).doesNotContain("【锚点引力】").doesNotContain("【收敛指令】");
    }

    /** spec「即兴轮次的增量 prompt SHALL 附带当前幕待完成锚点拍子提示」：depth 1..上限 轻引力 */
    @Test
    void 连续即兴注入轻引力拍子提示() {
        stubRun(runAtDepth("n1_01", 2), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{}"));

        assertThat(block)
                .contains("【锚点引力】")
                .contains("连续 2 轮")
                .contains("「幕末锚点」")
                .doesNotContain("【收敛指令】");
    }

    /** spec Scenario「深度超限触发收敛」：连续 6 轮（默认上限 5）→ 收敛指令引向最近锚点 */
    @Test
    void 深度超默认上限注入收敛指令() {
        stubRun(runAtDepth("n1_01", 6), graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{}"));

        assertThat(block)
                .contains("【收敛指令】")
                .contains("连续 6 轮")
                .contains("上限 5")
                .contains("「幕末锚点」")
                .contains("choices 至少一项导向该主线");
    }

    /** 作品级深度上限可调（自定义 2：depth 3 即收敛） */
    @Test
    void 作品自定义深度上限生效() {
        StoryGraph g = graph(VARIANT_GRAPH_JSON);
        g.setImproviseDepthLimit(2);
        stubRun(runAtDepth("n1_01", 3), g);

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{}"));

        assertThat(block).contains("【收敛指令】").contains("上限 2");
    }

    /** off 模式无引力（无即兴可计数） */
    @Test
    void 非bounded模式无引力() {
        StoryGraph off = graph(VARIANT_GRAPH_JSON);
        off.setImproviseMode(StoryGraph.IMPROVISE_OFF);
        stubRun(runAtDepth("n1_01", 6), off);

        assertThat(materializer.buildStoryNodeBlock(GAME_ID, gs("{}")))
                .doesNotContain("【锚点引力】").doesNotContain("【收敛指令】");
    }

    /** 锚点/结局节点自身即主线拍子，无需引力 */
    @Test
    void 锚点节点自身无需引力() {
        stubRun(runAtDepth("n1_07", 6), graph(VARIANT_GRAPH_JSON));

        assertThat(materializer.buildStoryNodeBlock(GAME_ID, gs("{}")))
                .doesNotContain("【锚点引力】").doesNotContain("【收敛指令】");
    }

    /** 幕内无其它带拍锚点（防御：无拍可引）无引力行 */
    @Test
    void 幕内无其它带拍锚点无引力() {
        // n1_07 去 beat：幕内唯一锚点无拍可引（且当前节点为 scene）
        StoryGraph g = graph(VARIANT_GRAPH_JSON
                .replace("\"type\": \"anchor\", \"beat\": \"幕末锚点\"", "\"type\": \"anchor\""));
        stubRun(runAtDepth("n1_01", 6), g);

        assertThat(materializer.buildStoryNodeBlock(GAME_ID, gs("{}")))
                .doesNotContain("【锚点引力】").doesNotContain("【收敛指令】");
    }

    // ==================== 轨道闸指令消费（tasks 4.7，spec「轨道闸（工程性收束）」） ====================

    /** 跨幕发散暂存的收敛指令在本块注入（一次性）并消费即清（落库） */
    @Test
    void 轨道闸指令注入并消费清除() {
        StoryRun run = runAt("n1_07", "[\"e1_01\"]");
        run.setPendingGate("【轨道收敛】玩家过幕 act1（初遇）出口时状态未命中任何出口轨道。"
                + "请把状态叙事性拉回合法轨道「信任线」（trust_信长 >= 60），勿再扩大发散。");
        stubRun(run, graph(VARIANT_GRAPH_JSON));

        String block = materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 50}"));

        assertThat(block)
                .contains("【轨道收敛】")
                .contains("信任线")
                .contains("勿再扩大发散");
        // 消费即清：指令一次性（仅跨幕后首个物化轮生效），清后随全字段 UPDATE 落库
        assertThat(run.getPendingGate()).isNull();
        verify(runRepo).update(run);
    }

    /** 无指令零写库：pendingGate 缺省 → 物化不触碰运行副本（只读语义保持） */
    @Test
    void 无轨道闸指令零写库() {
        stubRun(runAt("n1_07", "[\"e1_01\"]"), graph(VARIANT_GRAPH_JSON));

        materializer.buildStoryNodeBlock(GAME_ID, gs("{\"trust_信长\": 72}"));

        verify(runRepo, never()).update(any());
    }
}
