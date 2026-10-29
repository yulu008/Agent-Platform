package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.OriginResult;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopService#startGame(String, String, String, OriginResult)} 抢星判定单测
 * （star-lorebook tasks 3.1-3.3 / 8.2）。
 * <p>
 * 钉住四件事：
 * <ol>
 *   <li><b>主星命中 → claimed</b>：specter + constellation 命中名录可推演条目时，
 *       恰写一行 runtime，{@code claimed_by_player=true}、{@code activated_turn=0}
 *       （后者即 sticky 起点，本局恒亮）；</li>
 *   <li><b>非 specter 不进判定</b>：saint/godwarrior 阵营零写入；</li>
 *   <li><b>rollable=false 永不被抢</b>：NPC 专属星（天兽/天魔）即使 constellation 撞上
 *       也不写 claimed（双保险路径）；</li>
 *   <li><b>未命中/未物化零写入</b>：constellation 不在名录、名录为空、无星命开局
 *       （origin=null）三种情形均不写 runtime、不阻断开局。</li>
 * </ol>
 */
class WorkshopServiceStarClaimTest {

    private static final String WORLD_ID = "preset-adventure:t-test";
    private static final String CHAR_ID = "char-1";
    private static final String SET_ID = "set-1";

    private RpgStarIncarnationRepository starRepo;
    private RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private LorebookSetService lorebookSetService;
    private LoreArchiveService loreArchiveService;

    @BeforeEach
    void setUp() {
        starRepo = mock(RpgStarIncarnationRepository.class);
        starRuntimeRepo = mock(RpgStarIncarnationRuntimeRepository.class);
        loreArchiveService = mock(LoreArchiveService.class);
        // 开局同步 fork 出新局 set（add-rpg-lorebook-sets D4），抢星判定改读 findBySetId；
        // 五参重载携带抢星候选星名与命定人物键（rpg-lore-archival-loading D2 +
        // preset-destined-figures D3，物化侧写 claimed 变体段双语义）
        lorebookSetService = mock(LorebookSetService.class);
        LorebookSet set = new LorebookSet();
        set.setId(SET_ID);
        set.setWorldId(WORLD_ID);
        when(lorebookSetService.copyForNewGame(anyString(), any(), anyString(), any(), any()))
                .thenReturn(set);
    }

    /** 新局 set 名录（抢星判定的读取源） */
    private void stubSetStars(List<StarIncarnation> stars) {
        when(starRepo.findBySetId(SET_ID)).thenReturn(stars);
    }

    /** 构造带玩家角色仓储的 service（startGame 的硬前置：角色存在且属于该世界） */
    private WorkshopService newService() {
        RpgCharacterCardRepository charRepo = mock(RpgCharacterCardRepository.class);
        CharacterCard player = new CharacterCard();
        player.setId(CHAR_ID);
        player.setWorldId(WORLD_ID);
        player.setName("玩家");
        when(charRepo.findById(CHAR_ID)).thenReturn(player);
        return new WorkshopService(
                mock(RpgWorldSettingRepository.class),
                charRepo,
                mock(RpgLocationRepository.class),
                mock(RpgRelationshipRepository.class),
                mock(RpgTriggerRepository.class),
                mock(RpgGameStateRepository.class),
                mock(RpgTriggerRuntimeRepository.class),
                starRepo,
                starRuntimeRepo,
                mock(WorkshopLlmGenerator.class),
                mock(CharacterOriginService.class),
                mock(WorldCoverStorageService.class),
                mock(PlatformTransactionManager.class),
                mock(RpgWorldbookEntryRepository.class),
                lorebookSetService,
                loreArchiveService,
                mock(SessionService.class), mock(SessionRepository.class));
    }

    private OriginResult origin(String faction, String constellation) {
        OriginResult o = new OriginResult();
        o.setConstellation(constellation);
        o.setStarRank("Specter");
        o.setFaction(faction);
        o.setZodiacTraits("地煞副星：地魁星（木下秀吉）——仅文本，不反解析判定");
        return o;
    }

    /** 命定人物局的推演结果（figure 三字段非空，如信长预绑冥衣） */
    private OriginResult figureOrigin(String figureKey, String figureName, String constellation) {
        OriginResult o = origin("specter", constellation);
        o.setFigureKey(figureKey);
        o.setFigureDisplayName(figureName);
        o.setFigureEraNote("弘治二年二十二岁，稻生合战未起");
        return o;
    }

    /** 名录片段：天魁星可抢；天兽星 NPC 专属不可抢 */
    private StarIncarnation incarnation(String starName, String warlord, boolean rollable) {
        StarIncarnation i = new StarIncarnation();
        i.setId("inc-" + starName);
        i.setWorldId(WORLD_ID);
        i.setStarName(starName);
        i.setWarlordName(warlord);
        i.setLore(starName + "的命定叙事");
        i.setAliases(warlord);
        i.setRollable(rollable);
        return i;
    }

    @Test
    void 主星命中写claimed行且activatedTurn为0() {
        stubSetStars(List.of(
                incarnation("天魁星", "织田信长", true),
                incarnation("天兽星", "前田庆次", false)));

        GameState gs = newService().startGame(WORLD_ID, CHAR_ID, "s-1",
                origin("specter", "天魁星"));

        ArgumentCaptor<StarIncarnationRuntime> captor =
                ArgumentCaptor.forClass(StarIncarnationRuntime.class);
        verify(starRuntimeRepo).save(captor.capture());
        StarIncarnationRuntime saved = captor.getValue();
        assertThat(saved.getGameStateId()).isEqualTo(gs.getId());
        assertThat(saved.getStarName()).isEqualTo("天魁星");
        assertThat(saved.getIncarnationId()).isEqualTo("inc-天魁星");
        assertThat(saved.getClaimedByPlayer()).isTrue();
        // activated_turn=0 即开局抢星起点：sticky，本局所有回合恒亮
        assertThat(saved.getActivatedTurn()).isZero();
        // 抢星命中同步追加档案索引行（D4：GM 必须知道玩家持有什么星；第五参 figureKey
        // 决定【人即玩家】/【星归玩家】标记——无名新人为 null 走旧标记）
        verify(loreArchiveService).appendRevealedIndexLine(
                gs.getId(), "天魁星", "织田信长", true, null);
    }

    @Test
    void 继承开局_inheritSetId透传copyForNewGame且抢星读新局set名录() {
        stubSetStars(List.of(
                incarnation("天魁星", "织田信长", true)));

        // 带 inheritSetId 开局：来源 set 透传给 copyForNewGame（5.1/5.4）
        GameState gs = newService().startGame(WORLD_ID, CHAR_ID, "s-inherit",
                origin("specter", "天魁星"), "set-source");

        verify(lorebookSetService).copyForNewGame(eq(WORLD_ID), eq("set-source"),
                eq(gs.getId()), eq("天魁星"), isNull());
        // 抢星判定读 fork 出的新局 set 名录（stubSetStars → findBySetId(SET_ID)），据新局自身 origin 计算
        ArgumentCaptor<StarIncarnationRuntime> captor =
                ArgumentCaptor.forClass(StarIncarnationRuntime.class);
        verify(starRuntimeRepo).save(captor.capture());
        assertThat(captor.getValue().getStarName()).isEqualTo("天魁星");
        assertThat(captor.getValue().getClaimedByPlayer()).isTrue();
        assertThat(captor.getValue().getGameStateId()).isEqualTo(gs.getId());
    }

    @Test
    void 信长局_figureKey透传物化链路且索引行带人即玩家信号() {
        stubSetStars(List.of(
                incarnation("天魁星", "织田信长", true)));

        GameState gs = newService().startGame(WORLD_ID, CHAR_ID, "s-nb",
                figureOrigin("nobunaga", "织田信长", "天魁星"));

        // 预绑冥衣即被人物钉死的推演：figureKey 随开局透传档案物化链路
        // （命中天魁星档案的变体段/索引标记切换为「人即玩家」双语义）
        verify(lorebookSetService).copyForNewGame(eq(WORLD_ID), isNull(), eq(gs.getId()),
                eq("天魁星"), eq("nobunaga"));
        // 判定零改动自动命中：runtime claimed 行照写 + 索引行携带人物信号
        ArgumentCaptor<StarIncarnationRuntime> captor =
                ArgumentCaptor.forClass(StarIncarnationRuntime.class);
        verify(starRuntimeRepo).save(captor.capture());
        assertThat(captor.getValue().getStarName()).isEqualTo("天魁星");
        assertThat(captor.getValue().getClaimedByPlayer()).isTrue();
        verify(loreArchiveService).appendRevealedIndexLine(
                gs.getId(), "天魁星", "织田信长", true, "nobunaga");
    }

    @Test
    void 无名新人撞天魁星_figureKey为null维持星归玩家旧语义() {
        stubSetStars(List.of(
                incarnation("天魁星", "织田信长", true)));

        GameState gs = newService().startGame(WORLD_ID, CHAR_ID, "s-anon",
                origin("specter", "天魁星"));

        // 无 figureKey：透传 null（人物档不变体段、索引行【星归玩家】旧语义零改动）
        verify(lorebookSetService).copyForNewGame(eq(WORLD_ID), isNull(), eq(gs.getId()),
                eq("天魁星"), isNull());
        verify(loreArchiveService).appendRevealedIndexLine(
                gs.getId(), "天魁星", "织田信长", true, null);
    }

    @Test
    void 非specter阵营零写入() {
        stubSetStars(List.of(
                incarnation("天魁星", "织田信长", true)));

        newService().startGame(WORLD_ID, CHAR_ID, "s-2", origin("saint", "天魁星"));
        newService().startGame(WORLD_ID, CHAR_ID, "s-3", origin("godwarrior", "天魁星"));
        newService().startGame(WORLD_ID, CHAR_ID, "s-4", null);

        verify(starRuntimeRepo, never()).save(any());
        verify(loreArchiveService, never()).appendRevealedIndexLine(
                any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void rollable为false的NPC专属星即使撞星也不写claimed() {
        stubSetStars(List.of(
                incarnation("天兽星", "前田庆次", false)));

        newService().startGame(WORLD_ID, CHAR_ID, "s-5",
                origin("specter", "天兽星"));

        verify(starRuntimeRepo, never()).save(any());
        verify(loreArchiveService, never()).appendRevealedIndexLine(
                any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void 主星未命中或名录未物化零写入且开局不受阻断() {
        stubSetStars(List.of(
                incarnation("天魁星", "织田信长", true)));
        // 主星为名录之外的星（含地煞副星语义：副星只进 zodiacTraits 文本，不进判定）
        GameState missed = newService().startGame(WORLD_ID, CHAR_ID, "s-6",
                origin("specter", "地魁星"));
        assertThat(missed).isNotNull();

        // 名录未物化（空列表）：老世界开局同名录为零
        stubSetStars(List.of());
        GameState empty = newService().startGame(WORLD_ID, CHAR_ID, "s-7",
                origin("specter", "天魁星"));
        assertThat(empty).isNotNull();

        verify(starRuntimeRepo, never()).save(any());
        verify(loreArchiveService, never()).appendRevealedIndexLine(
                any(), any(), any(), anyBoolean(), any());
    }
}
