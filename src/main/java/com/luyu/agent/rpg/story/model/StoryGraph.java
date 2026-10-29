package com.luyu.agent.rpg.story.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 故事图实体（对应 rpg_story_graph 表，world 模板层作品资产）。
 * <p>
 * 一 world 可挂多图（与世界书条目同权）；graph_json 存幕全量（acts 容器
 * 序列化），improvise_mode/state_schema 拆独立列（运行期常读，免解析整图）。
 * 发布 = 快照复制到 shared_story_graph（不可变）；导入 = 物化副本
 * （对齐世界书三层数据模型，design D3）。
 */
public class StoryGraph {

    /** 即兴三档（design D6）：off 图边唯一选项 / bounded 混排+引力（默认）/ free 纯沙盒图退化为建议 */
    public static final String IMPROVISE_OFF = "off";
    public static final String IMPROVISE_BOUNDED = "bounded";
    public static final String IMPROVISE_FREE = "free";

    /** bounded 连续纯即兴轮深度上限缺省值（spec「默认 N 轮，作者可调」的 N） */
    public static final int DEFAULT_IMPROVISE_DEPTH_LIMIT = 5;

    private String id;
    private String tenantId;
    private String worldId;
    private String name;

    /** 即兴模式：off / bounded / free（默认 bounded） */
    private String improviseMode;

    /** bounded 即兴深度上限（连续纯即兴轮数，超过下一轮注入收敛指令）；null = 缺省 5 */
    private Integer improviseDepthLimit;

    /** 状态词汇表 JSON：{key: {"type":"number|number-range|string|flag", ...}}（guard/effects 引用白名单） */
    private String stateSchema;

    /** 幕全量 JSON：acts[]（节点/边/轨道表/变体），模板层不膨胀（design Risks） */
    private String graphJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public StoryGraph() {
    }

    /** 常用缺省构造：bounded 模式 */
    public StoryGraph(String id, String tenantId, String worldId, String name) {
        this.id = id;
        this.tenantId = tenantId;
        this.worldId = worldId;
        this.name = name;
        this.improviseMode = IMPROVISE_BOUNDED;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getWorldId() { return worldId; }
    public void setWorldId(String worldId) { this.worldId = worldId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getImproviseMode() { return improviseMode; }
    public void setImproviseMode(String improviseMode) { this.improviseMode = improviseMode; }
    public Integer getImproviseDepthLimit() { return improviseDepthLimit; }
    public void setImproviseDepthLimit(Integer improviseDepthLimit) { this.improviseDepthLimit = improviseDepthLimit; }
    public String getStateSchema() { return stateSchema; }
    public void setStateSchema(String stateSchema) { this.stateSchema = stateSchema; }
    public String getGraphJson() { return graphJson; }
    public void setGraphJson(String graphJson) { this.graphJson = graphJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 幕容器（graphJson 反序列化；坏 JSON 由调用方保证不出现——落库前必过校验链） */
    public List<StoryAct> acts(com.fasterxml.jackson.databind.ObjectMapper mapper) throws java.io.IOException {
        return mapper.readValue(graphJson == null ? "[]" : graphJson,
                mapper.getTypeFactory().constructCollectionType(List.class, StoryAct.class));
    }
}
