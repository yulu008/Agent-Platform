package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.LorebookSet;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgLorebookSetRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgWorldbookEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LorebookSetService} 单元测试（add-rpg-lorebook-sets 3.7，纯 Mockito）。
 * <p>
 * 钉住副本层六大语义：
 * <ol>
 *   <li><b>resolve 回退</b>：存档有 set 读 set 行、无 set（老存档）回退模板行且 set=null、存档不存在 null；</li>
 *   <li><b>懒物化</b>：ensureSet 无 set 时事务内复制模板行建 set（source_ref 取稳定键），有 set 直接返回；</li>
 *   <li><b>fork 保留 source_ref</b>：copyForNewGame 世界默认复制模板行、继承源 set 逐行复制且跨继承链
 *       保留最初模板的稳定键；源 set 不存在/不属该世界抛 IllegalArgumentException；</li>
 *   <li><b>导入去重跨继承链</b>：候选按 starName / source_ref（含继承链 fork 后仍指向模板 id）/ title 缺席判定；</li>
 *   <li><b>改名不断链</b>：saveStar 改 starName 时同事务 renameStar 同步 runtime，source_ref 保持稳定键不变；
 *       懒迁移下前端携模板行 id 亦能映射到物化 set 行；非本 set 行返回 null；</li>
 *   <li><b>孤儿生命周期</b>：orphanize 解除引用、deleteOrphanSet 被引用返回 -2（控制层判 409）、
 *       孤儿删除级联清条目行、不存在返回 -1。</li>
 * </ol>
 */
class LorebookSetServiceTest {

    private static final String WORLD_ID = "preset-adventure:t-test";
    private static final String GS_ID = "gs-1";
    private static final String SET_ID = "set-1";

    private RpgGameStateRepository stateRepo;
    private RpgLorebookSetRepository setRepo;
    private RpgStarIncarnationRepository starRepo;
    private RpgWorldbookEntryRepository entryRepo;
    private RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private LoreArchiveService loreArchiveService;
    private TransactionTemplate txTemplate;
    private LorebookSetService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stateRepo = mock(RpgGameStateRepository.class);
        setRepo = mock(RpgLorebookSetRepository.class);
        starRepo = mock(RpgStarIncarnationRepository.class);
        entryRepo = mock(RpgWorldbookEntryRepository.class);
        starRuntimeRepo = mock(RpgStarIncarnationRuntimeRepository.class);
        loreArchiveService = mock(LoreArchiveService.class);
        txTemplate = mock(TransactionTemplate.class);
        // TransactionTemplate 直通：execute 执行回调取值、executeWithoutResult 执行 Consumer
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<Object> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        service = new LorebookSetService(stateRepo, setRepo, starRepo, entryRepo,
                starRuntimeRepo, loreArchiveService, txTemplate);
    }

    // ==================== 1. resolve 回退 ====================

    @Test
    void resolve_存档无set时回退模板行且set为null() {
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(null);
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(templateStar("inc-1", "天魁星", "织田信长")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(templateEntry("wb-1", "钟表城")));

        LorebookSetService.ResolvedView view = service.resolve(GS_ID);

        // 老存档只读回退：set=null，条目取模板行（set_id IS NULL）
        assertThat(view.set()).isNull();
        assertThat(view.stars()).hasSize(1);
        assertThat(view.entries()).hasSize(1);
        verify(starRepo, never()).findBySetId(any());
    }

    @Test
    void resolve_存档有set时读set行不碰模板() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(starRepo.findBySetId(SET_ID)).thenReturn(List.of(setStar("s-1", "天魁星", "织田信长", SET_ID, "天魁星")));
        when(entryRepo.findBySetId(SET_ID)).thenReturn(List.of(setEntry("e-1", "钟表城", SET_ID, "wb-1")));

        LorebookSetService.ResolvedView view = service.resolve(GS_ID);

        assertThat(view.set()).isSameAs(set);
        assertThat(view.stars()).hasSize(1);
        assertThat(view.entries()).hasSize(1);
        verify(starRepo).findBySetId(SET_ID);
        verify(starRepo, never()).findByWorldId(any());
    }

    @Test
    void resolve_存档不存在返回null() {
        when(stateRepo.findById("gs-x")).thenReturn(null);

        assertThat(service.resolve("gs-x")).isNull();
    }

    // ==================== 2. 懒物化 ====================

    @Test
    void ensureSet_老存档首次写入懒物化_复制模板行并保留稳定键() {
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(null);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(templateStar("inc-1", "天魁星", "织田信长")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(templateEntry("wb-1", "钟表城")));

        LorebookSet set = service.ensureSet(GS_ID);

        assertThat(set).isNotNull();
        assertThat(set.getGameStateId()).isEqualTo(GS_ID);
        assertThat(set.getOrigin()).contains("首次编辑物化");
        verify(setRepo).save(any(LorebookSet.class));
        // 武将模板行 source_ref=starName（跨物化稳定键）
        ArgumentCaptor<StarIncarnation> starCap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).save(starCap.capture());
        StarIncarnation starCopy = starCap.getValue();
        assertThat(starCopy.getId()).isNotEqualTo("inc-1");
        assertThat(starCopy.getSetId()).isEqualTo(set.getId());
        assertThat(starCopy.getSourceRef()).isEqualTo("天魁星");
        // 通用模板行 source_ref=模板行 id
        ArgumentCaptor<WorldbookEntry> entryCap = ArgumentCaptor.forClass(WorldbookEntry.class);
        verify(entryRepo).save(entryCap.capture());
        assertThat(entryCap.getValue().getSourceRef()).isEqualTo("wb-1");
    }

    @Test
    void ensureSet_已有set直接返回不再复制() {
        LorebookSet existing = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(existing);

        LorebookSet result = service.ensureSet(GS_ID);

        assertThat(result).isSameAs(existing);
        verify(setRepo, never()).save(any());
        verify(starRepo, never()).save(any());
    }

    @Test
    void ensureSet_存档不存在返回null() {
        when(setRepo.findByGameStateId("gs-x")).thenReturn(null);
        when(stateRepo.findById("gs-x")).thenReturn(null);

        assertThat(service.ensureSet("gs-x")).isNull();
    }

    // ==================== 3. fork 保留 source_ref ====================

    @Test
    void copyForNewGame_世界默认复制模板行且source_ref取稳定键() {
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(templateStar("inc-1", "天魁星", "织田信长")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());

        LorebookSet set = service.copyForNewGame(WORLD_ID, null, "gs-new");

        assertThat(set.getName()).isEqualTo(LorebookSetService.ORIGIN_WORLD_DEFAULT);
        assertThat(set.getGameStateId()).isEqualTo("gs-new");
        ArgumentCaptor<StarIncarnation> cap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).save(cap.capture());
        assertThat(cap.getValue().getSourceRef()).isEqualTo("天魁星");
        assertThat(cap.getValue().getSetId()).isEqualTo(set.getId());
    }

    @Test
    void copyForNewGame_继承源set逐行复制且跨继承链保留source_ref() {
        LorebookSet src = set("set-src", WORLD_ID, "gs-a");
        src.setName("世界默认");
        when(setRepo.findById("set-src")).thenReturn(src);
        // 源 set 行本身是上一代 fork 的产物：id 已换、source_ref 仍指向最初模板键
        when(starRepo.findBySetId("set-src")).thenReturn(
                List.of(setStar("inc-gen2", "天魁星", "织田信长", "set-src", "天魁星")));
        when(entryRepo.findBySetId("set-src")).thenReturn(
                List.of(setEntry("wb-gen2", "钟表城", "set-src", "wb-tpl")));

        LorebookSet set = service.copyForNewGame(WORLD_ID, "set-src", "gs-b");

        assertThat(set.getName()).isEqualTo("继承自「世界默认」");
        ArgumentCaptor<StarIncarnation> starCap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).save(starCap.capture());
        StarIncarnation starCopy = starCap.getValue();
        assertThat(starCopy.getId()).isNotEqualTo("inc-gen2");
        assertThat(starCopy.getSetId()).isEqualTo(set.getId());
        // 跨继承链保留最初稳定键，而非重置为本代 id
        assertThat(starCopy.getSourceRef()).isEqualTo("天魁星");
        ArgumentCaptor<WorldbookEntry> entryCap = ArgumentCaptor.forClass(WorldbookEntry.class);
        verify(entryRepo).save(entryCap.capture());
        assertThat(entryCap.getValue().getSourceRef()).isEqualTo("wb-tpl");
        // 继承走源 set 行，不读世界模板
        verify(starRepo, never()).findByWorldId(any());
    }

    @Test
    void copyForNewGame_源set不属于该世界抛异常且零写入() {
        LorebookSet src = set("set-src", "other-world", "gs-a");
        when(setRepo.findById("set-src")).thenReturn(src);

        assertThatThrownBy(() -> service.copyForNewGame(WORLD_ID, "set-src", "gs-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于该世界");
        verify(setRepo, never()).save(any());
    }

    @Test
    void copyForNewGame_源set不存在抛异常() {
        when(setRepo.findById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.copyForNewGame(WORLD_ID, "nope", "gs-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("继承来源不存在");
    }

    @Test
    void copyForNewGame_只快照名录条目_揭示抢星runtime绝不继承() {
        // 继承开局：名录快照来自源 set，但揭示/抢星是存档级状态（runtime 表），
        // 新局从空白开始，绝不随 fork 复制（5.4）。
        LorebookSet src = set("set-src", WORLD_ID, "gs-a");
        when(setRepo.findById("set-src")).thenReturn(src);
        when(starRepo.findBySetId("set-src")).thenReturn(
                List.of(setStar("inc-1", "天魁星", "织田信长", "set-src", "天魁星")));
        when(entryRepo.findBySetId("set-src")).thenReturn(List.of());

        service.copyForNewGame(WORLD_ID, "set-src", "gs-b");

        verify(starRuntimeRepo, never()).save(any());
        verify(starRuntimeRepo, never()).findByGameStateId(any());
    }

    // ==================== 4. 导入去重跨继承链 ====================

    @Test
    void importCandidates_通用条目按source_ref跨继承链去重() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        // set 内经继承链 fork 的行：id 已换、source_ref 仍指模板 wb-1
        when(entryRepo.findBySetId(SET_ID)).thenReturn(
                List.of(setEntry("forked-id", "钟表城", SET_ID, "wb-1")));
        when(starRepo.findBySetId(SET_ID)).thenReturn(List.of());
        // 模板仍有 wb-1（已被继承）+ wb-2（工坊新增，未导入）
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(
                List.of(templateEntry("wb-1", "钟表城"), templateEntry("wb-2", "雾都")));
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());

        LorebookSetService.ImportCandidates c = service.importCandidates(GS_ID);

        // wb-1 因 source_ref 命中被排除（跨继承链去重），仅 wb-2 可导入
        assertThat(c.entries()).extracting(WorldbookEntry::getId).containsExactly("wb-2");
    }

    @Test
    void importCandidates_武将按starName去重_原生同名条目按title去重() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        // set 内已有天魁星（武将）+ 一条原生新增（source_ref=null，title=雾都）
        when(starRepo.findBySetId(SET_ID)).thenReturn(
                List.of(setStar("s-1", "天魁星", "织田信长", SET_ID, "天魁星")));
        when(entryRepo.findBySetId(SET_ID)).thenReturn(
                List.of(setEntry("e-native", "雾都", SET_ID, null)));
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(
                List.of(templateStar("inc-1", "天魁星", "织田信长"), templateStar("inc-2", "天兽星", "前田庆次")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(
                List.of(templateEntry("wb-2", "雾都"), templateEntry("wb-3", "大阪")));

        LorebookSetService.ImportCandidates c = service.importCandidates(GS_ID);

        // 天魁星已在 set → 排除；天兽星可导入
        assertThat(c.stars()).extracting(StarIncarnation::getStarName).containsExactly("天兽星");
        // 雾都虽 source_ref 不匹配，但 title 命中 set 原生行 → 排除；大阪可导入
        assertThat(c.entries()).extracting(WorldbookEntry::getTitle).containsExactly("大阪");
    }

    @Test
    void importCandidates_无set未物化时两轨皆空() {
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(null);

        LorebookSetService.ImportCandidates c = service.importCandidates(GS_ID);

        assertThat(c.stars()).isEmpty();
        assertThat(c.entries()).isEmpty();
    }

    @Test
    void importEntries_复制选中模板行为set行并保留source_ref() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        when(starRepo.findBySetId(SET_ID)).thenReturn(List.of());
        when(entryRepo.findBySetId(SET_ID)).thenReturn(List.of());
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(templateStar("inc-1", "天魁星", "织田信长")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());

        int n = service.importEntries(GS_ID, List.of("天魁星"), List.of());

        assertThat(n).isEqualTo(1);
        ArgumentCaptor<StarIncarnation> cap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).save(cap.capture());
        assertThat(cap.getValue().getSetId()).isEqualTo(SET_ID);
        assertThat(cap.getValue().getSourceRef()).isEqualTo("天魁星");
    }

    // ==================== 5. 改名不断链 + 懒迁移映射 ====================

    @Test
    void saveStar_改名同步runtime且source_ref保持稳定键不变() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(starRepo.findById("s-1")).thenReturn(setStar("s-1", "天魁星", "织田信长", SET_ID, "天魁星"));

        StarIncarnation payload = new StarIncarnation();
        payload.setId("s-1");
        payload.setStarName("天魁星·改");
        payload.setWarlordName("织田信长");

        StarIncarnation saved = service.saveStar(GS_ID, payload);

        // D8：starName 改动同事务同步本存档 runtime 星名，稳定键不断链
        verify(starRuntimeRepo).renameStar(GS_ID, "天魁星", "天魁星·改");
        assertThat(saved.getStarName()).isEqualTo("天魁星·改");
        assertThat(saved.getSourceRef()).isEqualTo("天魁星");
        verify(starRepo).update(payload);
    }

    @Test
    void saveStar_懒迁移_前端携模板行id映射到物化set行() {
        // 老存档无 set：首次编辑触发 ensureSet 物化
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(null);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of(templateStar("inc-tpl", "天魁星", "织田信长")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        // 前端提交的 payload 仍携模板行 id（弹窗展示的是回退模板行）
        when(starRepo.findById("inc-tpl")).thenReturn(templateStar("inc-tpl", "天魁星", "织田信长"));
        // 物化出的 set 行：新 id、source_ref=starName（findSetStarBySourceRef 据此反查定位）
        when(starRepo.findBySetId(any())).thenReturn(
                List.of(setStar("set-star-gen", "天魁星", "织田信长", SET_ID, "天魁星")));

        StarIncarnation payload = new StarIncarnation();
        payload.setId("inc-tpl");
        payload.setStarName("天魁星");
        payload.setWarlordName("织田信长改");

        StarIncarnation saved = service.saveStar(GS_ID, payload);

        // 模板 id 被映射为物化 set 行 id，更新落在 set 行上
        assertThat(saved.getId()).isEqualTo("set-star-gen");
        assertThat(saved.getSetId()).isNotNull();
        assertThat(saved.getSourceRef()).isEqualTo("天魁星");
        verify(starRepo).update(payload);
    }

    @Test
    void saveStar_目标行不属于本set返回null供控制层判404() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        // 行属于别的存档 set（既非本 set，也非可映射的模板行）
        when(starRepo.findById("other-1")).thenReturn(setStar("other-1", "天魁星", "织田信长", "set-OTHER", "天魁星"));

        StarIncarnation payload = new StarIncarnation();
        payload.setId("other-1");
        payload.setStarName("天魁星");

        assertThat(service.saveStar(GS_ID, payload)).isNull();
        verify(starRepo, never()).update(any());
    }

    @Test
    void saveEntry_新增行source_ref为null且绑定本set() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(entryRepo.findBySetId(SET_ID)).thenReturn(List.of());

        WorldbookEntry payload = new WorldbookEntry();
        payload.setTitle("新条目");
        payload.setContent("正文");
        payload.setEnabled(true);

        WorldbookEntry saved = service.saveEntry(GS_ID, payload);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSetId()).isEqualTo(SET_ID);
        assertThat(saved.getSourceRef()).isNull();
        verify(entryRepo).save(payload);
    }

    @Test
    void deleteStar_set行id直接命中删除() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(starRepo.deleteByIdAndSet("s-1", SET_ID)).thenReturn(1);

        int n = service.deleteStar(GS_ID, "s-1");

        assertThat(n).isEqualTo(1);
        verify(starRepo).deleteByIdAndSet("s-1", SET_ID);
    }

    @Test
    void deleteEntry_非本set且不可映射返回0() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(entryRepo.deleteByIdAndSet("e-x", SET_ID)).thenReturn(0);
        when(entryRepo.findById("e-x")).thenReturn(null);

        assertThat(service.deleteEntry(GS_ID, "e-x")).isZero();
    }

    // ==================== 6. 孤儿生命周期 ====================

    @Test
    void orphanize_删档解除存档引用而保留set与条目() {
        when(setRepo.orphanizeByGameStateId(GS_ID)).thenReturn(1);

        assertThat(service.orphanize(GS_ID)).isEqualTo(1);
        verify(setRepo).orphanizeByGameStateId(GS_ID);
        // 孤儿化不删条目行（副本内容随之保留，仍可作继承来源）
        verify(starRepo, never()).deleteBySetId(any());
    }

    @Test
    void deleteOrphanSet_仍被存档引用返回负2供控制层判409() {
        LorebookSet referenced = set(SET_ID, WORLD_ID, GS_ID); // gameStateId != null
        when(setRepo.findById(SET_ID)).thenReturn(referenced);

        assertThat(service.deleteOrphanSet(SET_ID)).isEqualTo(-2);
        verify(setRepo, never()).deleteOrphan(any());
        verify(starRepo, never()).deleteBySetId(any());
    }

    @Test
    void deleteOrphanSet_孤儿删除级联清双轨条目行() {
        LorebookSet orphan = set(SET_ID, WORLD_ID, null); // gameStateId == null = 孤儿
        when(setRepo.findById(SET_ID)).thenReturn(orphan);
        when(setRepo.deleteOrphan(SET_ID)).thenReturn(1);

        int n = service.deleteOrphanSet(SET_ID);

        assertThat(n).isEqualTo(1);
        verify(starRepo).deleteBySetId(SET_ID);
        verify(entryRepo).deleteBySetId(SET_ID);
        verify(setRepo).deleteOrphan(SET_ID);
    }

    @Test
    void deleteOrphanSet_set不存在返回负1() {
        when(setRepo.findById("nope")).thenReturn(null);

        assertThat(service.deleteOrphanSet("nope")).isEqualTo(-1);
    }

    // ==================== 7. 预设人物档案物化挂载（rpg-lore-archival-loading tasks 1.2/5.1） ====================

    @Test
    void copyForNewGame_开局同步物化预设人物档案_claim转发() {
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(
                List.of(templateStar("inc-1", "天魁星", "织田信长")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        // 物化输入按「set 落库后重查」取新 set 条目（事务已提交，stub findBySetId）
        when(starRepo.findBySetId(any())).thenReturn(
                List.of(setStar("gen-1", "天魁星", "织田信长", SET_ID, "天魁星")));

        service.copyForNewGame(WORLD_ID, null, "gs-new", "天魁星");

        // set 落库同点物化：gsId/worldId/set 条目/claimed 星名/figureKey 五参数转发
        // （D1/D2；figureKey 缺省 null = 无名新人/信胜旧语义）
        verify(loreArchiveService).materializePresetArchives(
                eq("gs-new"), eq(WORLD_ID), any(), eq("天魁星"), eq(null));
    }

    @Test
    void copyForNewGame_非冥斗士开局_claimed转发null() {
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());

        service.copyForNewGame(WORLD_ID, null, "gs-new", null);

        verify(loreArchiveService).materializePresetArchives(
                eq("gs-new"), eq(WORLD_ID), any(), eq(null), eq(null));
    }

    @Test
    void ensureSet_懒迁移同点物化老存档补档() {
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(null);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(GS_ID, WORLD_ID));
        when(starRepo.findByWorldId(WORLD_ID)).thenReturn(
                List.of(templateStar("inc-tpl", "天魁星", "织田信长")));
        when(entryRepo.findByWorldId(WORLD_ID)).thenReturn(List.of());
        when(starRepo.findBySetId(any())).thenReturn(
                List.of(setStar("gen-1", "天魁星", "织田信长", SET_ID, "天魁星")));

        service.ensureSet(GS_ID);

        // 老存档兼容（D6）：懒迁移首物化的同点补档，claimed/figureKey 恒 null
        verify(loreArchiveService).materializePresetArchives(
                eq(GS_ID), eq(WORLD_ID), any(), eq(null), eq(null));
    }

    @Test
    void ensureSet_已有set直接返回不再物化() {
        LorebookSet existing = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(existing);

        service.ensureSet(GS_ID);

        verify(loreArchiveService, never())
                .materializePresetArchives(any(), any(), any(), any(), any());
    }

    @Test
    void saveStar_改名同步runtime与档案索引事务链() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(starRepo.findById("s-1")).thenReturn(setStar("s-1", "天魁星", "织田信长", SET_ID, "天魁星"));

        StarIncarnation payload = new StarIncarnation();
        payload.setId("s-1");
        payload.setStarName("天罡星");
        payload.setWarlordName("织田信长改");

        service.saveStar(GS_ID, payload);

        // D9 三同步链：runtime 星名 → 档案重命名 + 索引行迁移（同事务挂载）
        verify(starRuntimeRepo).renameStar(GS_ID, "天魁星", "天罡星");
        verify(loreArchiveService).renameStarArchive(GS_ID, "天魁星", "天罡星", "织田信长改");
    }

    @Test
    void saveStar_未改名不触发档案同步() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(starRepo.findById("s-1")).thenReturn(setStar("s-1", "天魁星", "织田信长", SET_ID, "天魁星"));

        StarIncarnation payload = new StarIncarnation();
        payload.setId("s-1");
        payload.setStarName("天魁星");
        payload.setWarlordName("织田信长");

        service.saveStar(GS_ID, payload);

        verify(loreArchiveService, never()).renameStarArchive(any(), any(), any(), any());
    }

    // ==================== 分类规范化（add-star-lorebook-category D5 / tasks 6.4） ====================

    @Test
    void saveStar_分类trim后随行落库() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(starRepo.findById("s-1")).thenReturn(setStar("s-1", "天魁星", "织田信长", SET_ID, "天魁星"));

        StarIncarnation payload = new StarIncarnation();
        payload.setId("s-1");
        payload.setStarName("天魁星");
        payload.setWarlordName("织田信长");
        payload.setCategory("  织田家  ");

        StarIncarnation saved = service.saveStar(GS_ID, payload);

        // 正常保存回读：trim 后随行落库（返回值与 update 入参两条路径）
        assertThat(saved.getCategory()).isEqualTo("织田家");
        ArgumentCaptor<StarIncarnation> cap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).update(cap.capture());
        assertThat(cap.getValue().getCategory()).isEqualTo("织田家");
    }

    @Test
    void saveStar_分类空白归NULL落库() {
        LorebookSet set = set(SET_ID, WORLD_ID, GS_ID);
        when(setRepo.findByGameStateId(GS_ID)).thenReturn(set);
        when(starRepo.findById("s-1")).thenReturn(setStar("s-1", "天魁星", "织田信长", SET_ID, "天魁星"));

        StarIncarnation payload = new StarIncarnation();
        payload.setId("s-1");
        payload.setStarName("天魁星");
        payload.setWarlordName("织田信长");
        payload.setCategory("   ");

        StarIncarnation saved = service.saveStar(GS_ID, payload);

        // 纯空白分类 = 未分类（落 NULL），列表/注入块不出现标题行
        assertThat(saved.getCategory()).isNull();
        ArgumentCaptor<StarIncarnation> cap = ArgumentCaptor.forClass(StarIncarnation.class);
        verify(starRepo).update(cap.capture());
        assertThat(cap.getValue().getCategory()).isNull();
    }

    @Test
    void saveStar_分类超20字符先拒绝且零写入() {
        StarIncarnation payload = new StarIncarnation();
        payload.setId("s-1");
        payload.setStarName("天魁星");
        payload.setWarlordName("织田信长");
        payload.setCategory("一二三四五六七八九十一二三四五六七八九十一");   // 21 字符

        // 先校验后物化（D5）：入口即抛 IllegalArgumentException（控制层转 400），
        // ensureSet/setRepo 均未触碰（不白白物化 set）
        assertThatThrownBy(() -> service.saveStar(GS_ID, payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分类");
        verifyNoInteractions(setRepo);
        verify(starRepo, never()).update(any());
        verify(starRepo, never()).save(any());
    }

    // ==================== 构造辅助 ====================

    private static GameState gameState(String id, String worldId) {
        GameState gs = new GameState();
        gs.setId(id);
        gs.setWorldId(worldId);
        return gs;
    }

    private static LorebookSet set(String id, String worldId, String gameStateId) {
        LorebookSet s = new LorebookSet();
        s.setId(id);
        s.setWorldId(worldId);
        s.setName("世界默认");
        s.setOrigin("世界默认");
        s.setGameStateId(gameStateId);
        return s;
    }

    /** 世界模板行：set_id/source_ref 皆 null */
    private static StarIncarnation templateStar(String id, String starName, String warlord) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId(id);
        inc.setWorldId(WORLD_ID);
        inc.setStarName(starName);
        inc.setWarlordName(warlord);
        inc.setLore("命定叙事");
        inc.setRollable(true);
        inc.setSortOrder(0);
        return inc;
    }

    /** set 副本行：带 set 归属与 source_ref 稳定键 */
    private static StarIncarnation setStar(String id, String starName, String warlord,
                                           String setId, String sourceRef) {
        StarIncarnation inc = templateStar(id, starName, warlord);
        inc.setSetId(setId);
        inc.setSourceRef(sourceRef);
        return inc;
    }

    private static WorldbookEntry templateEntry(String id, String title) {
        WorldbookEntry e = new WorldbookEntry();
        e.setId(id);
        e.setWorldId(WORLD_ID);
        e.setTitle(title);
        e.setKeywords(title);
        e.setContent("设定正文");
        e.setEnabled(true);
        e.setSortOrder(0);
        return e;
    }

    private static WorldbookEntry setEntry(String id, String title, String setId, String sourceRef) {
        WorldbookEntry e = templateEntry(id, title);
        e.setSetId(setId);
        e.setSourceRef(sourceRef);
        return e;
    }
}
