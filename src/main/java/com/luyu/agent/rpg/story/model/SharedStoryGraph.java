package com.luyu.agent.rpg.story.model;

import java.time.Instant;

/**
 * 共享故事图包实体（对应 shared_story_graph 表，跨租户共享内容）。
 * <p>
 * 读写不对称（对齐 {@code SharedLorebookPack} / add-shared-worldbook-publish
 * design D2）：画廊列表/详情全平台可见（无租户过滤），发布/覆盖/撤回仅限
 * 发布者租户（{@code publisherTenantId} 为权限锚点，非隔离过滤列）。
 * {@code originGraphId} 是发布方自己的覆盖定位键（同租户同图重复发布 →
 * 覆盖；一 world 多图，故定位粒度是图而非世界），跨租户无意义。
 * <p>
 * {@code snapshotJson} 单列存全图快照（improviseMode/stateSchema/graphJson
 * 三件套），发布时点字符串级复制即不可变（spec「发布后编辑不影响快照」）；
 * {@code snapshotVersion} 预留 schema 演进分支（导入端按版本反序列化）。
 */
public class SharedStoryGraph {

    private String id;
    /** 发布名（快照自源图名，可改） */
    private String name;
    /** 简介（画廊卡片展示） */
    private String blurb;
    /** 发布者租户（发布/覆盖/撤回权限锚点） */
    private String publisherTenantId;
    /** 发布者用户（署名） */
    private String publisherUserId;
    /** 发布方世界 id（展示与审计；无 FK，防跨租户删世界阻塞共享库写） */
    private String originWorldId;
    /** 发布方源图 id（覆盖定位键，跨租户不引用） */
    private String originGraphId;
    /** 出处世界名（仅展示，跨租户不引用 world_id） */
    private String originWorldName;
    /** 全图快照 JSON：{improviseMode, stateSchema, graphJson}（发布时点复制，不可变） */
    private String snapshotJson;
    /** 快照 schema 版本（导入端按版本分支反序列化；当前 1） */
    private Integer snapshotVersion;
    /** 节点总数（画廊卡片展示免解析整包；覆盖时重算） */
    private Integer nodeCount;
    private Instant createdAt;
    private Instant updatedAt;
    /** 软下架标记：NULL = 在架 */
    private Instant revokedAt;

    public SharedStoryGraph() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBlurb() { return blurb; }
    public void setBlurb(String blurb) { this.blurb = blurb; }
    public String getPublisherTenantId() { return publisherTenantId; }
    public void setPublisherTenantId(String publisherTenantId) { this.publisherTenantId = publisherTenantId; }
    public String getPublisherUserId() { return publisherUserId; }
    public void setPublisherUserId(String publisherUserId) { this.publisherUserId = publisherUserId; }
    public String getOriginWorldId() { return originWorldId; }
    public void setOriginWorldId(String originWorldId) { this.originWorldId = originWorldId; }
    public String getOriginGraphId() { return originGraphId; }
    public void setOriginGraphId(String originGraphId) { this.originGraphId = originGraphId; }
    public String getOriginWorldName() { return originWorldName; }
    public void setOriginWorldName(String originWorldName) { this.originWorldName = originWorldName; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Integer getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(Integer snapshotVersion) { this.snapshotVersion = snapshotVersion; }
    public Integer getNodeCount() { return nodeCount; }
    public void setNodeCount(Integer nodeCount) { this.nodeCount = nodeCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
