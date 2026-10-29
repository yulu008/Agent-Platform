package com.luyu.agent.rpg.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 预设世界服务（preset-rpg-entry）。
 * <p>
 * 把 classpath 下的 md 源文（{@code rpg/preset-{key}.md}）解析成世界观行，
 * 以确定性 ID 幂等地物化到<b>当前租户</b>名下。用户改完 md 只需
 * {@code mvnw process-resources}，下次进预设向导即生效。
 * <p>
 * 全流程为本地运算 + 数据库写入：不调 LLM、不查配额、不做内容审查。
 */
@Service
public class PresetWorldService {

    private static final Logger log = LoggerFactory.getLogger(PresetWorldService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 预设世界 ID 前缀。拼上预设键与租户 UUID 后为
     * {@code preset-adventure:<36 字符>} = 53 字符，不越 {@code VARCHAR(64)} 界，
     * 且租户 ID 来自验签 JWT，跨租户必不冲突——这是零 DDL 迁移的幂等键。
     */
    static final String WORLD_ID_PREFIX = "preset-";

    /** 源文路径前缀，与 {@link #WORLD_ID_PREFIX} 同构：{@code rpg/preset-{key}.md} */
    private static final String SOURCE_PATH_PREFIX = "rpg/preset-";

    /**
     * 预设键有序清单（画廊墙排序稳定）：新增预设 = 丢一个 md + 随包封面进 resources，首页自动多一张卡。
     */
    private static final List<String> PRESET_KEYS = List.of("adventure");

    /**
     * 允许的预设键白名单。
     * <p>
     * 预设键来自 URL 查询参数（用户可控），且会被拼进 classpath 路径，
     * 因此必须先过白名单再拼路径，杜绝 {@code ../../} 式的资源穿越。
     */
    private static final Set<String> SUPPORTED_PRESETS = Set.copyOf(PRESET_KEYS);

    // md 小节标题（二级标题文本）→ 世界观列
    private static final String SEC_NAME = "名称";
    private static final String SEC_ERA = "时代";
    private static final String SEC_ATMOSPHERE = "氛围";
    private static final String SEC_RULES = "规则";
    private static final String SEC_SETTING = "世界描述";
    private static final String SEC_OPENING = "开场白模板";

    /** 冥星武将录小节标题（star-lorebook 真源，物化时解析入 rpg_star_incarnation） */
    private static final String SEC_STAR_INCARNATIONS = "冥星武将录";

    /** 命定人物小节标题（preset-destined-figures 真源，物化时解析入 rpg_destined_figure） */
    private static final String SEC_DESTINED_FIGURES = "命定人物";

    /** 小节切分标记：行首的二级标题 */
    private static final String SECTION_HEADER = "## ";

    /** 预设世界不建结构化地点（GM 依 setting_desc 即兴扮演，见 design D11） */
    private static final String EMPTY_JSON_ARRAY = "[]";

    private final RpgWorldSettingRepository worldRepo;
    private final RpgStarIncarnationRepository starIncarnationRepo;
    private final RpgDestinedFigureRepository destinedFigureRepo;
    private final WorldCoverStorageService coverStorage;

    public PresetWorldService(RpgWorldSettingRepository worldRepo,
                              RpgStarIncarnationRepository starIncarnationRepo,
                              RpgDestinedFigureRepository destinedFigureRepo,
                              WorldCoverStorageService coverStorage) {
        this.worldRepo = worldRepo;
        this.starIncarnationRepo = starIncarnationRepo;
        this.destinedFigureRepo = destinedFigureRepo;
        this.coverStorage = coverStorage;
    }

    /**
     * 物化结果。
     *
     * @param worldId   预设世界 ID（{@code preset-{key}:{tenantId}}）
     * @param worldName 解析出的世界名（md 缺 {@code ## 名称} 小节时为空串）
     */
    public record PresetWorld(String worldId, String worldName) {
    }

    /**
     * 幂等地物化预设世界：不存在则 INSERT，已存在（含软删后复活）则全量重写（含开场白模板）。
     * <p>
     * 每次都重写而非「仅首次创建」，是因为用户是<b>后填</b> md 的——若只建一次，
     * 首次点击物化出的半成品世界会永久固化（design D3）。
     * <p>
     * workshop-soft-delete-cascade D7：被软删的物化预设世界重新物化时复活原行
     * （清 deleted_at 后照常 update 全量重写），不因固定 preset- 主键 INSERT 冲突而失败。
     *
     * @param presetKey 预设键，须在 {@link #SUPPORTED_PRESETS} 内
     * @return 物化结果
     * @throws IllegalArgumentException 预设键不在白名单内
     * @throws IllegalStateException    源文缺失或读取失败（不创建残缺世界行）
     */
    public PresetWorld materialize(String presetKey) {
        if (presetKey == null || !SUPPORTED_PRESETS.contains(presetKey)) {
            throw new IllegalArgumentException("未知的预设键: " + presetKey);
        }
        String tenantId = TenantContext.requireTenantId();
        String worldId = worldIdOf(presetKey, tenantId);

        Map<String, String> sections = parseSections(loadSource(SOURCE_PATH_PREFIX + presetKey + ".md"));

        WorldSetting world = new WorldSetting();
        world.setId(worldId);
        world.setName(sections.getOrDefault(SEC_NAME, ""));
        world.setEra(sections.getOrDefault(SEC_ERA, ""));
        world.setAtmosphere(sections.getOrDefault(SEC_ATMOSPHERE, ""));
        world.setRules(parseRules(sections.get(SEC_RULES)));
        world.setSettingDesc(sections.getOrDefault(SEC_SETTING, ""));
        world.setLocations(EMPTY_JSON_ARRAY);
        world.setOpeningTemplate(sections.getOrDefault(SEC_OPENING, ""));

        // 已物化判断排除软删行（workshop-soft-delete-cascade D7）：软删的预设世界走
        // "复活原行 + 照常全量重写"而非 INSERT，绕开固定 preset- 主键冲突；
        // update 不含 cover 列，复活后封面维持原值。
        boolean refresh = worldRepo.findById(worldId) != null;
        if (!refresh && worldRepo.findSoftDeletedById(worldId) != null) {
            worldRepo.reviveById(worldId);
            refresh = true;
        }
        if (refresh) {
            worldRepo.update(world);
            // update() 刻意不动 opening_template 列（工坊编辑开场白走独立端点），
            // 预设刷新必须覆盖它，否则用户改 md 的开场白永远不生效。
            // 同理 update() 也不含 cover 列（world-card-gallery D7）：用户上传的封面
            // 天然活过全量重写，无需读旧行 carry over。
            worldRepo.updateOpeningTemplate(worldId, world.getOpeningTemplate());
        } else {
            // 首次物化落随包默认封面（资产缺失降级 null，前端渐变兜底）；
            // 落盘到租户私有区（works-view-and-world-publish D6）
            world.setCover(coverStorage.copyClasspathDefault(TenantContext.requireTenantId(), presetKey));
            worldRepo.save(world);
        }

        // 冥星武将录：与世界观行同源同刷（全量重写，幂等）。小节缺失时
        // 解析为空列表 → 清空既有名录，不阻断物化（其余世界字段照常落库）。
        List<StarIncarnation> incarnations =
                parseStarIncarnations(sections.get(SEC_STAR_INCARNATIONS), worldId);
        starIncarnationRepo.rewriteAll(worldId, incarnations);

        // 命定人物（preset-destined-figures）：同源同刷（全量重写，幂等）。
        // 小节缺失时同样清空既有档案，不阻断物化。
        List<DestinedFigure> figures =
                parseDestinedFigures(sections.get(SEC_DESTINED_FIGURES), worldId);
        destinedFigureRepo.rewriteAll(worldId, figures);

        log.info("预设世界已物化: preset={}, worldId={}, name={}, refresh={}, starIncarnations={}, destinedFigures={}",
                presetKey, worldId, world.getName(), refresh, incarnations.size(), figures.size());
        return new PresetWorld(worldId, world.getName());
    }

    /**
     * 白名单预设的只读描述（world-card-gallery 画廊墙）：解析 md 源文的名称与世界描述，
     * <b>不写库</b>——未物化预设直接上墙，点卡才 materialize。
     * <p>
     * 源文缺失的预设跳过（仅 warn），不让一张坏卡拉死整面墙。
     */
    public List<PresetDescriptor> describePresets() {
        String tenantId = TenantContext.requireTenantId();
        List<PresetDescriptor> descriptors = new ArrayList<>();
        for (String key : PRESET_KEYS) {
            try {
                Map<String, String> sections = parseSections(loadSource(SOURCE_PATH_PREFIX + key + ".md"));
                descriptors.add(new PresetDescriptor(key, worldIdOf(key, tenantId),
                        sections.getOrDefault(SEC_NAME, ""), sections.getOrDefault(SEC_SETTING, "")));
            } catch (IllegalStateException e) {
                log.warn("预设描述失败（源文缺失），跳过该卡: preset={}", key);
            }
        }
        return descriptors;
    }

    /** 画廊 DTO 用：预设键 + 确定性世界 ID + 名称 + 简介（世界描述小节） */
    public record PresetDescriptor(String presetKey, String worldId, String name, String blurb) {
    }

    /** 预设世界确定性 ID（与 materialize 同源）：{@code preset-{key}:{tenantId}} */
    public String worldIdOf(String presetKey, String tenantId) {
        return WORLD_ID_PREFIX + presetKey + ":" + tenantId;
    }

    /**
     * 从世界 ID 反解预设键（world-card-gallery D6 伺服兜底的租户闸门）。
     *
     * @return 仅当 id 形如 {@code preset-{key}:{tenantId}} 且租户后缀等于入参租户、
     *         key 在白名单内时返回 key；否则 null
     */
    public String presetKeyForTenant(String worldId, String tenantId) {
        if (worldId == null || !worldId.startsWith(WORLD_ID_PREFIX) || tenantId == null) {
            return null;
        }
        String rest = worldId.substring(WORLD_ID_PREFIX.length());
        int colon = rest.lastIndexOf(':');
        if (colon < 0) {
            return null;
        }
        String key = rest.substring(0, colon);
        String tenant = rest.substring(colon + 1);
        return tenant.equals(tenantId) && SUPPORTED_PRESETS.contains(key) ? key : null;
    }

    /**
     * 读取 classpath 源文（{@code ClassPathResource} + UTF-8，同 {@code GmContextAssembler}）。
     * <p>
     * 与 GM 提示词加载不同：那边失败降级为空串（提示词缺失只是效果变差），
     * 这边失败必须抛出——spec 要求返回 5xx 且不落残缺世界行。
     *
     * @throws IllegalStateException 资源不存在或 IO 失败
     */
    private String loadSource(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("预设世界源文加载失败: " + path, e);
        }
    }

    /**
     * 按行首二级标题切分小节。
     * <p>
     * 第一个 {@code ## } 之前的内容（文件头 HTML 注释、一级标题）整体忽略，
     * 因此源文可自由增删说明文字。小节正文两端空白裁掉，缺失的小节
     * 在返回的 map 中不存在，由调用方降级为空值。
     *
     * @return 小节标题 → 小节正文（保持文件内出现顺序）
     */
    Map<String, String> parseSections(String markdown) {
        Map<String, String> sections = new LinkedHashMap<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\r?\n", -1)) {
            if (line.startsWith(SECTION_HEADER)) {
                if (current != null) {
                    sections.put(current, body.toString().trim());
                }
                current = line.substring(SECTION_HEADER.length()).trim();
                body.setLength(0);
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        if (current != null) {
            sections.put(current, body.toString().trim());
        }
        return sections;
    }

    /**
     * 把 {@code ## 规则} 小节解析为 JSON 数组字符串。
     * <p>
     * 只认行首的 {@code - }（或 {@code * }）列表项，其余行忽略；小节缺失或
     * 无有效条目时降级为 {@code []}，序列化异常同样降级，绝不抛出。
     */
    String parseRules(String rulesSection) {
        List<String> rules = new ArrayList<>();
        if (rulesSection != null) {
            for (String line : rulesSection.split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ")) {
                    rules.add(trimmed.substring(2).trim());
                } else if (trimmed.startsWith("* ")) {
                    rules.add(trimmed.substring(2).trim());
                }
            }
        }
        try {
            return mapper.writeValueAsString(rules);
        } catch (Exception e) {
            log.warn("规则序列化失败，降级为空数组: {}", e.getMessage());
            return EMPTY_JSON_ARRAY;
        }
    }

    /**
     * 把 {@code ## 冥星武将录} 小节解析为名录实体列表（star-lorebook 真源解析）。
     * <p>
     * 每行一个列表项，段间以 {@code |} 分隔：
     * <pre>- 星名 - 武将名 | 命定叙事 | 别名1，别名2 | npc</pre>
     * <ul>
     *   <li>第 1 段以 {@code " - "} 拆星名与武将名（缺分隔或任一侧为空则整行跳过，不阻断）；</li>
     *   <li>别名段兼容全角/半角逗号，落库时规范化为半角逗号分隔；</li>
     *   <li>末段含 {@code npc} 标记 → {@code rollable=false}（NPC 专属星，不入可推演池、永不可被抢）；</li>
     *   <li>小节缺失时返回空列表（物化时清空既有名录，幂等）。</li>
     * </ul>
     * 包级可见：纯函数（仅依赖入参），供单测直接注入验证。
     */
    List<StarIncarnation> parseStarIncarnations(String section, String worldId) {
        List<StarIncarnation> incarnations = new ArrayList<>();
        if (section == null || section.isBlank()) {
            return incarnations;
        }
        int sortOrder = 0;
        for (String rawLine : section.split("\r?\n")) {
            String line = rawLine.trim();
            if (!line.startsWith("- ") && !line.startsWith("* ")) {
                continue;
            }
            String[] parts = splitPipe(line.substring(2).trim());
            String[] nameParts = splitStarWarlord(parts[0]);
            if (nameParts == null) {
                log.warn("冥星武将录行解析失败（缺星名-武将名分隔），跳过: {}", rawLine);
                continue;
            }
            StarIncarnation incarnation = new StarIncarnation();
            incarnation.setId(UUID.randomUUID().toString());
            incarnation.setWorldId(worldId);
            incarnation.setStarName(nameParts[0]);
            incarnation.setWarlordName(nameParts[1]);
            incarnation.setLore(parts.length > 1 ? parts[1].trim() : "");
            incarnation.setAliases(parts.length > 2 ? normalizeAliases(parts[2]) : "");
            boolean npcOnly = false;
            for (int i = 3; i < parts.length; i++) {
                if (parts[i].toLowerCase().contains("npc")) {
                    npcOnly = true;
                }
            }
            incarnation.setRollable(!npcOnly);
            incarnation.setSortOrder(sortOrder++);
            incarnations.add(incarnation);
        }
        return incarnations;
    }

    /** 按首个 {@code |} 前后裁剪（避开正则转义）；保留空段以维持段位语义 */
    private static String[] splitPipe(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int idx;
        while ((idx = text.indexOf('|', start)) >= 0) {
            parts.add(text.substring(start, idx));
            start = idx + 1;
        }
        parts.add(text.substring(start));
        return parts.toArray(new String[0]);
    }

    /**
     * 拆 {@code 星名 - 武将名}：以首个 {@code " - "} 为界，两侧裁空后任一侧为空则返回 null。
     */
    private static String[] splitStarWarlord(String text) {
        int idx = text.indexOf(" - ");
        if (idx < 0) {
            return null;
        }
        String starName = text.substring(0, idx).trim();
        String warlordName = text.substring(idx + 3).trim();
        if (starName.isEmpty() || warlordName.isEmpty()) {
            return null;
        }
        return new String[]{starName, warlordName};
    }

    /** 别名段规范化：全角逗号→半角，去除空项，半角逗号拼接 */
    private static String normalizeAliases(String raw) {
        String normalized = raw.trim().replace('，', ',');
        List<String> kept = new ArrayList<>();
        for (String alias : normalized.split(",", -1)) {
            String trimmed = alias.trim();
            if (!trimmed.isEmpty()) {
                kept.add(trimmed);
            }
        }
        return String.join(",", kept);
    }

    /**
     * 把 {@code ## 命定人物} 小节解析为人物档案列表（preset-destined-figures 真源解析）。
     * <p>
     * 小节内每个 {@code ### 人物名（figure_key）} 子标题开一个档案块，块内每行一个
     * {@code - key: value} 键值行（faction/constellation/starRank/eraNote/identity/
     * personality/background/aliases）：
     * <ul>
     *   <li>子标题括号兼容全角/半角，人物名或 figure_key 任一侧为空则整块跳过，不阻断；</li>
     *   <li>星命三字段（faction/constellation/starRank）空值或整行缺失均落 null
     *       （自由推演模式，星命由玩家生辰推演产出）；</li>
     *   <li>未知键告警跳过；键值行缺少冒号告警跳过；别名行走 {@link #normalizeAliases} 规范化；</li>
     *   <li>小节缺失时返回空列表（物化时清空既有档案，幂等）。</li>
     * </ul>
     * 包级可见：纯函数（仅依赖入参），供单测直接注入验证。
     */
    List<DestinedFigure> parseDestinedFigures(String section, String worldId) {
        List<DestinedFigure> figures = new ArrayList<>();
        if (section == null || section.isBlank()) {
            return figures;
        }
        DestinedFigure current = null;
        int sortOrder = 0;
        for (String rawLine : section.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.startsWith("### ")) {
                String[] headerParts = splitFigureHeader(line.substring(4).trim());
                if (headerParts == null) {
                    log.warn("命定人物子标题解析失败（缺人物名（figure_key）括号结构），跳过该块: {}", rawLine);
                    current = null;
                    continue;
                }
                current = new DestinedFigure();
                current.setId(UUID.randomUUID().toString());
                current.setWorldId(worldId);
                current.setDisplayName(headerParts[0]);
                current.setFigureKey(headerParts[1]);
                current.setSortOrder(sortOrder++);
                figures.add(current);
            } else if (current != null && (line.startsWith("- ") || line.startsWith("* "))) {
                String kv = line.substring(2).trim();
                int colon = kv.indexOf(':');
                if (colon < 0) {
                    log.warn("命定人物键值行缺少冒号，跳过: {}", rawLine);
                    continue;
                }
                applyFigureField(current, kv.substring(0, colon).trim(),
                        kv.substring(colon + 1).trim(), rawLine);
            }
        }
        return figures;
    }

    /**
     * 拆 {@code 人物名（figure_key）}：括号兼容全角/半角，两侧裁空后任一侧为空则返回 null。
     */
    private static String[] splitFigureHeader(String text) {
        int open = indexOfAny(text, '（', '(');
        int close = lastIndexOfAny(text, '）', ')');
        if (open <= 0 || close <= open) {
            return null;
        }
        String displayName = text.substring(0, open).trim();
        String figureKey = text.substring(open + 1, close).trim();
        if (displayName.isEmpty() || figureKey.isEmpty()) {
            return null;
        }
        return new String[]{displayName, figureKey};
    }

    private static int indexOfAny(String text, char full, char half) {
        int a = text.indexOf(full);
        int b = text.indexOf(half);
        return a < 0 ? b : (b < 0 ? a : Math.min(a, b));
    }

    private static int lastIndexOfAny(String text, char full, char half) {
        int a = text.lastIndexOf(full);
        int b = text.lastIndexOf(half);
        return Math.max(a, b);
    }

    /** 键值行落字段：星命三字段空值归一化为 null（自由推演）；未知键告警跳过 */
    private void applyFigureField(DestinedFigure figure, String key, String value, String rawLine) {
        String normalized = value.isEmpty() ? null : value;
        switch (key) {
            case "faction" -> figure.setFaction(normalized);
            case "constellation" -> figure.setConstellation(normalized);
            case "starRank" -> figure.setStarRank(normalized);
            case "eraNote" -> figure.setEraNote(normalized);
            case "identity" -> figure.setIdentity(normalized);
            case "personality" -> figure.setPersonality(normalized);
            case "background" -> figure.setBackground(normalized);
            case "aliases" -> figure.setAliases(normalized == null ? null : normalizeAliases(normalized));
            default -> log.warn("命定人物未知键，跳过: {} (key={})", rawLine, key);
        }
    }
}
