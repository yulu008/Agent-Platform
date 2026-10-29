package com.luyu.agent.rpg.model;

import java.time.Instant;

/**
 * 共享世界书包实体（对应 shared_lorebook_pack 表，跨租户共享内容）。
 * <p>
 * 读写不对称（add-shared-worldbook-publish design D2）：画廊列表/详情全平台可见（无租户过滤），
 * 发布/覆盖/撤回仅限发布者租户（{@code publisherTenantId} 为权限锚点，非隔离过滤列）。
 * {@code originWorldId} 仅作发布方自己的覆盖定位键（同租户同世界重复发布 → 覆盖），跨租户无意义。
 */
public class SharedLorebookPack {

    private String id;
    private String name;
    /** 简介（画廊卡片展示） */
    private String blurb;
    /** 发布者租户（发布/覆盖/撤回权限锚点） */
    private String publisherTenantId;
    /** 发布者用户（署名） */
    private String publisherUserId;
    /** 发布方世界 id（覆盖定位键，无 FK） */
    private String originWorldId;
    /** 出处世界名（仅展示） */
    private String originWorldName;
    /** 条目总数（覆盖时重算） */
    private Integer entryCount;
    private Instant createdAt;
    private Instant updatedAt;
    /** 软下架标记：NULL = 在架 */
    private Instant revokedAt;

    public SharedLorebookPack() {
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
    public String getOriginWorldName() { return originWorldName; }
    public void setOriginWorldName(String originWorldName) { this.originWorldName = originWorldName; }
    public Integer getEntryCount() { return entryCount; }
    public void setEntryCount(Integer entryCount) { this.entryCount = entryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
