package com.luyu.agent.rpg.planner.model;

/**
 * L2 门候选集元素（design D4：{id, status, version, payload}）。
 * <p>
 * id 是稳定标识 = 候选文件名去扩展名（candidate-01……文件名即 id，tasks 3.6），
 * 逐卡操作（毙掉/编辑/确认）以 id 定位；title 是候选标题行（「## 人物 1：甲」的
 * 标题文本），供卡片标题与返工排除表（manifest 候选状态行同步记忆，候选文件被
 * 返工整轮覆盖后排除表仍可从 manifest 取，D5）；version 是该候选所属的阶段轮次
 * 版本（同轮候选同版本，加载时取 StageState.version）；payload 是候选正文。
 * <p>
 * 可变字段 status/payload 经 {@code StageDraftSet} 领域操作变更（门操作层编排落盘）。
 *
 * @param <E> payload 类型（v1 为 String——Markdown 块：S3 人物卡 / S5 分支条目）
 */
public class DraftItem<E> {

    private final String id;
    private final String title;
    private final int version;
    private E payload;
    private DraftItemStatus status;

    public DraftItem(String id, String title, DraftItemStatus status, int version, E payload) {
        this.id = id;
        this.title = title;
        this.status = status == null ? DraftItemStatus.PENDING : status;
        this.version = version;
        this.payload = payload;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public DraftItemStatus getStatus() {
        return status;
    }

    public void setStatus(DraftItemStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public E getPayload() {
        return payload;
    }

    public void setPayload(E payload) {
        this.payload = payload;
    }

    public boolean isPending() {
        return status == DraftItemStatus.PENDING;
    }

    public boolean isRejected() {
        return status == DraftItemStatus.REJECTED;
    }
}
