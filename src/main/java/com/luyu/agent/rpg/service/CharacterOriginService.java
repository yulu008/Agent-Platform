package com.luyu.agent.rpg.service;

import com.luyu.agent.rpg.config.RpgTechniqueProperties;
import com.luyu.agent.rpg.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 星命角色起源计算引擎。
 * <p>
 * 纯 Java 确定性计算，不依赖 LLM。根据公历生日、出生时辰（十二地支）、
 * 阵营偏好，计算星座/星辰归属和属性加成。
 * <p>
 * 三阵营映射：
 * <ul>
 *   <li>圣斗士：三层漏斗（生日→季节组→星阶→组内星座），88 星座</li>
 *   <li>冥斗士：年柱天干×日柱地支交叉定位，108 冥星</li>
 *   <li>神斗士：出生月份直接分配，7 北斗主星</li>
 * </ul>
 */
@Service
public class CharacterOriginService {

    private static final Logger log = LoggerFactory.getLogger(CharacterOriginService.class);

    /** 招式发放配置（rpg-technique-tree-data D8）；无参构造仅供单测，运行时走 Spring 注入 */
    private final RpgTechniqueProperties techniqueProps;

    public CharacterOriginService() {
        this(new RpgTechniqueProperties());
    }

    @Autowired
    public CharacterOriginService(RpgTechniqueProperties techniqueProps) {
        this.techniqueProps = techniqueProps;
    }

    // ==================== 小宇宙起始区间表（rpg-cosmo-sense design D2）====================
    // 星阶组 → [下界, 上界]。表驱动：三巨头/海斗士星阶当前 roll 不到，预留注册即可，
    // 阵营后加时零改码。未收录星阶（Bronze/Hidden/Specter-DiSha）落 DEFAULT_COSMO_RANGE。
    /** 黄金组：第七感带内 roll（创角上限 700） */
    private static final int[] COSMO_RANGE_GOLD = {301, 700};
    /** 天罡/神斗士/海斗士：第六感高位 */
    private static final int[] COSMO_RANGE_ELITE = {250, 300};
    /** 白银：第六感中位 */
    private static final int[] COSMO_RANGE_SILVER = {150, 300};
    /** 其余（青铜/隐藏/地煞）：第六感全域随机 */
    private static final int[] COSMO_RANGE_DEFAULT = {1, 300};

    private static final Map<String, int[]> COSMO_START_RANGE = Map.of(
            ConstellationMap.GOLD, COSMO_RANGE_GOLD,
            "Specter-ThreeGreats", COSMO_RANGE_GOLD,
            SpecterStarMap.TIAN_GANG, COSMO_RANGE_ELITE,
            "GodWarrior", COSMO_RANGE_ELITE,
            "Marine", COSMO_RANGE_ELITE,
            ConstellationMap.SILVER, COSMO_RANGE_SILVER
    );

    // ==================== 时辰 → 星阶映射（圣斗士 Layer 2）====================
    // 时辰索引：子=0 丑=1 寅=2 卯=3 辰=4 巳=5 午=6 未=7 申=8 酉=9 戌=10 亥=11

    /**
     * 根据出生时辰返回星阶（Gold/Silver/Bronze/Hidden）。
     * <p>
     * 辰巳午未（日间）→ Gold，寅卯申酉（晨昏）→ Silver，
     * 子丑戌（深夜/夜晚）→ Bronze，亥时 → Hidden（凤凰触发）。
     */
    private String timeIndexToRank(int timeIndex) {
        return switch (timeIndex) {
            case 4, 5, 6, 7 -> ConstellationMap.GOLD;      // 辰巳午未
            case 2, 3, 8, 9 -> ConstellationMap.SILVER;     // 寅卯申酉
            case 0, 1, 10  -> ConstellationMap.BRONZE;      // 子丑戌
            case 11        -> ConstellationMap.HIDDEN;      // 亥→凤凰
            default         -> ConstellationMap.BRONZE;
        };
    }

    /**
     * 根据时辰判断上午/下午（Silver 层使用）。
     * 寅卯辰巳（上午）→ 0，申酉戌（下午）→ 1。
     */
    private int timeIndexToSilverSub(int timeIndex) {
        return (timeIndex >= 2 && timeIndex <= 5) ? 0 : 1;
    }

    // ==================== 公共入口 ====================

    /**
     * 计算星命。
     *
     * @param name     玩家姓名
     * @param gender   性别（male/female），不影响星命，仅影响叙事人称
     * @param birthDate 公历生日
     * @param timeIndex 出生时辰索引（0=子时 ... 11=亥时）
     * @param faction  阵营（saint/specter/godwarrior）
     * @return 星命计算结果
     */
    public OriginResult calculate(String name, String gender,
                                  LocalDate birthDate, int timeIndex, String faction) {
        return calculate(name, gender, birthDate, timeIndex, faction, 0);
    }

    /**
     * 计算星命（带小宇宙重roll 种子）。
     * <p>
     * rollSeed 参与小宇宙初值掷骰（design D1）：同一生辰五要素 + 同 seed 两遍计算必然同值，
     * 重roll = seed+1。星阶/星座/特质等其余星命结果不受 seed 影响（确定性）。
     *
     * @param rollSeed 小宇宙掷骰种子（缺失/负数由调用方回落 0）
     */
    public OriginResult calculate(String name, String gender,
                                  LocalDate birthDate, int timeIndex, String faction, int rollSeed) {
        log.info("星命计算: name={}, birthDate={}, timeIndex={}, faction={}, rollSeed={}",
                name, birthDate, timeIndex, faction, rollSeed);

        OriginResult result = new OriginResult();
        result.setFaction(faction);

        switch (faction) {
            case "saint" -> calculateSaint(result, birthDate, timeIndex);
            case "specter" -> calculateSpecter(result, birthDate);
            case "godwarrior" -> calculateGodWarrior(result, birthDate);
            default -> {
                log.warn("未知阵营 {}，回退到 saint", faction);
                calculateSaint(result, birthDate, timeIndex);
            }
        }

        // 生成角色卡富化建议
        enrichCharacterCard(result, name, gender, birthDate, timeIndex);

        // 小宇宙初值掷骰（星阶确定后，按星阶组区间映射）
        result.setCosmoIntensity(
                rollCosmoIntensity(name, birthDate, timeIndex, faction, result.getStarRank(), rollSeed));

        log.info("星命计算完成: constellation={}, starRank={}, bonus={}, cosmo={}",
                result.getConstellation(), result.getStarRank(), result.getStarBonus(),
                result.getCosmoIntensity());
        return result;
    }

    /**
     * 计算星命（命定人物模式，preset-destined-figures design D2）。
     * <p>
     * figureKey 处理矩阵：
     * <ul>
     *   <li>figure == null → 转发现行重载，无名新人缺省路径零改动；</li>
     *   <li>预绑星命（档案 faction 非空：kagetora/harunobu/nobunaga）→ 跳过漏斗，
     *       faction/constellation/starRank 取档案，特质按星座名查静态映射；</li>
     *   <li>自由推演（档案 faction 为空：nobukatsu）→ 现行漏斗照常，三阵营任选；</li>
     *   <li>姓名一律覆盖为档案人物名（星命与掷骰不掺用户输入，与前端锁定一致）；</li>
     *   <li>小宇宙按玩家生辰 + 锁定星阶区间掷骰（保留推演仪式感）；</li>
     *   <li>三件套底稿预填档案 identity/personality/background（档案缺失时降级现行富化建议）。</li>
     * </ul>
     * 关键收益：nobunaga 的预绑产出（specter + 天魁星）让 startGame 侧既有
     * {@code claimStarIncarnationIfHit} 零改动自动 claim 天魁星——预绑冥衣就是
     * 一次被人物钉死的推演结果。
     *
     * @param figure 命定人物档案（null = 无名新人）
     */
    public OriginResult calculate(String name, String gender,
                                  LocalDate birthDate, int timeIndex, String faction, int rollSeed,
                                  DestinedFigure figure) {
        if (figure == null) {
            return calculate(name, gender, birthDate, timeIndex, faction, rollSeed);
        }
        String figureName = figure.getDisplayName();
        log.info("星命计算(命定人物): figureKey={}, name={}, birthDate={}, timeIndex={}, faction={}, rollSeed={}",
                figure.getFigureKey(), figureName, birthDate, timeIndex, faction, rollSeed);

        boolean bound = figure.getFaction() != null;
        String effectiveFaction = bound ? figure.getFaction() : faction;

        OriginResult result = new OriginResult();
        result.setFaction(effectiveFaction);

        if (bound) {
            applyBoundStarDestiny(result, figure);
        } else {
            // 自由推演（信胜）：现行漏斗照常，三阵营任选
            switch (effectiveFaction) {
                case "saint" -> calculateSaint(result, birthDate, timeIndex);
                case "specter" -> calculateSpecter(result, birthDate);
                case "godwarrior" -> calculateGodWarrior(result, birthDate);
                default -> {
                    log.warn("未知阵营 {}，回退到 saint", effectiveFaction);
                    calculateSaint(result, birthDate, timeIndex);
                }
            }
        }

        // 人物身份落档：playerStates / GM 上下文 / 开场白均以这三字段感知“玩家是谁”
        result.setFigureKey(figure.getFigureKey());
        result.setFigureDisplayName(figureName);
        result.setFigureEraNote(figure.getEraNote());

        // 招式注入照常（按锁定星座/星阶走既有分档）；三件套底稿覆盖为人物档案
        enrichCharacterCard(result, figureName, gender, birthDate, timeIndex);
        if (figure.getIdentity() != null) {
            result.setIdentitySuggestion(figure.getIdentity());
        }
        if (figure.getPersonality() != null) {
            result.setPersonalitySuggestion(figure.getPersonality());
        }
        if (figure.getBackground() != null) {
            result.setBackgroundSuggestion(figure.getBackground());
        }

        // 小宇宙：按玩家生辰 + 锁定星阶区间掷骰（人物名参与哈希）
        result.setCosmoIntensity(
                rollCosmoIntensity(figureName, birthDate, timeIndex, effectiveFaction,
                        result.getStarRank(), rollSeed));

        log.info("星命计算完成(命定人物): figureKey={}, constellation={}, starRank={}, cosmo={}",
                figure.getFigureKey(), result.getConstellation(), result.getStarRank(),
                result.getCosmoIntensity());
        return result;
    }

    /**
     * 预绑星命落位（design D2：跳过漏斗）——kagetora/harunobu/nobunaga。
     * <p>
     * 特质按星座名查静态映射（saint 查 88 星座表、specter 查天罡表），
     * 星座未收录时降级为档案 personality；starBonus 按锁定星阶 + 星座属性落位
     * （天罡固定义理，对齐 calculateSpecter 现行口径）。
     */
    private void applyBoundStarDestiny(OriginResult result, DestinedFigure figure) {
        String constellation = figure.getConstellation();
        String starRank = figure.getStarRank();
        result.setConstellation(constellation);
        result.setStarRank(starRank);

        if (SpecterStarMap.FACTION.equals(figure.getFaction())) {
            String personality = SpecterStarMap.tianGangPersonalityByStar(constellation);
            result.setZodiacTraits(personality != null ? personality : figure.getPersonality());
            result.setStarBonus(StarBonusMap.getBonus(starRank, "righteousness"));
            return;
        }
        ConstellationEntry entry = ConstellationMap.byName(constellation);
        if (entry != null) {
            result.setZodiacTraits(entry.traits());
            result.setStarBonus(StarBonusMap.getBonus(starRank, entry.bonusAttr()));
        } else {
            result.setZodiacTraits(figure.getPersonality());
        }
    }

    /**
     * 小宇宙初值确定性掷骰：hash(姓名, 生辰, 时辰, 阵营, 星阶, rollSeed) 映射到星阶组区间。
     * <p>
     * 哈希对区间长度取模保证带内均匀；{@link Math#floorMod} 消化负哈希。包内可见供单测钉住
     * 各组区间边界与同输入同 seed 确定性。
     *
     * @return 落在星阶组 [下界, 上界] 内的整数
     */
    static int rollCosmoIntensity(String name, LocalDate birthDate, int timeIndex,
                                  String faction, String starRank, int rollSeed) {
        int[] range = COSMO_START_RANGE.getOrDefault(starRank, COSMO_RANGE_DEFAULT);
        int min = range[0];
        long span = (long) range[1] - min + 1;
        int hash = Objects.hash(name, birthDate, timeIndex, faction, starRank, rollSeed);
        int offset = (int) Math.floorMod((long) hash, span);
        return min + offset;
    }

    // ==================== 圣斗士三层漏斗 ====================

    private void calculateSaint(OriginResult result, LocalDate birthDate, int timeIndex) {
        // Layer 1: 生日 → 季节组
        int seasonIndex = ConstellationMap.getSeasonIndex(birthDate);

        // Layer 2: 时辰 → 星阶
        String rank = timeIndexToRank(timeIndex);

        // Layer 3: 组内收窄
        ConstellationEntry entry;

        if (ConstellationMap.HIDDEN.equals(rank)) {
            // 亥时 → 凤凰座隐藏触发
            entry = ConstellationMap.phoenix();
            result.setStarRank(ConstellationMap.HIDDEN);
        } else if (ConstellationMap.GOLD.equals(rank)) {
            // Gold 直接由季节确定
            entry = ConstellationMap.getGold(seasonIndex);
            result.setStarRank(ConstellationMap.GOLD);
        } else if (ConstellationMap.SILVER.equals(rank)) {
            // Silver 上午/下午二选一
            int subIndex = timeIndexToSilverSub(timeIndex);
            entry = ConstellationMap.getSilver(seasonIndex, subIndex);
            result.setStarRank(ConstellationMap.SILVER);
        } else {
            // Bronze 按日柱天干四选一
            String dayGan = DayPillarTable.getDayGan(birthDate);
            int ganIdx = DayPillarTable.ganIndex(dayGan);
            int subIndex = ganIdx % 4;
            entry = ConstellationMap.getBronze(seasonIndex, subIndex);
            result.setStarRank(ConstellationMap.BRONZE);

            // 天龙座拱极特殊触发：子时 Bronze → 天龙座
            if (timeIndex == 0) {
                entry = ConstellationMap.dragon();
                result.setStarRank(ConstellationMap.HIDDEN);
            }
        }

        result.setConstellation(entry.name());
        result.setZodiacTraits(entry.traits());

        // 属性加成
        Map<String, Integer> bonus = StarBonusMap.getBonus(result.getStarRank(), entry.bonusAttr());
        result.setStarBonus(bonus);
    }

    // ==================== 冥斗士年柱天干×日柱地支交叉定位 ====================

    private void calculateSpecter(OriginResult result, LocalDate birthDate) {
        // 年柱天干
        int year = birthDate.getYear();
        String yearGan = DayPillarTable.getYearGan(year);
        int yearGanIdx = DayPillarTable.ganIndex(yearGan);

        // 日柱地支
        String dayZhi = DayPillarTable.getDayZhi(birthDate);
        int dayZhiIdx = DayPillarTable.zhiIndex(dayZhi);

        // 天罡星：年柱天干选组，日柱地支选组内位置
        int tianGangGroupSize = SpecterStarMap.tianGangGroupSize(yearGanIdx);
        int tianGangSub = dayZhiIdx % tianGangGroupSize;
        String tianGangStar = SpecterStarMap.getTianGangStar(yearGan, tianGangSub);
        String tianGangPersonality = SpecterStarMap.getTianGangPersonality(yearGan, tianGangSub);

        // 地煞星：日柱地支选组，年柱天干选组内位置
        int diShaSub = yearGanIdx % 6;
        String diShaStar = SpecterStarMap.getDiShaStar(dayZhi, diShaSub);

        result.setConstellation(tianGangStar);
        result.setStarRank(SpecterStarMap.TIAN_GANG);
        result.setZodiacTraits(tianGangPersonality);

        // 属性加成：天罡义理+12
        Map<String, Integer> bonus = StarBonusMap.getBonus(SpecterStarMap.TIAN_GANG, "righteousness");
        result.setStarBonus(bonus);

        // 将地煞星存入特质描述末尾，供后续使用
        result.setZodiacTraits(tianGangPersonality + "（命星：" + tianGangStar + " / 副星：" + diShaStar + "）");
    }

    // ==================== 神斗士按月份直接分配 ====================

    private void calculateGodWarrior(OriginResult result, LocalDate birthDate) {
        int month = birthDate.getMonthValue();
        ConstellationEntry star = GodWarriorMap.getByMonth(month);

        result.setConstellation(star.name());
        result.setStarRank("GodWarrior");
        result.setZodiacTraits(star.traits());

        // 神斗士固定力+10耐力+10
        Map<String, Integer> bonus = StarBonusMap.getBonus("GodWarrior", star.bonusAttr());
        result.setStarBonus(bonus);
    }

    // ==================== 角色卡富化建议 ====================

    private void enrichCharacterCard(OriginResult result, String name, String gender,
                                     LocalDate birthDate, int timeIndex) {
        String constellation = result.getConstellation();
        String rank = result.getStarRank();
        String faction = result.getFaction();

        // identity 建议
        String rankLabel = rankToLabel(rank, faction);
        result.setIdentitySuggestion(constellation + rankLabel);

        // personality 建议
        result.setPersonalitySuggestion(result.getZodiacTraits());

        // background 建议
        String timeLabel = timeIndexToLabel(timeIndex);
        String genderLabel = "male".equals(gender) ? "少年" : "少女";
        StringBuilder bg = new StringBuilder();
        bg.append(name).append("，").append(birthDate.getYear()).append("年")
          .append(birthDate.getMonthValue()).append("月").append(birthDate.getDayOfMonth()).append("日")
          .append(timeLabel).append("出生的").append(genderLabel).append("。");
        bg.append("自幼被").append(constellation).append("的星光选中，");
        bg.append("命定成为").append(rankLabel).append("。");
        bg.append("（").append(result.getZodiacTraits()).append("）");
        result.setBackgroundSuggestion(bg.toString());

        // 招式注入：查招式目录后按星阶分档发放初始掌握状态（rpg-technique-tree-data D4/D5）
        List<StarTechnique> catalog = ConstellationTechniqueMap.getTechniques(
                result.getConstellation(), result.getFaction());
        String seedKey = birthDate + "|" + timeIndex + "|" + faction + "|" + name;
        result.setTechniques(grantInitialTechniques(catalog, result.getConstellation(),
                faction, rank, seedKey, techniqueProps));
    }

    /**
     * 建档初始招式发放（rpg-technique-tree-data D4/D5）。
     * <p>
     * 精英档（Gold/天罡/神斗士）全部根节点 learned=true + 精英熟练度；新人档确定性抽 1 根
     * （SHA-256 种子，{@code source="经典系列"} 招牌集优先）+ 新人熟练度；其余节点一律
     * learned=false、proficiency=0。单节点树与兜底招式恒为 learned=true + 精英熟练度。
     * <p>
     * 包内可见供单测钉住三阵营六星阶发放分档与种子确定性。
     */
    static List<TechniqueState> grantInitialTechniques(List<StarTechnique> catalog, String constellation,
                                                       String faction, String starRank, String seedKey,
                                                       RpgTechniqueProperties props) {
        int elite = props.getInitialProficiencyElite();
        int rookie = props.getInitialProficiencyRookie();
        List<String> roots = TechniqueTreeMap.getRoots(constellation, faction);
        if (roots.isEmpty()) {
            // 未建树条目（109 个单技能条目）：全目录即根集合，视为单节点树
            roots = catalog.stream().map(StarTechnique::name).toList();
        }
        boolean eliteRank = isEliteRank(starRank);
        boolean singleNode = catalog.size() == 1;
        String picked = (!eliteRank && !singleNode) ? pickRoot(catalog, roots, seedKey) : null;

        List<TechniqueState> states = new ArrayList<>(catalog.size());
        for (StarTechnique t : catalog) {
            int tier = TechniqueTreeMap.getTier(constellation, faction, t.name());
            if (tier == TechniqueTreeMap.TIER_UNKNOWN) {
                tier = 1;
            }
            List<String> prereqs = TechniqueTreeMap.getPrerequisites(constellation, faction, t.name());
            boolean learned;
            int proficiency;
            if (singleNode) {
                learned = true;
                proficiency = elite;
            } else if (eliteRank) {
                learned = roots.contains(t.name());
                proficiency = learned ? elite : 0;
            } else {
                learned = t.name().equals(picked);
                proficiency = learned ? rookie : 0;
            }
            states.add(new TechniqueState(t.name(), t.description(), t.source(),
                    tier, prereqs, learned, proficiency));
        }
        return List.copyOf(states);
    }

    /** 精英档星阶：Gold / Specter-TianGang / GodWarrior（D4）；其余星阶一律新人档。 */
    static boolean isEliteRank(String starRank) {
        return ConstellationMap.GOLD.equals(starRank)
                || SpecterStarMap.TIAN_GANG.equals(starRank)
                || "GodWarrior".equals(starRank);
    }

    /**
     * 确定性抽取 1 个根节点（D5）：候选含多根时优先 {@code source="经典系列"} 招牌集，
     * 再以 SHA-256 种子取模落位。MUST NOT 使用 Math.random / ThreadLocalRandom / UUID。
     */
    static String pickRoot(List<StarTechnique> catalog, List<String> roots, String seedKey) {
        Set<String> rootSet = new HashSet<>(roots);
        List<String> classic = catalog.stream()
                .filter(t -> rootSet.contains(t.name()) && "经典系列".equals(t.source()))
                .map(StarTechnique::name)
                .toList();
        List<String> candidates = classic.isEmpty() ? roots : classic;
        long seed = sha256Seed(seedKey);
        return candidates.get((int) Long.remainderUnsigned(seed, candidates.size()));
    }

    /** SHA-256 摘要前 4 字节为无符号 int（D5）；跨 JVM/跨版本恒定，预览与落档重算同值。 */
    static long sha256Seed(String seedKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seedKey.getBytes(StandardCharsets.UTF_8));
            return ((long) (digest[0] & 0xFF) << 24)
                    | ((long) (digest[1] & 0xFF) << 16)
                    | ((long) (digest[2] & 0xFF) << 8)
                    | (digest[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /** 星阶 → 中文标签 */
    private String rankToLabel(String rank, String faction) {
        return switch (rank) {
            case ConstellationMap.GOLD -> "黄金圣斗士";
            case ConstellationMap.SILVER -> "白银圣斗士";
            case ConstellationMap.BRONZE -> "青铜圣斗士";
            case ConstellationMap.HIDDEN -> "隐藏星座守护者";
            case "Specter-TianGang" -> "天罡冥斗士";
            case "Specter-DiSha" -> "地煞冥斗士";
            case "GodWarrior" -> "神斗士";
            default -> "星命守护者";
        };
    }

    /** 时辰索引 → 中文标签 */
    private String timeIndexToLabel(int timeIndex) {
        String[] labels = {
            "子时", "丑时", "寅时", "卯时", "辰时", "巳时",
            "午时", "未时", "申时", "酉时", "戌时", "亥时"
        };
        return timeIndex >= 0 && timeIndex < labels.length ? labels[timeIndex] : "未知时辰";
    }
}
