package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.OriginResult;
import com.luyu.agent.rpg.model.TechniqueState;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link WorkshopService#buildPlayerStatesJson(OriginResult)} 招式七字段落档测试
 * （rpg-technique-tree-data tasks 5.1）。
 * <p>
 * 钉住两件事：techniques[] 每项写全 name/description/source/tier/prerequisites/learned/proficiency
 * 七字段；techniques 为空或缺省时不写 techniques 键（非星命世界存档零影响）。
 * 落档 JSON 被 GM 读回渲染的闭环由 {@code GmContextAssemblerTechniqueTest} 覆盖（其样例即本方法的产物结构）。
 * buildPlayerStatesJson 为纯函数，构造 WorkshopService 只需 mock 依赖。
 */
class WorkshopServiceTechniquePersistTest {

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

    private OriginResult originWith(List<TechniqueState> techniques) {
        OriginResult o = new OriginResult();
        o.setConstellation("天马座");
        o.setStarRank("Bronze");
        o.setFaction("saint");
        o.setZodiacTraits("热情冲动");
        o.setStarBonus(Map.of("cosmo", 5));
        o.setTechniques(techniques);
        return o;
    }

    @Test
    void 招式七字段全部落档() {
        List<TechniqueState> techs = List.of(
                new TechniqueState("天马流星拳", "招牌拳", "经典系列", 1, List.of(), true, 200),
                new TechniqueState("天马彗星拳", "上位变体", "经典系列", 2, List.of("天马流星拳"), false, 0));

        String json = newService().buildPlayerStatesJson(originWith(techs));

        // 已学根节点：七字段齐全
        assertThat(json)
                .contains("\"name\":\"天马流星拳\"")
                .contains("\"description\":\"招牌拳\"")
                .contains("\"source\":\"经典系列\"")
                .contains("\"tier\":1")
                .contains("\"learned\":true")
                .contains("\"proficiency\":200");
        // 未学节点：前置数组 + learned=false + proficiency=0
        assertThat(json)
                .contains("\"prerequisites\":[\"天马流星拳\"]")
                .contains("\"tier\":2")
                .contains("\"learned\":false")
                .contains("\"proficiency\":0");
        // 根节点前置为空数组
        assertThat(json).contains("\"prerequisites\":[]");
    }

    @Test
    void 无招式时不写techniques键() {
        assertThat(newService().buildPlayerStatesJson(originWith(null)))
                .doesNotContain("techniques")
                .contains("\"constellation\":\"天马座\"");
        assertThat(newService().buildPlayerStatesJson(originWith(List.of())))
                .doesNotContain("techniques");
    }
}
