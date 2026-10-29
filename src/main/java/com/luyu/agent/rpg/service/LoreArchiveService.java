package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.service.MemoryFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 预设世界人物档案柜服务（rpg-lore-archival-loading design D1-D3 / D10）。
 * <p>
 * 把预设世界人物从注入通道迁到记忆通道，与即兴人物共用同一个存档档案柜
 * （两级模式：档案文件 + {@code MEMORY.md} 索引）：
 * <ul>
 *   <li><b>开局物化</b>（{@link #materializePresetArchives}）：set 物化的同一时点，
 *       将冥星武将录 set 条目复制为 {@code lore_<星名>.md}（含开局抢星 claimed 变体段）、
 *       世界全部 NPC 角色卡复制为 {@code npc_<cardId>.md}，frontmatter {@code type: lore}。
 *       文件落盘但<b>刻意不写索引行</b>——物化不等于可见，索引可见性 = 激活边界
 *       （剧透边界：沉睡星不得被目录剧透）。</li>
 *   <li><b>激活索引</b>（{@link #appendRevealedIndexLine}）：关键词命中/抢星命中时向
 *       {@code MEMORY.md} 追加一行索引（claimed 条目带【星归玩家】标记——GM 必须知道
 *       玩家持有什么星，否则抢星叙事崩坏）。</li>
 *   <li><b>改名同步</b>（{@link #renameStarArchive}）：set 内武将星名改名时同步重命名
 *       档案文件并更新索引行，避免索引指向悬空文件。</li>
 * </ul>
 * 容错哲学：档案与索引是增强，单文件 IO 失败一律降级告警跳过，绝不阻断开局/回合/编辑主流程。
 * <p>
 * 文件名前缀与即兴记忆天然共用（{@code npc_<cardId>.md} 即 GmMemoryCreate 的即兴建档名、
 * {@code lore_} 即 world_lore 的主题命名）：同一 NPC 单档案——开局物化先落盘，
 * GM 后续演化走 {@code GmMemoryStrReplace} 在其上沉淀（档案是存档内真源）。
 */
@Service
public class LoreArchiveService {

    private static final Logger log = LoggerFactory.getLogger(LoreArchiveService.class);

    /** 预设世界静态人物档案类型（区别于即兴 npc_memory 与即兴世界细节 world_lore） */
    public static final String TYPE_LORE = "lore";

    /** 索引行 claimed 四字标记（design D4：留在索引行而非档案正文常驻） */
    public static final String CLAIMED_MARKER = "【星归玩家】";

    /** 索引行人物扮演标记（preset-destined-figures D3）：人即玩家——该武将即玩家本人 */
    public static final String FIGURE_MARKER = "【人即玩家】";

    private final RpgCharacterCardRepository charRepo;
    private final RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    /** 固定根注入（测试用）；null = 按当前请求租户解析（与 SaveDeletionService 同模式） */
    private final Path savesRootOverride;

    @Autowired
    public LoreArchiveService(RpgCharacterCardRepository charRepo,
                              RpgStarIncarnationRuntimeRepository starRuntimeRepo) {
        this(charRepo, starRuntimeRepo, null);
    }

    /** 测试用构造器：替换存档记忆根目录（null 则按请求租户解析） */
    LoreArchiveService(RpgCharacterCardRepository charRepo,
                       RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                       Path savesRootOverride) {
        this.charRepo = charRepo;
        this.starRuntimeRepo = starRuntimeRepo;
        this.savesRootOverride = savesRootOverride;
    }

    // ==================== 开局物化（快照语义，不写索引） ====================

    /**
     * 物化预设人物档案：set 武将条目 → {@code lore_<星名>.md}、世界 NPC 角色卡 →
     * {@code npc_<cardId>.md}。文件已存在则跳过（开局快照：不覆盖 GM 已演化的档案、幂等）。
     * <p>
     * claimedStarName 命中条件与 {@code WorkshopService#claimStarIncarnationIfHit} 一致
     * （rollable + 星名相等）：档案变体段、runtime claimed 行、索引行标记三者天然同步。
     * 工坊后续编辑不回灌已物化档案（快照语义；增量人物走 GM 即兴建档）。
     */
    public void materializePresetArchives(String gameStateId, String worldId,
                                          List<StarIncarnation> setStars, String claimedStarName) {
        materializePresetArchives(gameStateId, worldId, setStars, claimedStarName, null);
    }

    /**
     * @param figureKey 命定人物键（preset-destined-figures D3）：人物扮演局撞星时命中档案的
     *                  变体段渲染「人即玩家」双语义（该武将即玩家本人）；缺省 null 维持旧
     *                  「星归玩家」语义（无名新人/信胜撞星）。
     */
    public void materializePresetArchives(String gameStateId, String worldId,
                                          List<StarIncarnation> setStars,
                                          String claimedStarName, String figureKey) {
        try {
            MemoryFileStore store = storeFor(gameStateId);
            int warlordArchives = 0;
            for (StarIncarnation star : setStars) {
                if (star == null || star.getStarName() == null || star.getStarName().isBlank()) {
                    continue;
                }
                try {
                    String fileName = starArchiveFileName(star.getStarName());
                    if (store.exists(fileName)) {
                        continue;
                    }
                    boolean claimed = Boolean.TRUE.equals(star.getRollable())
                            && star.getStarName().equals(claimedStarName);
                    store.write(fileName, starDisplay(star.getStarName(), star.getWarlordName()),
                            "冥星武将录·命定归属档案", TYPE_LORE,
                            starArchiveBody(star, claimed, figureKey));
                    warlordArchives++;
                } catch (Exception e) {
                    log.warn("武将档案物化失败，跳过（不阻断）: gameStateId={}, star={}",
                            gameStateId, star.getStarName(), e);
                }
            }
            int npcArchives = 0;
            for (CharacterCard card : charRepo.findByWorldIdAndType(worldId, "npc")) {
                if (card == null || card.getId() == null || card.getId().isBlank()) {
                    continue;
                }
                try {
                    String fileName = npcArchiveFileName(card.getId());
                    if (store.exists(fileName)) {
                        continue;
                    }
                    store.write(fileName, card.getName() == null ? fileName : card.getName(),
                            npcDescription(card), TYPE_LORE, npcArchiveBody(card));
                    npcArchives++;
                } catch (Exception e) {
                    log.warn("角色卡档案物化失败，跳过（不阻断）: gameStateId={}, cardId={}",
                            gameStateId, card.getId(), e);
                }
            }
            if (warlordArchives > 0 || npcArchives > 0) {
                log.info("预设人物档案已物化: gameStateId={}, warlordArchives={}, npcArchives={}"
                                + "（索引零写入，激活边界=可见边界）",
                        gameStateId, warlordArchives, npcArchives);
            }
        } catch (Exception e) {
            log.warn("预设人物档案物化失败，整组降级跳过（不阻断）: gameStateId={}", gameStateId, e);
        }
    }

    // ==================== 激活索引行 ====================

    /**
     * 向 {@code MEMORY.md} 追加一行揭示索引（格式与 GmMemoryInsert 同轨，幂等）：
     * {@code - [lore_天魁星](lore_天魁星.md) — 天魁星·织田信长【星归玩家】 | 命定归属档案}。
     * <p>
     * 索引行是命中轮滑窗残留 + 世界背景回顾兜底层的信息载体（design D6 三层可见性）。
     * MEMORY.md 不存在时由 {@link MemoryFileStore#appendIndexLine} 创建（与 GM 建索引同轨）。
     * IO 异常降级告警，不阻断回合主流程（runtime 行照写，仅索引缺失）。
     */
    public void appendRevealedIndexLine(String gameStateId, String starName,
                                        String warlordName, boolean claimed) {
        appendRevealedIndexLine(gameStateId, starName, warlordName, claimed, null);
    }

    /**
     * @param figureKey 命定人物键（preset-destined-figures D3）：claimed 行非空时索引标记由
     *                  【星归玩家】切换为【人即玩家】（GM 一眼识别玩家=武将本人）；
     *                  缺省 null 维持旧标记。
     */
    public void appendRevealedIndexLine(String gameStateId, String starName,
                                        String warlordName, boolean claimed, String figureKey) {
        if (starName == null || starName.isBlank()) {
            return;
        }
        try {
            MemoryFileStore store = storeFor(gameStateId);
            String fileName = starArchiveFileName(starName);
            store.appendIndexLine(fileName, indexDescription(starName, warlordName, claimed, figureKey));
            log.info("人物档案索引行已追加: gameStateId={}, file={}, claimed={}",
                    gameStateId, fileName, claimed);
        } catch (Exception e) {
            log.warn("档案索引行追加失败，降级跳过（不阻断回合）: gameStateId={}, star={}",
                    gameStateId, starName, e);
        }
    }

    // ==================== 改名同步（design D9） ====================

    /**
     * set 内武将星名改名的档案同步：重命名 {@code lore_<旧>.md} → {@code lore_<新>.md}
     * （经读旧写新删旧，顺带更新 frontmatter name）、更新 MEMORY.md 索引行
     * （移除旧行后按 runtime 行的 claimed 标记补新行）。
     * <p>
     * 语义：已有索引行（已激活）→ 文件与索引双同步；无索引行（未激活/老存档）→
     * 仅重命名文件。档案缺失时索引行照常迁移（老存档兼容：激活过但未物化）。
     * IO 异常降级告警，不阻断编辑保存。
     */
    public void renameStarArchive(String gameStateId, String oldStarName,
                                  String newStarName, String warlordName) {
        if (oldStarName == null || newStarName == null
                || oldStarName.isBlank() || newStarName.isBlank()) {
            return;
        }
        try {
            MemoryFileStore store = storeFor(gameStateId);
            String oldFile = starArchiveFileName(oldStarName);
            String newFile = starArchiveFileName(newStarName);

            // 文件重命名（读旧写新删旧，保 scope/turn 等非管理字段并更新显示名）
            if (store.exists(oldFile)) {
                if (store.exists(newFile)) {
                    log.warn("目标档案已存在，跳过重命名以免覆盖: gameStateId={}, file={}",
                            gameStateId, newFile);
                } else {
                    Map<String, String> old = store.read(oldFile);
                    if (old != null) {
                        store.write(newFile, starDisplay(newStarName, warlordName),
                                old.getOrDefault("description", ""), TYPE_LORE, old.get("content"));
                        store.delete(oldFile);
                    }
                }
            }

            // 索引行迁移：有旧行才补新行（未激活条目不新增索引，守住剧透边界）
            if (store.removeIndexLine(oldFile)) {
                store.appendIndexLine(newFile,
                        indexDescription(newStarName, warlordName, isClaimed(gameStateId, newStarName)));
            }
            log.info("武将档案改名同步完成: gameStateId={}, {} -> {}", gameStateId, oldFile, newFile);
        } catch (Exception e) {
            log.warn("武将档案改名同步失败，降级告警（不阻断保存）: gameStateId={}, {} -> {}",
                    gameStateId, oldStarName, newStarName, e);
        }
    }

    /** runtime 行是否已 claimed（改名同步时 star_name 已更新为新名，按新名查） */
    private boolean isClaimed(String gameStateId, String starName) {
        for (StarIncarnationRuntime rt : starRuntimeRepo.findByGameStateId(gameStateId)) {
            if (starName.equals(rt.getStarName()) && Boolean.TRUE.equals(rt.getClaimedByPlayer())) {
                return true;
            }
        }
        return false;
    }

    // ==================== 文件命名（共享真源，注入块提示行同用） ====================

    /** 武将档案文件名：{@code lore_<星名>.md}（星名是跨物化稳定键，消毒后可由星名推导） */
    public static String starArchiveFileName(String starName) {
        return "lore_" + sanitizeSegment(starName) + ".md";
    }

    /** NPC 角色卡档案文件名：{@code npc_<cardId>.md}（cardId 即 npc_states key） */
    public static String npcArchiveFileName(String cardId) {
        return "npc_" + sanitizeSegment(cardId) + ".md";
    }

    /**
     * 文件名段消毒：替换 Windows/Unix 非法字符与控制符为 {@code _}。
     * 必须确定性（同名同结果）：改名同步与一行式提示都靠星名推导文件名。
     */
    private static String sanitizeSegment(String raw) {
        String s = raw == null ? "" : raw.strip().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        return s.isEmpty() ? "unnamed" : s;
    }

    // ==================== 档案正文（design D10） ====================

    /** 武将档案正文：命定归属 + claimed 变体段 + 受控别名（缺省人物键 = 旧「星归玩家」语义） */
    static String starArchiveBody(StarIncarnation star, boolean claimed) {
        return starArchiveBody(star, claimed, null);
    }

    /**
     * 武将档案正文（preset-destined-figures D3 双语义）：命定归属 + claimed 变体段 + 受控别名。
     * <p>
     * figureKey 非空 = 人物扮演局撞星：变体段渲染「人即玩家」（此局武将即玩家本人，
     * 星与人合一）；figureKey 空 = 无名新人/信胜撞星：维持「星归玩家」（武将失衣、
     * 命定轨迹改变）。两段均提示「命定归属」为旧叙事仅供对照。
     */
    static String starArchiveBody(StarIncarnation star, boolean claimed, String figureKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(starDisplay(star.getStarName(), star.getWarlordName())).append('\n');
        if (star.getLore() != null && !star.getLore().isBlank()) {
            sb.append("\n## 命定归属\n\n").append(star.getLore().strip()).append('\n');
        }
        if (claimed) {
            String warlord = star.getWarlordName() == null ? "该武将" : star.getWarlordName();
            if (figureKey != null && !figureKey.isBlank()) {
                sb.append("\n## 人即玩家\n\n")
                        .append("此局").append(warlord).append("即玩家本人——玩家以")
                        .append(warlord).append("的身份入局，星与人合一，「")
                        .append(star.getStarName()).append("」冥衣的持有者就是玩家自己。")
                        .append("上文的「命定归属」是史实视角的旧叙事，仅供对照，")
                        .append("勿把玩家演成失去冥衣的另一个").append(warlord).append("。\n");
            } else {
                sb.append("\n## 星归玩家\n\n")
                        .append("此局「").append(star.getStarName()).append("」冥衣已归玩家所有。")
                        .append(warlord)
                        .append("不再持有此冥衣，命定轨迹随之改变——")
                        .append("上文的「命定归属」是冥衣原主视角的旧叙事，仅供对照。\n");
            }
        }
        if (star.getAliases() != null && !star.getAliases().isBlank()) {
            sb.append("\n## 别名\n\n").append(star.getAliases().strip()).append('\n');
        }
        return sb.toString();
    }

    /** NPC 角色卡档案正文（对齐首轮 system prompt 的卡字段序；空字段跳节） */
    static String npcArchiveBody(CharacterCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(card.getName() == null ? card.getId() : card.getName()).append('\n');
        section(sb, "身份", card.getIdentity());
        section(sb, "性格", card.getPersonality());
        section(sb, "背景", card.getBackground());
        section(sb, "动机", card.getMotivation());
        section(sb, "说话风格", card.getSpeechStyle());
        section(sb, "知识", card.getKnowledge());
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("\n## ").append(title).append("\n\n").append(value.strip()).append('\n');
    }

    /** 显示名：{@code 星名·武将名}（武将名缺省仅星名） */
    private static String starDisplay(String starName, String warlordName) {
        if (warlordName == null || warlordName.isBlank()) {
            return starName;
        }
        return starName + "·" + warlordName;
    }

    /** 索引行 description：显示名 + claimed 标记 + 档案口径（一行，供 GM 一眼识别） */
    private static String indexDescription(String starName, String warlordName, boolean claimed) {
        return indexDescription(starName, warlordName, claimed, null);
    }

    /** claimed 标记双语义（preset-destined-figures D3）：figureKey 非空 → 【人即玩家】 */
    private static String indexDescription(String starName, String warlordName,
                                           boolean claimed, String figureKey) {
        String display = starDisplay(starName, warlordName);
        String marker = claimed
                ? (figureKey != null && !figureKey.isBlank() ? FIGURE_MARKER : CLAIMED_MARKER)
                : "";
        return display + marker + " | 命定归属档案";
    }

    /** NPC 档案 description：身份钩子（截断 50 字与 GM 规范一致），缺省回退 */
    private static String npcDescription(CharacterCard card) {
        if (card.getIdentity() == null || card.getIdentity().isBlank()) {
            return "预设角色卡档案（NPC）";
        }
        String identity = card.getIdentity().replace("\r", " ").replace("\n", " ").strip();
        return identity.length() > 50 ? identity.substring(0, 50) + "…" : identity;
    }

    // ==================== 底座 ====================

    /** 存档根：固定注入优先，否则按当前请求租户解析（{@code tenants/<tid>/rpg-saves}） */
    private Path saveDir(String gameStateId) {
        return savesRootOverride != null
                ? savesRootOverride.resolve(RpgSavePaths.requireSafeGameStateId(gameStateId))
                : RpgSavePaths.saveDir(gameStateId);
    }

    /** 存档记忆存储（包级可见以便单测替换，模拟 IO 失败） */
    MemoryFileStore storeFor(String gameStateId) {
        return new MemoryFileStore(saveDir(gameStateId));
    }
}
