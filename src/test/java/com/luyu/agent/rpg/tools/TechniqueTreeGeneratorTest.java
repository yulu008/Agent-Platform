package com.luyu.agent.rpg.tools;

import com.luyu.agent.rpg.model.AttackType;
import com.luyu.agent.rpg.model.StarTechnique;
import com.luyu.agent.rpg.model.TechniqueNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TechniqueTreeGenerator} 纯函数部分的单元测试。
 * <p>
 * 刻意只覆盖 {@code buildPrompt} / {@code parseDraft} / {@code toSourceEntry} / {@code topoOrder}
 * —— 这四者不含 IO，无需 Spring 上下文。真正的 LLM 调用是一次性开发期动作，
 * 由人工审校 + {@code TechniqueTreeMapValidationTest} 兜住，不做自动化断言。
 */
class TechniqueTreeGeneratorTest {

    private static final List<StarTechnique> CATALOG = List.of(
            new StarTechnique("天马流星拳", "以光速挥出无数流星般的拳", "经典系列"),
            new StarTechnique("天马彗星拳", "将全部拳力集中于一点的终招", "经典系列"),
            new StarTechnique("天马闪光拳", "光属性小宇宙聚于拳", "Ω", AttackType.PHYSICAL));

    private static final Set<String> NAMES = Set.of("天马流星拳", "天马彗星拳", "天马闪光拳");

    // ==================== buildPrompt ====================

    @Test
    void prompt包含全部招式名与阵营标签() {
        String prompt = TechniqueTreeGenerator.buildPrompt(
                "天马座", "saint", CATALOG);

        assertThat(prompt).contains("天马座").contains("圣斗士");
        assertThat(prompt).contains("天马流星拳").contains("天马彗星拳").contains("天马闪光拳");
        // 描述、来源、攻击类型都要给到模型，来源版本序是它判断层级的参考
        assertThat(prompt).contains("以光速挥出无数流星般的拳").contains("经典系列").contains("Ω");
    }

    @Test
    void prompt注入多角色合集分支约束() {
        String prompt = TechniqueTreeGenerator.buildPrompt("双子座", "saint", CATALOG);

        // 领域约束：拉直多角色合集会产生语义荒谬的路径，必须显式提示分支
        assertThat(prompt).contains("多个不同角色的招式合集").contains("分支");
        assertThat(prompt).contains("帕拉朵珂丝").contains("银河星爆").contains("幸运之壁");
        // 来源版本序可作参考但不是唯一依据
        assertThat(prompt).contains("经典系列").contains("冥王神话LC").contains("不是】唯一依据");
        // 进阶措辞是最强信号
        assertThat(prompt).contains("上位变体").contains("改良版");
    }

    @Test
    void prompt声明结构硬约束与JSON契约() {
        String prompt = TechniqueTreeGenerator.buildPrompt("天马座", "specter", CATALOG);

        assertThat(prompt).contains("冥斗士");
        assertThat(prompt).contains("至少 1 个根节点");
        assertThat(prompt).contains("有向无环图");
        assertThat(prompt).contains("禁止自引用");
        assertThat(prompt).contains("{\"nodes\":[{\"name\":\"招式名\",\"prerequisites\":[\"前置招式名\"]}]}");
        assertThat(prompt).contains("禁止").contains("Markdown 代码围栏");
    }

    // ==================== parseDraft ====================

    @Test
    void 解析合法JSON产物() {
        var draft = TechniqueTreeGenerator.parseDraft("""
                {"nodes":[
                  {"name":"天马流星拳","prerequisites":[]},
                  {"name":"天马彗星拳","prerequisites":["天马流星拳"]},
                  {"name":"天马闪光拳","prerequisites":["天马彗星拳"]}
                ]}""", NAMES);

        assertThat(draft.ignoredNames()).isEmpty();
        assertThat(draft.ignoredPrereqs()).isEmpty();
        assertThat(draft.nodes()).hasSize(3);
        assertThat(draft.nodes().get(0).isRoot()).isTrue();
        assertThat(draft.nodes().get(1).prerequisites()).containsExactly("天马流星拳");
        assertThat(draft.nodes().get(2).prerequisites()).containsExactly("天马彗星拳");
    }

    @Test
    void 解析时剥掉代码围栏() {
        var draft = TechniqueTreeGenerator.parseDraft("""
                ```json
                {"nodes":[{"name":"天马流星拳","prerequisites":[]}]}
                ```""", NAMES);

        assertThat(draft.nodes()).hasSize(1);
        assertThat(draft.nodes().get(0).name()).isEqualTo("天马流星拳");
    }

    @Test
    void 越界招式名与悬空前置被丢弃() {
        var draft = TechniqueTreeGenerator.parseDraft("""
                {"nodes":[
                  {"name":"天马流星拳","prerequisites":["升龙霸"]},
                  {"name":"流星拳","prerequisites":["天马流星拳"]},
                  {"name":"天马彗星拳","prerequisites":["天马流星拳","天马流星拳","天马彗星拳"]}
                ]}""", NAMES);

        // 「升龙霸」不在目录内 = 悬空前置；「流星拳」是模型改写过的名字 = 越界节点
        assertThat(draft.ignoredPrereqs()).containsExactly("升龙霸");
        assertThat(draft.ignoredNames()).containsExactly("流星拳");
        assertThat(draft.nodes()).hasSize(2);
        assertThat(draft.nodes().get(0).prerequisites()).isEmpty();
        // 重复前置去重、自引用剔除，只留一个合法前置
        assertThat(draft.nodes().get(1).prerequisites()).containsExactly("天马流星拳");
    }

    @Test
    void 重复节点名只保留首个() {
        var draft = TechniqueTreeGenerator.parseDraft("""
                {"nodes":[
                  {"name":"天马流星拳","prerequisites":[]},
                  {"name":"天马流星拳","prerequisites":[]}
                ]}""", NAMES);

        assertThat(draft.nodes()).hasSize(1);
        assertThat(draft.ignoredNames()).containsExactly("天马流星拳（重复）");
    }

    @Test
    void 畸形产物返回空而不抛异常() {
        assertThat(parsedNodes("这不是 JSON")).isEmpty();
        assertThat(parsedNodes("{\"foo\":1}")).isEmpty();
        assertThat(parsedNodes("{\"nodes\":\"天马流星拳\"}")).isEmpty();
        assertThat(parsedNodes("")).isEmpty();
        assertThat(parsedNodes(null)).isEmpty();
    }

    private static List<TechniqueNode> parsedNodes(String response) {
        return TechniqueTreeGenerator.parseDraft(response, NAMES).nodes();
    }

    // ==================== toSourceEntry ====================

    @Test
    void 源码块缩进与既有表项一致() {
        String source = TechniqueTreeGenerator.toSourceEntry("天马座", List.of(
                new TechniqueNode("天马流星拳", List.of()),
                new TechniqueNode("天马彗星拳", List.of("天马流星拳"))));

        assertThat(source).isEqualTo("""
                            Map.entry("天马座", tree(
                                    node("天马流星拳"),
                                    node("天马彗星拳", "天马流星拳"))),
                """);
    }

    // ==================== topoOrder ====================

    @Test
    void 拓扑重排让基础招在前奥义在后() {
        List<TechniqueNode> reversed = List.of(
                new TechniqueNode("天马闪光拳", List.of("天马彗星拳")),
                new TechniqueNode("天马彗星拳", List.of("天马流星拳")),
                new TechniqueNode("天马流星拳", List.of()));

        assertThat(namesOf(TechniqueTreeGenerator.topoOrder(reversed)))
                .containsExactly("天马流星拳", "天马彗星拳", "天马闪光拳");
    }

    @Test
    void 成环时退回声明顺序() {
        List<TechniqueNode> cyclic = List.of(
                new TechniqueNode("天马流星拳", List.of("天马彗星拳")),
                new TechniqueNode("天马彗星拳", List.of("天马流星拳")));

        assertThat(namesOf(TechniqueTreeGenerator.topoOrder(cyclic)))
                .containsExactly("天马流星拳", "天马彗星拳");
    }

    private static List<String> namesOf(List<TechniqueNode> nodes) {
        return nodes.stream().map(TechniqueNode::name).toList();
    }
}
