package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.WorldSetting;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopService} 表单新建/编辑与条件级联软删除的单元测试
 * （workshop-soft-delete-cascade 8.1：删除语义按 D3/D4 重写）。
 * <p>
 * 钉住三件容易回归的事：
 * <ol>
 *   <li><b>类型运行时推导</b>：新建角色未传 type 时缺省 npc；编辑时表单不传 type
 *       必须保留库内现值（不能把已升级为 player 的角色降级）；startGame 把被选角色
 *       升级为 player，且角色不属于该世界时零写入；</li>
 *   <li><b>新建/编辑分流</b>：id 为空走 INSERT，id 命中已有记录走 UPDATE；</li>
 *   <li><b>条件级联软删除</b>：世界删除有存档 409、无存档单事务级联软删
 *       （通用世界书模板行与封面文件豁免）；角色删除仅存档玩家引用 409、
 *       否则级联软删两端关系与 npc 关联触发器——被关系/触发器引用
 *       不再构成拒绝理由（推翻 workshop-form-delete 阻塞式）。</li>
 * </ol>
 */
class WorkshopServiceFormDeleteTest {

    private static final String WORLD_ID = "world-1";
    private static final String CHAR_ID = "char-1";

    private RpgWorldSettingRepository worldRepo;
    private RpgCharacterCardRepository charRepo;
    private RpgLocationRepository locationRepo;
    private RpgRelationshipRepository relRepo;
    private RpgTriggerRepository triggerRepo;
    private RpgGameStateRepository stateRepo;
    private RpgStarIncarnationRepository starRepo;
    private RpgWorldbookEntryRepository worldbookRepo;
    private WorldCoverStorageService coverStorage;
    private LorebookSetService lorebookSetService;
    private WorkshopService service;

    @BeforeEach
    void setUp() {
        worldRepo = mock(RpgWorldSettingRepository.class);
        charRepo = mock(RpgCharacterCardRepository.class);
        locationRepo = mock(RpgLocationRepository.class);
        relRepo = mock(RpgRelationshipRepository.class);
        triggerRepo = mock(RpgTriggerRepository.class);
        stateRepo = mock(RpgGameStateRepository.class);
        starRepo = mock(RpgStarIncarnationRepository.class);
        worldbookRepo = mock(RpgWorldbookEntryRepository.class);
        RpgTriggerRuntimeRepository runtimeRepo = mock(RpgTriggerRuntimeRepository.class);
        WorkshopLlmGenerator llmGenerator = mock(WorkshopLlmGenerator.class);
        coverStorage = mock(WorldCoverStorageService.class);
        // 开局 fork 世界书副本（add-rpg-lorebook-sets D4）：startGame 依赖其非空返回，
        // inheritSetId/claimedStarName/figureKey（preset-destined-figures）缺省为 null
        // 故用 any() 匹配（anyString() 不匹配 null）
        lorebookSetService = mock(LorebookSetService.class);
        LorebookSet forked = new LorebookSet();
        forked.setId("set-1");
        forked.setWorldId(WORLD_ID);
        when(lorebookSetService.copyForNewGame(any(), any(), any(), any(), any())).thenReturn(forked);

        service = new WorkshopService(worldRepo, charRepo, locationRepo, relRepo, triggerRepo,
                stateRepo, runtimeRepo, starRepo,
                mock(RpgStarIncarnationRuntimeRepository.class), llmGenerator,
                mock(CharacterOriginService.class),
                coverStorage,
                mock(PlatformTransactionManager.class),
                worldbookRepo,
                lorebookSetService,
                mock(LoreArchiveService.class),
                mock(SessionService.class), mock(SessionRepository.class));
    }

    private CharacterCard card(String id, String worldId, String type) {
        CharacterCard c = new CharacterCard();
        c.setId(id);
        c.setWorldId(worldId);
        c.setName("旅行者");
        c.setType(type);
        return c;
    }

    // ==================== 角色类型运行时推导 ====================

    @Test
    void 新建角色未传类型时缺省为npc并走INSERT() {
        CharacterCard incoming = card(null, WORLD_ID, null);

        CharacterCard saved = service.saveCharacter(incoming);

        assertThat(saved.getType()).isEqualTo("npc");
        assertThat(saved.getId()).isNotBlank();
        verify(charRepo).save(incoming);
        verify(charRepo, never()).update(any());
    }

    @Test
    void 编辑已有角色未传类型时保留库内player且不降级() {
        CharacterCard existing = card(CHAR_ID, WORLD_ID, "player");
        existing.setKnowledge("[\"剑术\"]");
        when(charRepo.findById(CHAR_ID)).thenReturn(existing);
        // 表单不传 type，knowledge 固定为 '[]'
        CharacterCard incoming = card(CHAR_ID, WORLD_ID, null);
        incoming.setKnowledge("[]");

        CharacterCard saved = service.saveCharacter(incoming);

        assertThat(saved.getType()).isEqualTo("player");
        // knowledge 不在表单中编辑：传入 '[]' 时保留库内现值
        assertThat(saved.getKnowledge()).isEqualTo("[\"剑术\"]");
        verify(charRepo).update(incoming);
        verify(charRepo, never()).save(any());
    }

    @Test
    void startGame将被选角色升级为player() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, WORLD_ID, "npc"));

        GameState gs = service.startGame(WORLD_ID, CHAR_ID, "session-1");

        assertThat(gs.getPlayerCharId()).isEqualTo(CHAR_ID);
        verify(charRepo).updateType(CHAR_ID, "player");
        verify(stateRepo).save(gs);
    }

    @Test
    void startGame角色不属于该世界时抛异常且零写入() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, "other-world", "npc"));

        assertThatThrownBy(() -> service.startGame(WORLD_ID, CHAR_ID, "session-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该世界");

        verify(charRepo, never()).updateType(any(), any());
        verify(stateRepo, never()).save(any());
    }

    // ==================== 世界观新建/编辑分流 ====================

    @Test
    void 保存世界观已有id走UPDATE新id走INSERT() {
        WorldSetting existing = new WorldSetting();
        existing.setId(WORLD_ID);
        when(worldRepo.findById(WORLD_ID)).thenReturn(existing);

        WorldSetting edit = new WorldSetting();
        edit.setId(WORLD_ID);
        edit.setName("江户怪谈·改");
        service.saveWorld(edit);
        verify(worldRepo).update(edit);
        verify(worldRepo, never()).save(any());

        WorldSetting fresh = new WorldSetting();
        fresh.setName("钟表城");
        WorldSetting saved = service.saveWorld(fresh);
        assertThat(saved.getId()).isNotBlank();
        verify(worldRepo).save(fresh);
    }

    // ==================== 条件级联软删除角色（D4） ====================

    @Test
    void 无存档挂靠的角色级联软删两端关系与关联触发器() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, WORLD_ID, "npc"));
        when(stateRepo.countByPlayerCharId(CHAR_ID)).thenReturn(0);
        // 被关系/触发器引用不构成拒绝理由：一并级联软删

        service.deleteCharacter(CHAR_ID);

        verify(relRepo).softDeleteByCharId(CHAR_ID);
        verify(triggerRepo).softDeleteByNpcId(CHAR_ID);
        verify(charRepo).softDeleteById(CHAR_ID);
    }

    @Test
    void 作为玩家被存档引用的角色删除被拒绝() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, WORLD_ID, "player"));
        when(stateRepo.countByPlayerCharId(CHAR_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.deleteCharacter(CHAR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存档");

        verify(charRepo, never()).softDeleteById(any());
        verify(relRepo, never()).softDeleteByCharId(any());
        verify(triggerRepo, never()).softDeleteByNpcId(any());
    }

    @Test
    void 角色不存在时删除抛IllegalArgument() {
        when(charRepo.findById(CHAR_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteCharacter(CHAR_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(charRepo, never()).softDeleteById(any());
    }

    // ==================== 条件级联软删除世界观（D3） ====================

    @Test
    void 无存档世界级联软删全部子数据且通用世界书与封面豁免() {
        WorldSetting world = new WorldSetting();
        world.setId(WORLD_ID);
        world.setName("钟表城");
        world.setCover("abc.png");
        when(worldRepo.findById(WORLD_ID)).thenReturn(world);
        when(stateRepo.countByWorldId(WORLD_ID)).thenReturn(0);
        // 两个角色：级联经每个角色双向软删关系（关系表无 world_id 列）
        CharacterCard c1 = card("char-a", WORLD_ID, "npc");
        CharacterCard c2 = card("char-b", WORLD_ID, "npc");
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(c1, c2));

        service.deleteWorld(WORLD_ID);

        verify(relRepo).softDeleteByCharId("char-a");
        verify(relRepo).softDeleteByCharId("char-b");
        verify(charRepo).softDeleteByWorldId(WORLD_ID);
        verify(locationRepo).softDeleteByWorldId(WORLD_ID);
        verify(triggerRepo).softDeleteByWorldId(WORLD_ID);
        verify(starRepo).softDeleteTemplatesByWorldId(WORLD_ID);
        verify(worldRepo).softDeleteById(WORLD_ID);
        // 通用世界书模板行与封面文件豁免（design D3）：物理行与资产都不动
        verify(worldbookRepo, never()).softDeleteTemplateById(any());
        verify(coverStorage, never()).delete(any(), any());
    }

    @Test
    void 旗下有存档的世界删除被拒绝且零写入() {
        WorldSetting world = new WorldSetting();
        world.setId(WORLD_ID);
        world.setCover("abc.png");
        when(worldRepo.findById(WORLD_ID)).thenReturn(world);
        when(stateRepo.countByWorldId(WORLD_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.deleteWorld(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存档");

        verify(worldRepo, never()).softDeleteById(any());
        verify(charRepo, never()).softDeleteByWorldId(any());
        verify(coverStorage, never()).delete(any(), any());
    }

    @Test
    void 世界观不存在时删除抛IllegalArgument() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteWorld(WORLD_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(worldRepo, never()).softDeleteById(any());
    }
}
