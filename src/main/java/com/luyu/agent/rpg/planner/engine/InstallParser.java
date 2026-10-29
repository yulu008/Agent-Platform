package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.model.WorldbookEntry;

/**
 * 安装物化解析器（add-planner-pipeline tasks 5.1 / design D10）。
 * <p>
 * 工作区确认稿（纯文本）→ world 模板层实体载荷的纯函数转换，无 IO 无状态，
 * 单测直灌。产物小节与字段格式锚定各阶段生成器的成稿模板（小节标题/字段头
 * 是解析键，措辞不可改）：
 * <ul>
 *   <li>{@code 02-world.md}：{@code ## } 小节直取 WorldSetting 表单列（名称/
 *       时代/氛围/规则/世界描述/开场白模板——对齐 {@code PresetWorldService}
 *       物化惯例，locations 固定 {@code []} 对齐 design D11）；「世界书条目」
 *       小节的 {@code ### } 标题块 → 通用轨 WorldbookEntry 模板行。</li>
 *   <li>{@code 04-plot.md}：「叙事规则」小节 → 世界叙事规则文本（落
 *       {@code rpg_world_setting.narrative_rules} 列，tasks 5.1）。</li>
 *   <li>{@code 03-characters/} 候选卡：{@code ## } 标题取人物名、
 *       {@code - 字段：值} 行直取 CharacterCard 六字段（CharactersGenerator
 *       「S6 物化直取」锚点）；「### 命定档案」子节存在时同时物化
 *       DestinedFigure（preset-destined-figures 惯例——figureKey 用人物名：
 *       安装世界无重物化，名即稳定键；「无」值归 null = 自由推演）。</li>
 *   <li>{@code 05-branches/} 候选卡：字段直取 WorldbookEntry 形态
 *       （BranchDesignGenerator「按字段直取 WorldbookEntry 形态」锚点——
 *       触发词→keywords、优先级→sortOrder、注入内容→content）。轨位
 *       （world/star）是生成期设计提示：star 轨分支的触发词含星名/武将名，
 *       命中时与冥星武将录条目协同注入，物化落点统一通用轨——冥星轨表
 *       （rpg_star_incarnation）字段集（星名/武将名对）与分支条目不匹配且
 *       共享抢星运行时语义，不挪用。</li>
 * </ul>
 * 容错取向（对齐 {@code PresetWorldService} 解析惯例）：小节/字段行缺失降级
 * null 或空集合不阻断（L2 逐卡编辑后的格式漂移不废整包）；人物名缺失的
 * 候选卡跳过。世界名完整性由安装编排层守卫（NOT NULL 列，拒绝安装而非落
 * 半成品行）；名称/时代超出 schema 列宽（VARCHAR 200/100）时硬截断——
 * 全文仍在工作区确认稿可查，物化列宽不匹配不废整包（7.2 E2E 实证：
 * S2「时代」按模板写整段背景总览，超 100 字是常态而非漂移）。
 */
public final class InstallParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** rpg_world_setting.name 列宽（schema-h2.sql VARCHAR(200)）。 */
    static final int NAME_MAX = 200;

    /** rpg_world_setting.era 列宽（schema-h2.sql VARCHAR(100)）。 */
    static final int ERA_MAX = 100;

    /** 安装物化载荷（实体已绑定 worldId 与全新 id，Installer 直接落库）。 */
    public record MaterializedWorld(
            WorldSetting world,
            List<WorldbookEntry> worldbookEntries,
            List<CharacterCard> characters,
            List<DestinedFigure> figures,
            List<WorldbookEntry> branchEntries) {

        /** 物化实体总数（不含世界行本体；日志与安装响应统计用）。 */
        public int entryCount() {
            return worldbookEntries.size() + characters.size()
                    + figures.size() + branchEntries.size();
        }
    }

    private InstallParser() {
    }

    /**
     * 全量解析（调用方保证稿源为确认版：S6 安装前置 S1-S5 全 CONFIRMED，
     * 候选列表已过滤被毙——readStageFullText 同口径）。
     *
     * @param worldDraft          02-world.md 确认稿全文（null/空按缺小节降级）
     * @param plotDraft           04-plot.md 确认稿全文
     * @param characterCandidates 03-characters 非毙候选块列表（保持确认序）
     * @param branchCandidates    05-branches 非毙候选块列表
     * @param worldId             安装目标世界 id（Installer 生成的新 world_id）
     */
    public static MaterializedWorld parse(String worldDraft, String plotDraft,
                                          List<String> characterCandidates,
                                          List<String> branchCandidates,
                                          String worldId) {
        Map<String, String> sections = parseSections(worldDraft);

        WorldSetting world = new WorldSetting();
        world.setId(worldId);
        world.setName(clip(text(sections, "名称"), NAME_MAX));
        world.setEra(clip(text(sections, "时代"), ERA_MAX));
        world.setAtmosphere(text(sections, "氛围"));
        world.setRules(parseRules(sections.get("规则")));
        world.setSettingDesc(text(sections, "世界描述"));
        world.setLocations("[]");
        world.setOpeningTemplate(text(sections, "开场白模板"));
        world.setNarrativeRules(parseNarrativeRules(plotDraft));

        List<WorldbookEntry> worldbookEntries =
                parseWorldbookEntries(sections.get("世界书条目"), worldId);

        List<CharacterCard> characters = new ArrayList<>();
        List<DestinedFigure> figures = new ArrayList<>();
        List<String> cards = characterCandidates == null ? List.of() : characterCandidates;
        for (int i = 0; i < cards.size(); i++) {
            parseCharacter(cards.get(i), worldId, characters, figures, i);
        }

        List<WorldbookEntry> branchEntries = new ArrayList<>();
        List<String> branches = branchCandidates == null ? List.of() : branchCandidates;
        for (String block : branches) {
            WorldbookEntry entry = parseBranch(block, worldId);
            if (entry != null) {
                branchEntries.add(entry);
            }
        }
        return new MaterializedWorld(world, worldbookEntries, characters, figures, branchEntries);
    }

    // ==================== 小节切分与表单列 ====================

    /**
     * {@code ## } 二级标题切小节（对齐 {@code PresetWorldService.parseSections}
     * 惯例）：小节名与正文各自 strip；null/空稿返回空映射。三级标题
     * （{@code ### }）不构成新小节（「### 」前缀与「## 」不匹配）。
     */
    static Map<String, String> parseSections(String draft) {
        Map<String, String> sections = new LinkedHashMap<>();
        if (draft == null || draft.isBlank()) {
            return sections;
        }
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String rawLine : draft.split("\r?\n")) {
            if (rawLine.trim().startsWith("## ")) {
                if (current != null) {
                    sections.put(current, body.toString().strip());
                }
                current = rawLine.trim().substring(3).strip();
                body = new StringBuilder();
            } else if (current != null) {
                body.append(rawLine).append('\n');
            }
        }
        if (current != null) {
            sections.put(current, body.toString().strip());
        }
        return sections;
    }

    /** 小节正文（缺失/空白返回 null）。 */
    private static String text(Map<String, String> sections, String key) {
        String value = sections.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 有长度上限的表单列（名称/时代）防御截断：超 schema 列宽硬剪到上限
     * （不加省略号——上限本身是字符数预算）；null/空语义与 {@link #text} 一致。
     */
    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * 规则小节 → JSON 数组字符串（对齐 {@code PresetWorldService.parseRules}
     * 语义）：只认行首 {@code - }（或 {@code * }）列表项，缺失/无条目/序列化
     * 异常降级 {@code []}，绝不抛出。
     */
    static String parseRules(String rulesSection) {
        List<String> rules = new ArrayList<>();
        if (rulesSection != null) {
            for (String rawLine : rulesSection.split("\r?\n")) {
                String line = rawLine.trim();
                if (line.startsWith("- ")) {
                    rules.add(line.substring(2).trim());
                } else if (line.startsWith("* ")) {
                    rules.add(line.substring(2).trim());
                }
            }
        }
        try {
            return MAPPER.writeValueAsString(rules);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 剧情结构稿「叙事规则」小节正文；缺稿/缺小节返回 null（无世界定制）。 */
    static String parseNarrativeRules(String plotDraft) {
        return text(parseSections(plotDraft), "叙事规则");
    }

    // ==================== S2 通用轨世界书条目 ====================

    /**
     * 「世界书条目」小节 → 通用轨模板行：{@code ### } 标题切块，块内
     * {@code 关键词：} 行取触发词集，其余行聚合为正文（首行「条目正文：」
     * 前缀剥除）。缺失小节返回空列表；enabled 恒 true（安装产物默认启用）。
     */
    static List<WorldbookEntry> parseWorldbookEntries(String section, String worldId) {
        List<WorldbookEntry> entries = new ArrayList<>();
        int sortOrder = 0;
        for (String block : splitBlocks(section, "### ")) {
            String title = headerTitle(block, "### ");
            if (title == null) {
                continue;
            }
            WorldbookEntry entry = new WorldbookEntry();
            entry.setId(UUID.randomUUID().toString());
            entry.setWorldId(worldId);
            entry.setTitle(title);
            entry.setEnabled(Boolean.TRUE);
            entry.setSortOrder(sortOrder++);
            StringBuilder content = new StringBuilder();
            for (String rawLine : block.split("\r?\n")) {
                String line = rawLine.trim();
                if (line.startsWith("### ")) {
                    continue;
                }
                String keywords = stripPrefix(line, "关键词：");
                if (keywords != null) {
                    if (!keywords.isBlank()) {
                        entry.setKeywords(keywords);
                    }
                    continue;
                }
                String body = stripPrefix(line, "条目正文：");
                content.append(body != null ? body : rawLine).append('\n');
            }
            entry.setContent(content.isEmpty() ? null : content.toString().strip());
            entries.add(entry);
        }
        return entries;
    }

    // ==================== S5 分支条目 ====================

    /**
     * 分支候选块 → WorldbookEntry（title/keywords/sortOrder/content 字段直取）。
     * 字段行（触发词/优先级/轨位）在「注入内容：」行前识别消费；其后正文整段
     * 聚合。轨位行消费丢弃（物化落点统一通用轨，见类 javadoc）。标题缺失
     * （格式漂移）返回 null，调用方跳过。条目标题剥序号前缀取实际分支名
     * （title 是展示名也是关键词——带「分支 N：」前缀会引入泛词误命中）。
     */
    static WorldbookEntry parseBranch(String block, String worldId) {
        String title = headerTitle(block, "## ");
        if (title == null) {
            return null;
        }
        WorldbookEntry entry = new WorldbookEntry();
        entry.setId(UUID.randomUUID().toString());
        entry.setWorldId(worldId);
        entry.setTitle(stripIndexedPrefix(title));
        entry.setEnabled(Boolean.TRUE);
        entry.setSortOrder(0);
        StringBuilder content = new StringBuilder();
        boolean contentStarted = false;
        for (String rawLine : block.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                continue;
            }
            if (!contentStarted) {
                String keywords = stripPrefix(line, "触发词：");
                if (keywords != null) {
                    if (!keywords.isBlank()) {
                        entry.setKeywords(keywords);
                    }
                    continue;
                }
                String priority = stripPrefix(line, "优先级：");
                if (priority != null) {
                    entry.setSortOrder(parseInt(priority, 0));
                    continue;
                }
                if (stripPrefix(line, "轨位：") != null) {
                    continue;
                }
                String body = stripPrefix(line, "注入内容：");
                if (body != null) {
                    contentStarted = true;
                    if (!body.isBlank()) {
                        content.append(body).append('\n');
                    }
                    continue;
                }
            } else {
                content.append(rawLine).append('\n');
            }
        }
        entry.setContent(content.isEmpty() ? null : content.toString().strip());
        return entry;
    }

    // ==================== S3 人物卡与命定档案 ====================

    /**
     * 人物候选块 → CharacterCard（必产出）+ DestinedFigure（「### 命定档案」
     * 子节存在且含有效字段时）。人物名缺失（格式漂移）整卡跳过。
     * <p>
     * 标题格式锚定 {@code CharactersGenerator} 成稿模板：{@code ## 人物 N：<人物名>}
     * ——物化名剥序号前缀取 {@code <人物名>}；不匹配前缀的标题（L2 逐卡编辑后
     * 直呼其名如 {@code ## 甲}）整标题即名（漂移容错）。
     */
    static void parseCharacter(String block, String worldId,
                               List<CharacterCard> characters,
                               List<DestinedFigure> figures, int sortOrder) {
        String title = headerTitle(block, "## ");
        if (title == null) {
            return;
        }
        String name = stripIndexedPrefix(title);
        CharacterCard card = new CharacterCard();
        card.setId(UUID.randomUUID().toString());
        card.setWorldId(worldId);
        card.setName(name);
        card.setType("npc");

        // 命定档案子节切分：### 命定档案 行之后的块尾归子节
        String[] parts = splitDestinedSection(block);
        String main = parts[0];
        String destined = parts[1];

        for (String rawLine : main.split("\r?\n")) {
            String[] field = listField(rawLine);
            if (field == null) {
                continue;
            }
            switch (field[0]) {
                case "身份" -> card.setIdentity(blankToNull(field[1]));
                case "性格" -> card.setPersonality(blankToNull(field[1]));
                case "背景" -> card.setBackground(blankToNull(field[1]));
                case "动机" -> card.setMotivation(blankToNull(field[1]));
                case "说话风格" -> card.setSpeechStyle(blankToNull(field[1]));
                case "知识" -> card.setKnowledge(knowledgeJson(field[1]));
                default -> { /* 未知字段行忽略（格式漂移容错） */ }
            }
        }
        characters.add(card);

        DestinedFigure figure = parseDestinedFigure(destined, card, worldId, sortOrder);
        if (figure != null) {
            figures.add(figure);
        }
    }

    /**
     * 命定档案子节 → DestinedFigure（子节缺失/无有效字段返回 null）。
     * 四字段（faction/constellation/starRank/eraNote）值「无」归 null =
     * 自由推演（DestinedFigure 语义）；三件套底稿复用人物卡。
     */
    private static DestinedFigure parseDestinedFigure(String destined, CharacterCard card,
                                                       String worldId, int sortOrder) {
        String faction = null;
        String constellation = null;
        String starRank = null;
        String eraNote = null;
        for (String rawLine : destined.split("\r?\n")) {
            String[] field = listField(rawLine);
            if (field == null) {
                continue;
            }
            switch (field[0]) {
                case "faction" -> faction = destinedValue(field[1]);
                case "constellation" -> constellation = destinedValue(field[1]);
                case "starRank" -> starRank = destinedValue(field[1]);
                case "eraNote" -> eraNote = destinedValue(field[1]);
                default -> { }
            }
        }
        if (faction == null && constellation == null
                && starRank == null && eraNote == null) {
            return null;
        }
        DestinedFigure figure = new DestinedFigure();
        figure.setId(UUID.randomUUID().toString());
        figure.setWorldId(worldId);
        // 安装世界无重物化：人物名即跨查询稳定键（对齐 figureKey 语义）
        figure.setFigureKey(card.getName());
        figure.setDisplayName(card.getName());
        figure.setFaction(faction);
        figure.setConstellation(constellation);
        figure.setStarRank(starRank);
        figure.setEraNote(eraNote);
        figure.setIdentity(card.getIdentity());
        figure.setPersonality(card.getPersonality());
        figure.setBackground(card.getBackground());
        figure.setSortOrder(sortOrder);
        return figure;
    }

    /**
     * 剥候选标题序号前缀：{@code 人物 N：} / {@code 分支 N：}（锚定
     * CharactersGenerator / BranchDesignGenerator 成稿模板，全角/半角冒号
     * 兼容）；无前缀返回原标题（L2 逐卡编辑后的直呼其名格式，漂移容错）。
     */
    private static String stripIndexedPrefix(String title) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(人物|分支)\\s*\\d+\\s*[：:]\\s*(.*)$").matcher(title);
        if (matcher.matches()) {
            String name = matcher.group(2).strip();
            return name.isEmpty() ? title : name;
        }
        return title;
    }

    /** 「### 命定档案」行切主卡区/子节区（无该行时子节为空）。 */
    private static String[] splitDestinedSection(String block) {
        String[] lines = block.split("\r?\n");
        int splitAt = lines.length;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().startsWith("### 命定档案")) {
                splitAt = i;
                break;
            }
        }
        StringBuilder main = new StringBuilder();
        for (int i = 0; i < splitAt; i++) {
            main.append(lines[i]).append('\n');
        }
        StringBuilder destined = new StringBuilder();
        for (int i = splitAt; i < lines.length; i++) {
            destined.append(lines[i]).append('\n');
        }
        return new String[]{main.toString(), destined.toString()};
    }

    // ==================== 行级解析工具 ====================

    /** 块首标题行取标题（剥前缀；空标题返回 null）。 */
    private static String headerTitle(String block, String headerPrefix) {
        String first = block.split("\r?\n", 2)[0].trim();
        if (!first.startsWith(headerPrefix)) {
            return null;
        }
        String title = first.substring(headerPrefix.length()).strip();
        return title.isBlank() ? null : title;
    }

    /**
     * 按行首标题前缀切块（块含标题行，保持原序；标题前首段导语丢弃——
     * 同 {@code CandidateSplitter.split} 容错语义）。null/空文本返回空列表。
     */
    private static List<String> splitBlocks(String text, String headerPrefix) {
        List<String> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        StringBuilder current = null;
        for (String line : text.split("\r?\n")) {
            if (line.trim().startsWith(headerPrefix)) {
                if (current != null) {
                    blocks.add(current.toString().strip());
                }
                current = new StringBuilder();
            }
            if (current != null) {
                current.append(line).append('\n');
            }
        }
        if (current != null) {
            String last = current.toString().strip();
            if (!last.isEmpty()) {
                blocks.add(last);
            }
        }
        return blocks;
    }

    /**
     * {@code - 字段：值} 列表行 → [字段名, 值]（全角/半角冒号兼容）；
     * 非该形态返回 null。
     */
    private static String[] listField(String rawLine) {
        String line = rawLine.trim();
        if (!line.startsWith("- ")) {
            return null;
        }
        String rest = line.substring(2).trim();
        int idx = rest.indexOf('：');
        if (idx < 0) {
            idx = rest.indexOf(':');
        }
        if (idx <= 0) {
            return null;
        }
        return new String[]{rest.substring(0, idx).strip(), rest.substring(idx + 1).strip()};
    }

    /** 行前缀剥取（命中返回余文 strip；未命中返回 null）。 */
    private static String stripPrefix(String line, String prefix) {
        return line.startsWith(prefix) ? line.substring(prefix.length()).strip() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 命定档案字段值：「无」/空归 null（自由推演语义）。 */
    private static String destinedValue(String value) {
        String v = blankToNull(value);
        return "无".equals(v) ? null : v;
    }

    /** 知识字段（逗号分隔）→ JSON 数组；无有效项返回 null。 */
    private static String knowledgeJson(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (String item : value.split("[,，]")) {
            String trimmed = item.strip();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
