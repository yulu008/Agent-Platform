package com.luyu.agent.rpg.story.engine;

import java.util.List;

/**
 * 故事图整稿通过报告（{@code StoryGraphWorkshopService.finalizeGraph} 产物）。
 * <p>
 * 与 {@link TopologyResult} 同语义分级（拒绝/警告/修复），另合并词汇表
 * 与变体覆盖性校验结果，供工坊「校验报告逐条可定位」呈现：
 * <ul>
 *   <li>{@code errors}：拒绝级（词汇表外键/谓词语法/结构必需缺失/跨幕对接
 *       断裂——图不落库，spec「手工建图走同一校验」失败项逐条报出幕/节点/边 ID）</li>
 *   <li>{@code warnings}：警告级（变体覆盖缺口/死端/全图不可达——不阻断）</li>
 *   <li>{@code repairs}：确定性修复流水（断边改指锚点等，通过即落库）</li>
 *   <li>{@code graph}：通过时为修复后落库的图（失败时为 null，图保持编辑态）</li>
 * </ul>
 */
public class StoryGraphFinalizeReport {

    private final List<String> errors;
    private final List<String> warnings;
    private final List<String> repairs;
    private final com.luyu.agent.rpg.story.model.StoryGraph graph;

    public StoryGraphFinalizeReport(List<String> errors, List<String> warnings,
                                    List<String> repairs,
                                    com.luyu.agent.rpg.story.model.StoryGraph graph) {
        this.errors = errors;
        this.warnings = warnings;
        this.repairs = repairs;
        this.graph = graph;
    }

    /** 是否通过（errors 为空；warnings/repairs 不影响通过） */
    public boolean ok() {
        return errors.isEmpty();
    }

    /** 拒绝级错误清单（空 = 通过） */
    public List<String> getErrors() { return errors; }

    /** 警告清单（覆盖缺口/死端/不可达，不阻断） */
    public List<String> getWarnings() { return warnings; }

    /** 确定性修复流水（通过时已随修复后图落库） */
    public List<String> getRepairs() { return repairs; }

    /** 修复后的图（通过时非 null；失败时 null，图保持编辑态未落库） */
    public com.luyu.agent.rpg.story.model.StoryGraph getGraph() { return graph; }
}
