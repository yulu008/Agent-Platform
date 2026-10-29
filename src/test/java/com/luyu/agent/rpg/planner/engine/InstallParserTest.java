package com.luyu.agent.rpg.planner.engine;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.luyu.agent.rpg.planner.engine.InstallParser.MaterializedWorld;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安装物化解析器测试（add-planner-pipeline tasks 5.1）。
 * <p>
 * 纯函数直灌（无 IO 无状态）：产物小节格式锚定各阶段生成器成稿模板——
 * 全要素解析（WorldSetting 列 / 通用轨世界书条目 / 人物卡六字段 + 命定档案 /
 * 叙事规则 / 分支条目字段直取）与容错降级（缺小节降级 null / 空集合、
 * 人物名缺失跳过整卡、「无」归 null 自由推演、轨道行消费丢弃）。
 */
class InstallParserTest {

    private static final String WORLD_DRAFT = """
            ## 名称
            冥星武将录
            ## 时代
            战国末期
            ## 氛围
            黑暗幻想
            ## 规则
            - 星命决定命运
            - 武将可被夺舍
            ## 世界描述
            冥星升起的世界，灾厄将至。
            ## 开场白模板
            你在烽烟中睁开双眼。
            ## 世界书条目
            ### 冥星
            关键词：冥星,天灾
            条目正文：每隔四十九年现世的灾星。
            ### 夺舍仪式
            关键词：夺舍
            条目正文：星魂降临武将之身的仪式。
            """;

    private static final String PLOT_DRAFT = """
            ## 主线大纲
            主线从甲的复仇开始。
            ## 叙事规则
            - 每章聚焦一名武将
            - 禁止跳跃时间线
            """;

    private static final String CHARACTER_WITH_DESTINED = """
            ## 人物 1：甲
            - 身份：军师
            - 性格：狡黠
            - 背景：没落贵族之后
            - 动机：复仇
            - 说话风格：文雅而毒舌
            - 知识：星象学,兵法
            ### 命定档案
            - faction：织田家
            - constellation：天魁星
            - starRank：主星
            - eraNote：战国末期
            """;

    private static final String CHARACTER_PLAIN = """
            ## 人物 2：乙
            - 身份：浪人
            - 性格：沉默
            - 背景：来历不明
            - 动机：寻找失落的佩刀
            - 说话风格：寡言
            - 知识：剑术
            """;

    private static final String BRANCH_STAR_TRACK = """
            ## 分支 1：暗杀线
            触发词：暗杀,夜袭
            优先级：10
            轨位：star
            注入内容：玩家选择暗杀路线时，天魁星的光芒会先一步黯淡。
            细节由 GM 即兴展开。
            """;

    private static final String BRANCH_WORLD_TRACK = """
            ## 分支 2：村落地道
            触发词：地道
            优先级：5
            轨位：world
            注入内容：村落地道通往军械库。
            """;

    @Test
    void 全要素解析六小节直取WorldSetting列() {
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(CHARACTER_WITH_DESTINED),
                List.of(BRANCH_STAR_TRACK), "world-1");

        assertThat(payload.world().getId()).isEqualTo("world-1");
        assertThat(payload.world().getName()).isEqualTo("冥星武将录");
        assertThat(payload.world().getEra()).isEqualTo("战国末期");
        assertThat(payload.world().getAtmosphere()).isEqualTo("黑暗幻想");
        assertThat(payload.world().getRules()).isEqualTo("[\"星命决定命运\",\"武将可被夺舍\"]");
        assertThat(payload.world().getSettingDesc()).contains("冥星升起的世界");
        assertThat(payload.world().getOpeningTemplate()).isEqualTo("你在烽烟中睁开双眼。");
        // locations 固定 [] 对齐 design D11（预设世界同惯例：GM 依描述即兴）
        assertThat(payload.world().getLocations()).isEqualTo("[]");
        // 叙事规则取 04-plot「## 叙事规则」小节
        assertThat(payload.world().getNarrativeRules())
                .contains("每章聚焦一名武将").contains("禁止跳跃时间线");
    }

    @Test
    void 名称与时代超列宽时防御截断() {
        // 7.2 E2E 实证场景：S2「时代」按模板写整段背景总览，超出 rpg_world_setting.era
        // VARCHAR(100) 导致安装 500（原子回滚）；物化层硬剪到列宽不废整包，全文仍在
        // 工作区确认稿可查。
        String draft = """
                ## 名称
                %s
                ## 时代
                %s
                ## 氛围
                黑暗幻想
                """.formatted("超".repeat(250), "古".repeat(174));

        MaterializedWorld payload = InstallParser.parse(
                draft, null, List.of(), List.of(), "world-clip");

        assertThat(payload.world().getName()).hasSize(InstallParser.NAME_MAX);
        assertThat(payload.world().getEra()).hasSize(InstallParser.ERA_MAX);
        assertThat(payload.world().getName()).startsWith("超");
        assertThat(payload.world().getEra()).startsWith("古");
        // 未超长列不受影响（TEXT 无列宽）
        assertThat(payload.world().getAtmosphere()).isEqualTo("黑暗幻想");
    }

    @Test
    void 世界书条目块解析为通用轨模板行() {
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(), List.of(), "world-1");

        assertThat(payload.worldbookEntries()).hasSize(2);
        assertThat(payload.worldbookEntries().get(0).getTitle()).isEqualTo("冥星");
        assertThat(payload.worldbookEntries().get(0).getKeywords()).isEqualTo("冥星,天灾");
        assertThat(payload.worldbookEntries().get(0).getContent())
                .isEqualTo("每隔四十九年现世的灾星。");
        assertThat(payload.worldbookEntries().get(0).getWorldId()).isEqualTo("world-1");
        assertThat(payload.worldbookEntries().get(0).getEnabled()).isTrue();
        assertThat(payload.worldbookEntries().get(0).getSortOrder()).isZero();
        assertThat(payload.worldbookEntries().get(1).getTitle()).isEqualTo("夺舍仪式");
        assertThat(payload.worldbookEntries().get(1).getSortOrder()).isEqualTo(1);
        // 模板层语义：安装条目不挂 set 与 source_ref
        assertThat(payload.worldbookEntries().get(0).getSetId()).isNull();
        assertThat(payload.worldbookEntries().get(0).getSourceRef()).isNull();
    }

    @Test
    void 人物卡六字段直取且知识字段转JSON数组() {
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(CHARACTER_PLAIN), List.of(), "world-1");

        assertThat(payload.characters()).hasSize(1);
        assertThat(payload.characters().get(0).getName()).isEqualTo("乙");
        assertThat(payload.characters().get(0).getType()).isEqualTo("npc");
        assertThat(payload.characters().get(0).getId()).isNotBlank();
        assertThat(payload.characters().get(0).getWorldId()).isEqualTo("world-1");
        assertThat(payload.characters().get(0).getIdentity()).isEqualTo("浪人");
        assertThat(payload.characters().get(0).getPersonality()).isEqualTo("沉默");
        assertThat(payload.characters().get(0).getBackground()).isEqualTo("来历不明");
        assertThat(payload.characters().get(0).getMotivation()).isEqualTo("寻找失落的佩刀");
        assertThat(payload.characters().get(0).getSpeechStyle()).isEqualTo("寡言");
        assertThat(payload.characters().get(0).getKnowledge()).isEqualTo("[\"剑术\"]");
        // 无命定档案子节 → 无 figure（人物卡仍物化）
        assertThat(payload.figures()).isEmpty();
    }

    @Test
    void 命定档案子节物化DestinedFigure复用卡底稿() {
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(CHARACTER_WITH_DESTINED),
                List.of(), "world-1");

        assertThat(payload.figures()).hasSize(1);
        assertThat(payload.figures().get(0).getFigureKey()).isEqualTo("甲");
        assertThat(payload.figures().get(0).getDisplayName()).isEqualTo("甲");
        assertThat(payload.figures().get(0).getFaction()).isEqualTo("织田家");
        assertThat(payload.figures().get(0).getConstellation()).isEqualTo("天魁星");
        assertThat(payload.figures().get(0).getStarRank()).isEqualTo("主星");
        assertThat(payload.figures().get(0).getEraNote()).isEqualTo("战国末期");
        // 三件套底稿复用人物卡
        assertThat(payload.figures().get(0).getIdentity()).isEqualTo("军师");
        assertThat(payload.figures().get(0).getPersonality()).isEqualTo("狡黠");
        assertThat(payload.figures().get(0).getBackground()).isEqualTo("没落贵族之后");
        assertThat(payload.figures().get(0).getSortOrder()).isZero();
    }

    @Test
    void 分支条目字段直取WorldbookEntry且轨位消费丢弃() {
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(),
                List.of(BRANCH_STAR_TRACK, BRANCH_WORLD_TRACK), "world-1");

        assertThat(payload.branchEntries()).hasSize(2);
        // 标题剥序号前缀取实际分支名（title 是展示名也是关键词）
        assertThat(payload.branchEntries().get(0).getTitle()).isEqualTo("暗杀线");
        assertThat(payload.branchEntries().get(0).getKeywords()).isEqualTo("暗杀,夜袭");
        assertThat(payload.branchEntries().get(0).getSortOrder()).isEqualTo(10);
        assertThat(payload.branchEntries().get(0).getContent())
                .contains("天魁星的光芒会先一步黯淡")
                .contains("细节由 GM 即兴展开");
        assertThat(payload.branchEntries().get(0).getEnabled()).isTrue();
        assertThat(payload.branchEntries().get(1).getTitle()).isEqualTo("村落地道");
        assertThat(payload.branchEntries().get(1).getSortOrder()).isEqualTo(5);
        // 轨位是生成期设计提示：content 不含轨位行、不注入字段名
        assertThat(payload.branchEntries().get(0).getContent()).doesNotContain("轨位");
    }

    @Test
    void 缺稿缺小节全部降级不阻断() {
        MaterializedWorld payload = InstallParser.parse(null, null, null, null, "world-x");

        assertThat(payload.world().getName()).isNull();
        assertThat(payload.world().getRules()).isEqualTo("[]");
        assertThat(payload.world().getNarrativeRules()).isNull();
        assertThat(payload.worldbookEntries()).isEmpty();
        assertThat(payload.characters()).isEmpty();
        assertThat(payload.figures()).isEmpty();
        assertThat(payload.branchEntries()).isEmpty();
        assertThat(payload.entryCount()).isZero();
    }

    @Test
    void 人物名缺失的候选卡整卡跳过() {
        String malformed = """
                - 身份：无名
                - 性格：?
                """;
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(malformed), List.of(), "world-1");

        assertThat(payload.characters()).isEmpty();
        assertThat(payload.figures()).isEmpty();
    }

    @Test
    void 无序号前缀的标题整标题即名() {
        String plainName = """
                ## 甲
                - 身份：军师
                """;
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(plainName), List.of(), "world-1");

        // L2 逐卡编辑后的直呼其名格式：无「人物 N：」前缀，整标题即名
        assertThat(payload.characters()).hasSize(1);
        assertThat(payload.characters().get(0).getName()).isEqualTo("甲");
    }

    @Test
    void 分支标题缺失返回null跳过() {
        String malformed = """
                触发词：无题
                注入内容：无标题分支。
                """;
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(), List.of(malformed), "world-1");

        assertThat(payload.branchEntries()).isEmpty();
    }

    @Test
    void 命定档案无值归null自由推演且全空不出figure() {
        String freeForm = """
                ## 人物 3：丙
                - 身份：医师
                ### 命定档案
                - faction：无
                - constellation：无
                - starRank：无
                - eraNote：无
                """;
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(freeForm), List.of(), "world-1");

        // 人物卡照常物化；命定档案四字段全「无」= 全 null → 不出 figure（自由推演）
        assertThat(payload.characters()).hasSize(1);
        assertThat(payload.figures()).isEmpty();
    }

    @Test
    void 规则小节只认列表项且非列表行忽略() {
        String draft = """
                ## 名称
                测试世界
                ## 规则
                规则导语（非列表行，忽略）
                - 第一条
                * 第二条
                普通段落
                """;
        MaterializedWorld payload = InstallParser.parse(
                draft, null, List.of(), List.of(), "world-1");

        assertThat(payload.world().getRules()).isEqualTo("[\"第一条\",\"第二条\"]");
    }

    @Test
    void entryCount统计全部物化实体() {
        MaterializedWorld payload = InstallParser.parse(
                WORLD_DRAFT, PLOT_DRAFT, List.of(CHARACTER_WITH_DESTINED),
                List.of(BRANCH_STAR_TRACK), "world-1");

        // 2 世界书条目 + 1 人物卡 + 1 命定档案 + 1 分支条目 = 5
        assertThat(payload.entryCount()).isEqualTo(5);
    }
}
