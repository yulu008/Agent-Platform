package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OpeningNarrationService} 命定人物模式测试（preset-destined-figures tasks 6.1）。
 * <p>
 * 钉住三件事：playerStates figure 键非空的开局走 LLM 个性化开场白（figure 三元组入参）；
 * LLM 异常/空产出降级模板插值路径（人物局不因 LLM 故障阻断开局）；
 * figure 缺省零 LLM 交互、模板路径行为与现行一致（spec「无名新人路径不变」）。
 */
class OpeningNarrationServiceFigureTest {

    private static final String TEMPLATE = "{{player_name}} 于 {{era}} 的 {{current_location}} 醒来。";
    private static final String RENDERED = "织田信长 于 弘治二年（西历 1556 年） 的 清洲城 醒来。";

    private RpgWorldSettingRepository worldRepo;
    private RpgCharacterCardRepository charRepo;
    private RpgGameStateRepository stateRepo;
    private WorkshopLlmGenerator llmGenerator;
    private OpeningNarrationService service;

    @BeforeEach
    void setUp() {
        worldRepo = mock(RpgWorldSettingRepository.class);
        charRepo = mock(RpgCharacterCardRepository.class);
        stateRepo = mock(RpgGameStateRepository.class);
        llmGenerator = mock(WorkshopLlmGenerator.class);
        service = new OpeningNarrationService(worldRepo, charRepo, stateRepo, llmGenerator);
    }

    private static GameState gameState(String playerStates) {
        GameState gs = new GameState();
        gs.setId("gs-1");
        gs.setWorldId("w-1");
        gs.setPlayerCharId("c-1");
        gs.setPlayerStates(playerStates);
        gs.setCurrentLocation("清洲城");
        gs.setTurnCount(0);
        return gs;
    }

    private static WorldSetting world() {
        WorldSetting w = new WorldSetting();
        w.setName("战国圣衣录");
        w.setEra("弘治二年（西历 1556 年）");
        w.setAtmosphere("下克上之风席卷列国");
        w.setSettingDesc("圣衣与冥衣并立的战国");
        w.setOpeningTemplate(TEMPLATE);
        return w;
    }

    private static CharacterCard player() {
        CharacterCard c = new CharacterCard();
        c.setName("织田信长");
        c.setIdentity("尾张当主");
        return c;
    }

    @Test
    void 人物模式走LLM个性化开场白() {
        when(stateRepo.findById("gs-1")).thenReturn(gameState("""
                {"figure_key":"nobunaga","figure_display_name":"织田信长",
                 "figure_era_note":"弘治二年二十二岁，新取清洲的尾张当主"}"""));
        when(worldRepo.findById("w-1")).thenReturn(world());
        when(charRepo.findById("c-1")).thenReturn(player());
        when(llmGenerator.generatePersonalizedOpening(any(), any(),
                eq("织田信长"), eq("弘治二年二十二岁，新取清洲的尾张当主")))
                .thenReturn("尾张，清洲城。信长睁眼。");

        String opening = service.generateOpening("gs-1");

        assertThat(opening).isEqualTo("尾张，清洲城。信长睁眼。");
        verify(llmGenerator).generatePersonalizedOpening(any(), any(),
                eq("织田信长"), eq("弘治二年二十二岁，新取清洲的尾张当主"));
    }

    @Test
    void 人物模式LLM异常降级模板插值() {
        when(stateRepo.findById("gs-1")).thenReturn(gameState("""
                {"figure_key":"nobunaga","figure_display_name":"织田信长",
                 "figure_era_note":"弘治二年二十二岁"}"""));
        when(worldRepo.findById("w-1")).thenReturn(world());
        when(charRepo.findById("c-1")).thenReturn(player());
        when(llmGenerator.generatePersonalizedOpening(any(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM 不可用"));

        String opening = service.generateOpening("gs-1");

        assertThat(opening).isEqualTo(RENDERED);
    }

    @Test
    void 人物模式LLM空产出降级模板插值() {
        when(stateRepo.findById("gs-1")).thenReturn(gameState("""
                {"figure_key":"kagetora","figure_display_name":"长尾景虎",
                 "figure_era_note":"弘治二年二十六岁"}"""));
        when(worldRepo.findById("w-1")).thenReturn(world());
        when(charRepo.findById("c-1")).thenReturn(player());
        when(llmGenerator.generatePersonalizedOpening(any(), any(), anyString(), anyString()))
                .thenReturn("   ");

        String opening = service.generateOpening("gs-1");

        assertThat(opening).isEqualTo(RENDERED);
    }

    @Test
    void figure缺省零LLM交互模板路径行为不变() {
        when(stateRepo.findById("gs-1"))
                .thenReturn(gameState("{\"constellation\":\"天魁星\",\"faction\":\"specter\"}"));
        when(worldRepo.findById("w-1")).thenReturn(world());
        when(charRepo.findById("c-1")).thenReturn(player());

        String opening = service.generateOpening("gs-1");

        assertThat(opening).isEqualTo(RENDERED);
        verifyNoInteractions(llmGenerator);
    }
}
