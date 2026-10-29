package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StageVersionDiffer} 行级 diff 单测（add-planner-pipeline tasks 4.6，
 * spec planner-confirm-gates「diff 核对」）：变更部分 removed/added 相邻
 * 成对呈现、未变更条目全 context；公共前后缀掐头去尾不吞中间差异。
 */
class StageVersionDifferTest {

    /** 行渲染为 {@code type:text}，便于整列表断言。 */
    private static List<String> render(List<StageVersionDiffer.DiffLine> lines) {
        return lines.stream().map(l -> l.type() + ":" + l.text()).toList();
    }

    @Test
    void 单处修改removed与added相邻成对() {
        List<String> result = render(StageVersionDiffer.diff(
                "## 人物 1：甲\n性格：沉稳\n结局：战死",
                "## 人物 1：甲\n性格：狡黠\n结局：战死"));

        // spec「变更部分高亮呈现，未变更条目显示一致」：删旧/加新相邻即一处修改
        assertEquals(List.of(
                "context:## 人物 1：甲",
                "removed:性格：沉稳",
                "added:性格：狡黠",
                "context:结局：战死"), result);
    }

    @Test
    void 纯新增行全added() {
        List<String> result = render(StageVersionDiffer.diff(
                "## 分支 A",
                "## 分支 A\n触发词：背叛\n注入：旧主之血"));

        assertEquals(List.of(
                "context:## 分支 A",
                "added:触发词：背叛",
                "added:注入：旧主之血"), result);
    }

    @Test
    void 纯删除行全removed() {
        List<String> result = render(StageVersionDiffer.diff(
                "## 分支 A\n触发词：背叛",
                "## 分支 A"));

        assertEquals(List.of(
                "context:## 分支 A",
                "removed:触发词：背叛"), result);
    }

    @Test
    void 前后缀一致中段差异不被掐头去尾吞掉() {
        // 首尾行相同、中段整行替换——掐头去尾的边界（前后缀不得越过中段重叠）
        List<String> result = render(StageVersionDiffer.diff(
                "甲\n乙\n甲",
                "甲\n丙\n甲"));

        assertEquals(List.of(
                "context:甲",
                "removed:乙",
                "added:丙",
                "context:甲"), result);
    }

    @Test
    void 无变化全context() {
        List<String> result = render(StageVersionDiffer.diff("甲\n乙\n丙", "甲\n乙\n丙"));

        assertEquals(List.of("context:甲", "context:乙", "context:丙"), result);
    }

    @Test
    void 旧版为空全added新版为空全removed() {
        assertEquals(List.of("added:新行"), render(StageVersionDiffer.diff(null, "新行")));
        assertEquals(List.of("removed:旧行"), render(StageVersionDiffer.diff("旧行", null)));
        assertEquals(List.of(), StageVersionDiffer.diff(null, null));
    }

    @Test
    void 尾部换行不计行() {
        // 编辑器行数语义：末尾换行是行终结符不是空行
        List<String> withTrailingNewline = render(StageVersionDiffer.diff("甲\n乙", "甲\n乙\n"));

        assertEquals(render(StageVersionDiffer.diff("甲\n乙", "甲\n乙")), withTrailingNewline);
    }
}
