package com.luyu.agent.rpg.planner.engine;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.luyu.agent.rpg.story.engine.StoryGraphParser;
import com.luyu.agent.rpg.story.model.ConvergeOrbit;
import com.luyu.agent.rpg.story.model.GuardClause;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryNode;

/**
 * 前幕「锚点链 + 出口轨道表」manifest 摘要提取（story-graph-authoring tasks 2.2 /
 * story-graph-generation spec「生成注入纪律」）。
 * <p>
 * 生成第 k 幕时注入的前幕上下文 <b>SHALL NOT</b> 是前幕全量节点/边 JSON——
 * 本组件把已确认前幕骨架压缩为每幕一行摘要（入口、锚点链、出口轨道表），
 * 供 {@code StoryGraphPromptBuilder} 拼装（design D2「注入用前幕 manifest 摘要
 * 非全文，沿用 planner 注入纪律」）。同输入同输出（纯函数，不调 LLM）。
 * <p>
 * 输入是 S4.5 幕候选文件内容列表（candidate-NN.md，含 {@code <story_graph>}
 * 块），解析复用 1.2 的 {@link StoryGraphParser}（宽进严出同一套）。解析失败
 * 的幕在摘要中标注损坏（已确认幕经校验链，正常不可达——防御性降级不中断
 * 生成流，与 LLM fallback 分工：那是本幕生成的重试，这是前幕读取的兜底）。
 * 第 1 幕携带的 {@code state_schema} 随摘要一并提取（后续幕沿用词汇表）。
 */
@Component
public class StoryGraphDigest {

    /** 节点 ID 幕命名空间（与 StoryGraphParser 同构，序号最小 = 入口） */
    private static final Pattern NODE_ID = Pattern.compile("^n(\\d+)_(\\d+)$");

    /**
     * 摘要产物。
     *
     * @param summaryText 每幕一行的锚点链/轨道表摘要（空幕列表返回 null）
     * @param stateSchema 第 1 幕携带的状态词汇表 JSON（无则 null）
     */
    public record Digest(String summaryText, String stateSchema) {
    }

    private final StoryGraphParser parser;

    public StoryGraphDigest(StoryGraphParser parser) {
        this.parser = parser;
    }

    /**
     * 从前幕骨架候选文本列表构建摘要（列表序 = 幕序）。
     */
    public Digest build(List<String> actDrafts) {
        if (actDrafts == null || actDrafts.isEmpty()) {
            return new Digest(null, null);
        }
        StringBuilder sb = new StringBuilder();
        String stateSchema = null;
        for (int i = 0; i < actDrafts.size(); i++) {
            // 幕候选文件是 LLM 原文（含 <story_graph> 标签与前后噪声），
            // 必须走 parse（标签提取）而非 parsePlain（纯 JSON 直入）
            var result = parser.parse(actDrafts.get(i));
            sb.append("- 第 ").append(i + 1).append(" 幕");
            if (!result.ok() || result.getActs().isEmpty()) {
                // 防御性降级：已确认幕正常经校验链不可达此处，标注而非中断
                sb.append("：（骨架损坏，摘要不可用）\n");
                continue;
            }
            StoryAct act = result.getActs().get(0);
            appendActLine(sb, act);
            if (i == 0 && result.getStateSchema() != null) {
                stateSchema = result.getStateSchema();
            }
        }
        return new Digest(sb.toString(), stateSchema);
    }

    private void appendActLine(StringBuilder sb, StoryAct act) {
        sb.append("「").append(act.getTheme() == null ? "" : act.getTheme()).append("」：");
        String entry = entryNodeId(act);
        sb.append("入口 ").append(entry != null ? entry : "（未知）").append("；");
        sb.append("锚点 ");
        List<StoryNode> anchors = act.getNodes() == null ? List.of() : act.getNodes().stream()
                .filter(StoryNode::isAnchorLike)
                .sorted(Comparator.comparing(StoryNode::getId, Comparator.nullsLast(String::compareTo)))
                .toList();
        if (anchors.isEmpty()) {
            sb.append("（无）");
        } else {
            for (int j = 0; j < anchors.size(); j++) {
                StoryNode anchor = anchors.get(j);
                if (j > 0) {
                    sb.append("、");
                }
                sb.append(anchor.getId()).append("（").append(anchor.getBeat() == null ? "" : anchor.getBeat())
                        .append("）");
            }
        }
        sb.append("；出口轨道 ");
        List<ConvergeOrbit> orbits = act.getConvergeTo() == null ? List.of() : act.getConvergeTo();
        if (orbits.isEmpty()) {
            sb.append("（无）");
        } else {
            for (int j = 0; j < orbits.size(); j++) {
                ConvergeOrbit orbit = orbits.get(j);
                if (j > 0) {
                    sb.append("；");
                }
                sb.append(orbit.getOrbit() == null ? "轨道" + (j + 1) : orbit.getOrbit())
                        .append(" = ").append(guardText(orbit.getGuard()));
            }
        }
        sb.append('\n');
    }

    /** 幕入口节点 ID：序号最小（与 StoryGraphValidator.entryNode 同语义的只读复刻）。 */
    private static String entryNodeId(StoryAct act) {
        if (act.getNodes() == null || act.getNodes().isEmpty()) {
            return null;
        }
        String best = null;
        int bestSeq = Integer.MAX_VALUE;
        for (StoryNode node : act.getNodes()) {
            if (node.getId() == null) {
                continue;
            }
            Matcher matcher = NODE_ID.matcher(node.getId());
            if (matcher.matches()) {
                int seq = Integer.parseInt(matcher.group(2));
                if (seq < bestSeq) {
                    bestSeq = seq;
                    best = node.getId();
                }
            } else if (best == null) {
                best = node.getId();   // 非 n<act>_<seq> 形态的防御兜底
            }
        }
        return best;
    }

    /** guard 文本化：{@code key op value}（value 数组渲染为 [a, b]）。 */
    static String guardText(GuardClause guard) {
        if (guard == null) {
            return "（无条件）";
        }
        Object value = guard.getValue();
        String valueText = value instanceof List<?> list ? list.toString() : String.valueOf(value);
        return (guard.getKey() == null ? "?" : guard.getKey()) + " "
                + (guard.getOp() == null ? "?" : guard.getOp()) + " " + valueText;
    }
}
