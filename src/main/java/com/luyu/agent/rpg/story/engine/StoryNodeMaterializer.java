package com.luyu.agent.rpg.story.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryRun;
import com.luyu.agent.rpg.story.model.StoryVariant;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.RpgStoryRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点物化器（story-graph-runtime tasks 4.3，spec「节点正文物化与变体优先级链」）。
 * <p>
 * 玩家到达节点（开局入口或选边推进）后的每轮 GM 回合，把当前位置组装为
 * 结构指示注入块：节点 beat + 实际来路边 label/quote + 变体优先级链选中的
 * 种子（{@code beat_variant}/{@code body_seed}）。GM 叙述与
 * {@code <state_delta>} 照旧——本块只锁叙事骨架，不替代 GM 自由叙述；
 * 结构效果（{@code on_enter}/边 effects）已由 {@link StoryRunEngine}
 * 确定性结算，块内明示勿在 state_delta 中重复结算。
 * <p>
 * 变体优先级链（确定性）：所在幕授权（{@code allowExplicitVariants == true}）
 * 且节点声明 {@code variants[]} 时，<b>第一个</b> guard 命中的变体生效
 * （{@link GuardEvaluator} 白名单求值）；不命中或无变体 → 纯即兴
 * （块仍注入 beat 与来路，只是无变体种子行）。幕未授权时节点 variants
 * 整体被忽略（对齐 model 校验 1.6 语义）。
 * <p>
 * 锚点引力（tasks 4.6，spec「锚点引力与即兴深度上限」）：bounded 模式且
 * 非锚点节点且连续即兴 depth ≥ 1 时，块内追加引力行——depth 超过作品级
 * 上限（缺省 {@link StoryGraph#DEFAULT_IMPROVISE_DEPTH_LIMIT}，作者可调）
 * 注入收敛指令（强制引向当前幕待完成锚点拍子），未超限注入轻引力提示
 * （可自然铺垫伏笔，不强制）。off/free/锚点节点/无待完成锚点 → 无引力行。
 * <p>
 * 轨道闸指令消费（tasks 4.7，spec「轨道闸（工程性收束）」）：跨幕发散时
 * 引擎暂存于运行副本的收敛指令在本块注入（下一幕入口物化轮把状态拉回
 * 合法轨道），注入即清除——一次性（仅跨幕后首个物化轮生效，后续轮不重复）。
 * <p>
 * 零增量：未挂图（无 {@code story_run}）/未开图（current_node 空）/图不存在
 * 或数据损坏 / 当前节点不在图中 → {@code null}（spec「无故事图的 world 行为
 * 零增量」，调用方省略注入，不抛——物化是软增强，不阻塞游戏回合）。
 * <p>
 * 注入落位（GmContextAssembler 注入块惯例）：以 {@code \n\n##} 开头自成一段、
 * 追加在增量模板末尾（{@code ## GM 任务指令} 段之后、避开 {@code ## 玩家行动}
 * 段）——{@code RpgHistoryCleaner} 按「## 玩家行动 到下一个 \n## 」截取玩家
 * 原文，本块天然被清洗剔除。首轮由 {@code GameLoopService} 拼在 system
 * prompt 末尾（system 不落库，无清洗问题；首轮 userPrompt 是玩家行动原文
 * 不可注入）。
 */
@Component
public class StoryNodeMaterializer {

    private static final Logger log = LoggerFactory.getLogger(StoryNodeMaterializer.class);

    /** 纯 JSON 桩读写用（playerStates / walkedEdges / graph_json），无多态需求 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RpgStoryRunRepository runRepo;
    private final RpgStoryGraphRepository graphRepo;
    private final GuardEvaluator guardEvaluator;

    @Autowired
    public StoryNodeMaterializer(RpgStoryRunRepository runRepo,
                                 RpgStoryGraphRepository graphRepo,
                                 GuardEvaluator guardEvaluator) {
        this.runRepo = runRepo;
        this.graphRepo = graphRepo;
        this.guardEvaluator = guardEvaluator;
    }

    /**
     * 构建「故事图当前位置」结构指示块。
     *
     * @param gameStateId 存档 ID（查运行副本）
     * @param gs          当前 GameState（变体 guard 求值视图来源，调用方已加载）
     * @return 以 {@code \n\n## 故事图当前位置（结构指示）} 开头的注入块；
     *         未挂图/未开图/图不可解析 → {@code null}（零增量，调用方省略）
     */
    public String buildStoryNodeBlock(String gameStateId, GameState gs) {
        StoryRun run = runRepo.findByGameStateId(gameStateId);
        if (run == null || run.getGraphId() == null || run.getCurrentNode() == null) {
            return null; // 零增量：无图 world 行为与现状完全一致
        }
        StoryGraph graph = graphRepo.findById(run.getGraphId());
        if (graph == null) {
            log.warn("故事图当前位置块省略：图不存在 game={}, graph={}", gameStateId, run.getGraphId());
            return null;
        }
        List<StoryAct> acts = safeActs(graph);
        if (acts.isEmpty()) {
            return null; // 数据损坏：软增强降级，不阻塞回合
        }
        StoryNode node = findNode(acts, run.getCurrentNode());
        if (node == null) {
            log.warn("故事图当前位置块省略：当前节点不在图中 game={}, node={}",
                    gameStateId, run.getCurrentNode());
            return null;
        }
        StoryEdge arrivedVia = findArrivedEdge(acts, run.getWalkedEdges(), run.getCurrentNode());
        StoryVariant variant = selectVariant(acts, node, gs);
        String gravity = gravityDirective(graph, acts, node, run);
        // 轨道闸指令消费（4.7）：跨幕发散时引擎暂存的收敛指令在本块注入并清除
        // （一次性：仅跨幕后首个物化轮生效；下次选边/跨幕覆盖新指令）
        String gate = run.getPendingGate();
        if (gate != null && !gate.isBlank()) {
            run.setPendingGate(null);
            runRepo.update(run);
        }
        return renderBlock(node, arrivedVia, variant, gravity, gate);
    }

    // ==================== 内部辅助 ====================

    /** 块渲染：结构指示 + 变体种子 + 引力/收敛与轨道闸指令 + 叙述要求（解耦钉子：勿重复结算已生效数值） */
    private String renderBlock(StoryNode node, StoryEdge arrivedVia, StoryVariant variant,
                               String gravity, String gate) {
        StringBuilder sb = new StringBuilder("\n\n## 故事图当前位置（结构指示）\n\n");
        sb.append("本世界挂有分支叙事图，玩家正行至图上某节点。以下结构指示供你组织本轮")
                .append("叙述：以节点拍子为叙事骨架自然展开，勿向玩家提及图、节点编号或任何结构术语。\n\n");
        sb.append("【当前节点】").append(node.getId())
                .append("（").append(nodeTypeLabel(node.getType())).append("）\n");
        if (node.getBeat() != null && !node.getBeat().isBlank()) {
            sb.append("【节点拍子】").append(node.getBeat()).append("\n");
        }
        if (arrivedVia != null) {
            sb.append("【实际来路】玩家经选择「")
                    .append(arrivedVia.getLabel() != null ? arrivedVia.getLabel() : arrivedVia.getId())
                    .append("」");
            if (arrivedVia.getQuote() != null && !arrivedVia.getQuote().isBlank()) {
                sb.append("——「").append(arrivedVia.getQuote()).append("」");
            }
            sb.append("到达\n");
        }
        if (variant != null) {
            if (variant.getBeatVariant() != null && !variant.getBeatVariant().isBlank()) {
                sb.append("【变体基调】").append(variant.getBeatVariant()).append("\n");
            }
            if (variant.getBodySeed() != null && !variant.getBodySeed().isBlank()) {
                sb.append("【正文种子】").append(variant.getBodySeed()).append("\n");
            }
        }
        if (gravity != null) {
            sb.append(gravity).append("\n");
        }
        if (gate != null) {
            sb.append(gate).append("\n");
        }
        sb.append("【叙述要求】本位置的结构效果（进入效果/选边效果）已由系统确定性结算并生效：")
                .append("叙述自然体现其结果即可，勿在 state_delta 中重复加减这些已结算数值；")
                .append("state_delta 与 choices 的输出照旧。");
        return sb.toString();
    }

    /** 节点类型文案（未知/空类型防御性归为自由场景） */
    private String nodeTypeLabel(String type) {
        if (StoryNode.TYPE_ANCHOR.equals(type)) {
            return "锚点·必经拍子";
        }
        if (StoryNode.TYPE_ENDING.equals(type)) {
            return "结局";
        }
        return "自由场景";
    }

    /**
     * 变体优先级链：幕授权 && 节点声明 variants && 第一个 guard 命中（确定性）；
     * 无守卫的变体不参与命中链（spec「guard 命中用种子」——无 guard 无从命中，
     * 白名单哲学）；全不命中/未授权/无变体 → null（纯即兴）。
     */
    private StoryVariant selectVariant(List<StoryAct> acts, StoryNode node, GameState gs) {
        if (node.getVariants() == null || node.getVariants().isEmpty()) {
            return null;
        }
        StoryAct ownerAct = actOf(acts, node.getId());
        if (ownerAct == null || !Boolean.TRUE.equals(ownerAct.getAllowExplicitVariants())) {
            return null; // 幕未授权：variants 整体忽略，物化走纯即兴（对齐 1.6 校验语义）
        }
        Map<String, Object> view = guardView(gs);
        for (StoryVariant variant : node.getVariants()) {
            if (variant == null || variant.getGuard() == null) {
                continue;
            }
            if (guardEvaluator.evaluate(variant.getGuard(), view)) {
                return variant; // 第一个命中（确定性优先级链）
            }
        }
        return null;
    }

    /**
     * 锚点引力行（4.6）：bounded 且非锚点且 depth ≥ 1 才注入；depth 超上限 →
     * 收敛指令（强制引向当前幕待完成锚点拍子），未超限 → 轻引力提示（可
     * 铺垫伏笔不强制）。off/free/锚点/结局节点/幕内无其它带拍锚点 → null。
     */
    private String gravityDirective(StoryGraph graph, List<StoryAct> acts, StoryNode node, StoryRun run) {
        String mode = graph.getImproviseMode() == null
                ? StoryGraph.IMPROVISE_BOUNDED : graph.getImproviseMode();
        if (!StoryGraph.IMPROVISE_BOUNDED.equals(mode)) {
            return null; // off 无即兴、free 无引力
        }
        if (node.isAnchorLike()) {
            return null; // 锚点/结局本身即主线拍子，无需引力
        }
        int depth = run.getImproviseDepth() == null ? 0 : run.getImproviseDepth();
        if (depth < 1) {
            return null; // 上一轮走了图边或开局：无连续即兴
        }
        String beat = pendingAnchorBeat(acts, node.getId());
        if (beat == null) {
            return null; // 当前幕无其它带拍锚点（防御：无拍可引）
        }
        Integer limitRaw = graph.getImproviseDepthLimit();
        int limit = limitRaw == null || limitRaw < 1
                ? StoryGraph.DEFAULT_IMPROVISE_DEPTH_LIMIT : limitRaw;
        if (depth > limit) {
            return "【收敛指令】玩家已连续 " + depth + " 轮未沿图推进（上限 " + limit
                    + "）。本轮必须把剧情引向当前幕锚点拍子「" + beat
                    + "」，choices 至少一项导向该主线，勿再开新支线。";
        }
        return "【锚点引力】玩家已连续 " + depth + " 轮即兴。可自然铺垫通往当前幕锚点拍子「"
                + beat + "」的伏笔，不强制。";
    }

    /** 当前幕待完成锚点拍子：所在幕内 type=anchor、非当前节点、beat 非空的第一个（确定性） */
    private String pendingAnchorBeat(List<StoryAct> acts, String nodeId) {
        StoryAct ownerAct = actOf(acts, nodeId);
        if (ownerAct == null || ownerAct.getNodes() == null) {
            return null;
        }
        for (StoryNode node : ownerAct.getNodes()) {
            if (node == null || nodeId.equals(node.getId())) {
                continue;
            }
            if (StoryNode.TYPE_ANCHOR.equals(node.getType())
                    && node.getBeat() != null && !node.getBeat().isBlank()) {
                return node.getBeat();
            }
        }
        return null;
    }

    /** 到达边：walkedEdges 末条（开局首节点无来路）；防御：to 与当前节点不符时省略 */
    private StoryEdge findArrivedEdge(List<StoryAct> acts, String walkedEdges, String currentNode) {
        if (walkedEdges == null || walkedEdges.isBlank() || "null".equals(walkedEdges)) {
            return null;
        }
        List<String> walked;
        try {
            walked = MAPPER.readValue(walkedEdges,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (IOException e) {
            log.warn("walkedEdges 解析失败，当前位置块省略来路");
            return null;
        }
        if (walked.isEmpty()) {
            return null;
        }
        StoryEdge edge = findEdge(acts, walked.get(walked.size() - 1));
        return edge != null && currentNode.equals(edge.getTo()) ? edge : null;
    }

    /** 找节点所在幕（变体授权判定依据） */
    private StoryAct actOf(List<StoryAct> acts, String nodeId) {
        for (StoryAct act : acts) {
            if (act.getNodes() == null) {
                continue;
            }
            for (StoryNode node : act.getNodes()) {
                if (nodeId != null && nodeId.equals(node.getId())) {
                    return act;
                }
            }
        }
        return null;
    }

    /** acts 解析（防御版）：损坏 → 空列表（物化软增强降级，不抛不阻塞回合） */
    private List<StoryAct> safeActs(StoryGraph graph) {
        try {
            return graph.acts(MAPPER);
        } catch (IOException e) {
            log.warn("故事图解析失败，当前位置块省略: graph={}", graph.getId());
            return List.of();
        }
    }

    /** 全图找节点（跨幕：拼接边目标在邻幕） */
    private StoryNode findNode(List<StoryAct> acts, String nodeId) {
        for (StoryAct act : acts) {
            if (act.getNodes() == null) {
                continue;
            }
            for (StoryNode node : act.getNodes()) {
                if (nodeId != null && nodeId.equals(node.getId())) {
                    return node;
                }
            }
        }
        return null;
    }

    /** 全图找边（含引擎拼接边 x<act>_<seq>，它们挂在源幕 edges 里） */
    private StoryEdge findEdge(List<StoryAct> acts, String edgeId) {
        for (StoryAct act : acts) {
            if (act.getEdges() == null) {
                continue;
            }
            for (StoryEdge edge : act.getEdges()) {
                if (edgeId != null && edgeId.equals(edge.getId())) {
                    return edge;
                }
            }
        }
        return null;
    }

    /** guard 求值状态视图：playerStates 解析失败 → 空 Map（变体全不命中，防御对齐白名单哲学） */
    private Map<String, Object> guardView(GameState gs) {
        Map<String, Object> view = readPlayerStates(gs);
        return view == null ? Map.of() : view;
    }

    /** playerStates JSON → Map；null/空白/null 字面量 → 空 Map；解析失败 → null */
    private Map<String, Object> readPlayerStates(GameState gs) {
        String raw = gs.getPlayerStates();
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(raw, MAPPER.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IOException e) {
            log.warn("playerStates 解析失败，变体求值走空视图: game={}", gs.getId());
            return null;
        }
    }
}
