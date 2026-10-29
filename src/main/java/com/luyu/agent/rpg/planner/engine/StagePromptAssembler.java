package com.luyu.agent.rpg.planner.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.rpg.planner.model.StyleMode;

/**
 * 阶段 prompt 拼装件库（add-planner-pipeline tasks 3.2 / design D5/D7/D9）。
 * <p>
 * 纯静态拼装（无状态、可单测钉住措辞），供各 {@link StageGenerator} 组合：
 * <ul>
 *   <li>{@link #styleSection}：风格模式全局约束（D9，run 级参数贯穿每阶段）</li>
 *   <li>{@link #upstreamSummariesSection}：更早阶段的确认稿摘要行（D7——
 *       只注入 manifest 摘要行而非全文，天然隔离被毙候选）</li>
 *   <li>{@link #reworkSection}：返工约束（D5——上轮全稿 + 反馈历史 +
 *       「未提及部分保持不变」约束 + 已毙候选显式排除表）</li>
 * </ul>
 */
public final class StagePromptAssembler {

    /**
     * 直接上游确认稿的截断保护（防 prompt 膨胀；超过截断由确认稿质量把关）。
     * S3 候选集提额到 20 后聚合稿约 6-7K chars，5000 会砍卡尾——随候选
     * 上限同额度放大（12K 覆盖 20 卡 + L1 长稿余量）。
     */
    static final int UPSTREAM_TRUNCATE = 12000;

    private StagePromptAssembler() {
    }

    /**
     * 风格模式全局约束（D9：创建时采集、落 manifest、每阶段 prompt 组装时读取）。
     */
    public static String styleSection(StyleMode styleMode) {
        if (styleMode == StyleMode.REMIX) {
            return """
                    【风格：自由二创】
                    - 以原文/IP 为灵感源，允许大幅改造、混搭与原创设定，不必忠于原作细节
                    - 可借用世界观骨架、人物气质与标志性意象，但鼓励发展出你自己的新东西
                    - 不要照抄原作设定；产出应当是「受其启发的新作」，而非复述
                    """;
        }
        return """
                【风格：忠实拆解】
                - 严格贴原作设定拆解，不得虚构原作中不存在的关键设定
                - 不确定的内容标注「存疑」，宁可留白不可编造
                - 每条关键设定须可溯源（对应原文出处或公认设定资料）
                """;
    }

    /**
     * 指定阶段之前的全部阶段摘要行（D7：名称 + 一句话概括，非全文）。
     * <p>
     * manifest 只反映 confirmed 版本，被毙候选天然不进下游上下文。
     *
     * @param before 目标阶段（只汇总它之前的阶段；无已确认上游时返回空串）
     */
    public static String upstreamSummariesSection(PipelineManifest manifest, PipelineStage before) {
        List<String> lines = Arrays.stream(PipelineStage.values())
                .takeWhile(stage -> stage != before)
                .filter(stage -> manifest.stage(stage.code()).getSummary() != null)
                .map(stage -> "- " + stage.code() + " " + stage.label() + "："
                        + manifest.stage(stage.code()).getSummary())
                .collect(Collectors.toList());
        if (lines.isEmpty()) {
            return "";
        }
        return "【上游已确认摘要】\n" + String.join("\n", lines) + "\n\n";
    }

    /**
     * 直接上游确认稿正文（v1 简化：注入直接上游全文 + 截断保护）。
     * 返回 null 当无上游（S1）。
     */
    public static String upstreamConfirmedSection(String upstreamConfirmed) {
        if (upstreamConfirmed == null || upstreamConfirmed.isBlank()) {
            return null;
        }
        String truncated = upstreamConfirmed.length() > UPSTREAM_TRUNCATE
                ? upstreamConfirmed.substring(0, UPSTREAM_TRUNCATE) + "\n…（已截断）"
                : upstreamConfirmed;
        return "【直接上游确认稿全文】\n" + truncated + "\n\n";
    }

    /**
     * 从 S1 确认稿提取「人物全量名册」节（S3 候选生成的对照池）。
     * <p>
     * 节名锄定 faithful 成稿模板（{@link IpAnalysisGenerator#FAITHFUL_INSTRUCTION}
     * 「## 三、人物全量名册」）；提取该标题行到下一个 {@code ## } 节前。
     * remix（气质原型节）与无该节的旧稿返回 null，调用方零注入。
     * （planner-s3-batch-generate tasks 5.1：详情接口的 S3 名册剩余派生
     * 需跨包调用，改 public——同 extractRosterNames 的公开度对齐。）
     */
    public static String extractRosterSection(String s1Artifact) {
        if (s1Artifact == null || s1Artifact.isBlank()) {
            return null;
        }
        boolean inRoster = false;
        StringBuilder roster = new StringBuilder();
        for (String line : s1Artifact.split("\n", -1)) {
            if (line.startsWith("## ")) {
                if (inRoster) {
                    break;
                }
                inRoster = line.contains("人物全量名册");
                if (inRoster) {
                    roster.append(line).append('\n');
                }
                continue;
            }
            if (inRoster) {
                roster.append(line).append('\n');
            }
        }
        String section = roster.toString().strip();
        return section.isEmpty() ? null : section;
    }

    // ==================== S3 分批生成人名工具（planner-s3-batch-generate tasks 1.3，D7） ====================

    /** 名册条目行锚：`- **人名**`（主人物多行块与次要人物单行同锚，D7 双形态统一） */
    private static final Pattern ROSTER_NAME_LINE = Pattern.compile("^\\s*-\\s*\\*\\*(.+?)\\*\\*.*$");

    /** 候选卡标题序号前缀：`人物 N：`（锚定 CharactersGenerator 成稿模板，全/半角冒号兼容） */
    private static final Pattern CHARACTER_TITLE_PREFIX = Pattern.compile("^\\u4eba\\u7269\\s*\\d+\\s*[\\uff1a:]\\s*(.*)$");

    /**
     * 名册人名清单提取（D7 双形态统一锚）：每个条目块首行取加粗体人名（主人物多行块
     * 与次要人物 {@code - **乌彩蝶**：巫山派…} 单行同锚）。保序；null/空名册返回空列表
     *（调用方据此判「无名稿回退单批」）。与 {@link #extractRosterBlocks} 同序对应。
     */
    public static List<String> extractRosterNames(String rosterSection) {
        List<String> names = new ArrayList<>();
        for (String block : extractRosterBlocks(rosterSection)) {
            String name = rosterBlockName(block);
            if (name != null && !name.isEmpty() && !containsName(names, name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 名册条目块提取（分批切块单元，planner-s3-batch-generate 2.1）：锚行
     * （{@code - **人名**}）开启新块，后续非锚非标题行归入当前块（主人物多行块
     * 保留身份描述——批内 prompt 注入本批名册块时信息量不丢）；子标题行终止
     * 当前块（节内 {@code ### 主人物} 分组不跨块吸收）。保序；与
     * {@link #extractRosterNames} 同序对应。
     */
    public static List<String> extractRosterBlocks(String rosterSection) {
        List<String> blocks = new ArrayList<>();
        if (rosterSection == null || rosterSection.isBlank()) {
            return blocks;
        }
        StringBuilder current = null;
        for (String line : rosterSection.split("\n", -1)) {
            if (ROSTER_NAME_LINE.matcher(line).matches()) {
                if (current != null) {
                    blocks.add(current.toString().strip());
                }
                current = new StringBuilder(line.strip());
            } else if (current != null) {
                String stripped = line.strip();
                if (stripped.isEmpty()) {
                    continue;
                }
                if (stripped.startsWith("#")) {
                    blocks.add(current.toString().strip());
                    current = null;
                } else {
                    current.append('\n').append(stripped);
                }
            }
        }
        if (current != null) {
            blocks.add(current.toString().strip());
        }
        return blocks;
    }

    /** 条目块首行锚取人名（非锚首行返回 null——不可达，防御）。 */
    private static String rosterBlockName(String block) {
        if (block == null || block.isBlank()) {
            return null;
        }
        Matcher matcher = ROSTER_NAME_LINE.matcher(block.split("\n", -1)[0]);
        return matcher.matches() ? matcher.group(1).strip() : null;
    }

    /**
     * 批内名册块提取（planner-s3-batch-generate tasks 2.1）：名册条目块中
     * 首行人名命中给定人名清单的子集（保序，容差匹配 D7）——S3 分批编排的
     * 批内 prompt 只带本批名册块（spec「批内 prompt 不膨胀」），切块单元即
     * 条目块（主人物多行块的身份描述不丢）。人名无对应块时静默跳过
     *（名册编辑后的容错——漂移人名进不了任何批块由剩余清单重跑兑付）。
     */
    public static List<String> rosterBlocksForNames(String rosterSection, List<String> names) {
        List<String> matched = new ArrayList<>();
        if (rosterSection == null || rosterSection.isBlank() || names == null || names.isEmpty()) {
            return matched;
        }
        for (String block : extractRosterBlocks(rosterSection)) {
            String blockName = rosterBlockName(block);
            if (blockName != null && containsName(names, blockName)) {
                matched.add(block);
            }
        }
        return matched;
    }

    /** 人物卡身份钩子行：`- 身份：`（全/半角冒号，锚定 CharactersGenerator 成稿模板） */
    private static final Pattern IDENTITY_LINE = Pattern.compile("^\\s*-\\s*身份\\s*[\\uff1a:]\\s*(.+)$");

    /**
     * S3 紧凑索引行拼装（planner-s3-batch-generate tasks 4.1 / design D4 现算不缓存）：
     * 每张非毙候选卡一行 {@code - 人名：身份钩子（命定）}——人名取卡标题（剥
     * 「人物 N：」前缀）、身份钩子取卡内「- 身份：」首行值、命定标记取
     * 「### 命定档案」子节存在性。S3 是 L2 逐卡可编辑阶段（卡内容随时会改，
     * 缓存索引必然过期）；现算成本是每卡抽两行（微不足道）。调用方负责
     * REJECTED 过滤（与 readStageFullText 同口径，D4）。
     * 无有效行返回 null（调用方零注入）。
     */
    public static String compactCharacterIndex(List<String> keptCandidates) {
        if (keptCandidates == null || keptCandidates.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String candidate : keptCandidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String name = characterNameOfTitle(StageDraftSet.titleOf(candidate));
            if (name == null || name.isEmpty()) {
                continue;
            }
            sb.append("- ").append(name);
            String identity = identityHookOf(candidate);
            if (identity != null && !identity.isEmpty()) {
                sb.append("：").append(identity);
            }
            if (candidate.contains("### 命定档案")) {
                sb.append("（命定）");
            }
            sb.append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 卡内身份钩子提取（首行命中值；无身份行返回 null）。 */
    private static String identityHookOf(String candidate) {
        for (String line : candidate.split("\n", -1)) {
            Matcher matcher = IDENTITY_LINE.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1).strip();
            }
        }
        return null;
    }

    /**
     * 候选卡标题人名提取：{@code 人物 N：<人物名>} 剥序号前缀取人名（复用
     * {@code InstallParser.parseCharacter} 同款惯例）；无前缀的标题（L2 逐卡
     * 编辑后直呼其名如 {@code ## 甲}）整标题即名（漂移容差）。null/空白返回 null。
     */
    public static String characterNameOfTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String stripped = title.strip();
        Matcher matcher = CHARACTER_TITLE_PREFIX.matcher(stripped);
        if (matcher.matches()) {
            String name = matcher.group(1).strip();
            return name.isEmpty() ? stripped : name;
        }
        return stripped;
    }

    /**
     * 候选标题人名候选集（名册名↔标题容差匹配基，6.3 E2E 发现）：剥前缀人名
     * 之外补充「序号前缀本身」——成稿模板 {@code 人物 N：<名>} 的 N 是 LLM
     * 自编序号，当名册名本身是「人物NNN」形（编号名）时冒号后是 LLM 自拟
     * 身份名、前缀才是名册名；单候选提取会取到身份名，致 rosterRemaining
     * 与重名检测双侧失配（剩余虚高 / 重复卡漏检）。无「人物 N：」前缀但含
     * 冒号的标题（分批锚定句产出的 {@code <名>：<描述>} 标准形态）补「冒号
     * 前段」候选——冒号前即名册人名、整标题兜底；纯名单名（L2 直呼其名）
     * 整标题即名（原语义不变）。空/空白标题返回空集。
     */
    public static List<String> titleNameCandidates(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String stripped = title.strip();
        String primary = characterNameOfTitle(stripped);
        Matcher matcher = CHARACTER_TITLE_PREFIX.matcher(stripped);
        if (matcher.matches()) {
            String prefix = stripped.substring(0, matcher.start(1))
                    .replaceAll("[\\uff1a:]\\s*$", "").strip();
            List<String> candidates = new ArrayList<>(2);
            candidates.add(primary);
            if (!prefix.isBlank() && !sameName(prefix, primary)) {
                candidates.add(prefix);
            }
            return candidates;
        }
        int colon = stripped.indexOf('：');
        if (colon < 0) {
            colon = stripped.indexOf(':');
        }
        if (colon > 0) {
            String name = stripped.substring(0, colon).strip();
            if (!name.isEmpty() && !sameName(name, primary)) {
                return List.of(name, primary);
            }
        }
        return List.of(primary);
    }

    /** 标题与名册名容差匹配（候选集展开比对，任一命中即算同人）。 */
    public static boolean titleMatchesName(String title, String name) {
        for (String candidate : titleNameCandidates(title)) {
            if (sameName(candidate, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 候选集中命中名册人名域的子集（重名检测的身份比对键，6.3 E2E 后真实使用
     * 发现）：{@code 人物 N：} 的序号前缀是 LLM 自编排版序号——名册名为真实
     * 人名时「人物 N」不是身份，跨轮/跨批的自编序号天然撞车（返工轮候选
     * 「人物 1：甲」与分批产出「人物 1：乙」的「人物 1」互撞，致整批误判
     * 重名全丢）；名册名本身为编号形（「人物NNN」）时序号前缀命中名册、仍
     * 为身份键（6.3 E2E 双候选语义保持）；{@code <名>：<描述>} 形标题的冒号
     * 前段命中名册即为身份键（断点续跑的已成功批人物不重复生成）。名册域
     * 外的候选不判身份——宁保留人工毙，不误杀（spec「宁重复不漏人」）。
     */
    public static List<String> rosterHitNames(String title, List<String> rosterNames) {
        if (rosterNames == null || rosterNames.isEmpty()) {
            return List.of();
        }
        return titleNameCandidates(title).stream()
                .filter(candidate -> containsName(rosterNames, candidate))
                .collect(Collectors.toList());
    }

    /**
     * 人名容差匹配（D7）：精确匹配 + 去空白差异匹配（全/半角空格与零宽差异的
     * 兼容）；匹配不上的一律视为不同人——宁重复提示重跑该批，不静默漏人。
     */
    public static boolean sameName(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b) || normalizeName(a).equals(normalizeName(b));
    }

    /** 人名去空白归一化（D7 容差匹配基）。 */
    public static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "");
    }

    /** 名单容差查找（D7）。 */
    public static boolean containsName(List<String> names, String name) {
        for (String candidate : names) {
            if (sameName(candidate, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 名册剩余清单计算（spec「补一批」：名册 − 已产出 − 已毙）：已产出人名取
     * manifest 候选行标题（含 REJECTED —— 毙掉即出局，不进补批批内名册）。
     * 容差匹配；保序（名册序即分批切块序）。
     */
    public static List<String> rosterRemaining(List<String> rosterNames,
                                                List<String> producedTitles) {
        List<String> remaining = new ArrayList<>();
        for (String name : rosterNames) {
            boolean produced = false;
            for (String title : producedTitles) {
                if (titleMatchesName(title, name)) {
                    produced = true;
                    break;
                }
            }
            if (!produced) {
                remaining.add(name);
            }
        }
        return remaining;
    }

    /**
     * 返工约束段（D5：带反馈的整轮重生成）。
     * <p>
     * 首轮（无反馈历史）返回空串；返工轮注入四件套：上轮全稿、反馈历史、
     * 「未提及部分保持不变」约束、已毙候选显式排除表。
     */
    public static String reworkSection(GenerationContext context) {
        if (!context.isRework()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【返工要求】\n");
        sb.append("这是第 ").append(context.reworkRound()).append(" 轮返工。以下是此前的反馈历史（从早到晚）：\n");
        context.rounds().forEach(round ->
                sb.append("- 第 ").append(round.getRound()).append(" 轮反馈：").append(round.getFeedback()).append('\n'));
        sb.append("""
                请依据全部反馈重新生成完整稿件。硬约束：
                1. 反馈未提及的部分保持与上轮一致，不要自行改动
                2. 逐条回应反馈中点名的问题
                3. 输出仍为完整全稿（非补丁片段）
                """);
        if (context.currentDraft() != null && !context.currentDraft().isBlank()) {
            sb.append("\n【上轮全稿】\n").append(context.currentDraft()).append("\n\n");
        }
        if (context.rejectedTitles() != null && !context.rejectedTitles().isEmpty()) {
            sb.append("【已毙候选排除表（禁止再生成下列内容或其近似变体）】\n");
            context.rejectedTitles().forEach(title -> sb.append("- ").append(title).append('\n'));
            sb.append('\n');
        }
        return sb.toString();
    }
}
