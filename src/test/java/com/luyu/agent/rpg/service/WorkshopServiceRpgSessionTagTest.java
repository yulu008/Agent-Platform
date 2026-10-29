package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLocationRepository;
import com.luyu.agent.rpg.repository.RpgRelationshipRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.session.CreateSessionRequest;
import org.springframework.ai.session.Session;
import org.springframework.ai.session.SessionRepository;
import org.springframework.ai.session.SessionService;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkshopService#startGame(String, String, String)} RPG 会话打标单测
 * （separate-rpg-chat-sessions tasks 1.2）。
 * <p>
 * 钉住开局链路起点的幂等建壳/补标四态：
 * <ol>
 *   <li><b>全新 sessionId</b>：以请求租户为 userId 显式建壳，
 *       {@code metadata.kind="rpg"}（壳先于首回合存在，消灭 advisor 无标记建壳窗口）；</li>
 *   <li><b>壳已存在无 kind</b>：merge 补写 kind 且保留既有 metadata 键（幂等不重复建壳）；</li>
 *   <li><b>壳已带 kind=rpg</b>：跳过（不重复写库）；</li>
 *   <li><b>空/null sessionId、仓储异常、租户上下文缺失</b>：降级跳过/吞掉，
 *       开局不受阻断（对齐 startGame 现有空串容忍语义，失标由回填自愈）。</li>
 * </ol>
 */
class WorkshopServiceRpgSessionTagTest {

    private static final String WORLD_ID = "preset-adventure:t-tag";
    private static final String CHAR_ID = "char-1";

    private SessionService sessionService;
    private SessionRepository sessionRepository;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        sessionRepository = mock(SessionRepository.class);
        TenantContext.set("u-1", "t-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 带玩家角色仓储与 set 副本桩的 service（startGame 的硬前置） */
    private WorkshopService newService() {
        RpgCharacterCardRepository charRepo = mock(RpgCharacterCardRepository.class);
        CharacterCard player = new CharacterCard();
        player.setId(CHAR_ID);
        player.setWorldId(WORLD_ID);
        player.setName("玩家");
        when(charRepo.findById(CHAR_ID)).thenReturn(player);
        LorebookSetService lorebookSetService = mock(LorebookSetService.class);
        LorebookSet set = new LorebookSet();
        set.setId("set-1");
        set.setWorldId(WORLD_ID);
        when(lorebookSetService.copyForNewGame(anyString(), any(), anyString(), any(), any()))
                .thenReturn(set);
        return new WorkshopService(
                mock(RpgWorldSettingRepository.class),
                charRepo,
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
                lorebookSetService,
                mock(LoreArchiveService.class),
                sessionService,
                sessionRepository);
    }

    /** 已存在的壳 mock（带归属与 metadata） */
    private Session existingSession(String sessionId, String userId, Map<String, Object> metadata) {
        Session session = mock(Session.class);
        when(session.id()).thenReturn(sessionId);
        when(session.userId()).thenReturn(userId);
        when(session.createdAt()).thenReturn(Instant.now());
        when(session.metadata()).thenReturn(metadata);
        return session;
    }

    // ==================== 建壳 / 补标 / 幂等 ====================

    @Test
    void 全新sessionId开局建壳携带kindRpg() {
        GameState gs = newService().startGame(WORLD_ID, CHAR_ID, "s-new", null);

        assertThat(gs).isNotNull();
        ArgumentCaptor<CreateSessionRequest> captor = ArgumentCaptor.forClass(CreateSessionRequest.class);
        verify(sessionService).create(captor.capture());
        CreateSessionRequest req = captor.getValue();
        // 前端生成的 sessionId 直接作壳主键（消灭 advisor 自动建壳时序窗口）
        assertThat(req.id()).isEqualTo("s-new");
        assertThat(req.userId()).isEqualTo("t-1");
        assertThat(req.metadata()).containsEntry("kind", "rpg");
    }

    @Test
    void 壳已存在无kind时merge补标且保留既有metadata键() {
        // mock 构造与 when 语句分离：thenReturn 参数位内嵌套 when 会触发 UnfinishedStubbing
        Session existing = existingSession("s-dup", "t-1", Map.of("star", "天魁星"));
        when(sessionRepository.findById("s-dup")).thenReturn(existing);

        newService().startGame(WORLD_ID, CHAR_ID, "s-dup", null);

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());
        Session saved = captor.getValue();
        assertThat(saved.id()).isEqualTo("s-dup");
        assertThat(saved.userId()).isEqualTo("t-1");
        // merge 语义：补写 kind 的同时保留既有键，不重复建壳
        assertThat(saved.metadata())
                .containsEntry("kind", "rpg")
                .containsEntry("star", "天魁星");
        verify(sessionService, never()).create(any());
    }

    @Test
    void 壳已带kindRpg时跳过打标不重复写库() {
        Session tagged = existingSession("s-tagged", "t-1", Map.of("kind", "rpg"));
        when(sessionRepository.findById("s-tagged")).thenReturn(tagged);

        newService().startGame(WORLD_ID, CHAR_ID, "s-tagged", null);

        verify(sessionRepository, never()).save(any(Session.class));
        verify(sessionService, never()).create(any());
    }

    // ==================== 降级不阻断 ====================

    @Test
    void 空或null的sessionId跳过打标且开局正常() {
        GameState blank = newService().startGame(WORLD_ID, CHAR_ID, "  ", null);
        GameState nullId = newService().startGame(WORLD_ID, CHAR_ID, null, null);

        // 对齐 startGame 现有空串容忍语义：GameState 照常落库返回
        assertThat(blank).isNotNull();
        assertThat(nullId).isNotNull();
        verify(sessionRepository, never()).findById(any());
        verify(sessionService, never()).create(any());
    }

    @Test
    void 打标仓储异常时降级不阻断开局() {
        when(sessionRepository.findById("s-broken")).thenThrow(new RuntimeException("db down"));

        GameState gs = newService().startGame(WORLD_ID, CHAR_ID, "s-broken", null);

        assertThat(gs).isNotNull();
        assertThat(gs.getSessionId()).isEqualTo("s-broken");
        verify(sessionService, never()).create(any());
    }

    @Test
    void 租户上下文缺失时降级不阻断开局() {
        TenantContext.clear();

        assertThatCode(() -> {
            GameState gs = newService().startGame(WORLD_ID, CHAR_ID, "s-no-tenant", null);
            assertThat(gs).isNotNull();
        }).doesNotThrowAnyException();
        verify(sessionRepository, never()).findById(any());
        verify(sessionService, never()).create(any());
    }
}
