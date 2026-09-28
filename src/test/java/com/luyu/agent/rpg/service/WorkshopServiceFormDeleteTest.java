package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopService} 表单新建/编辑与阻塞式删除的单元测试。
 * <p>
 * 钉住三件容易回归的事：
 * <ol>
 *   <li><b>类型运行时推导</b>：新建角色未传 type 时兜底 npc；编辑时表单不传 type
 *       必须保留库内现值（不能把已升级为 player 的角色降级）；startGame 把被选角色
 *       升级为 player，且角色不属于该世界时零写入；</li>
 *   <li><b>新建/编辑分流</b>：id 为空走 INSERT，id 命中已有记录走 UPDATE；</li>
 *   <li><b>阻塞式删除</b>：被关系/触发器/存档引用时拒绝且消息指明原因，无引用时删除成功。</li>
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
    private WorkshopService service;

    @BeforeEach
    void setUp() {
        worldRepo = mock(RpgWorldSettingRepository.class);
        charRepo = mock(RpgCharacterCardRepository.class);
        locationRepo = mock(RpgLocationRepository.class);
        relRepo = mock(RpgRelationshipRepository.class);
        triggerRepo = mock(RpgTriggerRepository.class);
        stateRepo = mock(RpgGameStateRepository.class);
        RpgTriggerRuntimeRepository runtimeRepo = mock(RpgTriggerRuntimeRepository.class);
        WorkshopLlmGenerator llmGenerator = mock(WorkshopLlmGenerator.class);

        service = new WorkshopService(worldRepo, charRepo, locationRepo, relRepo, triggerRepo,
                stateRepo, runtimeRepo, llmGenerator, mock(PlatformTransactionManager.class));
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
    void 新建角色未传类型时兜底为npc并走INSERT() {
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

    // ==================== 阻塞式删除角色 ====================

    @Test
    void 无引用角色删除成功() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, WORLD_ID, "npc"));
        when(relRepo.countByCharId(CHAR_ID)).thenReturn(0);
        when(triggerRepo.countByNpcId(CHAR_ID)).thenReturn(0);
        when(stateRepo.countByPlayerCharId(CHAR_ID)).thenReturn(0);

        service.deleteCharacter(CHAR_ID);

        verify(charRepo).deleteById(CHAR_ID);
    }

    @Test
    void 被关系引用的角色删除被拒绝() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, WORLD_ID, "npc"));
        when(relRepo.countByCharId(CHAR_ID)).thenReturn(2);

        assertThatThrownBy(() -> service.deleteCharacter(CHAR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("角色间关系");

        verify(charRepo, never()).deleteById(any());
    }

    @Test
    void 被触发器引用的角色删除被拒绝() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, WORLD_ID, "npc"));
        when(relRepo.countByCharId(CHAR_ID)).thenReturn(0);
        when(triggerRepo.countByNpcId(CHAR_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.deleteCharacter(CHAR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("触发器");

        verify(charRepo, never()).deleteById(any());
    }

    @Test
    void 作为玩家被存档引用的角色删除被拒绝() {
        when(charRepo.findById(CHAR_ID)).thenReturn(card(CHAR_ID, WORLD_ID, "player"));
        when(relRepo.countByCharId(CHAR_ID)).thenReturn(0);
        when(triggerRepo.countByNpcId(CHAR_ID)).thenReturn(0);
        when(stateRepo.countByPlayerCharId(CHAR_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.deleteCharacter(CHAR_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存档");

        verify(charRepo, never()).deleteById(any());
    }

    @Test
    void 角色不存在时删除抛IllegalArgument() {
        when(charRepo.findById(CHAR_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteCharacter(CHAR_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(charRepo, never()).deleteById(any());
    }

    // ==================== 阻塞式删除世界观 ====================

    @Test
    void 空世界观删除成功() {
        WorldSetting world = new WorldSetting();
        world.setId(WORLD_ID);
        when(worldRepo.findById(WORLD_ID)).thenReturn(world);
        when(charRepo.countByWorldId(WORLD_ID)).thenReturn(0);
        when(locationRepo.countByWorldId(WORLD_ID)).thenReturn(0);
        when(triggerRepo.countByWorldId(WORLD_ID)).thenReturn(0);
        when(stateRepo.countByWorldId(WORLD_ID)).thenReturn(0);

        service.deleteWorld(WORLD_ID);

        verify(worldRepo).deleteById(WORLD_ID);
    }

    @Test
    void 旗下有角色的世界观删除被拒绝() {
        WorldSetting world = new WorldSetting();
        world.setId(WORLD_ID);
        when(worldRepo.findById(WORLD_ID)).thenReturn(world);
        when(charRepo.countByWorldId(WORLD_ID)).thenReturn(3);

        assertThatThrownBy(() -> service.deleteWorld(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("角色");

        verify(worldRepo, never()).deleteById(any());
    }

    @Test
    void 旗下有存档的世界观删除被拒绝() {
        WorldSetting world = new WorldSetting();
        world.setId(WORLD_ID);
        when(worldRepo.findById(WORLD_ID)).thenReturn(world);
        when(charRepo.countByWorldId(WORLD_ID)).thenReturn(0);
        when(locationRepo.countByWorldId(WORLD_ID)).thenReturn(0);
        when(triggerRepo.countByWorldId(WORLD_ID)).thenReturn(0);
        when(stateRepo.countByWorldId(WORLD_ID)).thenReturn(1);

        assertThatThrownBy(() -> service.deleteWorld(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("存档");

        verify(worldRepo, never()).deleteById(any());
    }

    @Test
    void 世界观不存在时删除抛IllegalArgument() {
        when(worldRepo.findById(WORLD_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteWorld(WORLD_ID))
                .isInstanceOf(IllegalArgumentException.class);

        verify(worldRepo, never()).deleteById(any());
    }
}
