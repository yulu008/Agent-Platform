package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.service.MemoryFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * {@link LoreArchiveService} 单元测试（rpg-lore-archival-loading tasks 1.4 / 2.5 / 5.2）。
 * <p>
 * 钉住四组行为：
 * <ol>
 *   <li><b>开局物化</b>：武将/角色卡档案文件名、frontmatter {@code type: lore}、正文结构、
 *       claimed 变体段、单文件 IO 降级、文件已存在跳过（快照保护）；</li>
 *   <li><b>剧透边界</b>（design D3）：物化只落档案文件，<b>零写</b> MEMORY.md；</li>
 *   <li><b>激活索引</b>：索引行格式与 claimed 标记、MEMORY.md 缺失时创建、
 *       幂等（重复追加不重复行）、追加 IO 异常降级不抛出；</li>
 *   <li><b>改名同步</b>（design D9）：已激活三同步（runtime claimed 决定新索引行标记）、
 *       未激活仅重命名文件不新增索引、档案缺失时索引行照常迁移。</li>
 * </ol>
 */
class LoreArchiveServiceTest {

    private static final String GS_ID = "gs-1";
    private static final String WORLD_ID = "w1";

    private RpgCharacterCardRepository charRepo;
    private RpgStarIncarnationRuntimeRepository starRuntimeRepo;

    @TempDir
    Path savesRoot;

    @BeforeEach
    void setUp() {
        charRepo = mock(RpgCharacterCardRepository.class);
        starRuntimeRepo = mock(RpgStarIncarnationRuntimeRepository.class);
        when(charRepo.findByWorldIdAndType(WORLD_ID, "npc")).thenReturn(List.of());
        when(starRuntimeRepo.findByGameStateId(GS_ID)).thenReturn(List.of());
    }

    private LoreArchiveService newService() {
        return new LoreArchiveService(charRepo, starRuntimeRepo, savesRoot);
    }

    private Path saveDir() {
        return savesRoot.resolve(GS_ID);
    }

    private String readArchive(String fileName) throws IOException {
        return Files.readString(saveDir().resolve(fileName));
    }

    // ==================== 开局物化（tasks 1.4 / design D1-D3、D10） ====================

    @Test
    void 武将档案_文件名frontmatter与正文结构() throws IOException {
        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "第六天魔王的命定叙事", "魔王,第六天魔王", true)), null);

        String raw = readArchive("lore_天魁星.md");
        assertThat(raw).startsWith("---\n");
        assertThat(raw).contains("name: \"天魁星·织田信长\"");
        assertThat(raw).contains("type: \"lore\"");
        assertThat(raw).contains("冥星武将录·命定归属档案");
        // 正文结构：标题 + 命定归属 + 别名
        assertThat(raw).contains("# 天魁星·织田信长");
        assertThat(raw).contains("## 命定归属").contains("第六天魔王的命定叙事");
        assertThat(raw).contains("## 别名").contains("魔王,第六天魔王");
        // 未命中 claimed：无变体段
        assertThat(raw).doesNotContain("## 星归玩家");
    }

    @Test
    void claimed命中_写星归玩家变体段_未命中与NPC专属星不写() throws IOException {
        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true),
                        star("天兽星", "前田庆次", "叙事", null, false)), "天魁星");

        assertThat(readArchive("lore_天魁星.md")).contains("## 星归玩家")
                .contains("已归玩家所有").contains("不再持有");
        // rollable=false 的 NPC 专属星：constellation 撞上也不写变体段（与抢星判定双保险一致）
        assertThat(readArchive("lore_天兽星.md")).doesNotContain("## 星归玩家");
    }

    // ==================== 变体段双语义（preset-destined-figures D3 / tasks 5.5） ====================

    @Test
    void 人物局命中_写人即玩家变体段与索引标记() throws IOException {
        // 信长局：figureKey 透传物化侧，命中档案的变体段与索引标记切换「人即玩家」
        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true)), "天魁星", "nobunaga");

        assertThat(readArchive("lore_天魁星.md"))
                .contains("## 人即玩家")
                .contains("此局织田信长即玩家本人")
                .contains("勿把玩家演成失去冥衣的另一个织田信长")
                .doesNotContain("## 星归玩家");

        // 索引行标记同步切换（【人即玩家】——GM 一眼识别玩家=武将本人）
        newService().appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", true, "nobunaga");
        assertThat(readArchive("MEMORY.md"))
                .contains("天魁星·织田信长【人即玩家】 | 命定归属档案")
                .doesNotContain("【星归玩家】");
    }

    @Test
    void 人物键缺省_维持星归玩家变体段与索引标记() throws IOException {
        // 无名新人/信胜撞星：figureKey 为 null（或四参重载）维持旧「星归玩家」语义
        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true)), "天魁星", null);

        assertThat(readArchive("lore_天魁星.md")).contains("## 星归玩家")
                .doesNotContain("## 人即玩家");

        newService().appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", true);
        assertThat(readArchive("MEMORY.md"))
                .contains("天魁星·织田信长【星归玩家】 | 命定归属档案")
                .doesNotContain("【人即玩家】");
    }

    @Test
    void 人物局未撞星_figureKey不产生任何变体段() throws IOException {
        // 防御性：figureKey 透传但星名不匹配（人物局实际必撞预绑星，双保险同 claimed 判定）
        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true)), null, "nobunaga");

        assertThat(readArchive("lore_天魁星.md"))
                .doesNotContain("## 人即玩家")
                .doesNotContain("## 星归玩家");
    }

    @Test
    void 角色卡档案_正文结构对齐卡字段_空字段跳节() throws IOException {
        CharacterCard card = new CharacterCard();
        card.setId("card-9");
        card.setName("林掌柜");
        card.setIdentity("当铺老板，记得 PC 典当过一枚家传玉佩");
        card.setSpeechStyle("慢条斯理");
        // personality/background/motivation/knowledge 全空 → 跳节
        when(charRepo.findByWorldIdAndType(WORLD_ID, "npc")).thenReturn(List.of(card));

        newService().materializePresetArchives(GS_ID, WORLD_ID, List.of(), null);

        String raw = readArchive("npc_card-9.md");
        assertThat(raw).contains("name: \"林掌柜\"").contains("type: \"lore\"");
        // description 钩子取 identity
        assertThat(raw).contains("当铺老板，记得 PC 典当过一枚家传玉佩");
        assertThat(raw).contains("# 林掌柜").contains("## 身份").contains("## 说话风格");
        assertThat(raw).doesNotContain("## 性格").doesNotContain("## 背景")
                .doesNotContain("## 动机").doesNotContain("## 知识");
    }

    @Test
    void 物化零写MEMORYmd_剧透边界_索引可见性等于激活边界() throws IOException {
        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true)), null);

        assertThat(Files.isRegularFile(saveDir().resolve("lore_天魁星.md"))).isTrue();
        assertThat(Files.isRegularFile(saveDir().resolve("MEMORY.md"))).isFalse();
    }

    @Test
    void 档案已存在跳过_保护GM演化沉淀() throws IOException {
        Files.createDirectories(saveDir());
        Files.writeString(saveDir().resolve("lore_天魁星.md"),
                "---\nname: \"GM 改过的档案\"\ntype: \"lore\"\n---\n已失冥衣");

        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true)), "天魁星");

        // 二次物化不覆盖：GM 演化内容原样保留，claimed 变体段也不回写
        assertThat(readArchive("lore_天魁星.md")).contains("已失冥衣").doesNotContain("## 星归玩家");
    }

    @Test
    void 星名含非法字符_文件名消毒且可由星名推导() throws IOException {
        newService().materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天:魁*星", "织田信长", "叙事", null, true)), null);

        assertThat(Files.isRegularFile(saveDir().resolve("lore_天_魁_星.md"))).isTrue();
        // 推导确定性：一行式提示与索引行共用同一文件名函数
        assertThat(LoreArchiveService.starArchiveFileName("天:魁*星")).isEqualTo("lore_天_魁_星.md");
    }

    @Test
    void 单文件IO失败_降级跳过不阻断其余物化() throws IOException {
        // storeFor 每次物化仅调一次、逐文件共用同一 store：用 spy 让首条 write 抛 IO、
        // 其余条目经 per-file catch 降级后仍走真实落盘
        Path gsDir = savesRoot.resolve("gs-2");
        MemoryFileStore flaky = spy(new MemoryFileStore(gsDir));
        doThrow(new IOException("盘符被占用")).doCallRealMethod().when(flaky)
                .write(anyString(), anyString(), anyString(), anyString(), anyString());
        LoreArchiveService service = new LoreArchiveService(charRepo, starRuntimeRepo, savesRoot) {
            @Override
            MemoryFileStore storeFor(String gameStateId) {
                return flaky;
            }
        };

        service.materializePresetArchives("gs-2", WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true),
                        star("天罡星", "关羽", "叙事", null, true)), null);

        // 首条（天魁星）失败跳过零文件；次条（天罡星）照常落盘（降级不阻断）
        assertThat(Files.isRegularFile(gsDir.resolve("lore_天魁星.md"))).isFalse();
        assertThat(Files.isRegularFile(gsDir.resolve("lore_天罡星.md"))).isTrue();
    }

    @Test
    void 物化IO整组失败_静默降级不抛出() {
        LoreArchiveService broken = new LoreArchiveService(charRepo, starRuntimeRepo, null) {
            @Override
            MemoryFileStore storeFor(String gameStateId) {
                throw new IllegalStateException("租户上下文缺失");
            }
        };

        assertThatCode(() -> broken.materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true)), null))
                .doesNotThrowAnyException();
    }

    // ==================== 激活索引行（tasks 2.5 / design D4） ====================

    @Test
    void 命中追加索引行_格式与claimed标记_MEMORYmd缺失时创建() throws IOException {
        newService().appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", false);

        String index = readArchive("MEMORY.md");
        assertThat(index).contains("- [lore_天魁星](lore_天魁星.md) — 天魁星·织田信长 | 命定归属档案");
        assertThat(index).doesNotContain("【星归玩家】");

        newService().appendRevealedIndexLine(GS_ID, "天兽星", "前田庆次", true);
        assertThat(readArchive("MEMORY.md"))
                .contains("- [lore_天兽星](lore_天兽星.md) — 天兽星·前田庆次【星归玩家】 | 命定归属档案");
    }

    @Test
    void 重复追加幂等_不重复行() throws IOException {
        LoreArchiveService service = newService();
        service.appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", false);
        service.appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", false);

        long occurrences = Files.readAllLines(saveDir().resolve("MEMORY.md")).stream()
                .filter(line -> line.contains("lore_天魁星.md")).count();
        assertThat(occurrences).isEqualTo(1);
    }

    @Test
    void 索引追加IO异常_降级不抛出() {
        LoreArchiveService broken = new LoreArchiveService(charRepo, starRuntimeRepo, savesRoot) {
            @Override
            MemoryFileStore storeFor(String gameStateId) {
                MemoryFileStore store = mock(MemoryFileStore.class);
                try {
                    doThrow(new IOException("只读卷")).when(store).appendIndexLine(anyString(), anyString());
                } catch (IOException ignored) {
                    // mock 声明性抛出，不会真实发生
                }
                return store;
            }
        };

        assertThatCode(() -> broken.appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", false))
                .doesNotThrowAnyException();
    }

    // ==================== 改名同步（tasks 5.2 / design D9） ====================

    private void materializeAndIndex(LoreArchiveService service) throws IOException {
        service.materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "旧叙事", "魔王", true)), null);
        service.appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", false);
    }

    @Test
    void 已激活改名_文件重命名frontmatter更新_索引行迁移() throws IOException {
        LoreArchiveService service = newService();
        materializeAndIndex(service);

        service.renameStarArchive(GS_ID, "天魁星", "天罡星", "织田信长");

        // 档案重命名 + frontmatter 显示名更新，正文保留
        assertThat(Files.notExists(saveDir().resolve("lore_天魁星.md"))).isTrue();
        String renamed = readArchive("lore_天罡星.md");
        assertThat(renamed).contains("name: \"天罡星·织田信长\"").contains("旧叙事");
        // 索引行迁移：旧行消失、新行出现（无 runtime claimed → 无标记）
        String index = readArchive("MEMORY.md");
        assertThat(index).doesNotContain("lore_天魁星.md");
        assertThat(index).contains("- [lore_天罡星](lore_天罡星.md) — 天罡星·织田信长 | 命定归属档案");
    }

    @Test
    void 已激活且claimed改名_新索引行带星归玩家标记() throws IOException {
        LoreArchiveService service = newService();
        materializeAndIndex(service);
        // runtime 行已改名同步为新星名且 claimed（saveStar 事务内的调用时序）
        when(starRuntimeRepo.findByGameStateId(GS_ID)).thenReturn(List.of(runtime("天罡星", true)));

        service.renameStarArchive(GS_ID, "天魁星", "天罡星", "织田信长");

        assertThat(readArchive("MEMORY.md"))
                .contains("天罡星·织田信长【星归玩家】 | 命定归属档案");
    }

    @Test
    void 未激活改名_仅重命名档案_不新增索引行守住剧透边界() throws IOException {
        LoreArchiveService service = newService();
        service.materializePresetArchives(GS_ID, WORLD_ID,
                List.of(star("天魁星", "织田信长", "叙事", null, true)), null);

        service.renameStarArchive(GS_ID, "天魁星", "天罡星", "织田信长");

        assertThat(Files.isRegularFile(saveDir().resolve("lore_天罡星.md"))).isTrue();
        assertThat(Files.notExists(saveDir().resolve("lore_天魁星.md"))).isTrue();
        // 无旧索引行 → 不追加新行（索引可见性 = 激活边界）
        assertThat(Files.notExists(saveDir().resolve("MEMORY.md"))).isTrue();
    }

    @Test
    void 老存档档案缺失_既有索引行照常迁移() throws IOException {
        LoreArchiveService service = newService();
        // 老存档：激活过（有索引行）但从未物化档案
        service.appendRevealedIndexLine(GS_ID, "天魁星", "织田信长", false);

        service.renameStarArchive(GS_ID, "天魁星", "天罡星", "织田信长");

        String index = readArchive("MEMORY.md");
        assertThat(index).doesNotContain("lore_天魁星.md");
        assertThat(index).contains("- [lore_天罡星](lore_天罡星.md)");
    }

    @Test
    void 改名IO异常_降级告警不阻断() {
        LoreArchiveService broken = new LoreArchiveService(charRepo, starRuntimeRepo, savesRoot) {
            @Override
            MemoryFileStore storeFor(String gameStateId) {
                MemoryFileStore store = mock(MemoryFileStore.class);
                try {
                    when(store.removeIndexLine(anyString())).thenReturn(true);
                    doThrow(new IOException("文件被占用")).when(store).appendIndexLine(anyString(), anyString());
                } catch (IOException ignored) {
                    // mock 声明性抛出，不会真实发生
                }
                return store;
            }
        };

        assertThatCode(() -> broken.renameStarArchive(GS_ID, "天魁星", "天罡星", "织田信长"))
                .doesNotThrowAnyException();
    }

    // ==================== 构造辅助 ====================

    private static StarIncarnation star(String starName, String warlord, String lore,
                                         String aliases, boolean rollable) {
        StarIncarnation inc = new StarIncarnation();
        inc.setId("inc-" + starName);
        inc.setWorldId(WORLD_ID);
        inc.setStarName(starName);
        inc.setWarlordName(warlord);
        inc.setLore(lore);
        inc.setAliases(aliases);
        inc.setRollable(rollable);
        return inc;
    }

    private static StarIncarnationRuntime runtime(String starName, boolean claimed) {
        StarIncarnationRuntime rt = new StarIncarnationRuntime();
        rt.setId(UUID.randomUUID().toString());
        rt.setGameStateId(GS_ID);
        rt.setStarName(starName);
        rt.setClaimedByPlayer(claimed);
        return rt;
    }
}
