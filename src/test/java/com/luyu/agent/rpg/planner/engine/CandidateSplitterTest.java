package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CandidateSplitter} 拆分语义测试（tasks 3.6）。
 * <p>
 * 钉住：## 二级标题切分且块首标题保留、首个标题前导语丢弃、块尾空白 trim、
 * ### 三级标题不切分（留在候选块内）、无二级标题整稿降级单候选、空输入空列表。
 */
class CandidateSplitterTest {

    @Test
    void 按二级标题切分且块首标题保留() {
        List<String> candidates = CandidateSplitter.split("""
                ## 人物 1：织田信长
                - 身份：尾张当主

                ## 人物 2：柴田胜家
                - 身份：猛将
                """);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).startsWith("## 人物 1：织田信长");
        assertThat(candidates.get(0)).contains("- 身份：尾张当主");
        assertThat(candidates.get(1)).startsWith("## 人物 2：柴田胜家");
    }

    @Test
    void 首个标题前的导语丢弃() {
        List<String> candidates = CandidateSplitter.split("""
                下面是人物候选集：
                请查收。

                ## 人物 1：某人
                内容
                """);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0)).startsWith("## 人物 1：某人");
        assertThat(candidates.get(0)).doesNotContain("请查收");
    }

    @Test
    void 三级标题不切分留在块内() {
        // 命定档案子节（###）是候选块内部结构，不得切成新候选
        List<String> candidates = CandidateSplitter.split("""
                ## 人物 1：长尾景虎
                - 身份：越后之龙
                ### 命定档案
                - faction: saint

                ## 人物 2：无档案者
                - 身份：路人
                """);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).contains("### 命定档案");
        assertThat(candidates.get(0)).contains("faction: saint");
    }

    @Test
    void 块尾空白被trim() {
        List<String> candidates = CandidateSplitter.split("""
                ## 人物 1：某人
                内容


                """);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0)).endsWith("内容");
    }

    @Test
    void 无二级标题时整稿降级单候选() {
        List<String> candidates = CandidateSplitter.split("模型格式漂移：整段输出没有候选标题\n但内容有效");

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0)).contains("整段输出没有候选标题");
    }

    @Test
    void 空白输入返回空列表() {
        assertThat(CandidateSplitter.split(null)).isEmpty();
        assertThat(CandidateSplitter.split("")).isEmpty();
        assertThat(CandidateSplitter.split("   \n  \n")).isEmpty();
    }

    @Test
    void 仅标题行序列也逐块成候选() {
        List<String> candidates = CandidateSplitter.split("## a\n## b\n## c");

        assertThat(candidates).hasSize(3);
        assertThat(candidates.get(2)).isEqualTo("## c");
    }
}
