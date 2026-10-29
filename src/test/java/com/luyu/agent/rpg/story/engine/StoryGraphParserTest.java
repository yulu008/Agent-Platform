package com.luyu.agent.rpg.story.engine;

import com.luyu.agent.rpg.story.model.StoryAct;
import com.luyu.agent.rpg.story.model.StoryEdge;
import com.luyu.agent.rpg.story.model.StoryNode;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StoryGraphParser} 单元测试（story-graph-model 任务 1.7）。
 * <p>
 * 覆盖解析层契约：{@code <story_graph>} 标签提取（闭合/未闭合截断/无块）、
 * 蛇形/驼峰双 key 兼容、节点 ID 幕命名空间校验、幕节点数上限 25 拒绝、
 * 全图/单幕双顶层形态、幕 id 缺失按顶层 act 数字字段补幂号、边 ID 缺省确定性补齐。
 */
class StoryGraphParserTest {

    private final StoryGraphParser parser = new StoryGraphParser();

    /** 合法单幕 JSON（蛇形 key），可指定 actId 与节点数 */
    private String actJson(String actId, int nodeCount) {
        String nodes = IntStream.rangeClosed(1, nodeCount)
                .mapToObj(i -> i == nodeCount
                        ? "{\"id\": \"n%s_%02d\", \"beat\": \"收束拍子\", \"type\": \"anchor\"}".formatted(actId.replaceAll("\\D", ""), i)
                        : "{\"id\": \"n%s_%02d\", \"beat\": \"拍子%d\", \"type\": \"scene\"}".formatted(actId.replaceAll("\\D", ""), i, i))
                .collect(Collectors.joining(","));
        String edges = IntStream.rangeClosed(1, nodeCount - 1)
                .mapToObj(i -> "{\"from\": \"n%s_%02d\", \"to\": \"n%s_%02d\", \"label\": \"前进\", \"quote\": \"继续\"}"
                        .formatted(actId.replaceAll("\\D", ""), i, actId.replaceAll("\\D", ""), i + 1))
                .collect(Collectors.joining(","));
        return """
                {
                  "id": "%s",
                  "theme": "本能寺之变前夜",
                  "ending_beat": "信长陨落",
                  "entry_guard": null,
                  "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                  "allow_explicit_variants": true,
                  "nodes": [%s],
                  "edges": [%s]
                }
                """.formatted(actId, nodes, edges);
    }

    @Test
    void 合法全图解析通过并携带顶层配置() {
        String llmOutput = "前言叙述。\n<story_graph>\n" + """
                {"acts": [%s, %s], "improvise_mode": "bounded",
                 "state_schema": {"trust_信长": {"type": "number", "min": 0, "max": 100}}}
                """.formatted(actJson("act1", 3), actJson("act2", 3)) + "\n</story_graph>\n结语。";

        StoryGraphParseResult result = parser.parse(llmOutput);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getActs()).hasSize(2);
        assertThat(result.getImproviseMode()).isEqualTo("bounded");
        assertThat(result.getStateSchema()).contains("trust_信长");
        assertThat(result.getActs().get(0).getConvergeTo()).hasSize(1);
        assertThat(result.getActs().get(0).getConvergeTo().get(0).getGuard().getKey()).isEqualTo("trust_信长");
    }

    @Test
    void 蛇形与驼峰双key兼容归一到同一实体() {
        String camelAct = """
                {"id": "act1", "theme": "主题", "endingBeat": "终拍",
                 "convergeTo": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "allowExplicitVariants": true,
                 "nodes": [
                   {"id": "n1_01", "beat": "拍子", "type": "scene",
                    "entryGuard": {"key": "阵营", "op": "==", "value": "织田"},
                    "onEnter": {"trust_信长": 5},
                    "divergenceDimensions": ["trust_信长"],
                    "variants": [{"guard": {"key": "trust_信长", "op": ">=", "value": 60},
                                  "beatVariant": "高信任收束", "bodySeed": "庄重基调"}]},
                   {"id": "n1_02", "beat": "收束", "type": "anchor"}],
                 "edges": [{"from": "n1_01", "to": "n1_02", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parse("<story_graph>" + camelAct + "</story_graph>");

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        StoryAct act = result.getActs().get(0);
        assertThat(act.getEndingBeat()).isEqualTo("终拍");
        assertThat(act.getConvergeTo()).hasSize(1);
        assertThat(act.getAllowExplicitVariants()).isTrue();
        StoryNode node = act.getNodes().get(0);
        assertThat(node.getEntryGuard().getKey()).isEqualTo("阵营");
        assertThat(node.getOnEnter()).containsEntry("trust_信长", 5L);
        assertThat(node.getDivergenceDimensions()).containsExactly("trust_信长");
        assertThat(node.getVariants().get(0).getBeatVariant()).isEqualTo("高信任收束");
        assertThat(node.getVariants().get(0).getBodySeed()).isEqualTo("庄重基调");
    }

    @Test
    void 节点ID幕命名空间违规被拒() {
        // act1 中出现幕 3 前缀节点 n3_05
        String json = """
                {"id": "act1", "theme": "主题",
                 "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "nodes": [
                   {"id": "n1_01", "beat": "拍子", "type": "scene"},
                   {"id": "n3_05", "beat": "违规", "type": "scene"},
                   {"id": "n1_02", "beat": "收束", "type": "anchor"}],
                 "edges": [{"from": "n1_01", "to": "n1_02", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json);

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("n3_05") && e.contains("幕命名空间违规"));
    }

    @Test
    void 跨幕拼接边x前缀ID合法() {
        // 回归钉（5.2 E2E 实测）：S4.5 整稿确认后 Stitcher 产出的拼接整图携带
        // x<act>_<seq> 跨幕拼接边（引擎独立命名空间，幂等清旧按 x 形态重算）；
        // 安装物化链复用 Parser 解析拼接整图，边 ID 校验须同时放行 e（模型边）
        // 与 x（引擎拼接边），否则 install 物化被 409 卡死。
        String json = """
                {"acts": [
                  {"id": "act1", "theme": "主题",
                   "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                   "nodes": [
                     {"id": "n1_01", "beat": "拍子", "type": "scene"},
                     {"id": "n1_02", "beat": "收束", "type": "anchor"}],
                   "edges": [{"id": "e1_01", "from": "n1_01", "to": "n1_02", "label": "去", "quote": "走"},
                             {"id": "x1_01", "from": "n1_02", "to": "n2_01", "label": "下一幕", "quote": "继续"}]},
                  {"id": "act2", "theme": "主题二",
                   "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                   "nodes": [
                     {"id": "n2_01", "beat": "拍子", "type": "scene"},
                     {"id": "n2_02", "beat": "收束", "type": "anchor"}],
                   "edges": [{"id": "e2_01", "from": "n2_01", "to": "n2_02", "label": "去", "quote": "走"}]}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getActs()).hasSize(2);
        assertThat(result.getActs().get(0).getEdges()).extracting(StoryEdge::getId)
                .containsExactly("e1_01", "x1_01");
    }

    @Test
    void 幕节点数超过25被拒绝() {
        StoryGraphParseResult result = parser.parsePlain(actJson("act1", 26));

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("节点数超限") && e.contains("26 > 25"));
        // 25 节点恰在上限内通过
        assertThat(parser.parsePlain(actJson("act1", 25)).ok()).isTrue();
    }

    @Test
    void 未闭合块按开标签截断提取仍可解析() {
        // 缺 </story_graph>：从开标签截取到文本末尾，JSON 完整则解析成功
        String json = actJson("act1", 3);
        StoryGraphParseResult result = parser.parse("叙述。<story_graph>\n" + json + "\n尾部多余文字");

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getActs()).hasSize(1);
    }

    @Test
    void 未闭合且JSON被截断时报解析错误() {
        String json = actJson("act1", 3);
        String truncated = json.substring(0, json.length() / 2);
        StoryGraphParseResult result = parser.parse("<story_graph>\n" + truncated);

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("JSON 解析失败"));
    }

    @Test
    void 无标签块时报错() {
        StoryGraphParseResult result = parser.parse("纯叙述无标签");

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("未找到 <story_graph> 块"));
    }

    @Test
    void 单幕顶层形态解析为一幕() {
        StoryGraphParseResult result = parser.parsePlain(actJson("act1", 3));

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getActs()).hasSize(1);
        assertThat(result.getImproviseMode()).isNull();
    }

    @Test
    void 幕缺失id时按顶层act数字字段补幂号() {
        // S4.5 幕契约教 LLM 产出顶层 "act": <幕号>（数字字段而非 id 字符串）；
        // 第 2 幕单幕解析若无此兑底会被数组序号补成 act1 → 节点 n2_xx 幕命名空间违规
        String json = """
                {"act": 2, "theme": "分裂与拆决",
                 "converge_to": [{"orbit": "持刀线", "guard": {"key": "已获刀", "op": "==", "value": true}}],
                 "nodes": [
                   {"id": "n2_01", "beat": "清洲城密议", "type": "scene"},
                   {"id": "n2_05", "beat": "清洲会议摊牌", "type": "anchor"}],
                 "edges": [{"from": "n2_01", "to": "n2_05", "label": "摊牌", "quote": "摊牌"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getActs()).hasSize(1);
        assertThat(result.getActs().get(0).getId()).isEqualTo("act2");
    }

    @Test
    void 边ID缺省时确定性补齐并跳过已用序号() {
        String json = """
                {"id": "act1", "theme": "主题",
                 "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "nodes": [
                   {"id": "n1_01", "beat": "拍子", "type": "scene"},
                   {"id": "n1_02", "beat": "中", "type": "scene"},
                   {"id": "n1_03", "beat": "收束", "type": "anchor"}],
                 "edges": [
                   {"id": "e1_01", "from": "n1_01", "to": "n1_02", "label": "去", "quote": "走"},
                   {"from": "n1_02", "to": "n1_03", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        // 第二条边缺 id：补齐 e1_1（e1_1 与 e1_01 不冲突，规则按字面去重）
        assertThat(result.getActs().get(0).getEdges().get(1).getId()).isEqualTo("e1_1");
    }

    @Test
    void 变体缺失beat_variant被拒() {
        String json = """
                {"id": "act1", "theme": "主题", "allow_explicit_variants": true,
                 "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "nodes": [
                   {"id": "n1_01", "beat": "收束", "type": "anchor",
                    "variants": [{"guard": {"key": "trust_信长", "op": ">=", "value": 60}}]},
                   {"id": "n1_02", "beat": "后", "type": "scene"}],
                 "edges": [{"from": "n1_01", "to": "n1_02", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json);

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("变体缺失 beat_variant"));
    }

    @Test
    void 单幕缺失id与act字段时按编排层幕号补齐() {
        // S4.5 实测回归钉：第 4 幕输出漏写顶层 act/id 字段时，旧逻辑按数组序号
        // 补成 act1 → n4_xx 全员误报「幕命名空间违规」，掩盖缺 beat/自创 type 等真实错误
        String json = """
                {"theme": "终幕",
                 "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "nodes": [
                   {"id": "n4_01", "beat": "拍子", "type": "scene"},
                   {"id": "n4_02", "beat": "收束", "type": "anchor"}],
                 "edges": [{"from": "n4_01", "to": "n4_02", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json, 4);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getActs().get(0).getId()).isEqualTo("act4");
    }

    @Test
    void 编排层幕号与节点前缀冲突仍报命名空间违规() {
        // 幕号 ground truth 是编排层：模型若生成他幕内容（n5_xx），
        // 不得被顶层缺失字段掩盖，须照常报违规供修复链纠偏
        String json = """
                {"theme": "主题",
                 "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "nodes": [
                   {"id": "n5_01", "beat": "拍子", "type": "scene"},
                   {"id": "n5_02", "beat": "收束", "type": "anchor"}],
                 "edges": [{"from": "n5_01", "to": "n5_02", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json, 4);

        assertThat(result.ok()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("n5_01") && e.contains("幕命名空间违规"));
    }

    @Test
    void 幕缺失id时按节点ID共识前缀补幂号() {
        // 无编排层幕号（工坊导入/Digest 重放）时：全部节点同前缀 n3_ → 幕补 act3
        String json = """
                {"theme": "主题",
                 "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "nodes": [
                   {"id": "n3_01", "beat": "拍子", "type": "scene"},
                   {"id": "n3_02", "beat": "收束", "type": "anchor"}],
                 "edges": [{"from": "n3_01", "to": "n3_02", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json);

        assertThat(result.ok()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getActs().get(0).getId()).isEqualTo("act3");
    }

    @Test
    void 节点前缀不一致时放弃共识按序号兜底() {
        // 混排幕（n1_/n2_ 并存）不得被共识掩盖：序号兜底 + 命名空间违规照常报错
        String json = """
                {"theme": "主题",
                 "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 60}}],
                 "nodes": [
                   {"id": "n1_01", "beat": "拍子", "type": "scene"},
                   {"id": "n2_01", "beat": "收束", "type": "anchor"}],
                 "edges": [{"from": "n1_01", "to": "n2_01", "label": "去", "quote": "走"}]}
                """;
        StoryGraphParseResult result = parser.parsePlain(json);

        assertThat(result.ok()).isFalse();
        assertThat(result.getActs().get(0).getId()).isEqualTo("act1");
        assertThat(result.getErrors()).anyMatch(e -> e.contains("n2_01") && e.contains("幕命名空间违规"));
    }
}
