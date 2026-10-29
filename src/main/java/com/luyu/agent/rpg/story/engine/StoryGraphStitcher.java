package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨幕边引擎拼接器（story-graph-authoring tasks 2.5，design D2：
 * LLM 只画幕内边，跨幕边由引擎确定性生成）。
 * <p>
 * 幕间衔接（幕 k 出口锚 → 幕 k+1 入口）不交给 LLM，本引擎按边界契约
 * 确定性生成：<b>排序后每对相邻幕</b>，源幕每个 {@code anchor} 节点
 * （按 ID 序号升序）各生成一条指向下幕入口（幕内 ID 序号最小节点）的
 * 拼接边；非末幕的 {@code ending} 节点视为结构异常（告警，不衔接——
 * 结局节点不该有出边）。
 * <p>
 * <b>幂等（spec「引擎拼接跨幕边幂等」）</b>：拼接边 ID 独立命名空间
 * {@code x<act>_<seq>}（区别于模型边 {@code e<act>_<seq>}），每次执行
 * 先清除各幕旧拼接边再重算——重复拼接（重入 / 回退解锁后重拼）生成的
 * 跨幕边集合完全一致，无副作用残留。
 * <p>
 * <b>越权跨幕边丢弃告警（spec「模型越权跨幕边被丢弃」）</b>：拼接前
 * 扫描各幕 edges，{@code from}/{@code to} 的节点 ID 幕号与所在幕不符
 * 即丢弃并记录告警（模型越权画的跨幕衔接一律作废，由拼接边替代）；
 * 幕号不可解析的悬空引用不在本层处理（断边改指锚点是
 * {@link StoryGraphValidator} 的职责）。修复就地 mutate acts（对齐
 * Validator 惯例，调用方直接落库拼接后结构）。
 */
@Component
public class StoryGraphStitcher {

    private static final Logger log = LoggerFactory.getLogger(StoryGraphStitcher.class);

    /** 拼接边 ID 独立命名空间：x&lt;act&gt;_&lt;seq&gt;（引擎边标记，幂等清旧依据） */
    static final Pattern CROSS_EDGE_ID_PATTERN = Pattern.compile("^x\\d+_\\d+$");

    /** 节点 ID 幕号提取：n&lt;act&gt;_&lt;seq&gt; 的 act 部分 */
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("^n(\\d+)_\\d+$");

    /** 节点 ID 序号提取（锚点排序与入口选择依据） */
    private static final Pattern NODE_SEQ_PATTERN = Pattern.compile("^n\\d+_(\\d+)$");

    /** 幕 ID 序号提取（act2 → 2；无数字视为无序号排最后，同 validateGraph 排序惯例） */
    private static final Pattern ACT_DIGIT_PATTERN = Pattern.compile("(\\d+)");

    /**
     * 执行跨幕拼接：清旧拼接边 → 越权跨幕边丢弃告警 → 相邻幕对确定性
     * 生成拼接边（就地挂入源幕 edges）。单幕输入无跨幕对，正常零边返回。
     */
    public StitchResult stitch(List<StoryAct> acts) {
        List<StoryEdge> crossEdges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (acts == null || acts.isEmpty()) {
            return new StitchResult(crossEdges, warnings);
        }
        // 幕按序号确定性排序（无序号排最后，同 validateGraph；不动调用方列表）
        List<StoryAct> sorted = new ArrayList<>(acts);
        sorted.sort(Comparator.comparingInt(a -> {
            Integer num = actNumber(a.getId());
            return num == null ? Integer.MAX_VALUE : num;
        }));

        // 1. 幂等清旧 + 越权跨幕边丢弃（告警，拼接边替代模型画的跨幕衔接）
        for (StoryAct act : sorted) {
            removeStaleCrossEdges(act);
            dropOverreachingEdges(act, warnings);
        }

        // 2. 相邻幕对拼接：源幕每个 anchor（序号升序）→ 下幕入口
        for (int i = 0; i + 1 < sorted.size(); i++) {
            stitchPair(sorted.get(i), sorted.get(i + 1), crossEdges, warnings);
        }
        if (!warnings.isEmpty()) {
            log.warn("跨幕拼接完成（{} 幕，{} 条拼接边，{} 项告警）：{}",
                    sorted.size(), crossEdges.size(), warnings.size(), warnings);
        }
        return new StitchResult(crossEdges, warnings);
    }

    // ==================== 幂等清旧与越权丢弃 ====================

    /** 清除旧拼接边（x 命名空间；重算后由新边取代，被清边不重复计入）。 */
    private void removeStaleCrossEdges(StoryAct act) {
        if (act.getEdges() == null) {
            return;
        }
        List<StoryEdge> kept = new ArrayList<>(act.getEdges().size());
        for (StoryEdge edge : act.getEdges()) {
            if (edge.getId() != null && CROSS_EDGE_ID_PATTERN.matcher(edge.getId()).matches()) {
                log.debug("清除旧拼接边（幂等重算）: 幕 {} 边 {}", act.getId(), edge.getId());
            } else {
                kept.add(edge);
            }
        }
        if (kept.size() != act.getEdges().size()) {
            log.warn("幕 {} 幕内旧拼接边清除（幂等重算）", act.getId());
            act.setEdges(kept);
        }
    }

    /**
     * 越权跨幕边丢弃：from/to 的节点 ID 幕号与所在幕不符即丢弃并告警
     * （含 to 恰为下幕入口的边——D2 语义：模型画的任何跨幕边都算越权，
     * 衔接一律由引擎拼接边承担）。悬空引用（幕号不可解析）不动。
     */
    private void dropOverreachingEdges(StoryAct act, List<String> warnings) {
        if (act.getEdges() == null || act.getEdges().isEmpty()) {
            return;
        }
        Integer actNum = actNumber(act.getId());
        List<StoryEdge> kept = new ArrayList<>(act.getEdges().size());
        for (StoryEdge edge : act.getEdges()) {
            String reason = overreachReason(act, actNum, edge);
            if (reason == null) {
                kept.add(edge);
                continue;
            }
            warnings.add("幕 " + act.getId() + " 边 " + edge.getId()
                    + "（" + edge.getFrom() + "->" + edge.getTo() + "）丢弃：" + reason
                    + "（跨幕衔接由引擎拼接生成）");
        }
        if (kept.size() != act.getEdges().size()) {
            int dropped = act.getEdges().size() - kept.size();
            act.setEdges(kept);
            log.warn("幕 {} 越权跨幕边丢弃 {} 条", act.getId(), dropped);
        }
    }

    /** 越权判定：返回丢弃原因（无越权返回 null 沿用原边）。 */
    private String overreachReason(StoryAct act, Integer actNum, StoryEdge edge) {
        if (actNum == null) {
            return null; // 幕号不可解析（校验链已报），本层不重复处理
        }
        Integer fromAct = nodeActNumber(edge.getFrom());
        if (fromAct != null && !fromAct.equals(actNum)) {
            return "from 越权跨幕（节点属幕 " + fromAct + "）";
        }
        Integer toAct = nodeActNumber(edge.getTo());
        if (toAct != null && !toAct.equals(actNum)) {
            return "to 越权跨幕（节点属幕 " + toAct + "）";
        }
        return null;
    }

    // ==================== 相邻幕对拼接 ====================

    /** 单对拼接：源幕每个 anchor（序号升序）→ 目标幕入口；无锚点/无入口告警跳过。 */
    private void stitchPair(StoryAct source, StoryAct target,
                            List<StoryEdge> crossEdges, List<String> warnings) {
        String entry = entryNode(target);
        if (entry == null) {
            warnings.add("幕 " + target.getId() + " 无节点，跳过 "
                    + source.getId() + "->" + target.getId() + " 跨幕衔接");
            return;
        }
        List<StoryNode> nodes = source.getNodes() == null ? List.of() : source.getNodes();
        int seq = 0;
        boolean hasAnchor = false;
        for (StoryNode node : nodes) {
            if (node.getId() == null) {
                continue;
            }
            if (StoryNode.TYPE_ANCHOR.equals(node.getType())) {
                hasAnchor = true;
                seq++;
                StoryEdge cross = new StoryEdge();
                cross.setId(String.format("x%d_%02d", actNumberOrFallback(source), seq));
                cross.setFrom(node.getId());
                cross.setTo(entry);
                cross.setLabel(entryLabel(target));
                cross.setQuote("继续前进");
                cross.setHint("幕间过渡（引擎拼接）");
                if (source.getEdges() == null) {
                    source.setEdges(new ArrayList<>());
                }
                source.getEdges().add(cross);
                crossEdges.add(cross);
            } else if (StoryNode.TYPE_ENDING.equals(node.getType())) {
                warnings.add("幕 " + source.getId() + " ending 节点 " + node.getId()
                        + " 出现在非末幕，不生成跨幕衔接（结局节点不应有出边）");
            }
        }
        if (!hasAnchor) {
            warnings.add("幕 " + source.getId() + " 无 anchor 出口节点，跳过 "
                    + source.getId() + "->" + target.getId() + " 跨幕衔接");
        }
    }

    /** 拼接边 label：确定性携带目标幕号与主题（作者可在工坊编辑覆盖）。 */
    private String entryLabel(StoryAct target) {
        Integer num = actNumber(target.getId());
        String theme = target.getTheme();
        if (num != null && theme != null && !theme.isBlank()) {
            return "进入第 " + num + " 幕「" + theme.trim() + "」";
        }
        return num == null ? "进入下一幕" : "进入第 " + num + " 幕";
    }

    // ==================== 确定性选择 ====================

    /** 入口节点：幕内 ID 序号最小者；无合法 ID 节点返回列表首个（防御，同 Validator）。 */
    private String entryNode(StoryAct act) {
        if (act.getNodes() == null || act.getNodes().isEmpty()) {
            return null;
        }
        String best = null;
        int bestSeq = Integer.MAX_VALUE;
        for (StoryNode node : act.getNodes()) {
            Integer seq = nodeSeq(node.getId());
            if (seq != null && seq < bestSeq) {
                bestSeq = seq;
                best = node.getId();
            }
        }
        return best != null ? best : act.getNodes().get(0).getId();
    }

    /** 幕号（拼接边 ID 用；不可解析退 0 保证 ID 形态稳定）。 */
    private int actNumberOrFallback(StoryAct act) {
        Integer num = actNumber(act.getId());
        return num == null ? 0 : num;
    }

    /** 幕 ID 首段数字（act2 → 2；无数字 null）。 */
    private Integer actNumber(String actId) {
        if (actId == null) {
            return null;
        }
        Matcher m = ACT_DIGIT_PATTERN.matcher(actId);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /** 节点 ID 幕号（n2_07 → 2；非形态返回 null）。 */
    private Integer nodeActNumber(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        Matcher m = NODE_ID_PATTERN.matcher(nodeId);
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }

    /** 节点 ID 序号（n2_07 → 7；非形态返回 null）。 */
    private Integer nodeSeq(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        Matcher m = NODE_SEQ_PATTERN.matcher(nodeId);
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }
}
