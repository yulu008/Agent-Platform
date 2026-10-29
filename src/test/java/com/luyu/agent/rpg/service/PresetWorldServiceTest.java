package com.luyu.agent.rpg.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PresetWorldService} 的单元测试（preset-rpg-entry / tasks 1.4）。
 * <p>
 * 钉住四件容易回归的事：
 * <ol>
 *   <li><b>小节切分</b>：按行首二级标题切分，文件头注释与一级标题整体忽略，
 *       注释内带缩进的 {@code ## 名称} 不得被误当成小节；</li>
 *   <li><b>缺失降级</b>：缺小节不抛异常，规则降级为空 JSON 数组；</li>
 *   <li><b>幂等分流</b>：首次物化走 INSERT，二次物化走 UPDATE 且必须单独刷新
 *       opening_template（{@code update()} 刻意不动该列）；</li>
 *   <li><b>租户隔离的确定性 ID</b>：{@code preset-adventure:<tenantId>}，
 *       长度不越 VARCHAR(64) 界，未知预设键在拼路径前即被白名单拒绝。</li>
 * </ol>
 */
class PresetWorldServiceTest {

    private static final String TENANT_ID = "t-test";
    private static final String PRESET_WORLD_ID = "preset-adventure:" + TENANT_ID;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 覆盖六小节的自造源文：含带缩进的干扰标题、多段世界描述、三行规则 */
    private static final String FULL_MARKDOWN = """
            <!--
              文件头注释，说明小节约定：
              ## 名称         name       ← 带缩进，不应被切成小节
              ## 开场白模板   opening    ← 同上
            -->
            # 一级标题也会被忽略

            ## 名称
            测试世界

            ## 时代
            测试时代

            ## 氛围
            测试氛围

            ## 规则
            - 规则一
            - 规则二
            - 规则三

            ## 世界描述
            第一段。

            第二段。

            ## 开场白模板
            {{player_name}} 抵达 {{world_name}}，命定{{constellation}}。
            """;

    private RpgWorldSettingRepository worldRepo;
    private RpgStarIncarnationRepository starRepo;
    private RpgDestinedFigureRepository figureRepo;
    private WorldCoverStorageService coverStorage;
    private PresetWorldService service;

    @BeforeEach
    void setUp() {
        // 物化以 TenantContext.requireTenantId() 拼确定性 ID，须先建立租户上下文
        // （同 WorkshopServiceLocalizeNamesTest 模式）
        TenantContext.set("u-test", TENANT_ID);
        worldRepo = mock(RpgWorldSettingRepository.class);
        starRepo = mock(RpgStarIncarnationRepository.class);
        figureRepo = mock(RpgDestinedFigureRepository.class);
        coverStorage = mock(WorldCoverStorageService.class);
        service = new PresetWorldService(worldRepo, starRepo, figureRepo, coverStorage);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ==================== 小节切分 ====================

    @Test
    void 完整六小节按二级标题切分且忽略前言() {
        Map<String, String> sections = service.parseSections(FULL_MARKDOWN);

        assertThat(sections).hasSize(6);
        assertThat(sections.get("名称")).isEqualTo("测试世界");
        assertThat(sections.get("时代")).isEqualTo("测试时代");
        assertThat(sections.get("氛围")).isEqualTo("测试氛围");
        assertThat(sections.get("世界描述")).isEqualTo("第一段。\n\n第二段。");
        // 占位符原样保留，供 OpeningNarrationService 在开局时插值
        assertThat(sections.get("开场白模板"))
                .isEqualTo("{{player_name}} 抵达 {{world_name}}，命定{{constellation}}。");
    }

    @Test
    void 缺失小节不抛异常且不出现在结果中() {
        Map<String, String> sections = service.parseSections("## 名称\n只有名字\n");

        assertThat(sections).containsOnlyKeys("名称");
        assertThat(sections.get("时代")).isNull();
        // 调用方的降级路径：getOrDefault 取空串，parseRules 取空数组
        assertThat(sections.getOrDefault("时代", "")).isEmpty();
        assertThat(service.parseRules(sections.get("规则"))).isEqualTo("[]");
    }

    @Test
    void 无二级标题的源文解析为空map() {
        assertThat(service.parseSections("# 只有前言\n随便写点什么\n")).isEmpty();
    }

    // ==================== 规则解析 ====================

    @Test
    void 规则三行解析为长度3的JSON数组() throws Exception {
        String json = service.parseRules("- 规则一\n- 规则二\n- 规则三");

        JsonNode rules = JSON.readTree(json);
        assertThat(rules).hasSize(3);
        assertThat(rules.get(0).asText()).isEqualTo("规则一");
        assertThat(rules.get(2).asText()).isEqualTo("规则三");
    }

    @Test
    void 规则小节含缩进与非列表行时只取列表项() throws Exception {
        String json = service.parseRules("  - 带缩进的规则\n说明文字不算规则\n* 星号也算\n\n- 末条\n");

        JsonNode rules = JSON.readTree(json);
        assertThat(rules).hasSize(3);
        assertThat(rules.get(0).asText()).isEqualTo("带缩进的规则");
        assertThat(rules.get(1).asText()).isEqualTo("星号也算");
    }

    @Test
    void 规则缺失或无有效条目降级为空数组() throws Exception {
        assertThat(JSON.readTree(service.parseRules(null))).isEmpty();
        assertThat(JSON.readTree(service.parseRules("一行说明都没有列表符号"))).isEmpty();
    }

    // ==================== 物化：首次 INSERT ====================

    @Test
    void 首次物化走save且ID为前缀拼租户() throws Exception {
        when(worldRepo.findById(anyString())).thenReturn(null);

        PresetWorldService.PresetWorld result = service.materialize("adventure");

        ArgumentCaptor<WorldSetting> captor = ArgumentCaptor.forClass(WorldSetting.class);
        verify(worldRepo).save(captor.capture());
        verify(worldRepo, never()).update(any());
        verify(worldRepo, never()).updateOpeningTemplate(anyString(), anyString());

        WorldSetting saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(PRESET_WORLD_ID);
        assertThat(result.worldId()).isEqualTo(PRESET_WORLD_ID);
        // 确定性 ID 必须留在 VARCHAR(64) 界内（前缀 17 + 租户 UUID 36 = 53）
        assertThat(saved.getId()).hasSizeLessThan(64);
        // 预设世界不建结构化地点（design D11）
        assertThat(saved.getLocations()).isEqualTo("[]");
        // 真实源文 rpg/preset-adventure.md 的六小节均已落位
        assertThat(result.worldName()).isEqualTo(saved.getName()).isNotBlank();
        assertThat(saved.getEra()).isNotBlank();
        assertThat(saved.getAtmosphere()).isNotBlank();
        assertThat(saved.getSettingDesc()).isNotBlank();
        assertThat(saved.getOpeningTemplate()).contains("{{player_name}}").contains("{{constellation}}");
        // 源文 ## 规则 现为 6 条（含 star-lorebook 新增的抢星规则）
        assertThat(JSON.readTree(saved.getRules())).hasSize(6);

        // star-lorebook：冥星武将录随物化全量重写，14 条且天兽/天魔为 NPC 专属星
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StarIncarnation>> starCaptor =
                (ArgumentCaptor<List<StarIncarnation>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(starRepo).rewriteAll(eq(PRESET_WORLD_ID), starCaptor.capture());
        List<StarIncarnation> incarnations = starCaptor.getValue();
        assertThat(incarnations).hasSize(14);
        assertThat(incarnations.get(0).getStarName()).isEqualTo("天魁星");
        assertThat(incarnations.get(0).getWarlordName()).isEqualTo("织田信长");
        assertThat(incarnations.get(0).getLore()).isNotBlank();
        assertThat(incarnations.get(0).getAliases()).contains("信长");
        assertThat(incarnations.get(0).getRollable()).isTrue();
        StarIncarnation beast = incarnations.stream()
                .filter(i -> "天兽星".equals(i.getStarName())).findFirst().orElseThrow();
        assertThat(beast.getWarlordName()).isEqualTo("前田庆次");
        assertThat(beast.getRollable()).isFalse();
        StarIncarnation demon = incarnations.stream()
                .filter(i -> "天魔星".equals(i.getStarName())).findFirst().orElseThrow();
        assertThat(demon.getWarlordName()).isEqualTo("森长可");
        assertThat(demon.getRollable()).isFalse();

        // preset-destined-figures：命定人物随物化全量重写，4 位且三种入局模式齐全
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DestinedFigure>> figureCaptor =
                (ArgumentCaptor<List<DestinedFigure>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(figureRepo).rewriteAll(eq(PRESET_WORLD_ID), figureCaptor.capture());
        List<DestinedFigure> figures = figureCaptor.getValue();
        assertThat(figures).hasSize(4);
        // 预绑圣衣：长尾景虎 → saint / 处女座 / Gold
        DestinedFigure kagetora = figures.get(0);
        assertThat(kagetora.getFigureKey()).isEqualTo("kagetora");
        assertThat(kagetora.getFaction()).isEqualTo("saint");
        assertThat(kagetora.getConstellation()).isEqualTo("处女座");
        assertThat(kagetora.getStarRank()).isEqualTo("Gold");
        assertThat(kagetora.getAliases()).contains("越后之龙");
        // 预绑冥衣：织田信长 → specter / 天魁星（与冥星武将录条目同星名）
        DestinedFigure nobunaga = figures.get(2);
        assertThat(nobunaga.getFigureKey()).isEqualTo("nobunaga");
        assertThat(nobunaga.getFaction()).isEqualTo("specter");
        assertThat(nobunaga.getConstellation()).isEqualTo("天魁星");
        // 自由推演：织田信胜星命三字段留空 → null
        DestinedFigure nobukatsu = figures.get(3);
        assertThat(nobukatsu.getFigureKey()).isEqualTo("nobukatsu");
        assertThat(nobukatsu.getFaction()).isNull();
        assertThat(nobukatsu.getConstellation()).isNull();
        assertThat(nobukatsu.getStarRank()).isNull();
    }

    // ==================== 物化：二次 UPDATE ====================

    @Test
    void 二次物化走update并单独刷新开场白模板() {
        when(worldRepo.findById(PRESET_WORLD_ID)).thenReturn(new WorldSetting());

        PresetWorldService.PresetWorld result = service.materialize("adventure");

        verify(worldRepo, never()).save(any());
        verify(worldRepo).update(any(WorldSetting.class));
        // update() 不动 opening_template 列，必须单独刷新，否则用户改 md 的开场白永不生效
        verify(worldRepo).updateOpeningTemplate(eq(PRESET_WORLD_ID), contains("{{player_name}}"));
        // 幂等：返回同一个 worldId，不产生第二行
        assertThat(result.worldId()).isEqualTo(PRESET_WORLD_ID);
    }

    // ==================== 封面语义（world-card-gallery tasks 10.1） ====================

    @Test
    void 首次物化cover落随包默认图() {
        when(worldRepo.findById(anyString())).thenReturn(null);
        when(coverStorage.copyClasspathDefault(TENANT_ID, "adventure")).thenReturn("bundled.png");

        service.materialize("adventure");

        ArgumentCaptor<WorldSetting> captor = ArgumentCaptor.forClass(WorldSetting.class);
        verify(worldRepo).save(captor.capture());
        assertThat(captor.getValue().getCover()).isEqualTo("bundled.png");
    }

    @Test
    void 随包封面资产缺失时cover降级null不阻断物化() {
        when(worldRepo.findById(anyString())).thenReturn(null);
        when(coverStorage.copyClasspathDefault(TENANT_ID, "adventure")).thenReturn(null);

        PresetWorldService.PresetWorld result = service.materialize("adventure");

        assertThat(result.worldId()).isEqualTo(PRESET_WORLD_ID);
        verify(worldRepo).save(any(WorldSetting.class));
    }

    @Test
    void 二次物化不触碰cover列用户上传封面活过重写() {
        // 用户上传过封面（cover 列 = uploaded.png）后 md 变更触发重物化：
        // refresh 分支不得调用 copyClasspathDefault（否则默认图覆盖上传图），
        // 且 update() 的 SQL 刻意不含 cover 列（D7，行内注释钉死），上传封面天然保留。
        WorldSetting existing = new WorldSetting();
        existing.setCover("uploaded.png");
        when(worldRepo.findById(PRESET_WORLD_ID)).thenReturn(existing);

        service.materialize("adventure");

        verify(worldRepo).update(any(WorldSetting.class));
        verify(coverStorage, never()).copyClasspathDefault(anyString(), anyString());
        verify(worldRepo, never()).updateCover(anyString(), anyString());
    }

    // ==================== 白名单 ====================

    @Test
    void 未知预设键在拼路径前即被拒() {
        assertThatThrownBy(() -> service.materialize("../gm-system-prompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的预设键");
        assertThatThrownBy(() -> service.materialize(null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(worldRepo);
        verifyNoInteractions(starRepo);
        verifyNoInteractions(figureRepo);
    }

    // ==================== 命定人物解析（preset-destined-figures） ====================

    /** 自造源文：半角括号子标题、全角括号子标题、坏标题、缺冒号行、未知键、空值别名 */
    private static final String FIGURE_SECTION = """
            ### 长尾景虎(kagetora)
            - faction: saint
            - constellation: 处女座
            - starRank: Gold
            - eraNote: 弘治二年二十六岁
            - identity: 越后之龙
            - personality: 冷峻自持
            - background: 幼年失怙、兄长乱政。
            - aliases: 景虎，越后之龙
            说明行不是键值行，忽略
            - 缺冒号的坏行 跳过

            ### 坏标题无括号
            - faction: saint

            ### 织田信胜（nobukatsu）
            - eraNote: 二十岁
            - unknownKey: 应告警跳过
            - aliases:
            """;

    @Test
    void 人物解析两块有效且坏行跳过不阻断() {
        List<DestinedFigure> figures =
                service.parseDestinedFigures(FIGURE_SECTION, PRESET_WORLD_ID);

        // 坏标题块（无括号）整体丢弃，块内键值行不外溢到前一档案
        assertThat(figures).hasSize(2);
        DestinedFigure kagetora = figures.get(0);
        assertThat(kagetora.getDisplayName()).isEqualTo("长尾景虎");
        // 半角括号子标题同样接受
        assertThat(kagetora.getFigureKey()).isEqualTo("kagetora");
        assertThat(kagetora.getFaction()).isEqualTo("saint");
        assertThat(kagetora.getStarRank()).isEqualTo("Gold");
        assertThat(kagetora.getEraNote()).isEqualTo("弘治二年二十六岁");
        assertThat(kagetora.getBackground()).isEqualTo("幼年失怙、兄长乱政。");
        // 全角逗号别名规范化为半角
        assertThat(kagetora.getAliases()).isEqualTo("景虎,越后之龙");
        assertThat(kagetora.getWorldId()).isEqualTo(PRESET_WORLD_ID);
        assertThat(kagetora.getSortOrder()).isEqualTo(0);

        // 信胜块：全角括号子标题；星命三字段整行缺失 → null（自由推演）；空值别名 → null
        DestinedFigure nobukatsu = figures.get(1);
        assertThat(nobukatsu.getDisplayName()).isEqualTo("织田信胜");
        assertThat(nobukatsu.getFigureKey()).isEqualTo("nobukatsu");
        assertThat(nobukatsu.getFaction()).isNull();
        assertThat(nobukatsu.getConstellation()).isNull();
        assertThat(nobukatsu.getStarRank()).isNull();
        assertThat(nobukatsu.getAliases()).isNull();
    }

    @Test
    void 人物小节缺失或空白解析为空列表() {
        assertThat(service.parseDestinedFigures(null, PRESET_WORLD_ID)).isEmpty();
        assertThat(service.parseDestinedFigures("   ", PRESET_WORLD_ID)).isEmpty();
    }

    // ==================== 冥星武将录解析（star-lorebook） ====================

    private static final String STAR_SECTION = """
            - 天魁星 - 织田信长 | 天罡之首，魔星中最早苏醒的一颗。 | 信长，第六天魔王
            - 天兽星 - 前田庆次 | 不列天罡之籍的传说之星。 | 庆次，庆次郎 | npc
            说明行不是列表项，忽略
            - 缺分隔的坏行 | 应被跳过
            * 天寿星 - 池田恒兴 | 历战而寿。
            """;

    @Test
    void 名录解析三行有效且npc标记落rollable() {
        List<StarIncarnation> incarnations =
                service.parseStarIncarnations(STAR_SECTION, PRESET_WORLD_ID);

        assertThat(incarnations).hasSize(3);
        // 排序按出现顺序：0=天魁星 1=天兽星 2=天寿星
        assertThat(incarnations.get(0).getSortOrder()).isEqualTo(0);
        assertThat(incarnations.get(0).getStarName()).isEqualTo("天魁星");
        assertThat(incarnations.get(0).getWarlordName()).isEqualTo("织田信长");
        assertThat(incarnations.get(0).getLore()).isEqualTo("天罡之首，魔星中最早苏醒的一颗。");
        // 全角逗号别名规范化为半角
        assertThat(incarnations.get(0).getAliases()).isEqualTo("信长,第六天魔王");
        assertThat(incarnations.get(0).getRollable()).isTrue();
        assertThat(incarnations.get(0).getWorldId()).isEqualTo(PRESET_WORLD_ID);

        assertThat(incarnations.get(1).getStarName()).isEqualTo("天兽星");
        assertThat(incarnations.get(1).getRollable()).isFalse();
        assertThat(incarnations.get(1).getAliases()).isEqualTo("庆次,庆次郎");

        // 星号列表项 + 无别名段：aliases 为空串，rollable 默认 true
        assertThat(incarnations.get(2).getStarName()).isEqualTo("天寿星");
        assertThat(incarnations.get(2).getAliases()).isEmpty();
        assertThat(incarnations.get(2).getRollable()).isTrue();
    }

    @Test
    void 名录小节缺失或空白解析为空列表() {
        assertThat(service.parseStarIncarnations(null, PRESET_WORLD_ID)).isEmpty();
        assertThat(service.parseStarIncarnations("   ", PRESET_WORLD_ID)).isEmpty();
        assertThat(service.parseStarIncarnations("只有一段说明文字\n没有列表项", PRESET_WORLD_ID)).isEmpty();
    }
    @Test
    void 物化时名录缺失小节清空既有名录不阻断() {
        // 真源 preset-adventure.md 现含冥星武将录小节，这里只钉“小节缺失→空列表全量重写”的
        // 降级语义（物化照常完成、返回同一 worldId）
        List<StarIncarnation> empty = service.parseStarIncarnations(null, PRESET_WORLD_ID);
        assertThat(empty).isEmpty();

        PresetWorldService.PresetWorld result = service.materialize("adventure");
        assertThat(result.worldId()).isEqualTo(PRESET_WORLD_ID);
        // 真实源文名录非空，rewriteAll 至少被调用一次（带 14 条）
        verify(starRepo).rewriteAll(eq(PRESET_WORLD_ID), anyList());
    }

    // ==================== 分类落 NULL（add-star-lorebook-category D2 / tasks 6.2） ====================

    @Test
    void 物化条目category全为NULL() {
        when(worldRepo.findById(anyString())).thenReturn(null);

        service.materialize("adventure");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StarIncarnation>> starCaptor =
                (ArgumentCaptor<List<StarIncarnation>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(starRepo).rewriteAll(eq(PRESET_WORLD_ID), starCaptor.capture());
        // md 真源「## 冥星武将录」小节不带分类语法（design D2）：物化产物 category 全 NULL；
        // 重物化 rewriteAll 仅重写 set_id NULL 模板行，set 副本行分类不受影响（既有隔离语义零改动）
        assertThat(starCaptor.getValue())
                .hasSize(14)
                .allSatisfy(inc -> assertThat(inc.getCategory()).isNull());
    }
}
