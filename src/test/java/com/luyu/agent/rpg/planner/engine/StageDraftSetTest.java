package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.model.DraftItem;
import com.luyu.agent.rpg.planner.model.DraftItemStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StageDraftSet} 单元测试（tasks 4.1，spec planner-confirm-gates
 * 「L2 候选级草稿集」/design D4）。
 * <p>
 * 钉住：候选状态独立性（毙掉/确认互不影响）、REJECTED 不可确认/不可编辑
 * （已毙不复活，救回走返工重生成）、整稿通过前置（无 PENDING）、已毙候选
 * 排除表（返工 prompt 注入源）、titleOf 标题行提取、id 唯一与未知 id 防御。
 */
class StageDraftSetTest {

    private static DraftItem<String> item(String id, String title) {
        return new DraftItem<>(id, title, DraftItemStatus.PENDING, 1, "payload-" + id);
    }

    @Test
    void 候选状态独立性() {
        StageDraftSet<String> set = StageDraftSet.of(List.of(
                item("candidate-01", "甲"), item("candidate-02", "乙"),
                item("candidate-03", "丙"), item("candidate-04", "丁"),
                item("candidate-05", "戊")));

        set.reject("candidate-02");
        set.confirmItem("candidate-01");

        // spec 场景：5 个候选毙 1 确认 1 → 1 rejected、1 confirmed、3 pending，其余不受影响
        assertThat(set.items()).extracting(DraftItem::getId, DraftItem::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("candidate-01", DraftItemStatus.CONFIRMED),
                        org.assertj.core.groups.Tuple.tuple("candidate-02", DraftItemStatus.REJECTED),
                        org.assertj.core.groups.Tuple.tuple("candidate-03", DraftItemStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple("candidate-04", DraftItemStatus.PENDING),
                        org.assertj.core.groups.Tuple.tuple("candidate-05", DraftItemStatus.PENDING));
    }

    @Test
    void 毙掉幂等且确认后可改毙() {
        StageDraftSet<String> set = StageDraftSet.of(List.of(item("candidate-01", "甲")));

        set.confirmItem("candidate-01");
        set.reject("candidate-01");   // 确认后改毙（允许）
        assertThat(set.items().get(0).getStatus()).isEqualTo(DraftItemStatus.REJECTED);

        set.reject("candidate-01");   // 已毙再毙幂等
        assertThat(set.items().get(0).getStatus()).isEqualTo(DraftItemStatus.REJECTED);
    }

    @Test
    void 已毙候选不可确认() {
        StageDraftSet<String> set = StageDraftSet.of(List.of(item("candidate-01", "甲")));
        set.reject("candidate-01");

        assertThatThrownBy(() -> set.confirmItem("candidate-01"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已毙候选不可确认");
    }

    @Test
    void 已毙候选不可编辑() {
        StageDraftSet<String> set = StageDraftSet.of(List.of(item("candidate-01", "甲")));
        set.reject("candidate-01");

        assertThatThrownBy(() -> set.edit("candidate-01", "新稿"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已毙候选不可编辑");
    }

    @Test
    void 待定与已确认候选可编辑() {
        DraftItem<String> confirmed = item("candidate-02", "乙");
        confirmed.setStatus(DraftItemStatus.CONFIRMED);
        StageDraftSet<String> set = StageDraftSet.of(List.of(
                item("candidate-01", "甲"), confirmed));

        set.edit("candidate-01", "甲新稿");
        set.edit("candidate-02", "乙新稿");

        assertThat(set.items()).extracting(DraftItem::getPayload)
                .containsExactly("甲新稿", "乙新稿");
        // 编辑不动状态
        assertThat(set.items()).extracting(DraftItem::getStatus)
                .containsExactly(DraftItemStatus.PENDING, DraftItemStatus.CONFIRMED);
    }

    @Test
    void 整稿通过前置校验() {
        StageDraftSet<String> set = StageDraftSet.of(List.of(
                item("candidate-01", "甲"), item("candidate-02", "乙")));

        assertThat(set.canApprove()).isFalse();   // 有 pending
        assertThat(set.pendingItems()).extracting(DraftItem::getId)
                .containsExactly("candidate-01", "candidate-02");

        set.confirmItem("candidate-01");
        assertThat(set.canApprove()).isFalse();   // 仍剩 1 pending

        set.reject("candidate-02");
        assertThat(set.canApprove()).isTrue();    // 逐个确认或毙掉后可整稿通过
        assertThat(set.pendingItems()).isEmpty();
    }

    @Test
    void 空集可整稿通过() {
        StageDraftSet<String> set = StageDraftSet.of(List.of());
        assertThat(set.canApprove()).isTrue();
        assertThat(set.rejectedTitles()).isEmpty();
    }

    @Test
    void 已毙候选排除表() {
        StageDraftSet<String> set = StageDraftSet.of(List.of(
                item("candidate-01", "人物 1：甲"), item("candidate-02", "人物 2：乙"),
                item("candidate-03", "人物 3：丙")));
        set.reject("candidate-02");
        set.reject("candidate-03");

        // D5：排除表取被毙候选的标题（返工 prompt 显式注入，防近似变体复活）
        assertThat(set.rejectedTitles()).containsExactly("人物 2：乙", "人物 3：丙");
    }

    @Test
    void titleOf提取二级标题行() {
        assertThat(StageDraftSet.titleOf("## 人物 1：甲\n- 身份：谋士\n正文"))
                .isEqualTo("人物 1：甲");
        // 跳过非标题行命中首个二级标题
        assertThat(StageDraftSet.titleOf("导语行\n\n## 分支 2：暗线\n触发词：甲"))
                .isEqualTo("分支 2：暗线");
    }

    @Test
    void titleOf无标题行截断兜底() {
        String longText = "a".repeat(50);
        assertThat(StageDraftSet.titleOf(longText)).isEqualTo("a".repeat(30) + "…");
        assertThat(StageDraftSet.titleOf("短文本")).isEqualTo("短文本");
        assertThat(StageDraftSet.titleOf(null)).isEmpty();
        assertThat(StageDraftSet.titleOf("  \n  ")).isEmpty();
    }

    @Test
    void 工厂拒绝重复id() {
        assertThatThrownBy(() -> StageDraftSet.of(List.of(
                item("candidate-01", "甲"), item("candidate-01", "甲"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("候选 id 重复");
    }

    @Test
    void 未知id操作拒绝() {
        StageDraftSet<String> set = StageDraftSet.of(List.of(item("candidate-01", "甲")));

        assertThatThrownBy(() -> set.reject("candidate-99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("候选不存在或已过期");
        assertThatThrownBy(() -> set.edit("candidate-99", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(set.find("candidate-99")).isEmpty();
        assertThat(set.find("candidate-01")).isPresent();
    }
}
