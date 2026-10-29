package com.luyu.agent.rpg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.DestinedFigure;
import com.luyu.agent.rpg.model.WorldbookEntry;
import com.luyu.agent.rpg.model.WorldSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工坊 LLM 生成器。
 * <p>
 * 用 {@link ChatClientRegistry#forRole(String)} 取 workshop 角色的纯净 client（无 advisor）调用默认模型，
 * 实现模板式 LLM 生成：关键词 → 完整世界观设定 / 完整角色卡 / 开场白模板。
 * 注意：不能用 forChat()——对话 client 挂 SessionMemoryAdvisor，单次调用无 sessionId 会直接抛异常。
 */
@Service
public class WorkshopLlmGenerator {

    private static final Logger log = LoggerFactory.getLogger(WorkshopLlmGenerator.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String WORLD_GEN_PROMPT = """
            请根据以下关键词生成一个 RPG 世界观设定，以 JSON 格式输出。
            关键词：%s

            命名硬约束：所有名称（世界名/角色名/地点名）必须使用汉字，且与世界文化风格一致
            （如日式世界用「东狼」式汉字名）；禁止罗马音、纯英文。

            JSON 格式要求：
            {
              "name": "世界名称",
              "settingDesc": "世界描述（100-200字）",
              "era": "时代背景",
              "rules": "[\"规则1\", \"规则2\", \"规则3\"]",
              "atmosphere": "氛围描述",
              "openingTemplate": "开场白模板，使用 {{world_name}} {{player_name}} {{era}} 等占位符"
            }

            请仅输出 JSON，不要包含其他文本。
            """;

    private static final String CHARACTER_GEN_PROMPT = """
            请根据以下描述生成一个 RPG 角色卡，以 JSON 格式输出。
            描述：%s
            角色类型：%s

            命名硬约束：角色名称必须使用汉字，且与世界文化风格一致
            （如日式世界用「东狼」式汉字名）；禁止罗马音、纯英文。

            JSON 格式要求：
            {
              "name": "角色名称",
              "type": "%s",
              "identity": "身份（如：流浪剑客、客栈小二、山贼）",
              "personality": "性格描述",
              "background": "背景故事（50-100字）",
              "motivation": "动机（如：贪财、好奇、复仇）",
              "speechStyle": "说话风格（如：粗犷、文雅、油滑）",
              "knowledge": "[\"知识领域1\", \"知识领域2\"]"
            }

            请仅输出 JSON，不要包含其他文本。
            """;

    private static final String OPENING_GEN_PROMPT = """
            请根据以下世界观和玩家角色信息，生成一个 RPG 开场白模板。
            开场白应该是沉浸式的第三人称叙述（100-200字），介绍世界背景和玩家处境。
            使用 {{}} 占位符引用变量（如 {{world_name}}, {{player_name}}, {{era}} 等）。

            世界观：%s
            玩家角色：%s

            请直接输出开场白模板文本，不要包含其他说明。
            """;

    /**
     * 命定人物开局个性化开场白提示词（preset-destined-figures design D4 / tasks 6.1）。
     * <p>
     * 与 {@link #OPENING_GEN_PROMPT} 的模板路径不同：产出为<b>直接叙述文本</b>（无占位符），
     * 由开局时按存档 playerStates 现场生成，不落 world.openingTemplate（模板全 world 共享，
     * 不能被人物局污染）。prompt 注入人物身份、史实处境（eraNote）与 1556 稻生合战前夕格局；
     * 无名新人维持模板路径零改动。
     */
    private static final String FIGURE_OPENING_GEN_PROMPT = """
            请根据以下世界观与命定人物身份，生成一个 RPG 开场白。
            开场白是沉浸式的第三人称叙述（150-250字），以玩家所扮演的命定历史人物为主角。

            世界观：%s
            玩家角色：%s
            命定人物：%s
            史实处境：%s

            硬性要求：
            1. 叙述以「玩家即该人物本人」的视角展开：点出其身份、当下的处境与抉择
            2. 融入 1556 年（弘治二年）稻生合战前夕的格局氛围——下克上之风席卷列国，
               织田兄弟陈兵相向，群雄各自厉兵秣马
            3. 结尾停在可行动的处境上，为玩家留出第一步抉择的空间
            4. 命名硬约束：所有称谓使用汉字；禁止罗马音、纯英文
            5. 请直接输出开场白文本，不要包含其他说明

            请直接输出开场白文本，不要包含其他说明。
            """;

    /**
     * 存量罗马音/英文名字批量汉字化改名提示词。
     * <p>
     * 输出为 {@code {旧名: 新汉字名}} 映射，校验矩阵在 {@code WorkshopService.localizeNames} 侧执行。
     * 清单包含两类条目：角色卡（带身份）与无角色卡的剧情临时 NPC 名（带存档状态摘要）。
     */
    private static final String RENAME_GEN_PROMPT = """
            以下 RPG 世界的角色名使用了罗马音或英文，请把每个名字改写为汉字名。

            世界名称：%s
            时代背景：%s
            世界氛围：%s

            待改名清单：
            %s

            改写要求：
            1. 新名必须全部使用汉字，禁止罗马音、拉丁字母与纯英文
            2. 保留原名的文化风格与读音意象（如日式名 okami azuma →「大神东」一类汉字名；
               西式名 Elena →「艾莲娜」一类中文译名）；若能从读音推断出合理的原名用字，优先沿用该写法
            3. 新名彼此不得重复，也不得与已是汉字的名字重复
            4. 只输出 JSON，key 必须与待改名清单中的旧名完全一致（不得做大小写或空格变体），value 为新汉字名

            JSON 格式：
            {
              "旧名1": "新汉字名1",
              "旧名2": "新汉字名2"
            }

            请仅输出 JSON，不要包含其他文本。
            """;

    /**
     * 预设向导步骤②三件套（身份/性格/背景）的星命改写提示词（preset-llm-identity-trio / design D5）。
     * <p>
     * 语义是「按星命改写」而非「润色用户底稿」：不喂用户当前三件套，moderation 面收敛到姓名一个自由文本。
     * 星命一致性硬约束入 prompt：身份不得与星阶/阵营矛盾（青铜不得产出黄金圣斗士、specter 不得产出圣斗士）。
     */
    private static final String ORIGIN_TRIO_GEN_PROMPT = """
            以下是角色「%s」的星命推演结果，请据此生成角色卡的身份、性格、背景三项，以 JSON 格式输出。

            性别：%s
            星座：%s
            星阶：%s
            阵营：%s
            星座特质：%s
            招式：%s

            硬性要求：
            1. identity（身份）必须与星阶、阵营一致：身份不得与星阶/阵营矛盾
               （如星阶为青铜圣斗士时不得写成黄金圣斗士；阵营为冥斗士时不得写成圣斗士身份）
            2. personality（性格）由星座特质自然展开，不得与特质矛盾
            3. background（背景故事）融合出生年份、星座与阵营叙事，50-150 字
            4. 命名硬约束：所有称谓必须使用汉字；禁止罗马音、纯英文
            5. 只输出 JSON，不要包含其他文本

            JSON 格式要求：
            {
              "identity": "身份",
              "personality": "性格描述",
              "background": "背景故事（50-150字）"
            }

            请仅输出 JSON，不要包含其他文本。
            """;

    /**
     * 命定人物模式三件套追加段（preset-destined-figures design D4）。
     * <p>
     * 拼接在 {@link #ORIGIN_TRIO_GEN_PROMPT} 主体之后：三件套须贴合档案史实底稿改写，
     * 可融合星命润色但不逐字照抄（用户在步骤②仍可自由编辑）。
     */
    private static final String FIGURE_TRIO_PROMPT_SECTION = """
            命定人物模式追加要求：该角色「%s」为命定历史人物，不是无名新人——
            人物身份底稿：%s
            人物性格底稿：%s
            史实处境：%s
            请让三件套贴合上述史实身份与处境改写（身份/性格/背景与人物档案语义一致），
            可融合星命推演结果润色，但不要逐字照抄底稿。
            """;

    /**
     * 世界书条目批量生成提示词（add-rpg-worldbook）。
     * <p>
     * 输出 JSON 数组（3-6 条 {title, keywords, content}）；命名为汉字硬约束，
     * 关键词为逗号分隔触发集，正文供命中时注入 GM 叙事。
     */
    private static final String WORLDBOOK_GEN_PROMPT = """
            请根据以下关键词，为 RPG 世界生成一批「世界书」设定条目，以 JSON 数组格式输出。
            关键词：%s
            世界背景：%s

            世界书条目是「名词 → 设定注解」式的碎片知识：某座城的来历、某件法器的副作用、某个组织的暗规等。
            玩家在对话中提到条目关键词时，系统会把该条设定注入给 GM 作为叙事依据。

            命名硬约束：所有名称必须使用汉字，且与世界文化风格一致；禁止罗马音、纯英文。

            生成要求：
            1. 生成 3-6 条彼此独立、互不重复的条目
            2. title 为条目核心名词（如某地名/器物名/组织名）
            3. keywords 为逗号分隔的触发词集（含 title 及其别名/相关词），玩家提到任一词即命中
            4. content 为该条设定正文（50-150 字），提供可直接用于叙事的细节
            5. 只输出 JSON 数组，不要包含其他文本

            JSON 格式要求：
            [
              { "title": "条目标题", "keywords": "关键词1,关键词2", "content": "设定正文" }
            ]

            请仅输出 JSON 数组，不要包含其他文本。
            """;

    private final ChatClientRegistry chatClientRegistry;

    public WorkshopLlmGenerator(ChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    /**
     * LLM 生成完整世界观设定。
     *
     * @param keywords 关键词描述
     * @return 解析后的 WorldSetting 对象
     */
    public WorldSetting generateWorld(String keywords) {
        String prompt = String.format(WORLD_GEN_PROMPT, keywords);
        String response = callLlm(prompt);
        if (response == null) {
            throw new RuntimeException("LLM 生成世界观失败");
        }
        try {
            String json = extractJson(response);
            return mapper.readValue(json, WorldSetting.class);
        } catch (Exception e) {
            log.error("解析 LLM 世界观输出失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析世界观失败: " + e.getMessage());
        }
    }

    /**
     * LLM 生成完整角色卡。
     * <p>
     * 创建时不区分玩家/NPC，类型固定为 npc；"谁是玩家"由开始冒险时的 playerCharId 运行时确定。
     *
     * @param description 角色描述
     * @return 解析后的 CharacterCard 对象
     */
    public CharacterCard generateCharacter(String description) {
        String prompt = String.format(CHARACTER_GEN_PROMPT, description, "npc", "npc");
        String response = callLlm(prompt);
        if (response == null) {
            throw new RuntimeException("LLM 生成角色卡失败");
        }
        try {
            String json = extractJson(response);
            return mapper.readValue(json, CharacterCard.class);
        } catch (Exception e) {
            log.error("解析 LLM 角色卡输出失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析角色卡失败: " + e.getMessage());
        }
    }

    /**
     * LLM 批量生成世界书条目草稿（工坊「世界书」tab 用）。
     * <p>
     * 单次调用、无 advisor 的 workshop 纯净 client（{@link #callLlm}），计量 call type "workshop"。
     * 返回草稿列表<b>不落库</b>——id/worldId 置空、enabled 默认 true、sortOrder 顺序编号，
     * 由前端预填编辑区、用户逐条确认后经保存端点写入。标题为空的无效草稿被过滤。
     *
     * @param keywords     生成关键词
     * @param worldContext 世界背景（名称/时代/氛围等，供 LLM 对齐文化风格；可为空）
     * @return 世界书条目草稿列表（3-6 条）
     * @throws RuntimeException LLM 调用失败、输出无法解析或无有效条目
     */
    public List<WorldbookEntry> generateWorldbookEntries(String keywords, String worldContext) {
        String prompt = String.format(WORLDBOOK_GEN_PROMPT,
                blankTo(keywords, "（未提供）"), blankTo(worldContext, "（未设定）"));
        String response = callLlm(prompt);
        if (response == null) {
            throw new RuntimeException("LLM 生成世界书条目失败");
        }
        try {
            List<WorldbookEntry> parsed = mapper.readValue(extractJson(response),
                    new TypeReference<List<WorldbookEntry>>() {});
            List<WorldbookEntry> drafts = new ArrayList<>();
            int order = 0;
            for (WorldbookEntry draft : parsed) {
                if (draft.getTitle() == null || draft.getTitle().isBlank()) {
                    continue;
                }
                draft.setId(null);
                draft.setWorldId(null);
                draft.setEnabled(Boolean.TRUE);
                draft.setSortOrder(order++);
                drafts.add(draft);
            }
            if (drafts.isEmpty()) {
                throw new RuntimeException("世界书条目为空");
            }
            log.info("LLM 世界书草稿已生成: {} 条", drafts.size());
            return drafts;
        } catch (Exception e) {
            log.error("解析 LLM 世界书输出失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析世界书条目失败: " + e.getMessage());
        }
    }

    /**
     * 预设向导步骤②三件套的生成结果（identity/personality/background 三字段 JSON）。
     */
    public record OriginTrio(String identity, String personality, String background) {}

    /**
     * LLM 按星命推演结果生成三件套（身份/性格/背景）。
     * <p>
     * 单次调用、无 advisor 的 workshop 纯净 client（{@link #callLlm}），计量 call type "workshop"。
     * 三字段任一缺失或空白视为解析失败，抛错由控制器转 500，前端保留输入框原值。
     *
     * @param techniques 招式名列表（可为 null/空，prompt 中显示「无」）
     * @return 三字段生成结果
     * @throws RuntimeException LLM 调用失败或输出无法解析为完整三字段 JSON
     */
    public OriginTrio generateOriginTrio(String name, String gender, String constellation,
                                         String starRank, String faction,
                                         String zodiacTraits, List<String> techniques) {
        return generateOriginTrio(name, gender, constellation, starRank, faction,
                zodiacTraits, techniques, null);
    }

    /**
     * LLM 按星命推演结果生成三件套（命定人物模式，preset-destined-figures tasks 6.2）。
     * <p>
     * figure 非空时 prompt 追加人物段：人物名、档案底稿与史实处境入 prompt，
     * 语义由「按星命改写」升级为「按星命 + 人物身份改写」（design D4）；
     * figure 为 null 时与 7 参版行为完全一致（无名新人零改动）。
     *
     * @param figure 命定人物档案（null = 无名新人）
     */
    public OriginTrio generateOriginTrio(String name, String gender, String constellation,
                                         String starRank, String faction,
                                         String zodiacTraits, List<String> techniques,
                                         DestinedFigure figure) {
        String prompt = buildOriginTrioPrompt(name, gender, constellation,
                starRank, faction, zodiacTraits, techniques, figure);
        String response = callLlm(prompt);
        if (response == null) {
            throw new RuntimeException("LLM 生成三件套失败");
        }
        try {
            Map<String, String> trio = mapper.readValue(extractJson(response),
                    new TypeReference<Map<String, String>>() {});
            String identity = trio.get("identity");
            String personality = trio.get("personality");
            String background = trio.get("background");
            if (isBlank(identity) || isBlank(personality) || isBlank(background)) {
                throw new RuntimeException("三字段不完整");
            }
            return new OriginTrio(identity.strip(), personality.strip(), background.strip());
        } catch (Exception e) {
            log.error("解析 LLM 三件套输出失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析三件套失败: " + e.getMessage());
        }
    }

    /**
     * 组装三件套生成 prompt。包内可见供单测钉住星命一致性硬约束（spec「星阶一致性约束在册」）。
     */
    static String buildOriginTrioPrompt(String name, String gender, String constellation,
                                        String starRank, String faction,
                                        String zodiacTraits, List<String> techniques) {
        return buildOriginTrioPrompt(name, gender, constellation, starRank, faction,
                zodiacTraits, techniques, null);
    }

    /**
     * 组装三件套生成 prompt（人物模式版，preset-destined-figures tasks 6.2）。
     * figure 非空时 base 段后空一行接 {@link #FIGURE_TRIO_PROMPT_SECTION}；
     * figure 为 null 时输出与 7 参版逐字一致。包内可见供单测钉住。
     */
    static String buildOriginTrioPrompt(String name, String gender, String constellation,
                                        String starRank, String faction,
                                        String zodiacTraits, List<String> techniques,
                                        DestinedFigure figure) {
        String techText = techniques == null || techniques.isEmpty()
                ? "无" : String.join("、", techniques);
        String base = String.format(ORIGIN_TRIO_GEN_PROMPT,
                blankTo(name, "（未命名）"),
                blankTo(gender, "（未设定）"),
                blankTo(constellation, "（未设定）"),
                blankTo(starRank, "（未设定）"),
                blankTo(faction, "（未设定）"),
                blankTo(zodiacTraits, "（未设定）"),
                techText);
        if (figure == null) {
            return base;
        }
        return base + "\n\n" + String.format(FIGURE_TRIO_PROMPT_SECTION,
                blankTo(figure.getDisplayName(), "（未命名）"),
                blankTo(figure.getIdentity(), "（未设定）"),
                blankTo(figure.getPersonality(), "（未设定）"),
                blankTo(figure.getEraNote(), "（未设定）"));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * LLM 生成开场白模板。
     *
     * @param world       世界观设定
     * @param playerCard  玩家角色卡
     * @return 含 {{}} 占位符的开场白模板
     */
    public String generateOpeningTemplate(WorldSetting world, CharacterCard playerCard) {
        String prompt = String.format(OPENING_GEN_PROMPT,
                formatWorld(world), formatCard(playerCard));
        String response = callLlm(prompt);
        if (response == null) {
            throw new RuntimeException("LLM 生成开场白失败");
        }
        return response.trim();
    }

    /**
     * LLM 生成命定人物开局个性化开场白（preset-destined-figures tasks 6.1）。
     * <p>
     * figureKey 非空的开局走本路径：prompt 注入人物身份（角色卡三件套——人物模式下
     * 已预填档案底稿、用户可编辑过）、史实处境（eraNote）与 1556 稻生合战前夕格局；
     * 产出为直接叙述文本（非模板、无占位符），开局现场生成、不落 world.openingTemplate
     * （模板全 world 共享）。无名新人维持 {@link #generateOpeningTemplate} 零改动。
     * 单次调用、无 advisor 的 workshop 纯净 client（{@link #callLlm}），计量 call type "workshop"。
     *
     * @param world             世界观设定
     * @param playerCard        玩家角色卡（人物模式：三件套已预填档案底稿）
     * @param figureDisplayName 命定人物名（来自 playerStates figure_display_name）
     * @param figureEraNote     史实处境一句话（来自 playerStates figure_era_note）
     * @return 个性化开场白文本
     * @throws RuntimeException LLM 调用失败
     */
    public String generatePersonalizedOpening(WorldSetting world, CharacterCard playerCard,
                                               String figureDisplayName, String figureEraNote) {
        String prompt = buildPersonalizedOpeningPrompt(world, playerCard,
                figureDisplayName, figureEraNote);
        String response = callLlm(prompt);
        if (response == null) {
            throw new RuntimeException("LLM 生成人物开场白失败");
        }
        return response.trim();
    }

    /**
     * 组装人物开局个性化开场白 prompt。包内可见供单测钉住人物身份与时代处境要素
     * （spec「人物模式走 LLM 开场白」）。
     */
    static String buildPersonalizedOpeningPrompt(WorldSetting world, CharacterCard playerCard,
                                                 String figureDisplayName, String figureEraNote) {
        return String.format(FIGURE_OPENING_GEN_PROMPT,
                formatWorld(world),
                playerCard != null ? formatCard(playerCard) : "（未设定）",
                blankTo(figureDisplayName, "（未命名）"),
                blankTo(figureEraNote, "（未设定）"));
    }

    /**
     * 无角色卡的剧情临时 NPC 待改名条目：拼音/罗马音名 + 存档状态摘要（供 LLM 推断身份与用字）。
     */
    public record OrphanNpcName(String name, String context) {}

    /**
     * LLM 批量生成「旧名 → 新汉字名」映射（工坊一键中文化改名用）。
     * <p>
     * 单次调用、无 advisor 的 workshop 纯净 client。本方法只负责调用与解析，
     * <b>不做任何校验也不写库</b>；覆盖完整性、CJK 含量、重名与冲突校验由
     * {@code WorkshopService.localizeNames} 在事务前统一执行。
     *
     * @param cards   待改名的角色卡（已筛出含拉丁字母的名字）
     * @param orphans 待改名的无卡临时 NPC 名（来自存档 npc_states key，含拉丁字母且非卡 ID/卡名）
     * @param world   世界观（提供文化风格依据）
     * @return 旧名 → 新名映射（保序，已剔除空白项）；两份入参均为空时返回空映射（不调 LLM）
     * @throws RuntimeException LLM 调用或 JSON 解析失败
     */
    public Map<String, String> renameToChinese(List<CharacterCard> cards,
                                               List<OrphanNpcName> orphans,
                                               WorldSetting world) {
        boolean noCards = cards == null || cards.isEmpty();
        boolean noOrphans = orphans == null || orphans.isEmpty();
        if (noCards && noOrphans) {
            return Map.of();
        }
        String cardListing = noCards ? "" : cards.stream()
                .map(c -> String.format("- %s（%s，身份：%s）",
                        c.getName(),
                        "player".equals(c.getType()) ? "玩家角色" : "NPC",
                        blankTo(c.getIdentity(), "未设定")))
                .collect(Collectors.joining("\n"));
        String orphanListing = noOrphans ? "" : orphans.stream()
                .map(o -> String.format("- %s（剧情临时 NPC，无角色卡；存档状态：%s）",
                        o.name(), blankTo(o.context(), "未知")))
                .collect(Collectors.joining("\n"));
        String listing = noCards ? orphanListing
                : noOrphans ? cardListing
                : cardListing + "\n" + orphanListing;
        String prompt = String.format(RENAME_GEN_PROMPT,
                world == null ? "（未设定）" : blankTo(world.getName(), "（未设定）"),
                world == null ? "（未设定）" : blankTo(world.getEra(), "（未设定）"),
                world == null ? "（未设定）" : blankTo(world.getAtmosphere(), "（未设定）"),
                listing);
        String response = callLlm(prompt);
        if (response == null) {
            throw new RuntimeException("LLM 生成改名映射失败");
        }
        try {
            Map<String, String> raw = mapper.readValue(extractJson(response),
                    new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank() && !v.isBlank()) {
                    result.put(k.strip(), v.strip());
                }
            });
            log.info("LLM 改名映射已生成: {} 项", result.size());
            return result;
        } catch (Exception e) {
            log.error("解析 LLM 改名输出失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析改名映射失败: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String callLlm(String prompt) {
        try {
            ChatClient client = chatClientRegistry.forRole("workshop");
            return client.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "workshop"))
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private static String formatWorld(WorldSetting world) {
        return String.format("名称=%s, 时代=%s, 描述=%s, 规则=%s, 氛围=%s",
                world.getName(), world.getEra(), world.getSettingDesc(),
                world.getRules(), world.getAtmosphere());
    }

    private static String formatCard(CharacterCard card) {
        return String.format("名称=%s, 身份=%s, 性格=%s, 背景=%s, 动机=%s",
                card.getName(), card.getIdentity(), card.getPersonality(),
                card.getBackground(), card.getMotivation());
    }
}
