package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.service.LoreArchiveService;
import com.luyu.agent.rpg.service.LorebookSetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StarLorebookScanner} 单元测试（star-lorebook design D3 + rpg-lore-archival-loading D4）。
 * <p>
 * 钉住关键词触发与 sticky 语义：玩家行动/别名/GM 上轮叙事命中激活、
 * 已激活条目常驻且不重复写行、未命中不注入、开关关闭与异常降级；
 * 激活即同步追加档案索引行（claimed=false），索引追加异常不拖垮激活与返回。
 */
class StarLorebookScannerTest {

    private static final String GS_ID = "gs-1";
    private static final String SESSION_ID = "sess-1";
    private static final String WORLD_ID = "preset-adventure:t1";

    private RpgGameStateRepository stateRepo;
    private LorebookSetService lorebookSetService;
    private LoreArchiveService loreArchiveService;
    private RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private SessionService sessionService;
    private StateDeltaSanitizer stateDeltaSanitizer;
    private RpgLorebookProperties lorebookProperties;
    private StarLorebookScanner scanner;

    @BeforeEach
    void setUp() {
        stateRepo = mock(RpgGameStateRepository.class);
        lorebookSetService = mock(LorebookSetService.class);
        loreArchiveService = mock(LoreArchiveService.class);
        starRuntimeRepo = mock(RpgStarIncarnationRuntimeRepository.class);
        sessionService = mock(SessionService.class);
        stateDeltaSanitizer = mock(StateDeltaSanitizer.class);
        lorebookProperties = new RpgLorebookProperties();
        // sanitizer 默认行为：原样返回（清洗逻辑自身已有独立测试）
        when(stateDeltaSanitizer.extractNarration(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        scanner = new StarLorebookScanner(stateRepo, starRuntimeRepo,
                sessionService, stateDeltaSanitizer, lorebookProperties, lorebookSetService,
                loreArchiveService);

        // 基线场景：gs 绑定 session，名录 2 条（织田信长可推演 / 前田庆次 NPC 专属），无已激活行
        when(stateRepo.findById(GS_ID)).thenReturn(gameState());
        stubStars(
                incarnation("id-1", "天魁星", "织田信长", "第六天魔王", "魔王", true),
                incarnation("id-2", "天兽星", "前田庆次", "倾奇者", null, false));
        when(starRuntimeRepo.findByGameStateId(GS_ID)).thenReturn(List.of());
    }

    /** 名录读取源经 LorebookSetService.resolve（add-rpg-lorebook-sets）：set/模板差异由 service 层测试覆盖 */
    private void stubStars(StarIncarnation... stars) {
        when(lorebookSetService.resolve(GS_ID)).thenReturn(
                new LorebookSetService.ResolvedView(null, List.of(stars), List.of()));
    }

    // ==================== 命中激活 ====================

    @Test
    void 玩家行动含武将名_激活写runtime并返回该条() {
        StarLorebookScanner.ScanOutcome outcome =
                scanner.scanAndCollect(GS_ID, "我向店家打听织田信长的动向", 3);

        // 写入 runtime：activated_turn=当前轮、claimed=false、star_name 冗余（稳定键）
        ArgumentCaptor<StarIncarnationRuntime> captor = ArgumentCaptor.forClass(StarIncarnationRuntime.class);
        verify(starRuntimeRepo).save(captor.capture());
        StarIncarnationRuntime saved = captor.getValue();
        assertThat(saved.getStarName()).isEqualTo("天魁星");
        assertThat(saved.getIncarnationId()).isEqualTo("id-1");
        assertThat(saved.getGameStateId()).isEqualTo(GS_ID);
        assertThat(saved.getActivatedTurn()).isEqualTo(3);
        assertThat(saved.getClaimedByPlayer()).isFalse();

        // 返回已揭示条目（本存档视图）+ 本轮新命中子集
        assertThat(outcome.revealed()).hasSize(1);
        assertThat(outcome.revealed().get(0).incarnation().getWarlordName()).isEqualTo("织田信长");
        assertThat(outcome.revealed().get(0).claimed()).isFalse();
        assertThat(outcome.newlyRevealed()).hasSize(1);
        assertThat(outcome.newlyRevealed().get(0).incarnation().getStarName()).isEqualTo("天魁星");

        // 激活即同步追加档案索引行（rpg-lore-archival-loading D4，claimed=false）
        verify(loreArchiveService).appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", false);
    }

    @Test
    void 别名命中_同样激活() {
        StarLorebookScanner.ScanOutcome outcome =
                scanner.scanAndCollect(GS_ID, "北边的魔王又打胜仗了", 2);

        assertThat(outcome.revealed()).hasSize(1);
        assertThat(outcome.revealed().get(0).incarnation().getStarName()).isEqualTo("天魁星");
        verify(starRuntimeRepo).save(any(StarIncarnationRuntime.class));
    }

    @Test
    void GM上一轮叙事命中_玩家行动无关键词仍激活() {
        // 仅扫玩家行动会因 GM 先提到武将名而漏激活（design D3）：叙事文本必须进扫描集
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我继续赶路"),
                assistantEvent("a1", "地平线上浮现天魁星的旗帜，军马踏起的烟尘遮蔽了半边天。")));

        StarLorebookScanner.ScanOutcome outcome =
                scanner.scanAndCollect(GS_ID, "我继续赶路", 5);

        assertThat(outcome.revealed()).hasSize(1);
        assertThat(outcome.revealed().get(0).incarnation().getStarName()).isEqualTo("天魁星");
    }

    @Test
    void GM叙事取最近非合成assistant消息_合成摘要不参与扫描() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                // 旧轮叙事含关键词（已被更近的合成摘要覆盖，不应再触发）
                assistantEvent("a1", "远处传来织田信长的军歌。"),
                // 合成摘要：跳过
                SessionEvent.builder().id("syn1").sessionId(SESSION_ID)
                        .message(new AssistantMessage("（前情摘要，含前田庆次的旧事）"))
                        .metadata(SessionEvent.METADATA_SYNTHETIC, true)
                        .timestamp(Instant.now()).build(),
                // 最近的非合成 GM 叙事：不含任何关键词
                assistantEvent("a2", "山道安静，只有风声。")));

        StarLorebookScanner.ScanOutcome outcome =
                scanner.scanAndCollect(GS_ID, "我加快脚步", 4);

        assertThat(outcome.revealed()).isEmpty();
        assertThat(outcome.newlyRevealed()).isEmpty();
        verify(starRuntimeRepo, never()).save(any());
        verify(loreArchiveService, never()).appendRevealedIndexLine(any(), any(), any(), anyBoolean());
    }

    @Test
    void set副本已物化时扫描命中编辑后内容_证明改动下一轮生效() {
        // 存档已物化 set：resolve 返回 set 行（用户把织田信长改名 + 新增一颗 set 原生星）。
        // 名录写手切到副本层后，弹窗编辑写入 set 行，下一轮扫描即读到编辑内容（4.4）。
        LorebookSet set = new LorebookSet();
        set.setId("set-1");
        when(lorebookSetService.resolve(GS_ID)).thenReturn(
                new LorebookSetService.ResolvedView(set, List.of(
                        incarnation("s-1", "天魁星", "织田信长改", "第六天魔王", "魔王", true),
                        incarnation("s-2", "自创星", "张三丰", "太极祖师", "真人", true)),
                        List.of()));

        // 命中改过名的武将：注入 set 副本内容（模板里仍叫「织田信长」，改动只在副本层）
        StarLorebookScanner.ScanOutcome renamed =
                scanner.scanAndCollect(GS_ID, "我投奔织田信长改麾下", 3);
        assertThat(renamed.revealed()).hasSize(1);
        assertThat(renamed.revealed().get(0).incarnation().getStarName()).isEqualTo("天魁星");

        // set 原生新增星（模板名录里没有）同样参与扫描命中
        StarLorebookScanner.ScanOutcome custom =
                scanner.scanAndCollect(GS_ID, "太极祖师张三丰现身", 4);
        assertThat(custom.revealed()).hasSize(1);
        assertThat(custom.revealed().get(0).incarnation().getStarName()).isEqualTo("自创星");
    }

    // ==================== sticky 常驻 ====================

    @Test
    void 已激活条目本轮无关键词_仍常驻且不重复写行() {
        // 第 3 轮已激活的行（含开局抢星 claimed 行）
        when(starRuntimeRepo.findByGameStateId(GS_ID)).thenReturn(List.of(
                runtimeRow("id-1", "天魁星", 3, false),
                runtimeRow("id-2", "天兽星", 0, true)));

        StarLorebookScanner.ScanOutcome outcome =
                scanner.scanAndCollect(GS_ID, "我继续赶路", 5);

        // 按名录顺序返回全部已激活条目（sticky：不随本轮是否再次命中而消失）
        assertThat(outcome.revealed()).hasSize(2);
        assertThat(outcome.revealed().get(0).incarnation().getStarName()).isEqualTo("天魁星");
        assertThat(outcome.revealed().get(0).claimed()).isFalse();
        assertThat(outcome.revealed().get(1).incarnation().getStarName()).isEqualTo("天兽星");
        assertThat(outcome.revealed().get(1).claimed()).isTrue();
        // 本轮无新命中 → 一行式注入块消费的新命中子集为空（旧全量渠道已退场）
        assertThat(outcome.newlyRevealed()).isEmpty();
        // 已激活条目不再重复写 runtime、不再追加索引行
        verify(starRuntimeRepo, never()).save(any());
        verify(loreArchiveService, never()).appendRevealedIndexLine(any(), any(), any(), anyBoolean());
    }

    // ==================== 未命中 / 降级 ====================

    @Test
    void 人物局claimed行携带人即玩家信号_无名新人局不携带() {
        // 信长局：playerStates 带 figure_key，开局抢星的天魁星行 playerIsFigure=true
        GameState figureGs = gameState();
        figureGs.setPlayerStates(
                "{\"figure_key\":\"nobunaga\",\"figure_display_name\":\"织田信长\"}");
        when(stateRepo.findById(GS_ID)).thenReturn(figureGs);
        when(starRuntimeRepo.findByGameStateId(GS_ID)).thenReturn(List.of(
                runtimeRow("id-1", "天魁星", 0, true)));

        StarLorebookScanner.ScanOutcome figure =
                scanner.scanAndCollect(GS_ID, "我继续赶路", 2);

        assertThat(figure.revealed()).hasSize(1);
        assertThat(figure.revealed().get(0).claimed()).isTrue();
        assertThat(figure.revealed().get(0).playerIsFigure()).isTrue();

        // 无 figure 键存档（无名新人撞星/旧档）：claimed 行不携带人物信号（旧语义）
        when(stateRepo.findById(GS_ID)).thenReturn(gameState());
        StarLorebookScanner.ScanOutcome anonymous =
                scanner.scanAndCollect(GS_ID, "我继续赶路", 2);

        assertThat(anonymous.revealed()).hasSize(1);
        assertThat(anonymous.revealed().get(0).claimed()).isTrue();
        assertThat(anonymous.revealed().get(0).playerIsFigure()).isFalse();
    }

    @Test
    void 未命中且无已激活_返回空且不写runtime() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of());

        StarLorebookScanner.ScanOutcome outcome =
                scanner.scanAndCollect(GS_ID, "我在溪边洗了把脸", 2);

        assertThat(outcome.revealed()).isEmpty();
        verify(starRuntimeRepo, never()).save(any());
    }

    @Test
    void 名录为空_直接返回空() {
        stubStars();

        assertThat(scanner.scanAndCollect(GS_ID, "织田信长来了", 2).revealed()).isEmpty();
        verify(starRuntimeRepo, never()).save(any());
    }

    @Test
    void 开关关闭_不触库直接返回空() {
        lorebookProperties.setEnabled(false);

        assertThat(scanner.scanAndCollect(GS_ID, "织田信长来了", 2).revealed()).isEmpty();
        verify(stateRepo, never()).findById(any());
        verify(starRuntimeRepo, never()).save(any());
    }

    @Test
    void 扫描异常_降级为空结果不阻断回合() {
        when(stateRepo.findById(GS_ID)).thenThrow(new RuntimeException("DB 连接中断"));

        assertThat(scanner.scanAndCollect(GS_ID, "织田信长来了", 2).revealed()).isEmpty();
    }

    @Test
    void 存档不存在_返回空() {
        when(stateRepo.findById("nope")).thenReturn(null);

        assertThat(scanner.scanAndCollect("nope", "织田信长来了", 2).revealed()).isEmpty();
        verify(starRuntimeRepo, never()).save(any());
    }

    // ==================== 索引行追加（rpg-lore-archival-loading tasks 2.5） ====================

    @Test
    void 索引追加抛异常_不拖垮激活与返回值() {
        doThrow(new RuntimeException("索引服务内部缺陷"))
                .when(loreArchiveService).appendRevealedIndexLine(
                        eq(GS_ID), eq("天魁星"), eq("织田信长"), eq(false));

        StarLorebookScanner.ScanOutcome outcome =
                scanner.scanAndCollect(GS_ID, "我向店家打听织田信长的动向", 3);

        // runtime 行照写（sticky 不受影响）、激活条目照常返回（仅索引缺失）
        verify(starRuntimeRepo).save(any(StarIncarnationRuntime.class));
        assertThat(outcome.revealed()).hasSize(1);
        assertThat(outcome.newlyRevealed()).hasSize(1);
    }

    // ==================== 构造辅助 ====================

    private static GameState gameState() {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setWorldId(WORLD_ID);
        gs.setSessionId(SESSION_ID);
        return gs;
    }

    private static StarIncarnation incarnation(String id, String star, String warlord,
                                                String lore, String aliases, boolean rollable) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId(id);
        inc.setStarName(star);
        inc.setWarlordName(warlord);
        inc.setLore(lore);
        inc.setAliases(aliases);
        inc.setRollable(rollable);
        return inc;
    }

    private static StarIncarnationRuntime runtimeRow(String incarnationId, String starName,
                                                      int activatedTurn, boolean claimed) {
        StarIncarnationRuntime rt = new StarIncarnationRuntime();
        rt.setId(UUID.randomUUID().toString());
        rt.setGameStateId(GS_ID);
        rt.setIncarnationId(incarnationId);
        rt.setStarName(starName);
        rt.setActivatedTurn(activatedTurn);
        rt.setClaimedByPlayer(claimed);
        return rt;
    }

    private static SessionEvent userEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new UserMessage(text)).timestamp(Instant.now()).build();
    }

    private static SessionEvent assistantEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new AssistantMessage(text)).timestamp(Instant.now()).build();
    }
}
