package com.luyu.agent.rpg.story.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.EventLog;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.story.model.ConvergeOrbit;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryRun;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.RpgStoryRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 故事图运行引擎（story-graph-runtime tasks 4.1，design D8：开局物化）。
 * <p>
 * 开局 = 模板图物化运行副本（{@code story_run}：图引用 + 当前节点 + 已走边 +
 * 物化缓存空槽）；入口节点取首幕最小序号（复用 {@link StoryGraphValidator}
 * 的确定性规则），其 {@code on_enter} 效果由本引擎确定性结算进初始
 * GameState（同 state_delta 合并语义：数值加减、非数值覆盖）——LLM 不参与
 * 开局结算。玩家游走只写运行副本，模板层不感知（「游走不影响模板图」spec 钉子）。
 * <p>
 * graphId 缺省时返回 {@code null} 零增量（spec「无故事图的 world 行为零增量」：
 * 无图时游戏流程与现状完全一致，调用方不感知引擎存在）。
 * <p>
 * 边结算（tasks 4.2，spec「边结算确定性」）：出选项 {@link #outgoingOptions}
 * 纯引擎过滤（边 guard + 目标节点 entry_guard 双守卫，不满足不出选项）；
 * 选边 {@link #settleEdge} 防御性复验后结算边 effects 与目标 on_enter
 * （同 state_delta 合并语义）并推进运行副本——LLM 不参与守卫判定与效果结算。
 * <p>
 * 物化缓存（tasks 4.4，spec「节点正文物化与变体优先级链」缓存段）：
 * {@link #storeBody} 按节点把物化正文连同当前到达路径 hash 写进运行副本
 * {@code materialized_bodies}；{@link #cachedBody} 命中仅当该存档此节点的
 * 到达路径与上次物化一致（walkedEdges 完整序列 SHA-256 比对，spec Scenario
 * 「不同来路强制重新物化」）；存档级强制重物化配置（{@code rematerialize=true}）
 * 恒不命中（4.9 多周目）。命中方（4.5 选边链路）可回放正文省 GM 调用，
 * 未命中走 GM 重新物化并适配新来路。
 * <p>
 * 轨道闸（tasks 4.7，spec「轨道闸（工程性收束）」）：跨幕边结算后对源幕
 * 出口轨道表（{@code converge_to}）纯引擎求值——不命中任何轨道 → 收敛
 * 指令暂存 {@code pendingGate}（下一幕入口物化注入把状态拉回合法轨道，
 * 一次性消费即清）；命中/发散均记存档事件日志（{@code rpg_event_log}）。
 * <p>
 * 图上回溯（tasks 4.8，spec「图上回溯与确定性重放」）：{@link #backtrackToNode}
 * 回退到任意已走分叉点，状态恢复用确定性重放（开局基线 + 入口 on_enter +
 * 保留前缀已走边 effects 依次结算，不依赖全量状态快照）；被回退边移出已走
 * 集合，物化缓存保留（路径 hash 规则自然失效）；{@link #walkedForks} 供
 * 回退 UI 的分叉点列表。
 */
@Component
public class StoryRunEngine {

    private static final Logger log = LoggerFactory.getLogger(StoryRunEngine.class);

    /** 纯 JSON 桩读写用（playerStates / graph_json），无多态需求 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RpgStoryGraphRepository graphRepo;
    private final RpgStoryRunRepository runRepo;
    private final RpgGameStateRepository stateRepo;
    private final RpgEventLogRepository eventRepo;
    private final StoryGraphValidator validator;
    private final GuardEvaluator guardEvaluator;

    @Autowired
    public StoryRunEngine(RpgStoryGraphRepository graphRepo,
                          RpgStoryRunRepository runRepo,
                          RpgGameStateRepository stateRepo,
                          RpgEventLogRepository eventRepo,
                          StoryGraphValidator validator,
                          GuardEvaluator guardEvaluator) {
        this.graphRepo = graphRepo;
        this.runRepo = runRepo;
        this.stateRepo = stateRepo;
        this.eventRepo = eventRepo;
        this.validator = validator;
        this.guardEvaluator = guardEvaluator;
    }

    /**
     * 开局物化：模板图 → 运行副本 + 入口节点 on_enter 效果结算初始 playerStates。
     *
     * @return 新建运行副本；graphId 空 → {@code null}（world 无图零增量）
     * @throws IllegalArgumentException 图不存在/跨租户、数据损坏或无可用入口
     *         （未整稿通过）——控制层映射 400（对齐 startGame「玩家角色不存在」语义）
     */
    public StoryRun bootstrapForGame(GameState gs, String graphId) {
        if (graphId == null || graphId.isBlank()) {
            return null; // 零增量：无图 world 行为与现状完全一致
        }
        StoryGraph graph = graphRepo.findById(graphId);
        if (graph == null) {
            throw new IllegalArgumentException("故事图不存在或不可用: " + graphId);
        }
        List<StoryAct> acts = requireActs(graph);
        if (acts.isEmpty()
                || acts.get(0).getNodes() == null
                || acts.get(0).getNodes().isEmpty()) {
            throw new IllegalArgumentException("故事图无可用入口节点（未整稿通过），不能作为开局图: " + graphId);
        }
        String entry = validator.entryNode(acts.get(0).getNodes());
        StoryNode entryNode = findNode(acts, entry);

        // 开局基线（4.8 图上回溯）：结算入口 on_enter 前的原始 playerStates——
        // 确定性重放链 = 基线 + 入口 on_enter + 保留已走边 effects 依次结算
        String baseline = gs.getPlayerStates();

        // 入口 on_enter 效果结算初始 GameState（纯引擎确定性，LLM 不参与）
        boolean mutated = applyEffects(gs, entryNode == null ? null : entryNode.getOnEnter());

        StoryRun run = new StoryRun();
        run.setId(UUID.randomUUID().toString());
        run.setGameStateId(gs.getId());
        run.setGraphId(graphId);
        run.setCurrentNode(entry);
        run.setWalkedEdges("[]");
        run.setMaterializedBodies("{}");
        run.setImproviseDepth(0);
        run.setRematerialize(false);
        run.setInitialPlayerStates(baseline);
        runRepo.save(run);
        if (mutated) {
            stateRepo.updatePlayerStates(gs.getId(), gs.getPlayerStates());
        }
        log.info("故事图开局物化: game={}, graph={}, entry={}, onEnter生效={}",
                gs.getId(), graphId, entry, mutated);
        return run;
    }

    /**
     * 效果结算（同 state_delta 合并语义：数值加减、非数值覆盖），就地写回
     * gs.playerStates。数值语义：现值为数值则以现值为基叠加，缺失/非数值
     * 从 0 起算；整数域整数化（{@code trust_信长: 55} 而非 {@code 55.0}）。
     * tasks 4.2 边结算（edge effects + 目标节点 on_enter）复用本方法。
     *
     * @return 是否发生实际变更（无 effects / 解析防御性保留原值 → false，调用方免落库）
     */
    boolean applyEffects(GameState gs, Map<String, Object> effects) {
        if (gs == null || effects == null || effects.isEmpty()) {
            return false;
        }
        Map<String, Object> states = readPlayerStates(gs);
        if (states == null) {
            return false; // 解析失败：防御性保留原值，不从空态起算覆盖
        }
        boolean mutated = false;
        for (Map.Entry<String, Object> effect : effects.entrySet()) {
            String key = effect.getKey();
            Object value = effect.getValue();
            if (key == null || value == null) {
                continue;
            }
            if (value instanceof Number delta) {
                Object current = states.get(key);
                double base = current instanceof Number n ? n.doubleValue() : 0;
                double result = base + delta.doubleValue();
                states.put(key, result == Math.rint(result) ? (long) result : result);
            } else {
                states.put(key, value);
            }
            mutated = true;
        }
        if (mutated) {
            try {
                gs.setPlayerStates(MAPPER.writeValueAsString(states));
            } catch (JsonProcessingException e) {
                // effects 值均为简单类型，理论不可达：防御性保留原值
                log.warn("playerStates 序列化失败，保留原值: game={}", gs.getId());
                return false;
            }
        }
        return mutated;
    }

    /** playerStates JSON → 可变 Map；null/空白/null 字面量 → 空 Map；解析失败 → null（保留原值信号） */
    private Map<String, Object> readPlayerStates(GameState gs) {
        String raw = gs.getPlayerStates();
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(raw, MAPPER.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IOException e) {
            log.warn("playerStates 解析失败，效果结算保留原值: game={}", gs.getId());
            return null;
        }
    }

    // ==================== 边结算（tasks 4.2，spec「边结算确定性」） ====================

    /**
     * 出选项：当前节点全部出边中，边 guard 与目标节点 entry_guard 均满足
     * （或无守卫）的边（tasks 4.5 混排渲染的图边数据源）。纯引擎求值，
     * spec「守卫不满足的边不出现」；跨幕拼接边同权（from 命中即出）。
     * <p>
     * 防御语义：图不存在/数据损坏/未开图 → 空列表（不出图边，GM 即兴兑底）。
     */
    public List<StoryEdge> outgoingOptions(StoryRun run, GameState gs) {
        if (run == null || run.getGraphId() == null || run.getCurrentNode() == null) {
            return List.of();
        }
        StoryGraph graph = graphRepo.findById(run.getGraphId());
        if (graph == null) {
            return List.of();
        }
        List<StoryAct> acts = safeActs(graph);
        Map<String, Object> view = guardView(gs);
        List<StoryEdge> options = new ArrayList<>();
        for (StoryAct act : acts) {
            if (act.getEdges() == null) {
                continue;
            }
            for (StoryEdge edge : act.getEdges()) {
                if (!run.getCurrentNode().equals(edge.getFrom())) {
                    continue;
                }
                if (!guardPasses(edge.getGuard(), view)) {
                    continue; // 边守卫不满足：不出选项（spec 钉子）
                }
                StoryNode target = findNode(acts, edge.getTo());
                if (target != null && !guardPasses(target.getEntryGuard(), view)) {
                    continue; // 目标入口守卫不满足：不可经任何边进入
                }
                options.add(edge);
            }
        }
        return options;
    }

    /**
     * 边结算：防御复验（边存在/从当前节点出发/双守卫）→ 边 effects 与目标
     * 节点 on_enter 结算写 GameState → 运行副本前进（current_node +
     * walked_edges 追加）并落库。效果与 GM 叙述解耦（spec 钉子：GM 未提及
     * 数值也确定性生效）；玩家游走只写运行副本，模板层只读。
     *
     * @return 到达的目标节点（4.3 物化器消费：beat/来路边）
     * @throws IllegalArgumentException 边不存在/不从当前节点出发（不可跳选、
     *         不可重放他边）/守卫不满足（选项生成后状态变化的竞态防御）/目标不存在
     */
    public StoryNode settleEdge(StoryRun run, GameState gs, String edgeId) {
        if (run == null || run.getGraphId() == null || run.getCurrentNode() == null) {
            throw new IllegalArgumentException("存档未挂故事图运行副本，不能选边");
        }
        StoryGraph graph = graphRepo.findById(run.getGraphId());
        if (graph == null) {
            throw new IllegalArgumentException("故事图不存在或不可用: " + run.getGraphId());
        }
        List<StoryAct> acts = requireActs(graph);
        StoryEdge edge = findEdge(acts, edgeId);
        if (edge == null) {
            throw new IllegalArgumentException("故事图边不存在: " + edgeId);
        }
        if (!edge.getFrom().equals(run.getCurrentNode())) {
            throw new IllegalArgumentException("边 " + edgeId + " 不从当前节点 "
                    + run.getCurrentNode() + " 出发（不可跳选或重放他边）");
        }
        Map<String, Object> view = guardView(gs);
        if (!guardPasses(edge.getGuard(), view)) {
            throw new IllegalArgumentException("边 " + edgeId + " 守卫不满足（选项生成后状态已变化）");
        }
        StoryNode target = findNode(acts, edge.getTo());
        if (target == null) {
            throw new IllegalArgumentException("边的目标节点不存在: " + edge.getTo());
        }
        if (!guardPasses(target.getEntryGuard(), view)) {
            throw new IllegalArgumentException("目标节点 " + edge.getTo() + " 入口守卫不满足");
        }

        // 效果结算：边 effects + 目标 on_enter（同 state_delta 合并语义，先边后节点）
        boolean mutated = applyEffects(gs, edge.getEffects());
        mutated |= applyEffects(gs, target.getOnEnter());

        // 轨道闸（tasks 4.7）：跨幕边结算后对源幕出口轨道表纯引擎求值（命中静默/
        // 发散暂存收敛指令随下方 update 落库，命中/发散均记存档事件日志）
        evaluateOrbitGate(acts, edge, run, gs);

        run.setCurrentNode(edge.getTo());
        run.setWalkedEdges(appendWalkedEdge(run.getWalkedEdges(), edgeId));
        run.setImproviseDepth(0); // 走图边即重置连续即兴计数（4.6 spec 钉子）
        runRepo.update(run);
        if (mutated) {
            stateRepo.updatePlayerStates(gs.getId(), gs.getPlayerStates());
        }
        log.info("故事图边结算: game={}, edge={}, to={}, walked={}, effects生效={}",
                gs.getId(), edgeId, edge.getTo(), run.getWalkedEdges(), mutated);
        return target;
    }

    // ==================== 物化缓存（tasks 4.4，spec 缓存段） ====================

    /**
     * 物化正文缓存条目（{@code materialized_bodies} JSON 值结构）：
     * body = 物化当时的 GM 叙述正文；pathHash = 物化时到达路径（walkedEdges
     * 完整序列）的 SHA-256 指纹——命中判定仅当两者都一致。
     */
    public static class CachedBody {

        /** 物化当时的 GM 叙述正文 */
        private String body;

        /** 物化时到达路径指纹（walkedEdges SHA-256 前 16 hex） */
        private String pathHash;

        public CachedBody() {
        }

        public CachedBody(String body, String pathHash) {
            this.body = body;
            this.pathHash = pathHash;
        }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public String getPathHash() { return pathHash; }
        public void setPathHash(String pathHash) { this.pathHash = pathHash; }
    }

    /**
     * 缓存命中查询：该存档此节点曾物化且到达路径与当前一致（walkedEdges
     * hash 比对）时返回缓存正文；未缓存/路径不一致/存档配置强制重物化 →
     * {@code null}（GM 重新物化，spec「不同来路强制重新物化」）。
     * <p>
     * 防御语义：materialized_bodies 坏 JSON → 恒不命中（不抛，缓存是软优化）。
     *
     * @param run   运行副本（须已推进到目标节点后查询，walkedEdges 为当前完整序列）
     * @param nodeId 节点 ID
     * @return 缓存正文；未命中 → {@code null}
     */
    public String cachedBody(StoryRun run, String nodeId) {
        if (run == null || nodeId == null || nodeId.isBlank()) {
            return null;
        }
        if (Boolean.TRUE.equals(run.getRematerialize())) {
            return null; // 存档级强制重物化（4.9 多周目配置）：恒不复用缓存
        }
        Map<String, CachedBody> cache = readCache(run.getMaterializedBodies());
        if (cache == null) {
            return null; // 坏 JSON：防御性恒不命中
        }
        CachedBody entry = cache.get(nodeId);
        if (entry == null || entry.getBody() == null) {
            return null;
        }
        if (!pathHashOf(run.getWalkedEdges()).equals(entry.getPathHash())) {
            return null; // 到达路径与上次物化不一致：强制重物化（spec 钉子）
        }
        return entry.getBody();
    }

    /**
     * 物化正文写缓存：记录 body 与当前到达路径 hash，幂等覆盖同节点旧条目
     * （回溯重物化即覆盖），落库运行副本。body null/空白不写（空物化无缓存价值）。
     */
    public void storeBody(StoryRun run, String nodeId, String body) {
        if (run == null || nodeId == null || nodeId.isBlank()
                || body == null || body.isBlank()) {
            return;
        }
        Map<String, CachedBody> cache = readCache(run.getMaterializedBodies());
        if (cache == null) {
            cache = new LinkedHashMap<>(); // 坏 JSON：从空重建（旧缓存条目放弃）
        }
        cache.put(nodeId, new CachedBody(body, pathHashOf(run.getWalkedEdges())));
        try {
            run.setMaterializedBodies(MAPPER.writeValueAsString(cache));
        } catch (JsonProcessingException e) {
            log.warn("物化缓存序列化失败，放弃写入: game={}, node={}", run.getGameStateId(), nodeId);
            return;
        }
        runRepo.update(run);
        log.debug("物化正文入缓存: game={}, node={}, bodyLen={}",
                run.getGameStateId(), nodeId, body.length());
    }

    // ==================== 选项混排三档（tasks 4.5，spec「选项混排与即兴三档」） ====================

    /**
     * 选项混排视图：生效档位 + 当前节点图出边（双守卫过滤）+ 本节点即兴许可。
     * 前端据 edges 渲染图边按钮（复用 choices 组件交互语义，点击以边 quote
     * 发起新回合并携带 edgeId）；据 improviseAllowed 决定 GM 即兴 choices
     * 是否进入选项区。
     */
    public record StoryOptionsView(String mode, String nodeId,
                                   List<StoryEdge> edges, boolean improviseAllowed) {}

    /**
     * 当前节点的选项混排视图（三档判定纯引擎求值）：
     * <ul>
     *   <li>即兴许可：锚点/结局强制 false（spec「锚点不被即兴绕过」）；
     *       节点级 improvise 覆盖作品级；其余按 mode（off 不允许即兴）；</li>
     *   <li>图边按钮：free 模式不渲染（图退化为建议路线，spec「free 模式图退化
     *       为建议」）；off/bounded 模式出双守卫过滤后的图出边；</li>
     *   <li>mode 缺省归 bounded（模型默认档）。</li>
     * </ul>
     * 零增量：未挂图/未开图/图不存在/数据损坏/存档缺失 → null（前端选项区
     * 与现状完全一致：不渲染图边、不过滤 GM choices）。
     */
    public StoryOptionsView storyOptions(String gameStateId) {
        StoryRun run = runRepo.findByGameStateId(gameStateId);
        if (run == null || run.getGraphId() == null || run.getCurrentNode() == null) {
            return null; // 零增量：无图 world 选项区与现状一致
        }
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            return null; // 存档缺失防御：零增量
        }
        StoryGraph graph = graphRepo.findById(run.getGraphId());
        if (graph == null) {
            log.warn("图选项视图省略：图不存在 game={}, graph={}", gameStateId, run.getGraphId());
            return null; // 软降级，不阻塞选项区
        }
        List<StoryAct> acts = safeActs(graph);
        StoryNode node = findNode(acts, run.getCurrentNode());
        if (node == null) {
            log.warn("图选项视图省略：当前节点不在图中 game={}, node={}",
                    gameStateId, run.getCurrentNode());
            return null; // 数据不一致态软降级
        }
        String mode = graph.getImproviseMode() == null
                ? StoryGraph.IMPROVISE_BOUNDED : graph.getImproviseMode();
        boolean improviseAllowed;
        if (node.isAnchorLike()) {
            improviseAllowed = false; // 锚点/结局强制图边（其出边恒为图边）
        } else if (node.getImprovise() != null) {
            improviseAllowed = node.getImprovise(); // 节点级覆盖作品级
        } else {
            improviseAllowed = !StoryGraph.IMPROVISE_OFF.equals(mode); // off 仅图边
        }
        List<StoryEdge> edges = StoryGraph.IMPROVISE_FREE.equals(mode)
                ? List.of() // free：图退化为建议，不出边按钮（状态与回溯仍可用）
                : outgoingOptions(run, gs);
        return new StoryOptionsView(mode, run.getCurrentNode(), edges, improviseAllowed);
    }

    /**
     * 选边结算入口（gameTurn 接线，tasks 4.5）：按存档查 run/gs 后结算
     * （结算顺序同 {@link #settleEdge}：守卫复验 → 边 effects → 目标 on_enter →
     * 推进运行副本）。
     *
     * @throws IllegalArgumentException 存档不存在/未挂图（不能选边）/边不存在/
     *         不从当前节点出发/守卫不满足（竞态防御）——控制层 SSE error 返回
     */
    public StoryNode settleEdgeForGame(String gameStateId, String edgeId) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            throw new IllegalArgumentException("游戏状态未找到: " + gameStateId);
        }
        StoryRun run = runRepo.findByGameStateId(gameStateId);
        return settleEdge(run, gs, edgeId); // run null → IAE「未挂图不能选边」（4.2 防御）
    }

    // ==================== 锚点引力计数（tasks 4.6，spec「锚点引力与即兴深度上限」） ====================

    /**
     * 回合完成记账（gameTurn doOnComplete 接线）：连续纯即兴轮计数。
     * <ul>
     *   <li>纯即兴轮（未走图边）：bounded 模式（或缺省）depth + 1；off/free
     *       置 0（spec「bounded 模式下 SHALL 计数」——off 无即兴、free 无引力）；</li>
     *   <li>走图边轮：置 0（{@link #settleEdge} 已同步重置并落库，此处幂等
     *       兑底，仅历史数据非零时补写）；</li>
     *   <li>零增量：未挂图（无 run/graphId）直接返回不计数不落库。</li>
     * </ul>
     * 计数供物化器下一轮注入引力/收敛指令（depth 超上限 → 收敛指令，
     * 1 ≤ depth ≤ 上限 → 轻引力拍子提示）；仅值变化时才落库（幂等省写）。
     *
     * @param gameStateId 存档 ID
     * @param walkedGraphEdge 本轮是否经图边推进（edgeId 回合为 true）
     */
    public void recordTurnOutcome(String gameStateId, boolean walkedGraphEdge) {
        StoryRun run = runRepo.findByGameStateId(gameStateId);
        if (run == null || run.getGraphId() == null) {
            return; // 零增量：无图 world 不计数
        }
        int current = run.getImproviseDepth() == null ? 0 : run.getImproviseDepth();
        int next;
        if (walkedGraphEdge) {
            next = 0; // 幂等兑底（settleEdge 已同步置 0；历史数据非零时此处补写）
        } else {
            StoryGraph graph = graphRepo.findById(run.getGraphId());
            String mode = graph == null || graph.getImproviseMode() == null
                    ? StoryGraph.IMPROVISE_BOUNDED : graph.getImproviseMode();
            next = StoryGraph.IMPROVISE_BOUNDED.equals(mode) ? current + 1 : 0;
        }
        if (next != current) {
            run.setImproviseDepth(next);
            runRepo.update(run);
            log.debug("即兴深度记账: game={}, improvised={}, depth {} -> {}",
                    gameStateId, !walkedGraphEdge, current, next);
        }
    }

    // ==================== 轨道闸（tasks 4.7，spec「轨道闸（工程性收束）」） ====================

    /**
     * 轨道闸求值：跨幕边结算后对源幕（from 节点所在幕）出口轨道表求值——命中
     * 任一轨道 → 静默通过（清 pendingGate，记录命中事件）；全不命中 → 收敛
     * 指令暂存 pendingGate（下一幕入口物化注入拉回，一次性消费即清）并记录
     * 发散事件。源幕无轨道表 / 幕内边 → 不闸（spec Scenario「轨道命中静默
     * 通过」/「状态发散被收敛指令拉回」）。选边即覆盖旧指令（指令生命周期 =
     * 跨幕后首个物化轮，prepareTurn 消费即清）。
     */
    private void evaluateOrbitGate(List<StoryAct> acts, StoryEdge edge, StoryRun run, GameState gs) {
        run.setPendingGate(null);
        StoryAct fromAct = actOf(acts, edge.getFrom());
        if (fromAct == null || fromAct.getConvergeTo() == null || fromAct.getConvergeTo().isEmpty()) {
            return; // 源幕无出口轨道表：不闸
        }
        if (fromAct == actOf(acts, edge.getTo())) {
            return; // 幕内边：轨道闸只在跨幕结算点求值（spec「经由跨幕边进入下一幕时」）
        }
        Map<String, Object> view = guardView(gs); // 结算后视图（effects/on_enter 已就地生效）
        for (ConvergeOrbit orbit : fromAct.getConvergeTo()) {
            if (orbit != null && guardPasses(orbit.getGuard(), view)) {
                recordGateEvent(run, gs, "story_orbit_hit",
                        "轨道命中: act=" + fromAct.getId() + ", orbit=" + orbit.getOrbit());
                log.info("轨道闸命中: game={}, act={}, orbit={}", gs.getId(), fromAct.getId(), orbit.getOrbit());
                return;
            }
        }
        ConvergeOrbit target = nearestOrbit(fromAct.getConvergeTo(), view);
        String directive = "【轨道收敛】玩家过幕 " + fromAct.getId()
                + (fromAct.getTheme() == null ? "" : "（" + fromAct.getTheme() + "）")
                + "出口时状态未命中任何出口轨道。请在本节点开场叙述中安排事件，把状态叙事性拉回合法轨道「"
                + (target != null ? target.getOrbit() : "出口轨道") + "」"
                + (target != null ? describeGuard(target.getGuard()) : "")
                + "，使后续守卫可自然命中，勿再扩大发散。";
        run.setPendingGate(directive);
        recordGateEvent(run, gs, "story_orbit_diverge",
                "轨道发散: act=" + fromAct.getId() + ", 注入收敛指令"
                        + (target != null ? "（目标轨道=" + target.getOrbit() + "）" : ""));
        log.info("轨道闸发散，暂存收敛指令: game={}, act={}", gs.getId(), fromAct.getId());
    }

    /** 节点所在幕（全图遍历，节点 ID 幕命名空间保证唯一） */
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

    /**
     * 发散时的引导目标轨道：数值比较型 guard 取与现值差距最小者（拉回距离
     * 最短），无可比数值时取第一条非空轨道（确定性——同图同状态同结果）。
     */
    private ConvergeOrbit nearestOrbit(List<ConvergeOrbit> orbits, Map<String, Object> view) {
        ConvergeOrbit best = null;
        double bestGap = Double.MAX_VALUE;
        for (ConvergeOrbit orbit : orbits) {
            if (orbit == null || orbit.getGuard() == null || orbit.getGuard().getKey() == null) {
                continue;
            }
            Double current = numeric(view.get(orbit.getGuard().getKey()));
            Double target = numeric(orbit.getGuard().getValue());
            if (current != null && target != null) {
                double gap = Math.abs(current - target);
                if (gap < bestGap) {
                    bestGap = gap;
                    best = orbit;
                }
            }
        }
        if (best != null) {
            return best;
        }
        for (ConvergeOrbit orbit : orbits) {
            if (orbit != null) {
                return orbit; // 无数值可比：第一条（确定性）
            }
        }
        return null;
    }

    /** 数值提取（Number/数值字符串）；非数值 → null */
    private static Double numeric(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 轨道守卫人类可读描述（指令内展示）：key op value */
    private static String describeGuard(com.luyu.agent.rpg.story.model.GuardClause guard) {
        if (guard == null) {
            return "";
        }
        return "（" + guard.getKey() + " " + guard.getOp() + " " + guard.getValue() + "）";
    }

    /** 轨道闸事件落存档事件日志（append-only；写失败软降级 log.error 不阻塞结算） */
    private void recordGateEvent(StoryRun run, GameState gs, String type, String content) {
        try {
            EventLog event = new EventLog();
            event.setGameStateId(run.getGameStateId());
            event.setTurn(gs.getTurnCount());
            event.setEventType(type);
            event.setContent(content);
            eventRepo.insert(event);
        } catch (Exception e) {
            log.error("轨道闸事件记录失败: game={}", run.getGameStateId(), e);
        }
    }

    // ==================== 图上回溯（tasks 4.8，spec「图上回溯与确定性重放」） ====================

    /** 分叉点视图（图上回溯 UI 数据源）：节点 ID/类型/拍子 + 到达所经边 label */
    public record StoryForkView(String nodeId, String nodeType, String beat, String arrivedViaLabel) {}

    /** 回溯结果：目标节点 + 重放后的 playerStates（前端 HUD 刷新数据源） */
    public record BacktrackResult(StoryNode node, String playerStates) {}

    /**
     * 图上回溯：回退到任意已走分叉点（入口节点或某条已走边的 to 节点，环上
     * 重复到达取最近一次）。状态恢复用确定性重放——开局基线 → 入口
     * on_enter → 保留前缀已走边 effects 与目标 on_enter 依次结算（不依赖
     * 全量状态快照，spec 钉子）；被回退的边移出已走集合；物化正文缓存保留
     * （按到达路径 hash 规则自然失效，4.4）。幂等：同目标重复回退重放同
     * 链路结果一致（spec Scenario「重放结果与逐步结算一致」）。
     *
     * @return 回退后所在节点与重放后 playerStates
     * @throws IllegalArgumentException 未挂图/目标不在已走路径上（仅可回退已走分叉点）
     */
    public BacktrackResult backtrackToNode(String gameStateId, String targetNodeId) {
        if (targetNodeId == null || targetNodeId.isBlank()) {
            throw new IllegalArgumentException("回溯目标节点不能为空");
        }
        StoryRun run = runRepo.findByGameStateId(gameStateId);
        if (run == null || run.getGraphId() == null || run.getCurrentNode() == null) {
            throw new IllegalArgumentException("存档未挂故事图运行副本，不能回溯");
        }
        StoryGraph graph = graphRepo.findById(run.getGraphId());
        if (graph == null) {
            throw new IllegalArgumentException("故事图不存在或不可用: " + run.getGraphId());
        }
        List<StoryAct> acts = requireActs(graph);
        List<String> walked = readWalkedEdges(run.getWalkedEdges());
        String entry = validator.entryNode(acts.get(0).getNodes());

        // 截断点：目标 = 入口 → 空前缀；否则取最后一条 to == 目标的已走边（最近到达）
        int cut = -1;
        if (!targetNodeId.equals(entry)) {
            for (int i = walked.size() - 1; i >= 0; i--) {
                StoryEdge edge = findEdge(acts, walked.get(i));
                if (edge != null && targetNodeId.equals(edge.getTo())) {
                    cut = i;
                    break;
                }
            }
            if (cut < 0) {
                throw new IllegalArgumentException("回溯目标不在已走路径上: " + targetNodeId
                        + "（仅可回退到已走过的分叉点）");
            }
        }
        List<String> kept = cut < 0 ? new ArrayList<>() : new ArrayList<>(walked.subList(0, cut + 1));

        // 确定性重放（spec 钉子：初始态 + 保留边 effects 依次结算，非快照）：
        // 基线 → 入口 on_enter → 每条保留边 effects + 目标 on_enter
        GameState replay = new GameState();
        replay.setId(gameStateId);
        replay.setPlayerStates(run.getInitialPlayerStates() == null
                ? "{}" : run.getInitialPlayerStates());
        StoryNode entryNode = findNode(acts, entry);
        applyEffects(replay, entryNode == null ? null : entryNode.getOnEnter());
        for (String edgeId : kept) {
            StoryEdge edge = findEdge(acts, edgeId);
            if (edge == null) {
                continue; // 防御：边不在图中（模板不可变，理论不可达），跳过结算保留截断语义
            }
            applyEffects(replay, edge.getEffects());
            StoryNode to = findNode(acts, edge.getTo());
            applyEffects(replay, to == null ? null : to.getOnEnter());
        }

        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            throw new IllegalArgumentException("游戏状态未找到: " + gameStateId);
        }
        gs.setPlayerStates(replay.getPlayerStates());
        stateRepo.updatePlayerStates(gameStateId, gs.getPlayerStates());
        run.setCurrentNode(targetNodeId);
        run.setWalkedEdges(toJson(kept, run.getWalkedEdges()));
        run.setImproviseDepth(0); // 结构性回退重新计数
        run.setPendingGate(null); // 旧收敛指令过期清（不再是跨幕后首物化轮语境）
        runRepo.update(run);
        log.info("故事图回溯: game={}, target={}, 保留边={}, 重放态={}",
                gameStateId, targetNodeId, kept.size(), replay.getPlayerStates());
        return new BacktrackResult(findNode(acts, targetNodeId), gs.getPlayerStates());
    }

    /**
     * 多周目开局（story-graph-runtime 4.9，spec「多周目同骨架不同游走」）：
     * 通关存档以同模板图开新周目——新运行副本基于模板图初始态（非上次运行
     * 副本游走痕迹）：walkedEdges 清空、当前节点回入口、即兴深度归零、旧收敛
     * 指令清；playerStates 确定性重放回「开局基线 + 入口 on_enter」（4.8 重放
     * 链的目标 = 入口特例，空已走边）。已物化正文缓存按存档级配置处理：复用
     * （缺省，同路径重走命中省 GM 调用）或强制重物化（清空缓存 +
     * {@code rematerialize=true} 恒不命中双保险，spec「强制重物化配置生效」）。
     * {@code initialPlayerStates} 不动——UPDATE 不携带该列（4.8 语义）：
     * 同模板图的开局基线跨周目不变。
     *
     * @param rematerialize 物化缓存存档级配置：null/false 复用，true 强制重物化
     * @return 入口节点 + 重放后的 playerStates（供前端 HUD 刷新）
     * @throws IllegalArgumentException 未挂图 / 图不存在 / 当前节点非结局
     *         （仅通关存档 MAY 开新周目）——控制层映射 400
     */
    public BacktrackResult startNewCycle(String gameStateId, Boolean rematerialize) {
        StoryRun run = runRepo.findByGameStateId(gameStateId);
        if (run == null || run.getGraphId() == null || run.getCurrentNode() == null) {
            throw new IllegalArgumentException("存档未挂故事图运行副本，不能开新周目");
        }
        StoryGraph graph = graphRepo.findById(run.getGraphId());
        if (graph == null) {
            throw new IllegalArgumentException("故事图不存在或不可用: " + run.getGraphId());
        }
        List<StoryAct> acts = requireActs(graph);
        // 通关判定：当前节点为结局（spec：已结束存档 MAY 开新周目）
        StoryNode endingNode = findNode(acts, run.getCurrentNode());
        if (endingNode == null || !StoryNode.TYPE_ENDING.equals(endingNode.getType())) {
            throw new IllegalArgumentException("存档尚未通关（当前节点非结局），不能开新周目");
        }
        String entry = validator.entryNode(acts.get(0).getNodes());
        StoryNode entryNode = findNode(acts, entry);
        // 干净重放（4.8 重放链目标 = 入口的特例）：基线 + 入口 on_enter，空已走边
        GameState replay = new GameState();
        replay.setId(gameStateId);
        replay.setPlayerStates(run.getInitialPlayerStates() == null
                ? "{}" : run.getInitialPlayerStates());
        applyEffects(replay, entryNode == null ? null : entryNode.getOnEnter());
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            throw new IllegalArgumentException("游戏状态未找到: " + gameStateId);
        }
        gs.setPlayerStates(replay.getPlayerStates());
        stateRepo.updatePlayerStates(gameStateId, gs.getPlayerStates());
        boolean force = Boolean.TRUE.equals(rematerialize);
        // 周目重置：同模板图（graphId 不动 = 复制模板而非上次运行副本），一局游走痕迹不带入
        run.setCurrentNode(entry);
        run.setWalkedEdges("[]");
        run.setMaterializedBodies(force ? "{}"
                : (run.getMaterializedBodies() == null ? "{}" : run.getMaterializedBodies()));
        run.setImproviseDepth(0);
        run.setPendingGate(null);
        run.setRematerialize(force);
        runRepo.update(run);
        log.info("故事图新周目: game={}, graph={}, entry={}, 强制重物化={}, 重放态={}",
                gameStateId, run.getGraphId(), entry, force, replay.getPlayerStates());
        return new BacktrackResult(entryNode, gs.getPlayerStates());
    }

    /**
     * 已走分叉点列表（图上回溯 UI 数据源）：入口节点 + 各已走边 to 节点，
     * 按游走序去重（环上重复到达保留最近到达位置的来路边信息）。未挂图/
     * 未开图 → 空列表（无回退点，零增量）。
     */
    public List<StoryForkView> walkedForks(String gameStateId) {
        StoryRun run = runRepo.findByGameStateId(gameStateId);
        if (run == null || run.getGraphId() == null || run.getCurrentNode() == null) {
            return List.of();
        }
        StoryGraph graph = graphRepo.findById(run.getGraphId());
        if (graph == null) {
            return List.of();
        }
        List<StoryAct> acts = safeActs(graph);
        if (acts.isEmpty() || acts.get(0).getNodes() == null || acts.get(0).getNodes().isEmpty()) {
            return List.of();
        }
        Map<String, StoryForkView> forks = new LinkedHashMap<>();
        StoryNode entryNode = findNode(acts, validator.entryNode(acts.get(0).getNodes()));
        if (entryNode != null && entryNode.getId() != null) {
            forks.put(entryNode.getId(), forkView(entryNode, null));
        }
        for (String edgeId : readWalkedEdges(run.getWalkedEdges())) {
            StoryEdge edge = findEdge(acts, edgeId);
            StoryNode to = edge == null ? null : findNode(acts, edge.getTo());
            if (to == null || to.getId() == null) {
                continue;
            }
            forks.remove(to.getId()); // 环重复到达：先移除重插，保持最近到达的游走尾序
            forks.put(to.getId(), forkView(to, edge.getLabel()));
        }
        return List.copyOf(forks.values());
    }

    private static StoryForkView forkView(StoryNode node, String arrivedViaLabel) {
        return new StoryForkView(node.getId(),
                node.getType() == null ? "scene" : node.getType(),
                node.getBeat(), arrivedViaLabel);
    }

    /** walkedEdges JSON → 边 ID 列表；null/空白/坏 JSON → 空列表（防御） */
    private List<String> readWalkedEdges(String walkedEdges) {
        if (walkedEdges == null || walkedEdges.isBlank() || "null".equals(walkedEdges)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(MAPPER.readValue(walkedEdges,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
        } catch (IOException e) {
            log.warn("walkedEdges 解析失败，按空游走序列处理");
            return new ArrayList<>();
        }
    }

    /** 边 ID 列表 → walkedEdges JSON（简单字符串列表理论不可失败；防御回退原串） */
    private static String toJson(List<String> edgeIds, String fallback) {
        try {
            return MAPPER.writeValueAsString(edgeIds);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    // ==================== 内部辅助 =====================

    /** 无守卫 = 放行；有守卫 = 纯引擎求值（null/缺键/类型不可比 → false，白名单哲学） */
    private boolean guardPasses(com.luyu.agent.rpg.story.model.GuardClause guard, Map<String, Object> view) {
        return guard == null || guardEvaluator.evaluate(guard, view);
    }

    /** guard 求值状态视图：playerStates 解析失败 → 空 Map（守卫全 false，防御对齐白名单哲学） */
    private Map<String, Object> guardView(GameState gs) {
        Map<String, Object> view = readPlayerStates(gs);
        return view == null ? Map.of() : view;
    }

    /** acts 解析（拒绝版）：落库前必过校验链，损坏理论不可达，防御性 IAE */
    private List<StoryAct> requireActs(StoryGraph graph) {
        try {
            return graph.acts(MAPPER);
        } catch (IOException e) {
            throw new IllegalArgumentException("故事图数据损坏: " + graph.getId());
        }
    }

    /** acts 解析（防御版）：损坏 → 空列表（不出选项，GM 即兴兑底，不抛） */
    private List<StoryAct> safeActs(StoryGraph graph) {
        try {
            return graph.acts(MAPPER);
        } catch (IOException e) {
            log.warn("故事图解析失败，不出图边: graph={}", graph.getId());
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

    /** 已走边追加（按游走顺序；坏 JSON 防御性从当前追加重算） */
    private String appendWalkedEdge(String walkedEdges, String edgeId) {
        List<String> walked = new ArrayList<>();
        if (walkedEdges != null && !walkedEdges.isBlank() && !"null".equals(walkedEdges)) {
            try {
                walked.addAll(MAPPER.readValue(walkedEdges,
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class)));
            } catch (IOException e) {
                log.warn("walkedEdges 解析失败，从当前边重算游走序列");
            }
        }
        walked.add(edgeId);
        try {
            return MAPPER.writeValueAsString(walked);
        } catch (JsonProcessingException e) {
            return walkedEdges; // 简单字符串列表，理论不可达
        }
    }

    /** materialized_bodies JSON → Map；null/空白/坏 JSON → null（恒不命中信号） */
    private Map<String, CachedBody> readCache(String materializedBodies) {
        if (materializedBodies == null || materializedBodies.isBlank()
                || "null".equals(materializedBodies)) {
            return null;
        }
        try {
            return MAPPER.readValue(materializedBodies, MAPPER.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, CachedBody.class));
        } catch (IOException e) {
            log.warn("materialized_bodies 解析失败，缓存恒不命中: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 到达路径指纹：walkedEdges 完整序列（从开局到当前，含入本节点的末边）的
     * SHA-256 前 16 hex；null/空白归一 "[]"。回溯后经另一条边重到同节点时
     * 序列必变（指纹不命中）；确定性重放同路径时指纹一致（命中即复用）。
     */
    private String pathHashOf(String walkedEdges) {
        String raw = walkedEdges == null || walkedEdges.isBlank()
                || "null".equals(walkedEdges) ? "[]" : walkedEdges;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return raw; // SHA-256 平台必有；防御降级用原文比对，语义仍成立
        }
    }
}
