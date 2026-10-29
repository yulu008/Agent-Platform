package com.luyu.agent.rpg.story.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 拓扑校验与修复报告（{@link StoryGraphValidator} 产物）。
 * <ul>
 *   <li>{@code errors}：拒绝级（结构必需缺失/环超限/跨幕对接断裂——图不落库）</li>
 *   <li>{@code warnings}：警告级（不可达剔除清单/死端/覆盖缺口——不阻断）</li>
 *   <li>{@code repairs}：确定性修复流水（原值→新值，spec：每次执行结果一致）</li>
 * </ul>
 */
public class TopologyResult {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> repairs = new ArrayList<>();

    /** 是否通过（errors 为空；warnings/repairs 不影响通过） */
    public boolean ok() {
        return errors.isEmpty();
    }

    public void error(String message) {
        errors.add(message);
    }

    public void warning(String message) {
        warnings.add(message);
    }

    /** 修复流水（格式：定位 + 原值 → 新值 + 修复动作） */
    public void repair(String message) {
        repairs.add(message);
    }

    public void merge(TopologyResult other) {
        errors.addAll(other.errors);
        warnings.addAll(other.warnings);
        repairs.addAll(other.repairs);
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<String> getRepairs() {
        return Collections.unmodifiableList(repairs);
    }
}
