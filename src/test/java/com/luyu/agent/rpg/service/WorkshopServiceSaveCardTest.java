package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.SaveCard;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopService#listSaveCards()} 的单元测试。
 * <p>
 * 钉住卡片契约：字段富化（join 世界名/角色名）、引用数据缺失时占位符回退、
 * 输出顺序与 repository 的 updated_at 倒序一致、空列表。
 */
class WorkshopServiceSaveCardTest {

    private RpgWorldSettingRepository worldRepo;
    private RpgCharacterCardRepository charRepo;
    private RpgGameStateRepository stateRepo;
    private WorkshopService service;

    @BeforeEach
    void setUp() {
        worldRepo = mock(RpgWorldSettingRepository.class);
        charRepo = mock(RpgCharacterCardRepository.class);
        stateRepo = mock(RpgGameStateRepository.class);
        service = new WorkshopService(worldRepo, charRepo,
                mock(RpgLocationRepository.class), mock(RpgRelationshipRepository.class),
                mock(RpgTriggerRepository.class), stateRepo,
                mock(RpgTriggerRuntimeRepository.class), mock(WorkshopLlmGenerator.class),
                mock(PlatformTransactionManager.class));
    }

    private GameState gs(String id, String sessionId, String worldId, String playerCharId,
                         String location, Integer turnCount, LocalDateTime updatedAt) {
        GameState gs = new GameState();
        gs.setId(id);
        gs.setSessionId(sessionId);
        gs.setWorldId(worldId);
        gs.setPlayerCharId(playerCharId);
        gs.setCurrentLocation(location);
        gs.setTurnCount(turnCount);
        gs.setUpdatedAt(updatedAt);
        return gs;
    }

    @Test
    void 卡片字段被世界名与角色名富化() {
        WorldSetting world = new WorldSetting();
        world.setId("w1");
        world.setName("艾尔多兰");
        CharacterCard player = new CharacterCard();
        player.setId("c1");
        player.setName("亚瑟");
        when(worldRepo.findById("w1")).thenReturn(world);
        when(charRepo.findById("c1")).thenReturn(player);
        LocalDateTime time = LocalDateTime.of(2026, 9, 17, 10, 0);
        when(stateRepo.findAll()).thenReturn(List.of(
                gs("gs1", "s1", "w1", "c1", "盗贼森林", 12, time)));

        List<SaveCard> cards = service.listSaveCards();

        assertThat(cards).hasSize(1);
        SaveCard card = cards.get(0);
        assertThat(card.getGameStateId()).isEqualTo("gs1");
        assertThat(card.getSessionId()).isEqualTo("s1");
        assertThat(card.getWorldId()).isEqualTo("w1");
        assertThat(card.getWorldName()).isEqualTo("艾尔多兰");
        assertThat(card.getPlayerCharName()).isEqualTo("亚瑟");
        assertThat(card.getCurrentLocation()).isEqualTo("盗贼森林");
        assertThat(card.getTurnCount()).isEqualTo(12);
        assertThat(card.getLastPlayedAt()).isEqualTo(time);
    }

    @Test
    void 世界或角色被删时占位符回退且地点为空回退() {
        when(worldRepo.findById("w-gone")).thenReturn(null);
        when(charRepo.findById("c-gone")).thenReturn(null);
        when(stateRepo.findAll()).thenReturn(List.of(
                gs("gs2", "s2", "w-gone", "c-gone", "  ", 3, LocalDateTime.now())));

        List<SaveCard> cards = service.listSaveCards();

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getWorldName()).isEqualTo("-");
        assertThat(cards.get(0).getPlayerCharName()).isEqualTo("-");
        assertThat(cards.get(0).getCurrentLocation()).isEqualTo("-");
    }

    @Test
    void 输出顺序与findAll的updated_at倒序一致() {
        when(stateRepo.findAll()).thenReturn(List.of(
                gs("gs-new", "s1", "w1", "c1", "地点", 9, LocalDateTime.now()),
                gs("gs-old", "s2", "w1", "c1", "地点", 1, LocalDateTime.now().minusDays(3))));

        List<SaveCard> cards = service.listSaveCards();

        assertThat(cards).extracting(SaveCard::getGameStateId)
                .containsExactly("gs-new", "gs-old");
    }

    @Test
    void 无存档时返回空列表() {
        when(stateRepo.findAll()).thenReturn(List.of());

        assertThat(service.listSaveCards()).isEmpty();
    }
}
