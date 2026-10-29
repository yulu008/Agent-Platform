package com.luyu.agent.rpg.model;

import java.time.Instant;

/**
 * 共享世界包实体（对应 shared_world 单表，works-view-and-world-publish）。
 * <p>
 * 读写不对称（design D3/D4，与 {@link SharedLorebookPack} 同约）：橱窗列表/详情
 * 全平台可见（无租户过滤），发布/覆盖/撤销仅限发布者租户（{@code publisherTenantId}
 * 为权限锚点，非隔离过滤列）。{@code originWorldId} 仅作发布方自己的覆盖定位键
 * （同发布者同源世界重复发布 → 覆盖），跨租户无意义。
 * <p>
 * {@code snapshotJson} 为世界卡全家桶快照文档（发布时点切片，此后源世界变更零渗入）；
 * {@code snapshotVersion} 预留 schema 演进（导入端按版本分支反序列化）。
 * {@code cover} 存共享区文件名（{@code data/covers/shared/}），发布时复制而非引用
 * 发布方私有文件（design D6：发布方删世界清封面不影响共享包）。
 */
public class SharedWorld {

    private String id;
    /** 包名（快照自源世界名） */
    private String name;
    /** 简介（快照自 settingDesc 压平） */
    private String blurb;
    /** 共享区封面文件名（data/covers/shared/）；NULL = 无封面 */
    private String cover;
    /** 发布者租户（发布/覆盖/撤销权限锚点） */
    private String publisherTenantId;
    /** 发布者用户（署名） */
    private String publisherUserId;
    /** 发布方世界 id（覆盖定位键，无 FK） */
    private String originWorldId;
    /** 出处世界名（仅展示，跨租户不引用 world_id） */
    private String originWorldName;
    /** 全家桶快照文档 JSON v1（world/characters/locations/relationships/triggers/worldbookEntries/starIncarnations） */
    private String snapshotJson;
    /** 快照 schema 版本（导入端按版本分支反序列化） */
    private Integer snapshotVersion;
    /** 子数据总数（覆盖时重算） */
    private Integer entryCount;
    private Instant createdAt;
    private Instant updatedAt;
    /** 软下架标记：NULL = 在架 */
    private Instant revokedAt;

    public SharedWorld() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBlurb() { return blurb; }
    public void setBlurb(String blurb) { this.blurb = blurb; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getPublisherTenantId() { return publisherTenantId; }
    public void setPublisherTenantId(String publisherTenantId) { this.publisherTenantId = publisherTenantId; }
    public String getPublisherUserId() { return publisherUserId; }
    public void setPublisherUserId(String publisherUserId) { this.publisherUserId = publisherUserId; }
    public String getOriginWorldId() { return originWorldId; }
    public void setOriginWorldId(String originWorldId) { this.originWorldId = originWorldId; }
    public String getOriginWorldName() { return originWorldName; }
    public void setOriginWorldName(String originWorldName) { this.originWorldName = originWorldName; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public Integer getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(Integer snapshotVersion) { this.snapshotVersion = snapshotVersion; }
    public Integer getEntryCount() { return entryCount; }
    public void setEntryCount(Integer entryCount) { this.entryCount = entryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
