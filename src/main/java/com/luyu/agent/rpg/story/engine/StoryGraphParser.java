package com.luyu.agent.rpg.story.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.story.model.ConvergeOrbit;
import com.luyu.agent.rpg.story.model.GuardClause;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 骨架 JSON 解析与归一器（story-graph-model 任务 1.2）。
 * <p>
 * 把 LLM 输出的 {@code <story_graph>} 块宽容解析为 model 实体：
 * <ul>
 *   <li>标签提取：完整闭合块优先，未闭合块按开标签截断兜底
 *       （同 {@code StateDeltaSanitizer} 模式，容忍空白变体与大小写漂移）</li>
 *   <li>蛇形/驼峰双 key 兼容（同 {@code GameLoopService.firstPresent} 模式）</li>
 *   <li>节点 ID 幕命名空间校验：{@code n<act>_<seq>} 前缀必须等于所在幕序号，
 *       违规报错拒绝（跨幕引用是拓扑校验的修复对象，ID 违规直接拒）</li>
 *   <li>幕节点数上限 {@value #MAX_NODES_PER_ACT} 拒绝（分幕小契约，design D2）</li>
 * </ul>
 * 语义校验（guard 语法/词汇表/拓扑/变体覆盖）不在此层——分别由
 * GuardEvaluator/StateSchemaValidator/StoryGraphValidator 承担（任务 1.3-1.6）。
 * 解析失败返回 errors 非空的结果，调用方走 LLM fallback 修复一次。
 */
@Component
public class StoryGraphParser {

    private static final Logger log = LoggerFactory.getLogger(StoryGraphParser.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 幕内节点数上限（分幕小契约，design D2：单次调用契约 ≤25 节点保稳定） */
    public static final int MAX_NODES_PER_ACT = 25;

    /** 匹配完整 <story_graph>...</story_graph> 块（容忍标签内空白变体，如 < story_graph >） */
    private static final Pattern BLOCK_PATTERN =
            Pattern.compile("<\\s*story_graph\\s*>\\s*([\\s\\S]*?)\\s*<\\s*/\\s*story_graph\\s*>",
                    Pattern.CASE_INSENSITIVE);

    /** 仅匹配开标签（用于未闭合块的截断与兜底提取） */
    private static final Pattern OPEN_TAG_PATTERN =
            Pattern.compile("<\\s*story_graph\\s*>", Pattern.CASE_INSENSITIVE);

    /** 节点 ID 幕命名空间：n<act>_<seq> */
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("^n(\\d+)_(\\d+)$");

    /** 边 ID 幕命名空间：e&lt;act&gt;_&lt;seq&gt;（模型边）或 x&lt;act&gt;_&lt;seq&gt;（引擎拼接边，2.5 Stitcher 独立命名空间） */
    private static final Pattern EDGE_ID_PATTERN = Pattern.compile("^[ex](\\d+)_(\\d+)$");

    /** 幕 ID 序号提取（act1/act2/... 或纯数字形态） */
    private static final Pattern DIGIT_PATTERN = Pattern.compile("(\\d+)");

    /**
     * 从 LLM 输出解析骨架（含 {@code <story_graph>} 标签提取，S4.5 幕生成场景）。
     * <p>
     * 顶层兼容两种形态：全图 {@code {"acts":[...], "improvise_mode":..., "state_schema":...}}
     * 或单幕 {@code {"id":"act1","theme":...,"nodes":[...],"edges":[...]}}（幂号亦可由
     * 顶层 {@code "act": <幕号>} 数字字段携带，幕 id 缺失时优先取该字段补齐）。
     */
    public StoryGraphParseResult parse(String llmOutput) {
        return parse(llmOutput, 0);
    }

    /**
     * 从 LLM 输出解析骨架（幕号已知场景，S4.5 逐幕生成链）。
     * <p>
     * 编排层生成第 N 幕时幕号是 ground truth：幕对象缺失 id 与顶层 act 字段时按
     * {@code expectedActNumber} 确定性补齐——否则单幕解析按数组序号恒补成 act1，
     * 节点 {@code n<N>_<seq>} 全员被误报幕命名空间违规，掩盖缺 beat/自创 type 等
     * 真实错误（实测第 4 幕漏写顶层字段即触发此连锁）。
     *
     * @param expectedActNumber 编排层已知幕号（1 起；≤0 视为未提供，走通用兜底链）
     */
    public StoryGraphParseResult parse(String llmOutput, int expectedActNumber) {
        List<String> errors = new ArrayList<>();
        String blockText = extractBlockText(llmOutput, errors);
        if (blockText == null) {
            return new StoryGraphParseResult(List.of(), null, null, errors);
        }
        return parseBlockText(blockText, expectedActNumber, errors);
    }

    /**
     * 解析纯 JSON 文本（无标签包裹；工坊导入/手工建图场景）。
     */
    public StoryGraphParseResult parsePlain(String json) {
        return parsePlain(json, 0);
    }

    /** 解析纯 JSON 文本（幕号已知场景；参数语义同 {@link #parse(String, int)}）。 */
    public StoryGraphParseResult parsePlain(String json, int expectedActNumber) {
        List<String> errors = new ArrayList<>();
        String text = json == null ? "" : json.trim();
        if (text.isEmpty()) {
            errors.add("JSON 文本为空");
            return new StoryGraphParseResult(List.of(), null, null, errors);
        }
        return parseBlockText(text, expectedActNumber, errors);
    }

    // ==================== 标签提取 ====================

    /**
     * 提取块内文本：优先完整闭合块，兼容未闭合块（从开标签截取到文本末尾）。
     *
     * @return 块内文本，或 null（无块；errors 已记录原因）
     */
    private String extractBlockText(String llmOutput, List<String> errors) {
        if (llmOutput == null || llmOutput.isEmpty()) {
            errors.add("输出为空，未找到 <story_graph> 块");
            return null;
        }
        Matcher matcher = BLOCK_PATTERN.matcher(llmOutput);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        Matcher open = OPEN_TAG_PATTERN.matcher(llmOutput);
        if (open.find()) {
            log.warn("<story_graph> 块未闭合（缺少 </story_graph>），按开标签截断提取");
            return llmOutput.substring(open.end()).trim();
        }
        errors.add("未找到 <story_graph> 块");
        return null;
    }

    // ==================== JSON 归一 ====================

    private StoryGraphParseResult parseBlockText(String blockText, int expectedActNumber,
                                                  List<String> errors) {
        JsonNode root;
        try {
            root = mapper.readTree(blockText);
        } catch (Exception e) {
            errors.add("<story_graph> 块 JSON 解析失败：" + e.getMessage());
            return new StoryGraphParseResult(List.of(), null, null, errors);
        }
        if (root == null || !root.isObject()) {
            errors.add("骨架 JSON 顶层不是对象");
            return new StoryGraphParseResult(List.of(), null, null, errors);
        }

        JsonNode actsNode = root.path("acts");
        if (actsNode.isArray()) {
            String improviseMode = textOrNull(root, "improvise_mode", "improviseMode");
            String stateSchema = stateSchemaJson(root);
            List<StoryAct> acts = new ArrayList<>();
            for (int i = 0; i < actsNode.size(); i++) {
                // 全图形态幂号由各幕自身携带（expectedActNumber 是单幕语义，不注入）
                StoryAct act = parseAct(unwrapAct(actsNode.get(i)), i, 0, errors);
                if (act != null) {
                    acts.add(act);
                }
            }
            if (actsNode.isEmpty()) {
                errors.add("acts 数组为空（至少需要一幕）");
            }
            return new StoryGraphParseResult(acts, improviseMode, stateSchema, errors);
        }

        if (looksLikeAct(root)) {
            StoryAct act = parseAct(root, 0, expectedActNumber, errors);
            // 单幕输出同样可携带顶层 state_schema（S4.5 第 1 幕产出词汇表，
            // story-graph-authoring tasks 2.2：后续幕沿用）；improviseMode 仍为 null
            //（作品级配置由整稿通过时另定，单幕生成阶段无意义）
            return new StoryGraphParseResult(
                    act == null ? List.of() : List.of(act), null, stateSchemaJson(root), errors);
        }

        errors.add("骨架 JSON 顶层既无 acts[] 数组也不像单幕结构（缺少 nodes/theme/id）");
        return new StoryGraphParseResult(List.of(), null, null, errors);
    }

    /** 兼容 {"act": {...}} 单层包裹形态（部分模型习惯包一层） */
    private JsonNode unwrapAct(JsonNode node) {
        JsonNode inner = node.path("act");
        return inner.isObject() ? inner : node;
    }

    /** 单幕判定：有 nodes / theme / id 任一即视为幕对象（acts 分支已优先） */
    private boolean looksLikeAct(JsonNode node) {
        return node.has("nodes") || node.has("theme") || node.has("id");
    }

    // ==================== 幕归一 ====================

    private StoryAct parseAct(JsonNode actNode, int index, int expectedActNumber, List<String> errors) {
        if (!actNode.isObject()) {
            errors.add("第 " + (index + 1) + " 幕不是 JSON 对象");
            return null;
        }
        StoryAct act = new StoryAct();
        String id = textOrNull(actNode, "id");
        if (id == null || id.isBlank()) {
            id = fallbackActId(actNode, index, expectedActNumber);
            log.debug("幕缺失 id，确定性补齐：{}", id);
        }
        act.setId(id.trim());
        act.setTheme(textOrNull(actNode, "theme"));
        act.setEndingBeat(textOrNull(actNode, "ending_beat", "endingBeat"));
        act.setEntryGuard(parseGuard(firstPresent(actNode, "entry_guard", "entryGuard"),
                "幕 " + id + " entry_guard", errors));
        act.setConvergeTo(parseConvergeTo(firstPresent(actNode, "converge_to", "convergeTo"), id, errors));
        act.setAllowExplicitVariants(boolOrNull(actNode, "allow_explicit_variants", "allowExplicitVariants"));

        Integer actNum = extractActNumber(act.getId());
        if (actNum == null) {
            errors.add("幕 ID 无法解析序号（应为 act1/act2 形式）：" + act.getId());
        }

        List<StoryNode> nodes = new ArrayList<>();
        JsonNode nodesNode = actNode.path("nodes");
        if (nodesNode.isArray()) {
            if (nodesNode.size() > MAX_NODES_PER_ACT) {
                errors.add("幕 " + act.getId() + " 节点数超限：" + nodesNode.size()
                        + " > " + MAX_NODES_PER_ACT + "（分幕小契约上限）");
            }
            for (JsonNode n : nodesNode) {
                StoryNode node = parseNode(n, act.getId(), actNum, errors);
                if (node != null) {
                    nodes.add(node);
                }
            }
        } else {
            errors.add("幕 " + act.getId() + " 缺少 nodes 数组");
        }
        act.setNodes(nodes);

        List<StoryEdge> edges = new ArrayList<>();
        JsonNode edgesNode = actNode.path("edges");
        if (edgesNode.isArray()) {
            Set<String> usedEdgeIds = new HashSet<>();
            for (JsonNode e : edgesNode) {
                StoryEdge edge = parseEdge(e, act.getId(), actNum, usedEdgeIds, errors);
                if (edge != null) {
                    edges.add(edge);
                }
            }
        } else {
            errors.add("幕 " + act.getId() + " 缺少 edges 数组");
        }
        act.setEdges(edges);
        return act;
    }

    /** 从幕 ID 提取序号（act2 → 2；无数字返回 null，调用方已报错） */
    private Integer extractActNumber(String actId) {
        Matcher m = DIGIT_PATTERN.matcher(actId);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    /**
     * 幂号兜底来源（按可信度降序）：顶层 {@code "act": <幕号>} 数字字段优先（S4.5
     * 单幕契约形态——单幕解析时数组序号恒为 0 无法反映真实幂号，第 2 幕会因此
     * 被补成 act1 导致节点 n2_xx 幕命名空间违规）；编排层已知幕号
     * {@code expectedActNumber} 次之（幕号 ground truth，防漏写顶层字段误补 act1
     * 连锁误报命名空间违规）；节点 ID 幕号共识再次（无幕号上下文时全部节点同
     * 前缀 n<k>_ 按 k 补）；数字字符串（"2"）宽容归一；末位按数组序号补齐。
     */
    private String fallbackActId(JsonNode actNode, int index, int expectedActNumber) {
        JsonNode actNumNode = actNode.path("act");
        if (actNumNode.isNumber()) {
            return "act" + actNumNode.asInt();
        }
        if (actNumNode.isTextual()) {
            String text = actNumNode.asText().trim();
            if (text.matches("\\d+")) {
                return "act" + text;
            }
        }
        if (expectedActNumber > 0) {
            return "act" + expectedActNumber;
        }
        Integer consensus = consensusActNumber(actNode.path("nodes"));
        if (consensus != null) {
            return "act" + consensus;
        }
        return "act" + (index + 1);
    }

    /**
     * 节点 ID 幕号共识：nodes 全部为 {@code n<k>_<seq>} 形态且共享同一 k 时返回 k。
     * 任一节点缺失 id / 形态不符 / 前缀不一致即放弃共识（返回 null 走序号兜底）——
     * 共识只用于幂号补齐，不掩盖真正的跨幕混排（混排由命名空间校验报错）。
     */
    private Integer consensusActNumber(JsonNode nodesNode) {
        if (!nodesNode.isArray() || nodesNode.isEmpty()) {
            return null;
        }
        Integer consensus = null;
        for (JsonNode n : nodesNode) {
            String id = n.isObject() ? textOrNull(n, "id") : null;
            if (id == null) {
                return null;
            }
            Matcher m = NODE_ID_PATTERN.matcher(id.trim());
            if (!m.matches()) {
                return null;
            }
            int actNum = Integer.parseInt(m.group(1));
            if (consensus == null) {
                consensus = actNum;
            } else if (consensus != actNum) {
                return null;
            }
        }
        return consensus;
    }

    // ==================== 节点归一 ====================

    private StoryNode parseNode(JsonNode nodeNode, String actId, Integer actNum, List<String> errors) {
        if (!nodeNode.isObject()) {
            errors.add("幕 " + actId + " 存在非对象节点");
            return null;
        }
        StoryNode node = new StoryNode();
        String id = textOrNull(nodeNode, "id");
        if (id == null || id.isBlank()) {
            errors.add("幕 " + actId + " 存在缺失 id 的节点（节点 id 必填）");
            return null;
        }
        node.setId(id.trim());

        if (actNum != null) {
            Matcher m = NODE_ID_PATTERN.matcher(node.getId());
            if (!m.matches()) {
                errors.add("节点 ID 不符合 n<act>_<seq> 幕命名空间格式：" + node.getId() + "（幕 " + actId + "）");
            } else if (Integer.parseInt(m.group(1)) != actNum) {
                errors.add("节点 ID 幕命名空间违规：" + node.getId()
                        + " 前缀属于幕 " + m.group(1) + "（当前幕 " + actId + "）");
            }
        }

        String beat = textOrNull(nodeNode, "beat");
        if (beat == null || beat.isBlank()) {
            errors.add("节点 " + node.getId() + " 缺少 beat（物化主指示，必填）");
        } else {
            node.setBeat(beat.trim());
        }

        String type = textOrNull(nodeNode, "type");
        String normalizedType = type == null ? StoryNode.TYPE_SCENE : type.trim().toLowerCase();
        if (!StoryNode.TYPE_SCENE.equals(normalizedType) && !StoryNode.TYPE_ANCHOR.equals(normalizedType)
                && !StoryNode.TYPE_ENDING.equals(normalizedType)) {
            errors.add("节点 " + node.getId() + " type 非法（应为 scene/anchor/ending）：" + type);
        } else {
            node.setType(normalizedType);
        }

        node.setImprovise(boolOrNull(nodeNode, "improvise"));
        node.setEntryGuard(parseGuard(firstPresent(nodeNode, "entry_guard", "entryGuard"),
                "节点 " + node.getId() + " entry_guard", errors));
        node.setOnEnter(parseOnEnter(firstPresent(nodeNode, "on_enter", "onEnter"), node.getId(), errors));
        node.setDivergenceDimensions(stringListOrNull(
                firstPresent(nodeNode, "divergence_dimensions", "divergenceDimensions")));
        node.setVariants(parseVariants(firstPresent(nodeNode, "variants"), node.getId(), errors));
        return node;
    }

    // ==================== 边归一 ====================

    private StoryEdge parseEdge(JsonNode edgeNode, String actId, Integer actNum,
                                Set<String> usedEdgeIds, List<String> errors) {
        if (!edgeNode.isObject()) {
            errors.add("幕 " + actId + " 存在非对象边");
            return null;
        }
        StoryEdge edge = new StoryEdge();
        String from = textOrNull(edgeNode, "from");
        String to = textOrNull(edgeNode, "to");
        if (from == null || from.isBlank()) {
            errors.add("幕 " + actId + " 存在缺失 from 的边");
            return null;
        }
        edge.setFrom(from.trim());
        if (to == null || to.isBlank()) {
            errors.add("边 " + edge.getFrom() + " -> ? 缺失 to（幕 " + actId + "）");
            return null;
        }
        edge.setTo(to.trim());
        edge.setLabel(textOrNull(edgeNode, "label"));
        edge.setQuote(textOrNull(edgeNode, "quote"));
        edge.setHint(textOrNull(edgeNode, "hint"));
        edge.setGuard(parseGuard(firstPresent(edgeNode, "guard"),
                "幕 " + actId + " 边 " + edge.getFrom() + "->" + edge.getTo(), errors));
        edge.setEffects(mapOrNull(firstPresent(edgeNode, "effects"),
                "幕 " + actId + " 边 " + edge.getFrom() + "->" + edge.getTo() + " effects", errors));

        String edgeId = textOrNull(edgeNode, "id");
        if (edgeId == null || edgeId.isBlank()) {
            // 模型可省略边 ID，引擎按序确定性补齐（跳过已用序号防冲突）
            edge.setId(nextEdgeId(actNum, usedEdgeIds));
            log.debug("边缺失 id，确定性补齐：{}", edge.getId());
        } else {
            edgeId = edgeId.trim();
            if (actNum != null) {
                Matcher m = EDGE_ID_PATTERN.matcher(edgeId);
                if (!m.matches()) {
                    errors.add("边 ID 不符合 e/x<act>_<seq> 幕命名空间格式（x 为引擎拼接边命名空间）："
                            + edgeId + "（幕 " + actId + "）");
                } else if (Integer.parseInt(m.group(1)) != actNum) {
                    errors.add("边 ID 幕命名空间违规：" + edgeId + "（当前幕 " + actId + "）");
                }
            }
            if (!usedEdgeIds.add(edgeId)) {
                errors.add("边 ID 重复：" + edgeId + "（幕 " + actId + "）");
            }
            edge.setId(edgeId);
        }
        return edge;
    }

    /** 确定性补齐边 ID：e<act>_<seq> 递增跳过已用；actNum 不可解析时以 0 兜底（该幕已报错） */
    private String nextEdgeId(Integer actNum, Set<String> usedEdgeIds) {
        int act = actNum == null ? 0 : actNum;
        int seq = 1;
        while (usedEdgeIds.contains("e" + act + "_" + seq)) {
            seq++;
        }
        String id = "e" + act + "_" + seq;
        usedEdgeIds.add(id);
        return id;
    }

    // ==================== guard / 轨道 / 变体 / map 归一 ====================

    private GuardClause parseGuard(JsonNode guardNode, String location, List<String> errors) {
        if (guardNode == null || guardNode.isMissingNode() || guardNode.isNull()) {
            return null;
        }
        if (!guardNode.isObject()) {
            errors.add(location + " 不是对象");
            return null;
        }
        String key = textOrNull(guardNode, "key");
        String op = textOrNull(guardNode, "op");
        if (key == null || key.isBlank() || op == null || op.isBlank()) {
            errors.add(location + " 缺少 key 或 op（简单谓词 key op value）");
            return null;
        }
        JsonNode valueNode = guardNode.path("value");
        if (valueNode.isMissingNode()) {
            errors.add(location + " 缺少 value");
            return null;
        }
        return new GuardClause(key.trim(), op.trim(), toPlainValue(valueNode));
    }

    private List<ConvergeOrbit> parseConvergeTo(JsonNode node, String actId, List<String> errors) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            errors.add("幕 " + actId + " converge_to 不是数组");
            return null;
        }
        List<ConvergeOrbit> orbits = new ArrayList<>();
        for (JsonNode o : node) {
            if (!o.isObject()) {
                errors.add("幕 " + actId + " converge_to 存在非对象轨道项");
                continue;
            }
            String orbit = textOrNull(o, "orbit");
            if (orbit == null || orbit.isBlank()) {
                errors.add("幕 " + actId + " converge_to 轨道项缺失 orbit 名");
                continue;
            }
            ConvergeOrbit co = new ConvergeOrbit();
            co.setOrbit(orbit.trim());
            co.setGuard(parseGuard(o.path("guard"), "幕 " + actId + " converge_to[" + orbit + "]", errors));
            orbits.add(co);
        }
        return orbits;
    }

    private List<StoryVariant> parseVariants(JsonNode node, String nodeId, List<String> errors) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            errors.add("节点 " + nodeId + " variants 不是数组");
            return null;
        }
        List<StoryVariant> variants = new ArrayList<>();
        for (JsonNode v : node) {
            if (!v.isObject()) {
                errors.add("节点 " + nodeId + " variants 存在非对象项");
                continue;
            }
            String beatVariant = textOrNull(v, "beat_variant", "beatVariant");
            if (beatVariant == null || beatVariant.isBlank()) {
                errors.add("节点 " + nodeId + " 变体缺失 beat_variant（基调锁定的主指示，必填）");
                continue;
            }
            StoryVariant sv = new StoryVariant();
            sv.setGuard(parseGuard(v.path("guard"), "节点 " + nodeId + " variant", errors));
            sv.setBeatVariant(beatVariant.trim());
            sv.setBodySeed(textOrNull(v, "body_seed", "bodySeed"));
            variants.add(sv);
        }
        return variants;
    }

    /**
     * on_enter 归一：兼容直接 effects map 与 {@code {"effects": {...}}} 包裹两种形态
     * （spec 表述为 on_enter.effects；state_schema 键为领域键如 trust_信长，与
     * 包裹键 effects 冲突概率可忽略）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseOnEnter(JsonNode node, String nodeId, List<String> errors) {
        Map<String, Object> raw = mapOrNull(node, "节点 " + nodeId + " on_enter", errors);
        if (raw == null) {
            return null;
        }
        if (raw.size() == 1 && "effects".equals(raw.keySet().iterator().next())
                && raw.get("effects") instanceof Map) {
            return (Map<String, Object>) raw.get("effects");
        }
        return raw;
    }

    private Map<String, Object> mapOrNull(JsonNode node, String location, List<String> errors) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(location + " 不是对象");
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), toPlainValue(e.getValue())));
        return map;
    }

    private List<String> stringListOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                list.add(item.asText().trim());
            }
        }
        return list;
    }

    /** state_schema 透传为规范 JSON 字符串（结构归一是 StateSchemaValidator 的事，任务 1.4） */
    private String stateSchemaJson(JsonNode root) {
        JsonNode schema = firstPresent(root, "state_schema", "stateSchema");
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(schema);
        } catch (Exception e) {
            return schema.toString();
        }
    }

    // ==================== 通用取值 ====================

    /** 依次尝试多个 key，返回首个存在且非 missing 的节点（兼容蛇形/驼峰两种命名） */
    private JsonNode firstPresent(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode candidate = node.path(key);
            if (!candidate.isMissingNode()) {
                return candidate;
            }
        }
        return null;
    }

    private String textOrNull(JsonNode node, String... keys) {
        JsonNode hit = firstPresent(node, keys);
        if (hit == null || hit.isNull()) {
            return null;
        }
        String text = hit.isTextual() ? hit.asText() : hit.toString();
        return text == null || text.isBlank() ? null : text;
    }

    private Boolean boolOrNull(JsonNode node, String... keys) {
        JsonNode hit = firstPresent(node, keys);
        if (hit == null || hit.isNull()) {
            return null;
        }
        if (hit.isBoolean()) {
            return hit.asBoolean();
        }
        String s = hit.isTextual() ? hit.asText().trim() : null;
        if ("true".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** JsonNode 转标准 Java 类型（guard value/effects 值的纯净载体，避免 JsonNode 泄漏进 model） */
    private Object toPlainValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(toPlainValue(item));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), toPlainValue(e.getValue())));
            return map;
        }
        return node.toString();
    }
}
