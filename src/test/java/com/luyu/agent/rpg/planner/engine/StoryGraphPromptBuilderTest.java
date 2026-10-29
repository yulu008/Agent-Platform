package com.luyu.agent.rpg.planner.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.StyleMode;

/**
 * {@link StoryGraphPromptBuilder} 幕生成 prompt 组装测试（story-graph-authoring
 * tasks 2.2 / spec「单幕生成小契约」「生成注入纪律」）。
 * <p>
 * 钉住：幕契约字段与硬约束（只画幕内边/ID 命名空间/≤25）、第 1 幕词汇表产出
 * vs 后续幕沿用注入、世界观摘要行 + S4 确认稿全文、前幕摘要拼接、
 * <b>prompt 不含前幕全量节点/边 JSON</b>（注入纪律核心断言）、幕级返工段、
 * 最后一幕 ending 提示。
 */
@DisplayName("S4.5 幕生成 prompt 组装")
class StoryGraphPromptBuilderTest {

    private final StoryGraphPromptBuilder builder = new StoryGraphPromptBuilder();

    private static final String PLOT_DRAFT = """
            ## 主线大纲
            - 开局锚点：夜袭织田邸
            ## 分幕结构
            - 第 1 幕｜主题：潜入与结识｜幕末拍子：拿到密信｜出口轨道：trust_信长 >= 30
            - 第 2 幕｜主题：分裂与抉择｜幕末拍子：清洲会议摊牌｜出口轨道：已获刀 == true
            - 第 3 幕｜主题：终局对决｜幕末拍子：比叡山火起｜出口轨道：终局 == true
            ## 叙事规则
            - 每章聚焦一名武将
            """;

    /** 前幕全量骨架（若被整段注入 prompt 会携带 n1_02 与 edges 数组——注入纪律断言的靶子） */
    private static final String ACT1_FULL_JSON = """
            <story_graph>
            {
              "act": 1, "theme": "潜入与结识",
              "converge_to": [{"orbit": "信任线", "guard": {"key": "trust_信长", "op": ">=", "value": 30}}],
              "state_schema": {"trust_信长": {"type": "number", "min": 0, "max": 100}},
              "nodes": [
                {"id": "n1_01", "type": "scene", "beat": "夜探织田邸"},
                {"id": "n1_02", "type": "scene", "beat": "廊下遇伏"},
                {"id": "n1_07", "type": "anchor", "beat": "拿到密信"}
              ],
              "edges": [{"from": "n1_01", "to": "n1_02", "label": "潜入"}]
            }
            </story_graph>
            """;

    private static final String ACT1_DIGEST = """
            - 第 1 幕「潜入与结识」：入口 n1_01；锚点 n1_07（拿到密信）；出口轨道 信任线 = trust_信长 >= 30
            """;

    private static PipelineManifest manifest() {
        PipelineManifest manifest = new PipelineManifest();
        manifest.setStyleMode("faithful");
        manifest.stage("S1").setSummary("冥星武将录的黑暗战国拆解");
        manifest.stage("S2").setSummary("星命决定命运的世界");
        manifest.stage("S3").setSummary("六位核心武将人物卡");
        manifest.stage("S4").setSummary("三幕主线：潜入、抉择、终局");
        return manifest;
    }

    private static StoryActOutlineParser.ActOutline outline(int actNumber) {
        return StoryActOutlineParser.parse(PLOT_DRAFT).get(actNumber - 1);
    }

    @Test
    void 幕契约指令含JSON字段与只画幕内边约束() {
        String prompt = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 2, 3, outline(2), PLOT_DRAFT, manifest(),
                ACT1_DIGEST, "{\"trust_信长\": {\"type\": \"number\"}}", null, null));

        assertThat(prompt).contains("<story_graph>");
        assertThat(prompt).contains("\"converge_to\"");
        assertThat(prompt).contains("\"entry_guard\"");
        assertThat(prompt).contains("\"allow_explicit_variants\"");
        // 只画幕内边约束（spec「幕内边生成与跨幕边引擎拼接」）
        assertThat(prompt).contains("只画幕内边");
        assertThat(prompt).contains("跨幕衔接（本幕出口锚 → 下一幕入口）由系统自动拼接");
        // ID 幕命名空间（本幕=2 → n2_01）
        assertThat(prompt).contains("n2_01");
        assertThat(prompt).contains("n2_01 到 n2_25");
        // 节点数上限
        assertThat(prompt).contains("本幕节点数 ≤ 25");
        // 标签包裹提示
        assertThat(prompt).contains("标签外的文字会被忽略");
    }

    @Test
    void 第1幕含词汇表产出指令且无沿用段() {
        String prompt = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 1, 3, outline(1), PLOT_DRAFT, manifest(),
                null, null, null, null));

        assertThat(prompt).contains("本幕是第 1 幕，额外在 JSON 顶层产出状态词汇表");
        assertThat(prompt).contains("\"state_schema\"");
        assertThat(prompt).contains("后续幕沿用此表不可新增");
        assertThat(prompt).doesNotContain("【状态词汇表（沿用既有，不得新增键）】");
        // 第 1 幕无前幕摘要段
        assertThat(prompt).doesNotContain("【前幕锚点链与出口轨道表");
    }

    @Test
    void 后续幕注入既有词汇表与沿用约束() {
        String prompt = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 2, 3, outline(2), PLOT_DRAFT, manifest(),
                ACT1_DIGEST, "{\"trust_信长\": {\"type\": \"number\"}}", null, null));

        assertThat(prompt).contains("【状态词汇表（沿用既有，不得新增键）】");
        assertThat(prompt).contains("trust_信长");
        assertThat(prompt).doesNotContain("额外在 JSON 顶层产出状态词汇表");
    }

    @Test
    void 世界观摘要行与S4确认稿全文注入() {
        String prompt = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 2, 3, outline(2), PLOT_DRAFT, manifest(),
                ACT1_DIGEST, "{\"trust_信长\": {\"type\": \"number\"}}", null, null));

        // 世界观摘要行（S2——D7 摘要行注入而非全文）
        assertThat(prompt).contains("- S2 世界观设定：星命决定命运的世界");
        assertThat(prompt).contains("- S1 IP 拆解：冥星武将录的黑暗战国拆解");
        // S4 确认稿全文（直接内容上游）
        assertThat(prompt).contains("【S4 剧情结构确认稿（主线大纲 + 分幕结构）】");
        assertThat(prompt).contains("夜袭织田邸");
        // 本幕定位强调
        assertThat(prompt).contains("【本幕任务定位】");
        assertThat(prompt).contains("第 2 幕｜主题：分裂与抉择");
    }

    @Test
    void 生成第5幕不注入前幕全量节点JSON() {
        // spec Scenario「后续幕生成不注入全图」：前 4 幕共 80 节点时 prompt 无全量 JSON
        String digestOfFourActs = """
                - 第 1 幕「潜入与结识」：入口 n1_01；锚点 n1_07（拿到密信）；出口轨道 信任线 = trust_信长 >= 30
                - 第 2 幕「分裂与抉择」：入口 n2_01；锚点 n2_05（清洲会议摊牌）；出口轨道 持刀线 = 已获刀 == true
                - 第 3 幕「联盟与背叛」：入口 n3_01；锚点 n3_09（金崎撤退）；出口轨道 阵营 in [织田, 明智]
                - 第 4 幕「孤立无援」：入口 n4_01；锚点 n4_06（比叡山围城）；出口轨道 民心 <= 40
                """;

        String prompt = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 5, 5, new StoryActOutlineParser.ActOutline(5, "终局对决", "比叡山火起", "终局 == true"),
                PLOT_DRAFT, manifest(), digestOfFourActs, "{\"trust_信长\": {}}", null, null));

        // 前幕锚点链摘要注入
        assertThat(prompt).contains("【前幕锚点链与出口轨道表（摘要，非全图）】");
        assertThat(prompt).contains("n4_06（比叡山围城）");
        // 注入纪律：前幕非锚点节点 ID 与 beat 正文不出现（幕契约模板的字段名示例不算
        // ——断言靶子是前幕具体数据而非 JSON 字段名）
        assertThat(prompt).doesNotContain("n1_02");
        assertThat(prompt).doesNotContain("n2_03");
        assertThat(prompt).doesNotContain("n3_04");
        assertThat(prompt).doesNotContain("夜探织田邸");
        // 前幕全量 JSON 的节点数组形态（多节点 JSON 序列化片段）不在 prompt 中
        assertThat(prompt).doesNotContain(ACT1_FULL_JSON.replaceAll("\\s+", "").substring(0, 60));
    }

    @Test
    void 最后一幕含ending提示非末幕含禁止提示() {
        String last = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 3, 3, outline(3), PLOT_DRAFT, manifest(),
                ACT1_DIGEST, "{}", null, null));
        String middle = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 2, 3, outline(2), PLOT_DRAFT, manifest(),
                ACT1_DIGEST, "{}", null, null));

        assertThat(last).contains("本幕是最后一幕，终局节点用 ending");
        assertThat(middle).contains("本幕不是最后一幕，不得出现 ending 节点");
    }

    @Test
    void 风格段按run风格注入() {
        String faithful = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 1, 3, outline(1), PLOT_DRAFT, manifest(), null, null, null, null));
        String remix = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.REMIX, 1, 3, outline(1), PLOT_DRAFT, manifest(), null, null, null, null));

        assertThat(faithful).contains("【风格：忠实拆解】");
        assertThat(remix).contains("【风格：自由二创】");
    }

    @Test
    void 幕级返工注入反馈历史与上轮幕稿() {
        PipelineManifest.ReRound round1 = new PipelineManifest.ReRound();
        round1.setRound(1);
        round1.setFeedback("锚点拍子与主线不符");
        PipelineManifest.ReRound round2 = new PipelineManifest.ReRound();
        round2.setRound(2);
        round2.setFeedback("轨道数值过严");

        String prompt = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 2, 3, outline(2), PLOT_DRAFT, manifest(),
                ACT1_DIGEST, "{}", List.of(round1, round2), "上轮第 2 幕骨架稿"));

        assertThat(prompt).contains("这是本幕（第 2 幕）的第 2 轮返工");
        assertThat(prompt).contains("- 第 1 轮反馈：锚点拍子与主线不符");
        assertThat(prompt).contains("- 第 2 轮反馈：轨道数值过严");
        assertThat(prompt).contains("反馈未提及的节点/边保持与上轮一致");
        assertThat(prompt).contains("【上轮本幕骨架稿】");
        assertThat(prompt).contains("上轮第 2 幕骨架稿");
        // 首轮无返工段的对偶断言
        String firstRound = builder.buildActPrompt(new StoryGraphPromptBuilder.ActPromptSpec(
                StyleMode.FAITHFUL, 1, 3, outline(1), PLOT_DRAFT, manifest(), null, null, null, null));
        assertThat(firstRound).doesNotContain("【返工要求】");
    }

    // ===== 2.4 解析失败修复 prompt =====

    @Test
    void 修复prompt含幕号原输出与逐条错误清单() {
        String prompt = builder.buildActRepairPrompt(2, ACT1_FULL_JSON,
                List.of("幕 act2 节点数超限：30 > 25（分幕小契约上限）",
                        "edges 引用不存在的节点 n9_01"));

        assertThat(prompt).contains("「第 2 幕」");
        assertThat(prompt).contains("【原始输出（需修复）】");
        // 原输出原样回喂（含标签与噪声）
        assertThat(prompt).contains("<story_graph>");
        assertThat(prompt).contains("n1_07");
        // 错误清单逐条呈现
        assertThat(prompt).contains("- 幕 act2 节点数超限：30 > 25（分幕小契约上限）");
        assertThat(prompt).contains("- edges 引用不存在的节点 n9_01");
        // 修复硬约束（只修错处 / 超限拆分 / 小契约 / 完整输出）
        assertThat(prompt).contains("只修正错误清单指出的问题");
        assertThat(prompt).contains("上限 25");
        assertThat(prompt).contains("只画幕内边");
        // 幕号契约重申（防「幕命名空间违规」伪错误误导修复方向：顶层字段缺失时补字段，不改前缀）
        assertThat(prompt).contains("\"act\": 2");
        assertThat(prompt).contains("前缀正确的节点不要改编号");
        // beat 必填 / type 枚举与自创类型改写指引（实测第 4 幕自创 entry/plot/choice 等类型修复失败钉）
        assertThat(prompt).contains("每个节点必须有非空 \"beat\"");
        assertThat(prompt).contains("只允许 \"scene\"");
        assertThat(prompt).contains("多支路汇聚的收束节点 → anchor");
    }

    @Test
    void 修复prompt空错误清单走兑底文案() {
        String prompt = builder.buildActRepairPrompt(1, "坏输出", List.of());

        assertThat(prompt).contains("（无具体错误，输出格式不符合契约）");
        assertThat(prompt).contains("坏输出");
    }
}
