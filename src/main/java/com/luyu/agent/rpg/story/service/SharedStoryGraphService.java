package com.luyu.agent.rpg.story.service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.story.engine.StateSchemaValidator;
import com.luyu.agent.rpg.story.engine.StoryGraphValidator;
import com.luyu.agent.rpg.story.engine.TopologyResult;
import com.luyu.agent.rpg.story.engine.VariantCoverageValidator;
import com.luyu.agent.rpg.story.model.SharedStoryGraph;
import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryGraph;
import com.luyu.agent.rpg.story.repository.RpgStoryGraphRepository;
import com.luyu.agent.rpg.story.repository.SharedStoryGraphRepository;
import com.luyu.agent.tenancy.TenantContext;

/**
 * 共享故事图包服务（story-graph-authoring tasks 3.3）。
 * <p>
 * 传播链与 {@code SharedPackService} 快照哲学同构：发布方模板图 → publish
 * 快照（shared_story_graph 不可变）→ import 物化 → 接收方模板层副本，此后
 * 工坊编辑/开局物化全部既有机制接管。每跳单一写手、无合并、无反向写回；
 * 覆盖更新/撤回不影响任何已导入副本（spec「发布后编辑不影响快照」）。
 * <p>
 * 发布前置整稿校验：发布即全平台可见，坏图不进共享库——publish 内重放与
 * {@code finalizeGraph} 同一条校验链（schema/拓扑/跨幕对接），errors 非空
 * 抛 ISE（控制层 409）。导入端信任快照（发布端已过链），仅做 preset 拒绝
 * 与坏快照防御。
 * <p>
 * 异常约定（控制层映射）：{@link IllegalArgumentException} → 400（参数/
 * 校验拒绝）；{@link IllegalStateException} → 409（未过整稿链）；
 * {@link NoSuchElementException} → 404（包不存在）。
 */
@Service
public class SharedStoryGraphService {

    private static final Logger log = LoggerFactory.getLogger(SharedStoryGraphService.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    /** 物化预设世界 id 前缀（导入拒绝：rewriteAll 会清掉导入行，对齐 SharedPackService design D5） */
    static final String PRESET_WORLD_PREFIX = "preset-";

    private final SharedStoryGraphRepository sharedRepo;
    private final RpgStoryGraphRepository graphRepo;
    private final RpgWorldSettingRepository worldRepo;
    private final StateSchemaValidator stateSchemaValidator;
    private final VariantCoverageValidator variantCoverageValidator;
    private final StoryGraphValidator storyGraphValidator;

    public SharedStoryGraphService(SharedStoryGraphRepository sharedRepo,
                                   RpgStoryGraphRepository graphRepo,
                                   RpgWorldSettingRepository worldRepo,
                                   StateSchemaValidator stateSchemaValidator,
                                   VariantCoverageValidator variantCoverageValidator,
                                   StoryGraphValidator storyGraphValidator) {
        this.sharedRepo = sharedRepo;
        this.graphRepo = graphRepo;
        this.worldRepo = worldRepo;
        this.stateSchemaValidator = stateSchemaValidator;
        this.variantCoverageValidator = variantCoverageValidator;
        this.storyGraphValidator = storyGraphValidator;
    }

    // ==================== 发布 / 覆盖更新 ====================

    /** 发布结果：包 id、是否覆盖了既有包、快照节点总数 */
    public record PublishResult(String packId, boolean overwrite, int nodeCount) {
    }

    /**
     * 发布（或覆盖更新）共享故事图包：全图快照写入共享库。
     * <p>
     * 校验：① 图存在且属本租户（findById 带租户过滤，跨租户 id 天然不可见 →
     * 400 不泄露存在性语义）；② 图非空且通过整稿校验链（schema + 拓扑 +
     * 跨幕对接；errors 非空 → ISE 409，坏图不进共享库）。快照 = 当前库中
     * graph_json/state_schema/improvise_mode 的字符串级复制（发布时点不可变）。
     * 同租户同源图已有包 → 覆盖（快照重置为新发布时点，撤回后重发布复活）。
     */
    public PublishResult publish(String graphId, String name, String blurb) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("包名不能为空");
        }
        String tenantId = TenantContext.requireTenantId();
        StoryGraph graph = graphRepo.findById(graphId);
        if (graph == null) {
            throw new IllegalArgumentException("故事图不存在或不属于当前租户: " + graphId);
        }

        // 整稿校验链重放（与 finalizeGraph 同一组件：发布门槛 = 整稿通过）
        List<StoryAct> acts = parseActs(graph);
        if (acts.isEmpty()) {
            throw new IllegalArgumentException("空图不能发布（先在工坊搭建幕/节点/边并整稿通过）");
        }
        List<String> errors = new ArrayList<>(stateSchemaValidator.validate(acts, graph.getStateSchema()));
        TopologyResult topology = storyGraphValidator.validateGraph(acts);
        errors.addAll(topology.getErrors());
        if (!errors.isEmpty()) {
            throw new IllegalStateException("图未通过整稿校验，不能发布（先在工坊修复）:\n"
                    + String.join("\n", errors));
        }
        // 覆盖性警告不阻断发布（即兴兜底始终可用，对齐 finalize 语义）
        List<String> warnings = variantCoverageValidator.validate(acts, graph.getStateSchema());
        if (!warnings.isEmpty()) {
            log.info("发布图带覆盖性警告（不阻断）: graph={}, warnings={}", graphId, warnings.size());
        }

        String snapshotJson = buildSnapshot(graph);
        int nodeCount = acts.stream()
                .mapToInt(a -> a.getNodes() == null ? 0 : a.getNodes().size()).sum();
        WorldSetting world = worldRepo.findById(graph.getWorldId());

        SharedStoryGraph existing = sharedRepo.findPackByPublisherAndGraph(tenantId, graphId);
        boolean overwrite = existing != null;
        String packId = overwrite ? existing.getId() : UUID.randomUUID().toString();

        if (overwrite) {
            sharedRepo.refreshPack(packId, name, blurb, snapshotJson, nodeCount, tenantId);
        } else {
            SharedStoryGraph pack = new SharedStoryGraph();
            pack.setId(packId);
            pack.setName(name);
            pack.setBlurb(blurb);
            pack.setPublisherTenantId(tenantId);
            pack.setPublisherUserId(TenantContext.getUserId());
            pack.setOriginWorldId(graph.getWorldId());
            pack.setOriginGraphId(graphId);
            pack.setOriginWorldName(world != null ? world.getName() : null);
            pack.setSnapshotJson(snapshotJson);
            pack.setSnapshotVersion(1);
            pack.setNodeCount(nodeCount);
            sharedRepo.insertPack(pack);
        }
        log.info("共享故事图已发布: packId={}, graph={}, nodes={}, 覆盖={}",
                packId, graphId, nodeCount, overwrite);
        return new PublishResult(packId, overwrite, nodeCount);
    }

    // ==================== 画廊 / 详情 ====================

    /** 画廊列表：全平台在架包（读侧无租户过滤） */
    public List<SharedStoryGraph> listVisible() {
        return sharedRepo.listVisible();
    }

    /** 包详情（含快照，画廊抽屉/导入预览用）；revoked/不存在返回 null（供控制层判 404） */
    public SharedStoryGraph loadPack(String packId) {
        return sharedRepo.findVisibleById(packId);
    }

    // ==================== 导入 ====================

    /** 导入结果：物化副本图 id 与目标世界 */
    public record ImportResult(String graphId, String targetWorldId, String name) {
    }

    /**
     * 导入共享故事图包到当前租户的目标世界模板层（物化副本）。
     * <p>
     * 前置校验（对齐 SharedPackService design D5）：① 包在架（404）；
     * ② 目标世界非 preset- 前缀；③ 目标世界存在且属本租户。写入：新行 id、
     * tenant_id 归导入方（既有 repo 强制过滤）、world_id 重绑、快照三件套
     * 复制（improvise_mode/state_schema/graph_json）。快照已过发布端整稿链，
     * 导入端仅做坏快照防御；模板层后续编辑互不影响（副本独立行）。
     */
    public ImportResult importGraph(String packId, String targetWorldId) {
        SharedStoryGraph pack = sharedRepo.findVisibleById(packId);
        if (pack == null) {
            throw new NoSuchElementException("共享故事图不存在或已下架: " + packId);
        }
        if (targetWorldId == null || targetWorldId.isBlank()) {
            throw new IllegalArgumentException("目标世界不能为空");
        }
        if (targetWorldId.startsWith(PRESET_WORLD_PREFIX)) {
            throw new IllegalArgumentException(
                    "不支持导入到物化预设世界（重物化会清除导入内容），请选择自建世界");
        }
        if (worldRepo.findById(targetWorldId) == null) {
            throw new IllegalArgumentException("目标世界不存在或不属于当前租户: " + targetWorldId);
        }

        // 快照解析（防御坏快照；版本 1 = 直接三件套复制）
        var snapshot = parseSnapshot(pack);
        String snapshotGraphJson = snapshot.path("graphJson").asText("[]");
        try {
            mapper.readTree(snapshotGraphJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("共享包快照损坏（graph_json 非法），导入中止");
        }

        StoryGraph imported = new StoryGraph(UUID.randomUUID().toString(),
                TenantContext.requireTenantId(), targetWorldId, pack.getName());
        imported.setImproviseMode(snapshot.path("improviseMode").asText(StoryGraph.IMPROVISE_BOUNDED));
        // 4.6 深度上限随快照传播；旧快照（无字段）容错缺省 null = 引擎侧取 5
        imported.setImproviseDepthLimit(snapshot.hasNonNull("improviseDepthLimit")
                ? snapshot.get("improviseDepthLimit").asInt() : null);
        imported.setStateSchema(snapshot.hasNonNull("stateSchema")
                ? snapshot.get("stateSchema").asText() : null);
        imported.setGraphJson(snapshotGraphJson);
        graphRepo.save(imported);
        log.info("共享故事图已导入: packId={}, targetWorld={}, importedGraph={}",
                packId, targetWorldId, imported.getId());
        return new ImportResult(imported.getId(), targetWorldId, pack.getName());
    }

    // ==================== 撤回 ====================

    /**
     * 撤回（软下架）自己的包：置 revoked_at，画廊不再列出、详情 404；
     * 已导入副本零清理（快照哲学，无回链）。
     *
     * @return true = 撤回成功；false = 包不存在/非发布者/已在架外（供控制层判 404）
     */
    public boolean revoke(String packId) {
        boolean revoked = sharedRepo.revoke(packId, TenantContext.requireTenantId(),
                java.time.Instant.now()) > 0;
        if (revoked) {
            log.info("共享故事图已撤回: packId={}", packId);
        }
        return revoked;
    }

    // ==================== 内部 ====================

    /** 幕容器解析（库中 graph_json 已过保存链；防御性失败 → 400 语义） */
    private List<StoryAct> parseActs(StoryGraph graph) {
        try {
            return mapper.readValue(graph.getGraphJson() == null ? "[]" : graph.getGraphJson(),
                    mapper.getTypeFactory().constructCollectionType(List.class, StoryAct.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("graph_json 解析失败: " + e.getMessage());
        }
    }

    /** 快照四件套（发布时点字符串级复制，不可变：improvise_mode/深度上限/state_schema/graph_json） */
    private String buildSnapshot(StoryGraph graph) {
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("improviseMode", graph.getImproviseMode() == null
                ? StoryGraph.IMPROVISE_BOUNDED : graph.getImproviseMode());
        if (graph.getImproviseDepthLimit() != null) {
            snapshot.put("improviseDepthLimit", graph.getImproviseDepthLimit());
        }
        if (graph.getStateSchema() != null) {
            snapshot.put("stateSchema", graph.getStateSchema());
        }
        snapshot.put("graphJson", graph.getGraphJson() == null ? "[]" : graph.getGraphJson());
        return snapshot.toString();
    }

    /** 快照 JSON 解析（防御） */
    private com.fasterxml.jackson.databind.JsonNode parseSnapshot(SharedStoryGraph pack) {
        try {
            return mapper.readTree(pack.getSnapshotJson() == null ? "{}" : pack.getSnapshotJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("共享包快照损坏（snapshot_json 非法），导入中止");
        }
    }
}
