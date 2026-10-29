package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.StoryAct;

import java.util.List;

/**
 * 骨架解析结果（{@link StoryGraphParser} 产物）。
 * <p>
 * {@code errors} 非空时 {@code acts} 为部分或空结果，调用方 SHALL 丢弃 acts
 * 并走 LLM fallback 修复一次（generation 任务 2.4）；修复仍失败标记本幕
 * FAILED 可重试。顶层 {@code improviseMode} 仅全图 JSON（acts 数组形态）
 * 携带；{@code stateSchema} 全图与单幕形态均可携带（S4.5 第 1 幕单幕输出
 * 产出词汇表，后续幕沿用——story-graph-authoring tasks 2.2）。
 */
public class StoryGraphParseResult {

    private final List<StoryAct> acts;
    private final String improviseMode;
    private final String stateSchema;
    private final List<String> errors;

    public StoryGraphParseResult(List<StoryAct> acts, String improviseMode,
                                 String stateSchema, List<String> errors) {
        this.acts = acts;
        this.improviseMode = improviseMode;
        this.stateSchema = stateSchema;
        this.errors = errors;
    }

    /** 解析是否成功（无错误时 acts 可直接进入后续校验链） */
    public boolean ok() {
        return errors == null || errors.isEmpty();
    }

    public List<StoryAct> getActs() { return acts; }
    public String getImproviseMode() { return improviseMode; }
    public String getStateSchema() { return stateSchema; }
    public List<String> getErrors() { return errors; }
}
