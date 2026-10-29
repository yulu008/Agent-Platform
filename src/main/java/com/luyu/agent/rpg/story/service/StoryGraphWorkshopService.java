package com.luyu.agent.rpg.story.service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.story.engine.StateSchemaValidator;
import com.luyu.agent.rpg.story.engine.StoryGraphFinalizeReport;
import com.luyu.agent.rpg.story.engine.StoryGraphValidator;
import com.luyu.agent.rpg.story.engine.TopologyResult;
import com.luyu.agent.rpg.story.engine.VariantCoverageValidator;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.model.StoryNode;
import com.luyu.agent.rpg.story.model.StoryVariant;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.RpgStoryRunRepository;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 工坊故事图服务（story-graph-authoring tasks 3.1）。
 * <p>
 * 作者侧 CRUD：建图（校验即兴三档与词汇表语法）→ 图级全量保存（幕/节点/边/
 * 变体都在 graph_json 里，前端表单组装提交）+ 保存时即时校验（词汇表/guard
 * 语法——spec「编辑视图修改变体并即时校验：保存时即时报出谓词语法错误，
 * 不落库」）→ 整稿通过执行全图确定性校验链（词汇表 + 变体覆盖性 + 拓扑
 * 校验/确定性修复/跨幕对接/全图可达性——与 planner S4.5 整稿同一校验器
 * 组件，spec「手工建图走同一校验」），通过才落修复后图，报告逐条可定位。
 * <p>
 * 租户隔离由 Repository 全 SQL 过滤承载（跨租户访问 null → 控制层 404
 * 不泄露存在性）；删除带运行副本守卫（对齐世界书「有存档 409」惯例）。
 */
@Service
public class StoryGraphWorkshopService {

    private static final Logger log = LoggerFactory.getLogger(StoryGraphWorkshopService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** 即兴三档（design D6） */
    private static final Set<String> IMPROVISE_MODES = Set.of(
            StoryGraph.IMPROVISE_OFF, StoryGraph.IMPROVISE_BOUNDED, StoryGraph.IMPROVISE_FREE);

    private final RpgStoryGraphRepository graphRepo;
    private final RpgStoryRunRepository runRepo;
    private final RpgWorldSettingRepository worldRepo;
    private final StateSchemaValidator stateSchemaValidator;
    private final VariantCoverageValidator variantCoverageValidator;
    private final StoryGraphValidator storyGraphValidator;

    public StoryGraphWorkshopService(RpgStoryGraphRepository graphRepo,
                                      RpgStoryRunRepository runRepo,
                                      RpgWorldSettingRepository worldRepo,
                                      StateSchemaValidator stateSchemaValidator,
                                      VariantCoverageValidator variantCoverageValidator,
                                      StoryGraphValidator storyGraphValidator) {
        this.graphRepo = graphRepo;
        this.runRepo = runRepo;
        this.worldRepo = worldRepo;
        this.stateSchemaValidator = stateSchemaValidator;
        this.variantCoverageValidator = variantCoverageValidator;
        this.storyGraphValidator = storyGraphValidator;
    }

    // ==================== 建图 / 列表 / 详情 ====================

    /**
     * 建图（world 模板层新图）：名称必填、即兴模式三档校验（缺省 bounded）、
     * 即兴深度上限正整数校验（缺省 null = 引擎侧取 5）、
     * 词汇表 JSON 语法即检；graph_json 初始为空幕容器（编辑逐步长出）。
     */
    public StoryGraph create(String worldId, String name, String improviseMode,
                             Integer improviseDepthLimit, String stateSchema) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("故事图名称不能为空");
        }
        String mode = improviseMode == null || improviseMode.isBlank()
                ? StoryGraph.IMPROVISE_BOUNDED : improviseMode.trim();
        if (!IMPROVISE_MODES.contains(mode)) {
            throw new IllegalArgumentException("即兴模式非法（应为 off/bounded/free）：" + mode);
        }
        if (improviseDepthLimit != null && improviseDepthLimit < 1) {
            throw new IllegalArgumentException("即兴深度上限必须是正整数（1 以上）：" + improviseDepthLimit);
        }
        if (worldRepo.findById(worldId) == null) {
            throw new NoSuchElementException("世界不存在：" + worldId);
        }
        String schema = normalizeSchema(stateSchema);
        if (schema != null) {
            List<String> schemaErrors = new ArrayList<>();
            stateSchemaValidator.parseSchema(schema, schemaErrors);
            if (!schemaErrors.isEmpty()) {
                throw new IllegalArgumentException("state_schema 词汇表非法：" + String.join("；", schemaErrors));
            }
        }
        StoryGraph graph = new StoryGraph(UUID.randomUUID().toString(),
                TenantContext.requireTenantId(), worldId, name.trim());
        graph.setImproviseMode(mode);
        graph.setImproviseDepthLimit(improviseDepthLimit);
        graph.setStateSchema(schema);
        graph.setGraphJson("[]");
        graphRepo.save(graph);
        log.info("工坊建图: id={}, world={}, name={}, mode={}", graph.getId(), worldId, name, mode);
        return graph;
    }

    /** 按 world 列出全部故事图（一 world 多图；空 world 返回空列表） */
    public List<StoryGraph> listByWorld(String worldId) {
        return graphRepo.findByWorldId(worldId);
    }

    /** 图详情；不存在/跨租户返回 null（控制层 404，不泄露存在性） */
    public StoryGraph get(String id) {
        return graphRepo.findById(id);
    }

    // ==================== 保存 + 即时校验 ====================

    /**
     * 图级全量保存（图/幕/节点/边/变体编辑落点）：可空字段不更新。
     * 即时校验（词汇表语法 + guard/effects 引用键白名单与类型匹配）——
     * 错误即拒绝不落库（spec「保存时即时报出该变体的谓词语法错误」）；
     * 拓扑结构与可达性不在保存时校验（草稿允许未成形，整稿通过链兜底）。
     * 词汇表变更对既有图同样复验（删键会让既有 guard 悬空）。
     */
    public StoryGraph save(String id, String name, String improviseMode,
                           Integer improviseDepthLimit, String stateSchema, String graphJson) {
        StoryGraph graph = requireGraph(id);
        if (name != null) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("故事图名称不能为空");
            }
            graph.setName(name.trim());
        }
        if (improviseMode != null) {
            if (!IMPROVISE_MODES.contains(improviseMode)) {
                throw new IllegalArgumentException("即兴模式非法（应为 off/bounded/free）：" + improviseMode);
            }
            graph.setImproviseMode(improviseMode);
        }
        if (improviseDepthLimit != null) {
            if (improviseDepthLimit < 1) {
                throw new IllegalArgumentException("即兴深度上限必须是正整数（1 以上）：" + improviseDepthLimit);
            }
            graph.setImproviseDepthLimit(improviseDepthLimit);
        }
        String targetSchema = stateSchema != null ? normalizeSchema(stateSchema) : graph.getStateSchema();
        String targetJson = graphJson != null ? graphJson : graph.getGraphJson();
        List<StoryAct> acts = parseActs(targetJson);

        // 即时校验：有引用才需要白名单（空图草稿允许暂缺词汇表，整稿链兜底强制）
        if (hasReferences(acts) || (targetSchema != null && !acts.isEmpty())) {
            List<String> errors = stateSchemaValidator.validate(acts, targetSchema);
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("保存校验未通过（未落库）：\n" + String.join("\n", errors));
            }
        }
        if (stateSchema != null) {
            List<String> schemaErrors = new ArrayList<>();
            stateSchemaValidator.parseSchema(stateSchema, schemaErrors);
            if (!schemaErrors.isEmpty()) {
                throw new IllegalArgumentException("state_schema 词汇表非法：" + String.join("；", schemaErrors));
            }
            graph.setStateSchema(stateSchema);
        }
        if (graphJson != null) {
            graph.setGraphJson(graphJson);
        }
        graphRepo.update(graph);
        log.info("工坊保存故事图: id={}, acts={}", id, acts.size());
        return graph;
    }

    // ==================== 整稿通过（全图校验链） ====================

    /**
     * 整稿通过：全图确定性校验链（与 planner S4.5 整稿同一组件——
     * spec「手工建图走同一校验」）：
     * <ol>
     *   <li>词汇表（含 guard 谓词语法/引用键/类型匹配，StateSchemaValidator）</li>
     *   <li>变体覆盖性（仅警告，VariantCoverageValidator）</li>
     *   <li>拓扑校验与确定性修复 + 跨幕对接 + 全图可达性（StoryGraphValidator）</li>
     * </ol>
     * errors 非空 → 拒绝不落库（图保持编辑态）；全部通过 → 修复后结构重新
     * 序列化落库，报告携带 repairs/warnings 供前端逐条呈现。
     */
    public StoryGraphFinalizeReport finalizeGraph(String id) {
        StoryGraph graph = requireGraph(id);
        List<StoryAct> acts = parseActs(graph.getGraphJson());
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        errors.addAll(stateSchemaValidator.validate(acts, graph.getStateSchema()));
        warnings.addAll(variantCoverageValidator.validate(acts, graph.getStateSchema()));

        TopologyResult topology = storyGraphValidator.validateGraph(acts);
        errors.addAll(topology.getErrors());
        warnings.addAll(topology.getWarnings());

        if (!errors.isEmpty()) {
            log.info("整稿通过校验未通过（图未落库）: id={}, errors={}", id, errors.size());
            return new StoryGraphFinalizeReport(errors, warnings, topology.getRepairs(), null);
        }
        // 通过：修复后结构落库（validateGraph 就地 mutate acts，同 planner 链语义）
        try {
            graph.setGraphJson(mapper.writeValueAsString(acts));
        } catch (Exception e) {
            throw new IllegalStateException("修复后图序列化失败：" + e.getMessage());
        }
        graphRepo.update(graph);
        log.info("整稿通过: id={}, repairs={}, warnings={}", id,
                topology.getRepairs().size(), warnings.size());
        return new StoryGraphFinalizeReport(errors, warnings, topology.getRepairs(), graph);
    }

    // ==================== 删除 ====================

    /**
     * 删图：有运行副本拒绝（409 语义，运行副本归存档域级联）；不存在 404；
     * 成功幂等（幂等对齐已删 404 报告而非二次成功）。
     */
    public void delete(String id) {
        requireGraph(id);
        if (runRepo.existsByGraphId(id)) {
            throw new IllegalStateException("该图存在故事运行副本（按存档删除级联），不能直接删除");
        }
        graphRepo.deleteById(id);
        log.info("工坊删图: id={}", id);
    }

    // ==================== 辅助 ====================

    private StoryGraph requireGraph(String id) {
        StoryGraph graph = graphRepo.findById(id);
        if (graph == null) {
            throw new NoSuchElementException("故事图不存在：" + id);
        }
        return graph;
    }

    /** graph_json 解析（坏 JSON → 400 语义快速失败） */
    private List<StoryAct> parseActs(String graphJson) {
        try {
            return mapper.readValue(graphJson == null ? "[]" : graphJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, StoryAct.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("graph_json 解析失败：" + e.getMessage());
        }
    }

    /** 幕/节点/边/变体是否声明任何 guard/effects/on_enter 引用（有引用才需要白名单） */
    private static boolean hasReferences(List<StoryAct> acts) {
        for (StoryAct act : acts) {
            if (act.getEntryGuard() != null || (act.getConvergeTo() != null && !act.getConvergeTo().isEmpty())) {
                return true;
            }
            if (act.getNodes() != null) {
                for (StoryNode node : act.getNodes()) {
                    if (node.getEntryGuard() != null
                            || (node.getOnEnter() != null && !node.getOnEnter().isEmpty())
                            || (node.getVariants() != null && !node.getVariants().isEmpty())) {
                        return true;
                    }
                    if (node.getVariants() != null) {
                        for (StoryVariant variant : node.getVariants()) {
                            if (variant.getGuard() != null) {
                                return true;
                            }
                        }
                    }
                }
            }
            if (act.getEdges() != null) {
                for (StoryEdge edge : act.getEdges()) {
                    if (edge.getGuard() != null
                            || (edge.getEffects() != null && !edge.getEffects().isEmpty())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 词汇表入参归一（空白 → null 视为未提供） */
    private static String normalizeSchema(String stateSchema) {
        return stateSchema == null || stateSchema.isBlank() ? null : stateSchema;
    }
}
