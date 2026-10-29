package com.luyu.agent.rpg.planner.engine;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StagePromptAssembler#extractRosterSection} 名册节提取测试
 * （S3 名册对照注入的拼装件，名册遗漏反馈）。
 * <p>
 * 钉住：faithful 成稿模板节名锚定（含标题行）、提取到下一 {@code ## } 节前
 * 截止、名册节在稿末尾完整提取；remix 稿（气质原型节）与无名册节稿
 * 返回 null（调用方零注入容错，旧 run 无副作用）。
 */
class StagePromptAssemblerTest {

    @Test
    void 提取名册节含标题且到下一节截止() {
        String s1 = """
                # IP 拆解报告

                ## 一、核心概念
                概念内容。

                ## 三、人物全量名册
                - **严嵩**
                  - 身份：明代奸臣
                - **欧阳轩**：济州大侠

                ## 四、主线与关键剧情节点
                1. 严嵩挑起武林动荡
                """;

        String roster = StagePromptAssembler.extractRosterSection(s1);

        assertThat(roster).startsWith("## 三、人物全量名册");
        assertThat(roster).contains("- **严嵩**");
        assertThat(roster).contains("欧阳轩");
        // 名册节之后的节（## 四）不进名册
        assertThat(roster).doesNotContain("## 四");
    }

    @Test
    void 名册节在稿末尾完整提取() {
        String s1 = "## 一、核心概念\n概念。\n## 三、人物全量名册\n- **甲**：身份";

        assertThat(StagePromptAssembler.extractRosterSection(s1))
                .isEqualTo("## 三、人物全量名册\n- **甲**：身份");
    }

    @Test
    void remix稿与无名册稿返回null() {
        // remix 成稿是「人物气质原型」节，节名不匹配名册锚
        assertThat(StagePromptAssembler.extractRosterSection(
                "## 三、值得保留的人物气质原型\n- 原型甲")).isNull();
        assertThat(StagePromptAssembler.extractRosterSection(
                "## 一、核心概念\n概念")).isNull();
        // 容错：null / 空白输入（S1 稿不存在的旧 run）
        assertThat(StagePromptAssembler.extractRosterSection(null)).isNull();
        assertThat(StagePromptAssembler.extractRosterSection("  ")).isNull();
    }

    // ===== S3 分批人名工具与紧凑索引（planner-s3-batch-generate tasks 1.3/4.1，D4/D7） =====

    /** 名册节夹具：主人物多行块 + 次要人物单行双形态 + 子标题分组 */
    private static final String ROSTER = """
            ## 三、人物全量名册
            ### 主人物
            - **严嵩**
              - 身份：明代奸臣
            - **欧阳轩**：济州大侠
            - **乌彩蝶**：巫山派次要人物
            """;

    @Test
    void 名册人名提取双形态统一保序() {
        // 主人物多行块（首行锚取加粗名）与次要人物单行同锚；子标题行不破坏提取
        assertThat(StagePromptAssembler.extractRosterNames(ROSTER))
                .containsExactly("严嵩", "欧阳轩", "乌彩蝶");
        // null / 空白名册返回空列表（调用方据此判「无名稿回退单批」）
        assertThat(StagePromptAssembler.extractRosterNames(null)).isEmpty();
        assertThat(StagePromptAssembler.extractRosterNames("  ")).isEmpty();
    }

    @Test
    void 剩余清单推导排除已产出标题含容差匹配() {
        // 已产出标题是卡标题（「人物 N：人名」剥前缀）；名册序保持 = 分批切块序
        assertThat(StagePromptAssembler.rosterRemaining(
                List.of("甲", "乙", "丙", "丁", "戊"),
                List.of("人物 1：甲", "人物 2：丙")))
                .containsExactly("乙", "丁", "戊");
        // D7 容差：标题人名带空白差异仍判已产出（去空白归一，宁重复不漏人）
        assertThat(StagePromptAssembler.rosterRemaining(
                List.of("丁"), List.of("人物 3：丁 "))).isEmpty();
        // 空名册 / 空产出防御
        assertThat(StagePromptAssembler.rosterRemaining(List.of(), List.of("人物 1：甲")))
                .isEmpty();
        assertThat(StagePromptAssembler.rosterRemaining(List.of("甲"), List.of()))
                .containsExactly("甲");
    }

    @Test
    void 编号形名册名与模板标题双候选匹配() {
        // 6.3 E2E 发现：名册名本身是「人物NNN」（编号名）时，LLM 按成稿模板产
        // 「人物 021：<自拟身份名>」——剥前缀取到身份名与名册名失配（剩余虚高 /
        // 重复卡漏检）；候选集展开补「序号前缀本身」+ 去空白归一兑住
        assertThat(StagePromptAssembler.titleNameCandidates("人物 021：魔弹圣女"))
                .containsExactly("魔弹圣女", "人物 021");
        // 真人名册（原语义不变）：剥前缀人名为首选，前缀作为第二候选
        assertThat(StagePromptAssembler.titleNameCandidates("人物 1：甲"))
                .containsExactly("甲", "人物 1");
        // 无前缀标题整标题即名（L2 逐卡编辑后直呼其名）；空标题空集
        assertThat(StagePromptAssembler.titleNameCandidates("甲"))
                .containsExactly("甲");
        assertThat(StagePromptAssembler.titleNameCandidates(null)).isEmpty();
        assertThat(StagePromptAssembler.titleNameCandidates("  ")).isEmpty();
        // rosterRemaining 口径：编号名册 × 模板标题（空格差异归一）判已产出
        assertThat(StagePromptAssembler.rosterRemaining(
                List.of("人物021", "人物022"),
                List.of("人物 021：魔弹圣女")))
                .containsExactly("人物022");
        assertThat(StagePromptAssembler.titleMatchesName("人物 021：魔弹圣女", "人物021"))
                .isTrue();
        assertThat(StagePromptAssembler.titleMatchesName("人物 021：魔弹圣女", "人物022"))
                .isFalse();
    }

    @Test
    void 重名身份键限定名册人名域() {
        // 6.3 E2E 后真实使用发现（run 5ffd6d5b）：真实人名名册下「人物 N」序
        // 号前缀是 LLM 自编——不作身份键（跨轮/跨批自编序号撞车不误杀）；
        // 冒号后命中名册的名字才是身份键
        assertThat(StagePromptAssembler.rosterHitNames(
                "人物 1：晓净师太", List.of("严嵩", "晓净师太")))
                .containsExactly("晓净师太");
        // 编号形名册：序号前缀命中名册、仍为身份键（6.3 E2E 双候选语义保持）
        assertThat(StagePromptAssembler.rosterHitNames(
                "人物 021：魔弹圣女", List.of("人物021", "人物022")))
                .containsExactly("人物 021");
        // 名册域外（虚构人物/变体名）：无命中不判身份——宁保留人工毙，不误杀
        assertThat(StagePromptAssembler.rosterHitNames(
                "张三丰：武当祖师", List.of("严嵩"))).isEmpty();
        // 无前缀标题：整标题即名，命中名册才作身份键
        assertThat(StagePromptAssembler.rosterHitNames("甲", List.of("甲", "乙")))
                .containsExactly("甲");
        // 空/空白标题与 null 名册防御
        assertThat(StagePromptAssembler.rosterHitNames(null, List.of("甲"))).isEmpty();
        assertThat(StagePromptAssembler.rosterHitNames("  ", List.of("甲"))).isEmpty();
        assertThat(StagePromptAssembler.rosterHitNames("甲", null)).isEmpty();
    }

    @Test
    void 批内名册块按人名保序提取子集() {
        // 请求序反于名册序：返回按名册序（切块单元 = 条目块，主人物块的身份描述不丢）
        List<String> blocks = StagePromptAssembler.rosterBlocksForNames(
                ROSTER, List.of("乌彩蝶", "严嵩"));
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).startsWith("- **严嵩**").contains("- 身份：明代奸臣");
        assertThat(blocks.get(1)).isEqualTo("- **乌彩蝶**：巫山派次要人物");
        // 漂移人名静默跳过（名册编辑后容错——由剩余清单重跑兑付）；null 防御
        assertThat(StagePromptAssembler.rosterBlocksForNames(ROSTER, List.of("不存在的人")))
                .isEmpty();
        assertThat(StagePromptAssembler.rosterBlocksForNames(null, List.of("严嵩"))).isEmpty();
        assertThat(StagePromptAssembler.rosterBlocksForNames(ROSTER, null)).isEmpty();
    }

    @Test
    void 紧凑索引行格式人名身份钩子与命定标记() {
        String cardA = """
                ## 人物 1：严嵩
                - 身份：明代奸臣
                - 性格：贪婪

                ### 命定档案
                - faction: specter
                """;
        String cardB = """
                ## 人物 2：欧阳轩
                - 性格：豪爽
                """;
        // L2 逐卡编辑后直呼其名（无「人物 N：」前缀）也容差成行
        String cardC = "## 甲\n- 身份：明代书生";

        assertThat(StagePromptAssembler.compactCharacterIndex(List.of(cardA, cardB, cardC)))
                .isEqualTo("- 严嵩：明代奸臣（命定）\n- 欧阳轩\n- 甲：明代书生\n");
    }

    @Test
    void 紧凑索引行无效输入返回null() {
        // null / 空集 / 全无效元素（null、空白卡）→ 无有效行返回 null（调用方零注入）
        // （Arrays.asList 容纳 null 元素——List.of 会拒绝 null）
        assertThat(StagePromptAssembler.compactCharacterIndex(null)).isNull();
        assertThat(StagePromptAssembler.compactCharacterIndex(List.of())).isNull();
        assertThat(StagePromptAssembler.compactCharacterIndex(Arrays.asList(null, "  "))).isNull();
    }

    @Test
    void 名描述形标题冒号前段进候选集() {
        // 分批锚定句产出「名：描述」形（标题以名册原名开头）：冒号前段是身份
        // 名候选（整标题兜底）——修复前只取整标题，rosterRemaining 与重名检测
        // 双侧失配（已成功批人物回剩余被重复生成）
        assertThat(StagePromptAssembler.titleNameCandidates("清风：武当四老之一，清灵孪生弟"))
                .containsExactly("清风", "清风：武当四老之一，清灵孪生弟");
        assertThat(StagePromptAssembler.titleNameCandidates("乌元敬: 巫山派掌门"))
                .containsExactly("乌元敬", "乌元敬: 巫山派掌门");
        // 已产出「名：描述」形：剩余推导排除该人 + 身份键命中名册
        assertThat(StagePromptAssembler.rosterRemaining(
                        List.of("清风", "阴步云"), List.of("清风：武当四老之一，清灵孪生弟")))
                .containsExactly("阴步云");
        assertThat(StagePromptAssembler.rosterHitNames(
                        "清风：武当四老之一，清灵孪生弟", List.of("清风", "阴步云")))
                .containsExactly("清风");
        // 纯名单名（L2 直呼其名）原语义不变；冒号前段空防御
        assertThat(StagePromptAssembler.titleNameCandidates("无我"))
                .containsExactly("无我");
        assertThat(StagePromptAssembler.titleNameCandidates("：描述"))
                .containsExactly("：描述");
    }
}
