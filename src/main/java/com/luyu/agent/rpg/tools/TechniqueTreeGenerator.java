package com.luyu.agent.rpg.tools;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.rpg.model.ConstellationTechniqueMap;
import com.luyu.agent.rpg.model.StarTechnique;
import com.luyu.agent.rpg.model.TechniqueNode;
import com.luyu.agent.rpg.model.TechniqueTreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 招式技能树的<b>离线</b>生成器（开发期一次性工具）。
 * <p>
 * 用途：往 {@link ConstellationTechniqueMap} 加了新招式、或要为尚未建树的条目补树时，
 * 让 LLM 产出一份 DAG 初稿，<b>人工审校后</b>粘进 {@link TechniqueTreeMap}。
 * <p>
 * <b>刻意不注册进任何工具池、不暴露 REST 端点</b>：树数据必须静态可审阅、可 diff、可版本化，
 * 运行时重生成会让「同一星座的前置关系」变成不可复现的东西。本类只是省掉手工编排 64 棵树的力气。
 * 运行方式：临时写一个 {@code @SpringBootTest} 调 {@link #generateAllPending()} 并把返回值打印/落盘，
 * 用完删掉那个测试即可（仓库现无 {@code @SpringBootTest} 先例，故不留常驻入口）。
 * <p>
 * <b>为什么必须要人工审校</b>：同一星座的招式目录可能混着两类东西 ——
 * 同角色的进阶链（天龙座庐山系，顺序即强弱）与<b>多个不同角色的招式合集</b>
 * （双子座 = 撒加/加隆线 + Ω 帕拉朵珂丝线，巨蟹座含席勒线）。后者按列表顺序拉直会产生
 * 语义荒谬的路径（让帕拉朵珂丝的「幸运之壁」成为「银河星爆」的前置）。模型无法稳定区分二者，
 * prompt 里已显式注入该约束，但仍需人眼复核。
 * <p>
 * 模型装配复用 {@code WorkshopLlmGenerator} 的模式：{@link ChatClientRegistry#forRole(String)}
 * 取 workshop 角色的纯净 client（无 advisor）非流式 {@code .call()}。
 * 注意不能用 {@code forChat()} —— 对话 client 挂 SessionMemoryAdvisor，单次调用无 sessionId 会直接抛异常。
 */
@Service
public class TechniqueTreeGenerator {

    private static final Logger log = LoggerFactory.getLogger(TechniqueTreeGenerator.class);

    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 招式名在目录中的呈现格式：名字 | 描述 | 来源版本 | 攻击类型 */
    private static final String CATALOG_LINE_FORMAT = "%d. %s ｜ %s ｜ 来源：%s ｜ 类型：%s";

    /**
     * 生成提示词。
     * <p>
     * 输出契约是 <b>JSON</b> 而非直接的 Java 源码片段：让模型吐 {@code node("A", "B")} 很容易
     * 出现括号不配对、引号未转义、中文引号混入等问题，且无法校验；吐 JSON 则由
     * {@link #parseDraft} 做结构校验与名字白名单过滤，源码格式由 {@link #toSourceEntry} 统一产出。
     */
    private static final String TREE_GEN_PROMPT = """
            你是圣斗士星矢系列的武学考据顾问。下面给出某个星座/星辰收录的全部招式，
            请为它们编排一棵「前置技能树」：必须先学会前置招式，才能领悟后继招式。

            星座/星辰：%s
            阵营：%s

            招式目录（名字必须逐字沿用，禁止改写、禁止新增、禁止遗漏）：
            %s

            编排规则：
            1. 描述里的进阶措辞是最强信号——「上位变体」「强化版」「改良版」「究极」「最终奥义」
               意味着该招式位于被提及者的下游。
            2. 目录可能是【多个不同角色的招式合集】，此时必须按角色线【分支】，
               绝不可按列表顺序拉直成一条链。
               例：双子座含撒加/加隆线（银河星爆、异次元空间、幻胧魔皇拳）与 Ω 帕拉朵珂丝线
               （幸运之壁、歧路幻景、绯红雅努斯、世界即终结、最后终点），两线各自成支、互不为前置；
               例：巨蟹座含迪斯马斯克的积尸气主线与独立的水柱拳。
            3. 来源版本的先后（经典系列 → 黄金魂/冥界篇/Asgard篇 → 冥王神话LC → Ω）
               可作层级参考，但【不是】唯一依据——后期作品里的基础招式不该成为前期奥义的前置。
            4. 攻击类型不同（PHYSICAL 物理 / SPATIAL 空间 / MENTAL 精神）通常意味着并行支线，
               而非上下游关系。
            5. 招牌招式（多为经典系列的第一招）应是根节点，保证新人建档时有招可发。

            结构硬约束（违反即产物不可用）：
            - 至少 1 个根节点（prerequisites 为空数组）
            - 必须是有向无环图，禁止任何形式的环
            - prerequisites 只能引用本次目录中出现的招式名
            - 禁止自引用；每个招式名只出现一次
            - 允许一个节点有多个前置（DAG 而非严格的树）

            仅输出如下 JSON，不要包含任何其他文本、解释或 Markdown 代码围栏：
            {"nodes":[{"name":"招式名","prerequisites":["前置招式名"]}]}
            """;

    private final ChatClientRegistry chatClientRegistry;

    public TechniqueTreeGenerator(ChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    /**
     * 一个待建树的条目。
     *
     * @param faction       阵营（saint/godwarrior/specter）
     * @param constellation 星座/星辰名
     * @param techniques    该条目的招式目录
     */
    public record PendingEntry(String faction, String constellation, List<StarTechnique> techniques) {

        String label() {
            return faction + "/" + constellation;
        }
    }

    /**
     * 工作队列：招式数 ≥ 2 且 {@link TechniqueTreeMap} 中尚无树定义的条目。
     * <p>
     * 基线数据已全部建满，故正常返回空列表；往招式表加了新招式后这里会重新出现待办。
     */
    public List<PendingEntry> pendingEntries() {
        List<PendingEntry> pending = new ArrayList<>();
        for (String faction : TechniqueTreeMap.FACTIONS) {
            ConstellationTechniqueMap.rawTechniqueTable(faction).forEach((constellation, techs) -> {
                if (techs.size() >= 2 && TechniqueTreeMap.getTree(constellation, faction).isEmpty()) {
                    pending.add(new PendingEntry(faction, constellation, techs));
                }
            });
        }
        return pending;
    }

    /**
     * 为单个条目生成可直接粘贴的 {@code Map.entry(...)} 源码块。
     *
     * @return 源码块；LLM 调用失败或产物为空时返回 {@code null}
     */
    public String generateDraft(String constellation, String faction) {
        List<StarTechnique> catalog = ConstellationTechniqueMap.rawTechniqueTable(faction).get(constellation);
        if (catalog == null || catalog.size() < 2) {
            log.warn("跳过 {}/{}：招式数不足 2，无需建树", faction, constellation);
            return null;
        }
        String response = callLlm(buildPrompt(constellation, faction, catalog));
        if (response == null) {
            return null;
        }
        Draft draft = parseDraft(response, namesOf(catalog));
        if (draft.nodes().isEmpty()) {
            log.warn("{}/{} 的生成产物为空，需人工编排", faction, constellation);
            return null;
        }
        if (!draft.ignoredNames().isEmpty() || !draft.ignoredPrereqs().isEmpty()) {
            log.warn("{}/{} 生成产物有越界项被丢弃——招式名 {}、前置名 {}；请人工复核",
                    faction, constellation, draft.ignoredNames(), draft.ignoredPrereqs());
        }
        log.info("{}/{} 生成 {} 个节点（{} 个根）", faction, constellation,
                draft.nodes().size(), draft.nodes().stream().filter(TechniqueNode::isRoot).count());
        return toSourceEntry(constellation, topoOrder(draft.nodes()));
    }

    /** 为全部待建条目生成源码块，条目之间以空行分隔。 */
    public String generateAllPending() {
        List<PendingEntry> pending = pendingEntries();
        if (pending.isEmpty()) {
            return "// 无待建树条目：招式表中所有多技能星座均已建树";
        }
        StringBuilder sb = new StringBuilder();
        for (PendingEntry e : pending) {
            String block = generateDraft(e.constellation(), e.faction());
            sb.append("// ---- ").append(e.label()).append(" ----\n");
            sb.append(block == null ? "// 生成失败，需人工编排\n" : block).append('\n');
        }
        return sb.toString();
    }

    // ==================== 纯函数部分（无需 Spring 上下文即可单测） ====================

    /** 拼装提示词：把招式目录渲染成带序号的清单。 */
    static String buildPrompt(String constellation, String faction, List<StarTechnique> catalog) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < catalog.size(); i++) {
            StarTechnique t = catalog.get(i);
            list.append(CATALOG_LINE_FORMAT.formatted(
                            i + 1, t.name(), t.description(), t.source(), t.attackType()))
                    .append('\n');
        }
        return TREE_GEN_PROMPT.formatted(constellation, factionLabel(faction), list.toString().stripTrailing());
    }

    /**
     * 解析结果。
     *
     * @param nodes          通过白名单过滤的节点
     * @param ignoredNames   被丢弃的招式名（不在目录内，即模型改写或臆造了名字）
     * @param ignoredPrereqs 被丢弃的前置名（不在目录内，即悬空引用）
     */
    record Draft(List<TechniqueNode> nodes, List<String> ignoredNames, List<String> ignoredPrereqs) {

        static final Draft EMPTY = new Draft(List.of(), List.of(), List.of());
    }

    /**
     * 把 LLM 响应解析成节点列表，并用招式目录做白名单过滤。
     * <p>
     * 过滤而非报错：模型偶尔会「顺手优化」招式名（如把「庐山升龙霸」写成「升龙霸」），
     * 这种名字进树就是悬空引用。丢弃后 {@code TechniqueTreeMapValidationTest} 的覆盖断言
     * 会因为节点缺失而失败，把问题暴露在 CI 而不是运行时。
     *
     * @param catalogNames 该条目的合法招式名集合
     */
    static Draft parseDraft(String llmResponse, Set<String> catalogNames) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return Draft.EMPTY;
        }
        JsonNode root;
        try {
            root = mapper.readTree(stripCodeFence(llmResponse));
        } catch (Exception e) {
            log.warn("招式树生成产物不是合法 JSON: {}", e.getMessage());
            return Draft.EMPTY;
        }
        JsonNode nodesNode = root.path("nodes");
        if (!nodesNode.isArray()) {
            log.warn("招式树生成产物缺少 nodes 数组");
            return Draft.EMPTY;
        }

        List<TechniqueNode> nodes = new ArrayList<>();
        List<String> ignoredNames = new ArrayList<>();
        Set<String> ignoredPrereqs = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();

        for (JsonNode item : nodesNode) {
            String name = item.path("name").asText("").trim();
            if (name.isEmpty() || !catalogNames.contains(name)) {
                if (!name.isEmpty()) {
                    ignoredNames.add(name);
                }
                continue;
            }
            if (!seen.add(name)) {
                ignoredNames.add(name + "（重复）");
                continue;
            }
            List<String> prereqs = new ArrayList<>();
            JsonNode prereqNode = item.path("prerequisites");
            if (prereqNode.isArray()) {
                for (JsonNode p : prereqNode) {
                    String pname = p.asText("").trim();
                    if (pname.isEmpty()) {
                        continue;
                    }
                    if (!catalogNames.contains(pname)) {
                        ignoredPrereqs.add(pname);
                    } else if (!pname.equals(name) && !prereqs.contains(pname)) {
                        prereqs.add(pname);      // 自引用与重复前置就地剔除
                    }
                }
            }
            nodes.add(new TechniqueNode(name, prereqs));
        }
        return new Draft(List.copyOf(nodes), List.copyOf(ignoredNames), List.copyOf(ignoredPrereqs));
    }

    /**
     * 渲染成可直接粘进 {@link TechniqueTreeMap} 的源码块（缩进对齐既有表项）。
     * <p>
     * 前置名逐个转义双引号 —— 招式名里现无引号，但这是生成源码，防御性转义不可省。
     */
    static String toSourceEntry(String constellation, List<TechniqueNode> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("            Map.entry(\"").append(escape(constellation)).append("\", tree(\n");
        for (int i = 0; i < nodes.size(); i++) {
            TechniqueNode n = nodes.get(i);
            sb.append("                    node(\"").append(escape(n.name())).append('"');
            for (String p : n.prerequisites()) {
                sb.append(", \"").append(escape(p)).append('"');
            }
            sb.append(')');
            // 末位节点还要额外闭合 tree( 与 Map.entry( 两层括号，非末位只跟逗号
            sb.append(i == nodes.size() - 1 ? "))," : ",");
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 按拓扑深度重排节点，让源码读起来是「基础招在前、奥义在后」。
     * <p>
     * 有环时退回模型的声明顺序 —— 环由校验测试负责报错，这里不做二次判断。
     */
    static List<TechniqueNode> topoOrder(List<TechniqueNode> nodes) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, TechniqueNode> byName = new LinkedHashMap<>();
        for (TechniqueNode n : nodes) {
            byName.put(n.name(), n);
            indegree.put(n.name(), n.prerequisites().size());
            for (String p : n.prerequisites()) {
                successors.computeIfAbsent(p, k -> new ArrayList<>()).add(n.name());
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((name, deg) -> {
            if (deg == 0) {
                queue.add(name);
            }
        });
        List<TechniqueNode> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            ordered.add(byName.get(cur));
            for (String next : successors.getOrDefault(cur, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        return ordered.size() == nodes.size() ? ordered : nodes;
    }

    // ==================== 私有辅助 ====================

    private String callLlm(String prompt) {
        try {
            ChatClient client = chatClientRegistry.forRole("workshop");
            return client.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "workshop"))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("招式树生成的 LLM 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private static Set<String> namesOf(List<StarTechnique> catalog) {
        Set<String> names = new LinkedHashSet<>();
        catalog.forEach(t -> names.add(t.name()));
        return names;
    }

    private static String factionLabel(String faction) {
        if (faction == null) {
            return "圣斗士";
        }
        return switch (faction) {
            case TechniqueTreeMap.FACTION_SPECTER -> "冥斗士";
            case TechniqueTreeMap.FACTION_GOD_WARRIOR -> "神斗士";
            default -> "圣斗士";
        };
    }

    /** 剥掉 ```json / ``` 围栏（尽管 prompt 已禁止，模型仍常加） */
    private static String stripCodeFence(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
