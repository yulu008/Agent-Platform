package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.OriginResult;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link WorkshopService#buildPlayerStatesJson(OriginResult)} 小宇宙注入测试（rpg-cosmo-sense tasks 2.2）。
 * <p>
 * 钉住两件事：星命开局（cosmoIntensity 非空）playerStates 含数值 cosmo 键；
 * cosmoIntensity 缺省（如非星命路径不产出初值）时不写 cosmo 键，回合结算与展示据此跳过（零影响）。
 * buildPlayerStatesJson 为纯函数，构造 WorkshopService 只需 mock 依赖。
 */
class WorkshopServiceCosmoTest {

    private WorkshopService newService() {
        return new WorkshopService(
                mock(RpgWorldSettingRepository.class),
                mock(RpgCharacterCardRepository.class),
                mock(RpgLocationRepository.class),
                mock(RpgRelationshipRepository.class),
                mock(RpgTriggerRepository.class),
                mock(RpgGameStateRepository.class),
                mock(RpgTriggerRuntimeRepository.class),
                mock(RpgStarIncarnationRepository.class),
                mock(RpgStarIncarnationRuntimeRepository.class),
                mock(WorkshopLlmGenerator.class),
                mock(CharacterOriginService.class),
                mock(WorldCoverStorageService.class),
                mock(PlatformTransactionManager.class),
                mock(RpgWorldbookEntryRepository.class),
                mock(LorebookSetService.class),
                mock(LoreArchiveService.class),
                mock(SessionService.class), mock(SessionRepository.class));
    }

    private OriginResult origin(Integer cosmo) {
        OriginResult o = new OriginResult();
        o.setConstellation("金牛座");
        o.setStarRank("Gold");
        o.setFaction("saint");
        o.setZodiacTraits("沉稳坚韧");
        o.setStarBonus(Map.of("endurance", 15));
        o.setCosmoIntensity(cosmo);
        return o;
    }

    @Test
    void 星命开局注入数值cosmo键() {
        String json = newService().buildPlayerStatesJson(origin(339));

        assertThat(json)
                .contains("\"cosmo\":339")
                .contains("\"constellation\":\"金牛座\"")
                .contains("\"star_rank\":\"Gold\"");
    }

    @Test
    void cosmoIntensity缺省时不写cosmo键() {
        String json = newService().buildPlayerStatesJson(origin(null));

        assertThat(json)
                .doesNotContain("cosmo")
                .contains("\"constellation\":\"金牛座\"");
    }
}
