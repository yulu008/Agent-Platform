package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.Relationship;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopService#localizeNames(String)} 单元测试（一键中文化改名）。
 * <p>
 * 钉住四件容易回归的事：
 * <ol>
 *   <li><b>原子性</b>：LLM 映射未通过校验矩阵时四张表零写入 —— 校验在事务之前，
 *       任何写入都不该发生；</li>
 *   <li><b>空操作短路</b>：无含拉丁字母的名字（卡名与存档 npcStates 无卡 key）时不调 LLM（省一次网络往返）；</li>
 *   <li><b>引用重写方向</b>：名字列写新汉字名，而 npcStates key / trigger.npcId /
 *       relationship 两端一律迁移到<b>角色卡 ID</b>（不是新名字）—— 这是「改名只动 name 一列」
 *       永久安全的前提；已是卡 ID 的值不动；</li>
 *   <li><b>无卡临时 NPC</b>：存档 npcStates 中既非卡 ID 也非卡名、含拉丁字母的拼音 key
 *       （GM 对无卡 NPC 的常见写法）迁移到新汉字名而非卡 ID，且不动卡表。</li>
 * </ol>
 * 事务用 {@code TransactionTemplate} 包裹，测试注入 mock 的 {@link PlatformTransactionManager}：
 * {@code getTransaction} 返回 null 时模板仍会执行回调并调用 commit，等价于「直接执行」。
 */
class WorkshopServiceLocalizeNamesTest {

    private static final String WORLD_ID = "world-1";

    private RpgCharacterCardRepository charRepo;
    private RpgRelationshipRepository relRepo;
    private RpgTriggerRepository triggerRepo;
    private RpgGameStateRepository stateRepo;
    private WorkshopLlmGenerator llmGenerator;
    private WorkshopService service;

    @BeforeEach
    void setUp() {
        // WorkshopService 中文化改名的笔记本跟随（RpgSavePaths.saveDir）按请求租户解析，
        // 需先建立租户上下文（同 RpgGameStateSnapshotRepositoryTest 模式）
        TenantContext.set("u-test", "t-test");
        RpgWorldSettingRepository worldRepo = mock(RpgWorldSettingRepository.class);
        charRepo = mock(RpgCharacterCardRepository.class);
        RpgLocationRepository locationRepo = mock(RpgLocationRepository.class);
        relRepo = mock(RpgRelationshipRepository.class);
        triggerRepo = mock(RpgTriggerRepository.class);
        stateRepo = mock(RpgGameStateRepository.class);
        RpgTriggerRuntimeRepository runtimeRepo = mock(RpgTriggerRuntimeRepository.class);
        llmGenerator = mock(WorkshopLlmGenerator.class);

        service = new WorkshopService(worldRepo, charRepo, locationRepo, relRepo, triggerRepo,
                stateRepo, runtimeRepo, llmGenerator, mock(PlatformTransactionManager.class));

        WorldSetting world = new WorldSetting();
        world.setId(WORLD_ID);
        world.setName("江户怪谈");
        world.setEra("江户时代");
        world.setAtmosphere("阴雨连绵的驿站小镇");
        when(worldRepo.findById(WORLD_ID)).thenReturn(world);
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(stateRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(triggerRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(relRepo.findAll()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static CharacterCard card(String id, String name, String type) {
        CharacterCard card = new CharacterCard();
        card.setId(id);
        card.setWorldId(WORLD_ID);
        card.setName(name);
        card.setType(type);
        return card;
    }

    @Test
    void 无拉丁字母名字时不调LLM且返回空摘要() {
        when(charRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(card("npc-1", "大神东", "npc"), card("pc-1", "旅行者", "player")));

        WorkshopService.NameLocalizeSummary summary = service.localizeNames(WORLD_ID);

        assertThat(summary.renames()).isEmpty();
        assertThat(summary.gameStateCount()).isZero();
        assertThat(summary.notebookFileCount()).isZero();
        verify(llmGenerator, never()).renameToChinese(anyList(), anyList(), any());
        verify(charRepo, never()).updateName(anyString(), anyString());
    }

    @Test
    void 存档npcStates只有卡ID与汉字key时不调LLM() {
        when(charRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(card("npc-1", "大神东", "npc")));
        GameState gs = new GameState();
        gs.setId("gs-1");
        gs.setWorldId(WORLD_ID);
        // 卡 ID key 与汉字 key 均不当作无卡拼音名
        gs.setNpcStates("{\"npc-1\":{\"status\":\"警戒\"},\"货郎\":{\"status\":\"idle\"}}");
        when(stateRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(gs));

        WorkshopService.NameLocalizeSummary summary = service.localizeNames(WORLD_ID);

        assertThat(summary.renames()).isEmpty();
        verify(llmGenerator, never()).renameToChinese(anyList(), anyList(), any());
        verify(stateRepo, never()).updateNpcStates(anyString(), anyString());
    }

    @Test
    void 映射缺项时整体失败且零写入() {
        when(charRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(card("npc-1", "okami azuma", "npc"), card("npc-2", "yuki", "npc")));
        // 只覆盖一个旧名
        when(llmGenerator.renameToChinese(anyList(), anyList(), any())).thenReturn(Map.of("okami azuma", "大神东"));

        assertThatThrownBy(() -> service.localizeNames(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("yuki");

        assertNothingWritten();
    }

    @Test
    void 新名不含汉字或仍含拉丁字母时整体失败() {
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(card("npc-1", "okami azuma", "npc")));

        when(llmGenerator.renameToChinese(anyList(), anyList(), any())).thenReturn(Map.of("okami azuma", "Okami"));
        assertThatThrownBy(() -> service.localizeNames(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("汉字");

        when(llmGenerator.renameToChinese(anyList(), anyList(), any())).thenReturn(Map.of("okami azuma", "大神East"));
        assertThatThrownBy(() -> service.localizeNames(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拉丁字母");

        assertNothingWritten();
    }

    @Test
    void 新名重复或与现存名冲突时整体失败() {
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(
                card("npc-1", "okami azuma", "npc"),
                card("npc-2", "yuki", "npc"),
                card("npc-3", "老者", "npc")));

        // 两个旧名映射到同一个新名
        when(llmGenerator.renameToChinese(anyList(), anyList(), any()))
                .thenReturn(Map.of("okami azuma", "大神东", "yuki", "大神东"));
        assertThatThrownBy(() -> service.localizeNames(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");

        // 新名撞上未参与改名的现存汉字名
        when(llmGenerator.renameToChinese(anyList(), anyList(), any()))
                .thenReturn(Map.of("okami azuma", "老者", "yuki", "雪"));
        assertThatThrownBy(() -> service.localizeNames(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突");

        assertNothingWritten();
    }

    @Test
    void 改名成功后名字写新名而三处引用改写为卡ID() {
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(
                card("pc-1", "旅行者", "player"),
                card("npc-1", "okami azuma", "npc")));
        when(llmGenerator.renameToChinese(anyList(), anyList(), any())).thenReturn(Map.of("okami azuma", "大神东"));

        GameState gs = new GameState();
        gs.setId("gs-1");
        gs.setWorldId(WORLD_ID);
        gs.setNpcStates("{\"okami azuma\":{\"status\":\"警戒\"},\"pc-1\":{\"status\":\"正常\"}}");
        when(stateRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(gs));

        Trigger trigger = new Trigger();
        trigger.setId("t-1");
        trigger.setWorldId(WORLD_ID);
        trigger.setNpcId("okami azuma");
        Trigger alreadyMigrated = new Trigger();
        alreadyMigrated.setId("t-2");
        alreadyMigrated.setNpcId("pc-1");
        when(triggerRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(trigger, alreadyMigrated));

        Relationship rel = new Relationship();
        rel.setId("r-1");
        rel.setCharAId("okami azuma");
        rel.setCharBId("pc-1");
        when(relRepo.findAll()).thenReturn(List.of(rel));

        WorkshopService.NameLocalizeSummary summary = service.localizeNames(WORLD_ID);

        // 摘要：改名清单 + 各表计数
        assertThat(summary.renames()).containsExactly(
                new WorkshopService.RenamedCharacter("npc-1", "okami azuma", "大神东"));
        assertThat(summary.gameStateCount()).isEqualTo(1);
        assertThat(summary.npcStateKeyCount()).isEqualTo(1);
        assertThat(summary.triggerCount()).isEqualTo(1);
        assertThat(summary.relationshipCount()).isEqualTo(1);

        // 名字列写新汉字名，未参与改名的卡不动
        verify(charRepo).updateName("npc-1", "大神东");
        verify(charRepo, never()).updateName(eq("pc-1"), anyString());

        // npcStates：旧名 key → 卡 ID，value 原样；已是卡 ID 的 key 保留
        ArgumentCaptor<String> npcStates = ArgumentCaptor.forClass(String.class);
        verify(stateRepo).updateNpcStates(eq("gs-1"), npcStates.capture());
        assertThat(npcStates.getValue())
                .contains("\"npc-1\"")
                .contains("警戒")
                .contains("\"pc-1\"")
                .doesNotContain("okami azuma");

        // 触发器：命中旧名的改写为卡 ID，已是卡 ID 的不动
        verify(triggerRepo).updateNpcId("t-1", "npc-1");
        verify(triggerRepo, never()).updateNpcId(eq("t-2"), anyString());

        // 关系：仅命中旧名的一端被替换，另一端原样传回
        verify(relRepo).updateCharRefs("r-1", "npc-1", "pc-1");
    }

    @Test
    void 无卡临时NPC拼音key被改为汉字名且不动卡表() {
        // 世界里只有汉字名角色卡，但存档 npcStates 里混着 GM 写的无卡拼音 key
        when(charRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(card("pc-1", "旅行者", "player")));
        GameState gs = new GameState();
        gs.setId("gs-1");
        gs.setWorldId(WORLD_ID);
        gs.setNpcStates("{\"xuanhai\":{\"status\":\"ally_preparing\",\"location\":\"鬼灯屋酒馆\"},"
                + "\"pc-1\":{\"status\":\"正常\"}}");
        when(stateRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(gs));
        when(llmGenerator.renameToChinese(anyList(), anyList(), any()))
                .thenReturn(Map.of("xuanhai", "玄海"));

        WorkshopService.NameLocalizeSummary summary = service.localizeNames(WORLD_ID);

        // 摘要：无卡 NPC 的 cardId 为 null
        assertThat(summary.renames()).containsExactly(
                new WorkshopService.RenamedCharacter(null, "xuanhai", "玄海"));
        assertThat(summary.gameStateCount()).isEqualTo(1);
        assertThat(summary.npcStateKeyCount()).isEqualTo(1);

        // 不写卡表（无卡可改）
        verify(charRepo, never()).updateName(anyString(), anyString());

        // npcStates：拼音 key → 新汉字名（不是卡 ID），卡 ID key 原样保留
        ArgumentCaptor<String> npcStates = ArgumentCaptor.forClass(String.class);
        verify(stateRepo).updateNpcStates(eq("gs-1"), npcStates.capture());
        assertThat(npcStates.getValue())
                .contains("\"玄海\"")
                .contains("ally_preparing")
                .contains("\"pc-1\"")
                .doesNotContain("xuanhai");
    }

    @Test
    void 无卡拼音key与卡名混合改名时引用各归其位() {
        when(charRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(
                card("npc-1", "okami azuma", "npc"),
                card("pc-1", "旅行者", "player")));
        GameState gs = new GameState();
        gs.setId("gs-1");
        gs.setWorldId(WORLD_ID);
        gs.setNpcStates("{\"okami azuma\":{\"status\":\"警戒\"},\"atao\":{\"status\":\"sleeping\"},\"pc-1\":{\"status\":\"正常\"}}");
        when(stateRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(gs));
        when(llmGenerator.renameToChinese(anyList(), anyList(), any()))
                .thenReturn(Map.of("okami azuma", "大神东", "atao", "阿桃"));

        WorkshopService.NameLocalizeSummary summary = service.localizeNames(WORLD_ID);

        // 卡旧名 → 卡 ID；无卡拼音名 → 新汉字名，两类都在改名清单里
        assertThat(summary.renames()).containsExactly(
                new WorkshopService.RenamedCharacter("npc-1", "okami azuma", "大神东"),
                new WorkshopService.RenamedCharacter(null, "atao", "阿桃"));
        verify(charRepo).updateName("npc-1", "大神东");

        ArgumentCaptor<String> npcStates = ArgumentCaptor.forClass(String.class);
        verify(stateRepo).updateNpcStates(eq("gs-1"), npcStates.capture());
        assertThat(npcStates.getValue())
                .contains("\"npc-1\"")
                .contains("\"阿桃\"")
                .contains("\"pc-1\"")
                .doesNotContain("okami azuma")
                .doesNotContain("atao");
    }

    @Test
    void 无卡拼音key映射缺项时整体失败零写入() {
        when(charRepo.findByWorldId(WORLD_ID))
                .thenReturn(List.of(card("pc-1", "旅行者", "player")));
        GameState gs = new GameState();
        gs.setId("gs-1");
        gs.setWorldId(WORLD_ID);
        gs.setNpcStates("{\"xuanhai\":{\"status\":\"ally_preparing\"}}");
        when(stateRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(gs));
        // LLM 返回空映射（未覆盖 xuanhai）
        when(llmGenerator.renameToChinese(anyList(), anyList(), any())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.localizeNames(WORLD_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("xuanhai");

        assertNothingWritten();
    }

    @Test
    void 世界不存在时抛参数异常() {
        assertThatThrownBy(() -> service.localizeNames("no-such-world"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(llmGenerator, never()).renameToChinese(anyList(), anyList(), any());
    }

    private void assertNothingWritten() {
        verify(charRepo, never()).updateName(anyString(), anyString());
        verify(stateRepo, never()).updateNpcStates(anyString(), anyString());
        verify(triggerRepo, never()).updateNpcId(anyString(), anyString());
        verify(relRepo, never()).updateCharRefs(anyString(), anyString(), anyString());
    }
}
