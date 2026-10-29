package com.luyu.agent.rpg.planner.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.luyu.agent.rpg.planner.model.DraftItem;
import com.luyu.agent.rpg.planner.model.DraftItemStatus;

/**
 * L2 门候选集引擎（design D4：S3 人物与 S5 分支共用同一候选级返工引擎）。
 * <p>
 * 纯领域模型（无 IO）：元素 {@link DraftItem}（{id, status, version, payload}）、
 * 候选级状态机（PENDING→CONFIRMED/REJECTED；CONFIRMED→REJECTED；REJECTED 无出边）。
 * 加载与持久化由门操作服务层编排（候选文件真源 + manifest 候选状态索引，D3/D4），
 * 本类只管集合语义与状态转换——确定性、可测。
 * <p>
 * 操作语义（spec planner-confirm-gates「L2 候选级草稿集」）：
 * <ul>
 *   <li>毙掉/确认：候选级状态独立变更，其余候选不受影响</li>
 *   <li>整稿通过前置：集合内不存在 PENDING（须逐个确认或毙掉）</li>
 *   <li>已毙候选排除表：返工 prompt 注入，防近似变体复活（D5）</li>
 * </ul>
 *
 * @param <E> payload 类型（v1 为 String——Markdown 块）
 */
public final class StageDraftSet<E> {

    private static final int TITLE_FALLBACK_LEN = 30;

    private final List<DraftItem<E>> items;

    private StageDraftSet(List<DraftItem<E>> items) {
        this.items = List.copyOf(items);
    }

    /**
     * 工厂：候选 id 必须唯一（重复 id 是数据损坏——文件序号与 manifest 状态行失配，
     * 显式失败而非静默丢弃）。
     */
    public static <E> StageDraftSet<E> of(List<DraftItem<E>> items) {
        Set<String> ids = new HashSet<>();
        for (DraftItem<E> item : items) {
            if (!ids.add(item.getId())) {
                throw new IllegalArgumentException("候选 id 重复: " + item.getId());
            }
        }
        return new StageDraftSet<>(items);
    }

    /** 有序候选视图（生成序，即候选文件序）。 */
    public List<DraftItem<E>> items() {
        return items;
    }

    /** 按 id 定位候选。 */
    public Optional<DraftItem<E>> find(String id) {
        return items.stream().filter(i -> i.getId().equals(id)).findFirst();
    }

    /**
     * 毙掉（PENDING/CONFIRMED → REJECTED）：已毙再毙幂等。
     *
     * @return 被毙候选（便于调用方同步 manifest 状态行）
     * @throws IllegalArgumentException id 不存在（前端引用了过期候选）
     */
    public DraftItem<E> reject(String id) {
        DraftItem<E> item = require(id);
        item.setStatus(DraftItemStatus.REJECTED);
        return item;
    }

    /**
     * 逐卡确认（PENDING/CONFIRMED → CONFIRMED）：REJECTED 不可确认（已毙不复活，
     * 想救回来走返工重生成）。
     */
    public DraftItem<E> confirmItem(String id) {
        DraftItem<E> item = require(id);
        if (item.getStatus() == DraftItemStatus.REJECTED) {
            throw new IllegalStateException("已毙候选不可确认（救回走返工重生成）: " + id);
        }
        item.setStatus(DraftItemStatus.CONFIRMED);
        return item;
    }

    /**
     * 逐卡编辑（PENDING/CONFIRMED 可编辑；REJECTED 不可——毙掉后文件留在树上只为
     * 回看，编辑无意义）。版本记账由门操作层经 recordDraftEdit 统一处理。
     */
    public DraftItem<E> edit(String id, E newPayload) {
        DraftItem<E> item = require(id);
        if (item.getStatus() == DraftItemStatus.REJECTED) {
            throw new IllegalStateException("已毙候选不可编辑: " + id);
        }
        item.setPayload(newPayload);
        return item;
    }

    /** 整稿通过前置校验：集合内不存在 PENDING（spec「须逐个确认或毙掉」）。 */
    public boolean canApprove() {
        return items.stream().noneMatch(DraftItem::isPending);
    }

    /** PENDING 候选列表（整稿通过的阻塞提示用）。 */
    public List<DraftItem<E>> pendingItems() {
        return items.stream().filter(DraftItem::isPending).toList();
    }

    /** 已毙候选排除表（标题行；返工 prompt 显式注入，D5——防近似变体复活）。 */
    public List<String> rejectedTitles() {
        return items.stream().filter(DraftItem::isRejected).map(DraftItem::getTitle).toList();
    }

    /**
     * 候选标题行提取（「## 人物 1：甲」→「人物 1：甲」；无二级标题行时截取正文
     * 前 30 字兜底——CandidateSplitter 降级单候选场景）。
     * 供 initStageCandidates 落 manifest 候选状态行（被毙候选的记忆真源）。
     */
    public static String titleOf(String payload) {
        if (payload == null) {
            return "";
        }
        for (String line : payload.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("## ")) {
                return stripped.substring(3).strip();
            }
        }
        String whole = payload.strip();
        return whole.length() > TITLE_FALLBACK_LEN
                ? whole.substring(0, TITLE_FALLBACK_LEN) + "…" : whole;
    }

    private DraftItem<E> require(String id) {
        return find(id).orElseThrow(() ->
                new IllegalArgumentException("候选不存在或已过期: " + id));
    }
}
