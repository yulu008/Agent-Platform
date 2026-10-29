package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.service.LorebookSetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.session.SessionService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorldbookScanner} 单元测试（add-rpg-worldbook task 6.2）。
 * <p>
 * 钉住瞬时注入语义：命中当轮返回、下一轮不再命中即不返回（无状态）、未命中返回空、
 * 停用条目跳过、开关关闭返回空、任何异常降级为空列表（绝不阻断回合主流程）。
 * <p>
 * 存档 sessionId 置空 → {@code lastGmNarration} 直接返回空串，扫描文本仅覆盖玩家行动，
 * 无需 mock {@link SessionService}。
 */
class WorldbookScannerTest {

    private static final String GS_ID = "gs-1";
    private static final String WORLD_ID = "world-1";

    private RpgGameStateRepository stateRepo;
    private LorebookSetService lorebookSetService;
    private RpgWorldbookProperties properties;
    private WorldbookScanner scanner;

    @BeforeEach
    void setUp() {
        stateRepo = mock(RpgGameStateRepository.class);
        lorebookSetService = mock(LorebookSetService.class);
        SessionService sessionService = mock(SessionService.class);
        StateDeltaSanitizer sanitizer = mock(StateDeltaSanitizer.class);
        properties = new RpgWorldbookProperties();   // 默认 enabled=true
        scanner = new WorldbookScanner(stateRepo, sessionService, sanitizer, properties, lorebookSetService);

        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setWorldId(WORLD_ID);
        gs.setSessionId(null);   // 无 session → lastGmNarration 返回空串
        when(stateRepo.findById(GS_ID)).thenReturn(gs);
    }

    /** 条目读取源经 LorebookSetService.resolve（add-rpg-lorebook-sets）：set/模板差异由 service 层测试覆盖 */
    private void stubEntries(WorldbookEntry... entries) {
        when(lorebookSetService.resolve(GS_ID)).thenReturn(
                new LorebookSetService.ResolvedView(null, List.of(), List.of(entries)));
    }

    private WorldbookEntry entry(String title, String keywords, boolean enabled) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId("wb-" + title);
        e.setWorldId(WORLD_ID);
        e.setTitle(title);
        e.setKeywords(keywords);
        e.setContent(title + " 的设定正文");
        e.setEnabled(enabled);
        e.setSortOrder(0);
        return e;
    }

    @Test
    void 命中当轮返回该条目() {
        stubEntries(entry("钟表城", "雾都,齿轮塔", true));

        List<WorldbookEntry> hits = scanner.scan(GS_ID, "我走进钟表城的街道");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getTitle()).isEqualTo("钟表城");
    }

    @Test
    void 关键词项命中亦入选() {
        stubEntries(entry("钟表城", "雾都,齿轮塔", true));

        // 玩家行动未提标题，但提到关键词「齿轮塔」
        List<WorldbookEntry> hits = scanner.scan(GS_ID, "齿轮塔在雾中若隐若现");

        assertThat(hits).hasSize(1);
    }

    @Test
    void 瞬时无状态_下一轮不再命中即不返回() {
        stubEntries(entry("钟表城", "雾都", true));

        // 第一轮命中
        assertThat(scanner.scan(GS_ID, "来到钟表城")).hasSize(1);
        // 第二轮玩家行动不含任何关键词 → 不再返回（不 sticky，无 runtime 记忆）
        assertThat(scanner.scan(GS_ID, "我在路边休息")).isEmpty();
    }

    @Test
    void 未命中返回空() {
        stubEntries(entry("钟表城", "雾都", true));

        assertThat(scanner.scan(GS_ID, "今天天气不错")).isEmpty();
    }

    @Test
    void 停用条目即使命中关键词也跳过() {
        stubEntries(entry("钟表城", "雾都", false));

        assertThat(scanner.scan(GS_ID, "我走进钟表城")).isEmpty();
    }

    @Test
    void enabled为null视为启用() {
        WorldbookEntry e = entry("钟表城", "雾都", true);
        e.setEnabled(null);
        stubEntries(e);

        assertThat(scanner.scan(GS_ID, "钟表城到了")).hasSize(1);
    }

    @Test
    void 开关关闭直接返回空() {
        properties.setEnabled(false);
        stubEntries(entry("钟表城", "雾都", true));

        assertThat(scanner.scan(GS_ID, "我走进钟表城")).isEmpty();
    }

    @Test
    void 无条目返回空() {
        stubEntries();

        assertThat(scanner.scan(GS_ID, "我走进钟表城")).isEmpty();
    }

    @Test
    void set副本已物化时扫描命中编辑后条目_证明改动下一轮生效() {
        // 弹窗编辑后的 set 行：给「钟表城」加了关键词「自鸣钟」，并新增一条 set 原生条目。
        // 条目写手切到副本层后，下一轮瞬时注入即读到编辑内容（4.4）。
        LorebookSet set = new LorebookSet();
        set.setId("set-1");
        when(lorebookSetService.resolve(GS_ID)).thenReturn(
                new LorebookSetService.ResolvedView(set, List.of(), List.of(
                        entry("钟表城", "雾都,自鸣钟", true),
                        entry("大阪城", "难波京", true))));

        // 命中编辑后新增的关键词「自鸣钟」
        assertThat(scanner.scan(GS_ID, "自鸣钟在雾中敲响")).hasSize(1);
        // set 原生新增条目同样参与扫描
        assertThat(scanner.scan(GS_ID, "难波京的街市"))
                .extracting(WorldbookEntry::getTitle).contains("大阪城");
    }

    @Test
    void 存档不存在返回空() {
        when(stateRepo.findById(GS_ID)).thenReturn(null);

        assertThat(scanner.scan(GS_ID, "我走进钟表城")).isEmpty();
    }

    @Test
    void 异常降级为空列表不阻断回合() {
        when(lorebookSetService.resolve(anyString())).thenThrow(new RuntimeException("DB 故障"));

        assertThat(scanner.scan(GS_ID, "我走进钟表城")).isEmpty();
    }
}
