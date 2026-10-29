package com.luyu.agent.rpg.planner.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.model.PipelineStage;

/**
 * S4.5 阶段插入的枚举语义钉子（story-graph-authoring tasks 2.1 /
 * story-graph-generation spec「planner S4.5 阶段接线」）。
 * <p>
 * 阶段顺序由枚举 ordinal 决定（add-planner-pipeline D1「加阶段=加枚举」）；
 * 本测试钉住插入位与关键关系，防止后续枚举插入/重排静默破坏
 * 「S4 整稿通过解锁 S4.5、S4.5 未通过阻塞 S5」与「S5 输入不变」两条 spec 约束。
 */
@DisplayName("PipelineStage S4.5 插入语义")
class PipelineStageTest {

    @Test
    void S4点5插入于S4与S5之间() {
        List<PipelineStage> order = List.of(PipelineStage.values());
        assertThat(order).containsExactly(
                PipelineStage.S1_IP_ANALYSIS,
                PipelineStage.S2_WORLD_SETTING,
                PipelineStage.S3_CHARACTERS,
                PipelineStage.S4_PLOT_STRUCTURE,
                PipelineStage.S4P5_STORY_GRAPH,
                PipelineStage.S5_BRANCH_DESIGN,
                PipelineStage.S6_INSTALL);
    }

    @Test
    void S4整稿通过后顺序推进至S4点5() {
        // S4.next() = S4.5：confirmStage 推进 currentStage 的取值来源
        assertThat(PipelineStage.S4_PLOT_STRUCTURE.next())
                .isEqualTo(PipelineStage.S4P5_STORY_GRAPH);
    }

    @Test
    void S4点5整稿通过后顺序推进至S5() {
        assertThat(PipelineStage.S4P5_STORY_GRAPH.next())
                .isEqualTo(PipelineStage.S5_BRANCH_DESIGN);
        // 顺序前驱：S5 的状态机上游是 S4.5（回退解锁 currentStage 定位）
        assertThat(PipelineStage.S5_BRANCH_DESIGN.prev())
                .isEqualTo(PipelineStage.S4P5_STORY_GRAPH);
    }

    @Test
    void S4点5未通过时S5生成被状态机拒绝() {
        // currentStage 停在 S4.5（未确认未推进）时对 S5 触发生成：跨阶段跳过被拒
        assertThat(PipelineStateMachine.canStartStage(
                PipelineStage.S4P5_STORY_GRAPH, PipelineStage.S5_BRANCH_DESIGN,
                com.luyu.agent.rpg.planner.model.PipelineStageStatus.NOT_STARTED))
                .isFalse();
        // S4.5 自身（NOT_STARTED）作为当前阶段可入 RUNNING：幕生成编排的进入条件
        assertThat(PipelineStateMachine.canStartStage(
                PipelineStage.S4P5_STORY_GRAPH, PipelineStage.S4P5_STORY_GRAPH,
                com.luyu.agent.rpg.planner.model.PipelineStageStatus.NOT_STARTED))
                .isTrue();
    }

    @Test
    void S4点5码目录与门级() {
        assertThat(PipelineStage.S4P5_STORY_GRAPH.code()).isEqualTo("S4.5");
        assertThat(PipelineStage.S4P5_STORY_GRAPH.dir()).isEqualTo("04.5-story-graph");
        // L2 返工门：幕级候选（编辑/返工/毙掉/整稿通过）
        assertThat(PipelineStage.S4P5_STORY_GRAPH.gate())
                .isEqualTo(PipelineStage.GateLevel.L2);
        assertThat(PipelineStage.S4P5_STORY_GRAPH.label()).isEqualTo("故事图骨架");
        assertThat(PipelineStage.fromCode("S4.5")).isEqualTo(PipelineStage.S4P5_STORY_GRAPH);
    }

    @Test
    void S5内容上游仍锚定S4() {
        // 顺序前驱与内容上游分离：S5 注入的是 S4 剧情结构确认稿（spec「S5 及后续
        // 阶段的输入输出不受影响」），S4.5 纯骨架 JSON 不进 S5 的语义上下文
        assertThat(PipelineStage.S5_BRANCH_DESIGN.upstream())
                .isEqualTo(PipelineStage.S4_PLOT_STRUCTURE);
        // 其余阶段默认 upstream = prev（S4.5 的上游即 S4 剧情结构）
        assertThat(PipelineStage.S4P5_STORY_GRAPH.upstream())
                .isEqualTo(PipelineStage.S4_PLOT_STRUCTURE);
        // S1 无上游；S6 顺序末位
        assertThat(PipelineStage.S1_IP_ANALYSIS.upstream()).isNull();
        assertThat(PipelineStage.S6_INSTALL.next()).isNull();
    }

    @Test
    void 未知阶段码拒绝() {
        assertThatThrownBy(() -> PipelineStage.fromCode("S4.6"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的管线阶段码");
    }
}
