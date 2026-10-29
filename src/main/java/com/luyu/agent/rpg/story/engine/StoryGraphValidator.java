package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拓扑校验与确定性修复器（story-graph-model 任务 1.5）。
 * <p>
 * 全部行为确定性（同输入同输出，不调用 LLM），顺序执行四段：
 * <ol>
 *   <li><b>A 断边/跨幕修复</b>（repair 报告原值→新值）：from 非本幕节点丢弃；
 *       to 为跨幕节点丢弃（跨幕边由引擎拼接生成，spec「跨幕引用被丢弃重连」）；
 *       to 悬空（如 n2_99 不存在）改指最近同幕锚点（ID 序号距离最小，平局取
 *       序号小者），无锚点可指则丢弃（spec「断边确定性改指锚点」）；
 *       to == 下一幕入口节点的边为引擎拼接边，保留</li>
 *   <li><b>B 入口可达性剔除</b>（warning）：入口节点 = 幕内 ID 序号最小节点；
 *       BFS 不可达节点（含孤儿——无关联节点同样不可达）及其全部边剔除</li>
 *   <li><b>C 结构必需</b>（error 拒绝）：converge_to 轨道表非空；剔除后幕内
 *       仍有锚点语义节点（anchor/ending，spec「缺失必需字段被拒」——锚点被
 *       剔即触发缺失）</li>
 *   <li><b>D 环深度与死端</b>：环边数超 {@value #MAX_CYCLE_EDGES} 拒绝；
 *       非 ending 节点无法到达任何锚点（死端/死环）仅警告（运行期即兴兜底）</li>
 * </ol>
 * {@link #validateAct} 供 S4.5 逐幕生成链（严格幕内边）；{@link #validateGraph}
 * 供整稿通过链（各幕校验 + 跨幕对接：非末幕锚点应有出口边即拼接边存在 +
 * 全图可达性）。修复就地 mutate acts（调用方直接落库修复后结构）。
 */
@Component
public class StoryGraphValidator {

    /** 环深度上限（环上边数；环允许但限尺寸，防死循环拓扑） */
    public static final int MAX_CYCLE_EDGES = 8;

    /** 节点 ID 序号提取：n&lt;act&gt;_&lt;seq&gt; 的 seq 部分 */
    private static final Pattern NODE_SEQ_PATTERN = Pattern.compile("^n\\d+_(\\d+)$");

    /** 首段数字提取（act2 → 2；n3_05 → 3） */
    private static final Pattern FIRST_DIGIT_PATTERN = Pattern.compile("(\\d+)");

    /**
     * 单幕校验（S4.5 逐幕生成链）：to 仅允许本幕节点，跨幕引用一律丢弃。
     */
    public TopologyResult validateAct(StoryAct act) {
        return validateAct(act, null);
    }

    /**
     * 全图校验链（3.1 整稿通过）：幕排序 + 逐幕校验（携带下一幕入口，拼接边
     * 合法）+ 跨幕对接检查 + 全图可达性。
     */
    public TopologyResult validateGraph(List<StoryAct> acts) {
        TopologyResult result = new TopologyResult();
        if (acts == null || acts.isEmpty()) {
            result.error("图为空（无幕）");
            return result;
        }
        // 幕按序号确定性排序（无序号的排最后）
        List<StoryAct> sorted = new ArrayList<>(acts);
        sorted.sort(Comparator.comparingInt(a -> {
            Integer num = actNumber(a.getId());
            return num == null ? Integer.MAX_VALUE : num;
        }));
        // 幕序号重复 → 拒绝（命名空间冲突）
        Set<Integer> seenNumbers = new HashSet<>();
        for (StoryAct act : sorted) {
            Integer num = actNumber(act.getId());
            if (num != null && !seenNumbers.add(num)) {
                result.error("幕序号重复：" + act.getId());
            }
        }

        // 逐幕校验（携带下一幕入口：引擎拼接边 to = 下一幕入口合法）
        for (int i = 0; i < sorted.size(); i++) {
            String nextEntry = i + 1 < sorted.size()
                    ? entryNode(sorted.get(i + 1).getNodes()) : null;
            result.merge(validateAct(sorted.get(i), nextEntry));
        }

        // 跨幕对接：非末幕锚点应有出口边（拼接后校验——单幕生成时拼接边归 2.5 引擎）
        for (int i = 0; i + 1 < sorted.size(); i++) {
            StoryAct act = sorted.get(i);
            if (act.getNodes() == null) {
                continue;
            }
            Set<String> fromIds = new HashSet<>();
            if (act.getEdges() != null) {
                for (StoryEdge edge : act.getEdges()) {
                    if (edge.getFrom() != null) {
                        fromIds.add(edge.getFrom());
                    }
                }
            }
            for (StoryNode node : act.getNodes()) {
                if (StoryNode.TYPE_ANCHOR.equals(node.getType()) && !fromIds.contains(node.getId())) {
                    result.error("幕 " + act.getId() + " 锚点 " + node.getId()
                            + " 无出口边（跨幕对接断裂，引擎拼接边缺失）");
                }
            }
        }
        // 末幕非 ending 锚点无出边：警告（可能是未接 ending 的收尾）
        StoryAct last = sorted.get(sorted.size() - 1);
        if (last.getNodes() != null) {
            Set<String> fromIds = new HashSet<>();
            if (last.getEdges() != null) {
                for (StoryEdge edge : last.getEdges()) {
                    if (edge.getFrom() != null) {
                        fromIds.add(edge.getFrom());
                    }
                }
            }
            for (StoryNode node : last.getNodes()) {
                if (node.isAnchorLike() && !StoryNode.TYPE_ENDING.equals(node.getType())
                        && !fromIds.contains(node.getId())) {
                    result.warning("幕 " + last.getId() + " 锚点 " + node.getId()
                            + "（末幕非 ending）无出口边");
                }
            }
        }

        // 全图可达性（首幕入口跨幕 BFS；单幕已剔除主体，残余为跨幕断裂——仅警告）
        String firstEntry = entryNode(sorted.get(0).getNodes());
        if (firstEntry != null) {
            List<StoryEdge> allEdges = new ArrayList<>();
            for (StoryAct act : sorted) {
                if (act.getEdges() != null) {
                    allEdges.addAll(act.getEdges());
                }
            }
            Set<String> reachable = bfsForward(firstEntry, allEdges);
            for (StoryAct act : sorted) {
                if (act.getNodes() != null) {
                    for (StoryNode node : act.getNodes()) {
                        if (!reachable.contains(node.getId())) {
                            result.warning("全图不可达（跨幕断裂）：幕 "
                                    + act.getId() + " 节点 " + node.getId());
                        }
                    }
                }
            }
        }
        return result;
    }

    // ==================== 单幕校验 ====================

    TopologyResult validateAct(StoryAct act, String nextActEntry) {
        TopologyResult result = new TopologyResult();
        if (act == null) {
            result.error("幕为空");
            return result;
        }
        List<StoryNode> nodes = act.getNodes() == null ? new ArrayList<>() : act.getNodes();
        List<StoryEdge> edges = act.getEdges() == null ? new ArrayList<>() : act.getEdges();
        Integer actNumber = actNumber(act.getId());

        Set<String> nodeIds = new LinkedHashSet<>();
        for (StoryNode node : nodes) {
            if (node.getId() != null) {
                nodeIds.add(node.getId());
            }
        }

        // ---- A. 断边/跨幕引用修复（报告原值→新值） ----
        List<StoryEdge> keptEdges = new ArrayList<>();
        for (StoryEdge edge : edges) {
            if (edge.getFrom() == null || edge.getTo() == null) {
                result.repair("幕 " + act.getId() + " 边 " + edge.getId() + " 丢弃：from/to 为空");
                continue;
            }
            if (!nodeIds.contains(edge.getFrom())) {
                result.repair("幕 " + act.getId() + " 边 " + edge.getId()
                        + "（" + edge.getFrom() + "->" + edge.getTo() + "）丢弃：from 非本幕节点");
                continue;
            }
            if (nodeIds.contains(edge.getTo())) {
                keptEdges.add(edge);
                continue;
            }
            if (edge.getTo().equals(nextActEntry)) {
                keptEdges.add(edge); // 引擎拼接边（指向下一幕入口）
                continue;
            }
            Integer toActNumber = actNumber(edge.getTo());
            if (toActNumber != null && !toActNumber.equals(actNumber)) {
                result.repair("幕 " + act.getId() + " 边 " + edge.getId()
                        + "（" + edge.getFrom() + "->" + edge.getTo()
                        + "）丢弃：跨幕引用（跨幕边由引擎确定性拼接生成）");
                continue;
            }
            // 悬空引用：断边改指最近同幕锚点（无锚点则丢弃）
            String nearest = nearestAnchor(edge.getFrom(), anchorNodes(nodes));
            if (nearest == null) {
                result.repair("幕 " + act.getId() + " 边 " + edge.getId()
                        + "（" + edge.getFrom() + "->" + edge.getTo() + "）丢弃：断边且幕内无锚点可改指");
                continue;
            }
            result.repair("幕 " + act.getId() + " 边 " + edge.getId() + " to: "
                    + edge.getTo() + " -> " + nearest + "（断边改指最近同幕锚点）");
            edge.setTo(nearest);
            keptEdges.add(edge);
        }

        // ---- B. 入口可达性剔除（含孤儿：无关联节点同样不可达） ----
        String entry = entryNode(nodes);
        if (entry == null) {
            result.error("幕 " + act.getId() + " 无法确定入口节点（无节点）");
            return result;
        }
        Set<String> reachable = bfsForward(entry, keptEdges);
        List<StoryNode> keptNodes = new ArrayList<>();
        List<String> removedIds = new ArrayList<>();
        for (StoryNode node : nodes) {
            if (reachable.contains(node.getId())) {
                keptNodes.add(node);
            } else {
                removedIds.add(node.getId());
            }
        }
        if (!removedIds.isEmpty()) {
            result.warning("幕 " + act.getId() + " 从入口不可达的节点剔除（含孤儿及其全部边）：" + removedIds);
            keptEdges.removeIf(e -> !reachable.contains(e.getTo()));
        }

        // ---- C. 结构必需（剔除后复查，锚点被剔即触发缺失） ----
        if (act.getConvergeTo() == null || act.getConvergeTo().isEmpty()) {
            result.error("幕 " + act.getId() + " 缺少 converge_to 轨道表（工程性收束依据）");
        }
        if (anchorNodes(keptNodes).isEmpty()) {
            result.error("幕 " + act.getId() + " 缺少 anchor 出口节点（叙事性收束挂点）");
        }

        // 就地落修复结果（调用方直接使用）
        act.setNodes(keptNodes);
        act.setEdges(keptEdges);

        // ---- D. 环深度 + 死端 ----
        for (String cycleError : findCyclesOverLimit(entry, keptEdges, act.getId())) {
            result.error(cycleError);
        }
        List<StoryNode> anchors = anchorNodes(keptNodes);
        if (!anchors.isEmpty()) {
            Set<String> anchorIds = new LinkedHashSet<>();
            for (StoryNode anchor : anchors) {
                anchorIds.add(anchor.getId());
            }
            Set<String> canReachAnchor = bfsBackward(anchorIds, keptEdges);
            for (StoryNode node : keptNodes) {
                if (node.isAnchorLike() || node.getId().equals(entry)) {
                    continue;
                }
                if (!canReachAnchor.contains(node.getId())) {
                    result.warning("幕 " + act.getId() + " 节点 " + node.getId()
                            + " 无法到达任何锚点（死端/死环，运行期依赖即兴兜底收敛）");
                }
            }
        }
        return result;
    }

    // ==================== 环检测 ====================

    /** DFS 染色找环（节点 ≤25 递归安全）；环边数超上限的报 error */
    private List<String> findCyclesOverLimit(String entry, List<StoryEdge> edges, String actId) {
        List<String> errors = new ArrayList<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (StoryEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
        }
        Map<String, Integer> color = new HashMap<>(); // 0=白 1=灰 2=黑
        List<String> path = new ArrayList<>();
        dfsCycle(entry, adjacency, color, path, actId, errors);
        return errors;
    }

    private void dfsCycle(String node, Map<String, List<String>> adjacency, Map<String, Integer> color,
                          List<String> path, String actId, List<String> errors) {
        color.put(node, 1);
        path.add(node);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            int nextColor = color.getOrDefault(next, 0);
            if (nextColor == 1) {
                // 回边：path 中 next 到当前构成环
                int start = path.indexOf(next);
                int cycleEdges = path.size() - start;
                if (cycleEdges > MAX_CYCLE_EDGES) {
                    errors.add("幕 " + actId + " 环深度超限：" + cycleEdges + " > " + MAX_CYCLE_EDGES
                            + "（环 " + String.join("->", path.subList(start, path.size())) + "->" + next + "）");
                }
            } else if (nextColor == 0) {
                dfsCycle(next, adjacency, color, path, actId, errors);
            }
        }
        path.remove(path.size() - 1);
        color.put(node, 2);
    }

    // ==================== 可达性 ====================

    private Set<String> bfsForward(String start, List<StoryEdge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (StoryEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getFrom(), k -> new ArrayList<>()).add(edge.getTo());
        }
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    /** 反向可达：能沿边走到 starts 集合的节点（含 starts 自身） */
    private Set<String> bfsBackward(Set<String> starts, List<StoryEdge> edges) {
        Map<String, List<String>> reverse = new HashMap<>();
        for (StoryEdge edge : edges) {
            reverse.computeIfAbsent(edge.getTo(), k -> new ArrayList<>()).add(edge.getFrom());
        }
        Set<String> visited = new LinkedHashSet<>(starts);
        Deque<String> queue = new ArrayDeque<>(starts);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String prev : reverse.getOrDefault(current, List.of())) {
                if (visited.add(prev)) {
                    queue.add(prev);
                }
            }
        }
        return visited;
    }

    // ==================== 确定性选择 ====================

    /** 锚点语义节点（anchor/ending），按列表序 */
    private List<StoryNode> anchorNodes(List<StoryNode> nodes) {
        List<StoryNode> anchors = new ArrayList<>();
        for (StoryNode node : nodes) {
            if (node.isAnchorLike() && node.getId() != null) {
                anchors.add(node);
            }
        }
        return anchors;
    }

    /** 最近同幕锚点：ID 序号距离最小，平局取序号小者（确定性） */
    private String nearestAnchor(String fromId, List<StoryNode> anchors) {
        Integer fromSeq = nodeSeq(fromId);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestSeq = Integer.MAX_VALUE;
        for (StoryNode anchor : anchors) {
            Integer seq = nodeSeq(anchor.getId());
            if (seq == null) {
                continue;
            }
            int dist = fromSeq == null ? 0 : Math.abs(seq - fromSeq);
            if (dist < bestDist || (dist == bestDist && seq < bestSeq)) {
                best = anchor.getId();
                bestDist = dist;
                bestSeq = seq;
            }
        }
        return best;
    }

    /** 入口节点：幕内 ID 序号最小者；全违规时取列表首个（防御，校验链已报） */
    String entryNode(List<StoryNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        String best = null;
        int bestSeq = Integer.MAX_VALUE;
        for (StoryNode node : nodes) {
            Integer seq = nodeSeq(node.getId());
            if (seq != null && seq < bestSeq) {
                bestSeq = seq;
                best = node.getId();
            }
        }
        return best != null ? best : nodes.get(0).getId();
    }

    /** 节点 ID 序号（n2_07 → 7；非格式返回 null） */
    private Integer nodeSeq(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        Matcher m = NODE_SEQ_PATTERN.matcher(nodeId);
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }

    /** 首段数字（act2 → 2；n3_05 → 3；无数字 null） */
    private Integer actNumber(String id) {
        if (id == null) {
            return null;
        }
        Matcher m = FIRST_DIGIT_PATTERN.matcher(id);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }
}
