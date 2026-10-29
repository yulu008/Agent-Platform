package com.luyu.agent.rpg.eval.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 静态层装配器单测（content-eval tasks 3.3）：三源字段映射、指称词表宽容拆分、
 * 单源异常独立降级。
 */
class StaticEvalCaseAssemblerTest {

    private static final String WORLD_ID = "preset-adventure:t1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RpgDestinedFigureRepository figureRepo;
    private RpgCharacterCardRepository cardRepo;
    private RpgStarIncarnationRepository starRepo;
    private StaticEvalCaseAssembler assembler;

    @BeforeEach
    void setUp() {
        figureRepo = mock(RpgDestinedFigureRepository.class);
        cardRepo = mock(RpgCharacterCardRepository.class);
        starRepo = mock(RpgStarIncarnationRepository.class);
        assembler = new StaticEvalCaseAssembler(figureRepo, cardRepo, starRepo);
        when(figureRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(cardRepo.findByWorldIdAndType(WORLD_ID, "npc")).thenReturn(List.of());
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
    }

    @Test
    void 命定档案源_字段映射与设定包结构() throws Exception {
        DestinedFigure f = new DestinedFigure();
        f.setId("df-1");
        f.setFigureKey("nobunaga");
        f.setDisplayName("织田信长");
        f.setFaction("织田家");
        f.setConstellation("天魔星");
        f.setStarRank("上等星");
        f.setEraNote("战国");
        f.setIdentity("尾张出身的战国大名");
        f.setPersonality("多变而锐利");
        f.setBackground("以桶狭间一战成名");
        f.setAliases("第六天魔王,尾张的大傻瓜");
        when(figureRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(f));

        List<EvalCase> cases = assembler.assemble(WORLD_ID);

        assertThat(cases).hasSize(1);
        EvalCase c = cases.get(0);
        assertThat(c.layer()).isEqualTo("static");
        assertThat(c.sourceType()).isEqualTo(EvalCase.SOURCE_DESTINED);
        assertThat(c.subject()).isEqualTo("nobunaga");
        assertThat(c.subjectLabel()).isEqualTo("织田信长");
        // 指称词表：displayName 头位 + aliases 拆分（英文逗号亦容）
        assertThat(c.aliases()).containsExactly("织田信长", "第六天魔王", "尾张的大傻瓜");
        // 静态层动态字段一律 null
        assertThat(c.earlyWindow()).isNull();
        assertThat(c.lateWindow()).isNull();
        assertThat(c.npcStatesJson()).isNull();
        assertThat(c.playerStatesJson()).isNull();
        assertThat(c.targetFingerprint()).isNull();

        JsonNode pack = JSON.readTree(c.settingPackJson());
        assertThat(pack.get("identity").asText()).isEqualTo("尾张出身的战国大名");
        assertThat(pack.get("personality").asText()).isEqualTo("多变而锐利");
        assertThat(pack.get("background").asText()).isEqualTo("以桶狭间一战成名");
        JsonNode structured = JSON.readTree(pack.get("structuredJson").asText());
        assertThat(structured.get("faction").asText()).isEqualTo("织田家");
        assertThat(structured.get("constellation").asText()).isEqualTo("天魔星");
        assertThat(structured.get("starRank").asText()).isEqualTo("上等星");
        assertThat(structured.get("eraNote").asText()).isEqualTo("战国");

        JsonNode unit = JSON.readTree(c.unitJson());
        assertThat(unit.get("source").asText()).isEqualTo("destined");
        assertThat(unit.get("subject").asText()).isEqualTo("nobunaga");
        assertThat(unit.get("worldId").asText()).isEqualTo(WORLD_ID);
    }

    @Test
    void NPC卡源_字段映射与npc类型过滤() throws Exception {
        CharacterCard card = new CharacterCard();
        card.setId("cc-1");
        card.setName("阿茜");
        card.setIdentity("茶屋的看板娘");
        card.setPersonality("爽朗");
        card.setBackground("商人家庭出身");
        card.setMotivation("攒钱赎回妹妹");
        card.setSpeechStyle("江户口语，爱用比喻");
        card.setKnowledge("知晓商路行情");
        when(cardRepo.findByWorldIdAndType(WORLD_ID, "npc")).thenReturn(List.of(card));

        List<EvalCase> cases = assembler.assemble(WORLD_ID);

        // player 卡不参评由查询层 type=npc 保证（被评的是 NPC 一致性）
        verify(cardRepo).findByWorldIdAndType(WORLD_ID, "npc");
        assertThat(cases).hasSize(1);
        EvalCase c = cases.get(0);
        assertThat(c.layer()).isEqualTo("static");
        assertThat(c.sourceType()).isEqualTo(EvalCase.SOURCE_NPC_CARD);
        assertThat(c.subject()).isEqualTo("cc-1");
        assertThat(c.subjectLabel()).isEqualTo("阿茜");
        assertThat(c.aliases()).containsExactly("阿茜");

        JsonNode structured = JSON.readTree(JSON.readTree(c.settingPackJson())
                .get("structuredJson").asText());
        assertThat(structured.get("motivation").asText()).isEqualTo("攒钱赎回妹妹");
        assertThat(structured.get("speechStyle").asText()).isEqualTo("江户口语，爱用比喻");
        assertThat(structured.get("knowledge").asText()).isEqualTo("知晓商路行情");
    }

    @Test
    void 冥星名录源_叙事正文与指称词表() throws Exception {
        StarIncarnation s = new StarIncarnation();
        s.setId("si-1");
        s.setStarName("天魁星");
        s.setWarlordName("前田庆次");
        s.setLore("倾奇者，乱世中放浪的武士");
        s.setAliases("庆次，天下第一的倾奇者");
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(s));

        List<EvalCase> cases = assembler.assemble(WORLD_ID);

        assertThat(cases).hasSize(1);
        EvalCase c = cases.get(0);
        assertThat(c.sourceType()).isEqualTo(EvalCase.SOURCE_STAR_LORE);
        assertThat(c.subject()).isEqualTo("si-1");
        assertThat(c.subjectLabel()).isEqualTo("前田庆次");
        assertThat(c.aliases()).containsExactly("前田庆次", "庆次", "天下第一的倾奇者");

        JsonNode pack = JSON.readTree(c.settingPackJson());
        // starLore：身份三要素融于叙事正文，仅 lore + structured.starName
        assertThat(pack.get("lore").asText()).isEqualTo("倾奇者，乱世中放浪的武士");
        //（record 序列化 null 字段输出 JSON null，path().isNull() 覆盖 null/缺失两形态）
        assertThat(pack.path("identity").isNull()).isTrue();
        assertThat(pack.path("personality").isNull()).isTrue();
        assertThat(pack.path("background").isNull()).isTrue();
        JsonNode structured = JSON.readTree(pack.get("structuredJson").asText());
        assertThat(structured.get("starName").asText()).isEqualTo("天魁星");
    }

    @Test
    void 指称词表_中英文分隔混用_去重保序() {
        // preset md 用中文逗号，工坊手作卡历史数据可能混用——均需兼容
        assertThat(StaticEvalCaseAssembler.aliasList("主名", "别名甲，别名乙;别名甲， 主名 "))
                .containsExactly("主名", "别名甲", "别名乙");
        assertThat(StaticEvalCaseAssembler.aliasList(null, " "))
                .isEmpty();
        assertThat(StaticEvalCaseAssembler.aliasList(" 独名 ", null))
                .containsExactly("独名");
    }

    @Test
    void 单源异常_跳过该源不中断其余源() {
        DestinedFigure f = new DestinedFigure();
        f.setId("df-2");
        f.setFigureKey("matsu");
        f.setDisplayName("松");
        when(figureRepo.findByWorldId(anyString()))
                .thenThrow(new RuntimeException("rpg_destined_figure 读取失败"));
        CharacterCard card = new CharacterCard();
        card.setId("cc-2");
        card.setName("竹");
        when(cardRepo.findByWorldIdAndType(WORLD_ID, "npc")).thenReturn(List.of(card));
        StarIncarnation s = new StarIncarnation();
        s.setId("si-2");
        s.setWarlordName("梅");
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(s));

        List<EvalCase> cases = assembler.assemble(WORLD_ID);

        // 三源独立降级：命定源挂掉，NPC 卡与冥星名录正常返回
        assertThat(cases).extracting(EvalCase::sourceType)
                .containsExactly(EvalCase.SOURCE_NPC_CARD, EvalCase.SOURCE_STAR_LORE);
    }
}
