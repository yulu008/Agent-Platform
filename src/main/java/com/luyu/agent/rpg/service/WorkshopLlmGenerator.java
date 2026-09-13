package com.luyu.agent.rpg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.WorldSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

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
    private static final ObjectMapper mapper = new ObjectMapper();

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
     * 存量罗马音/英文名字批量汉字化改名提示词。
     * <p>
     * 输出为 {@code {旧名: 新汉字名}} 映射，校验矩阵在 {@code WorkshopService.localizeNames} 侧执行。
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
               西式名 Elena →「艾莲娜」一类中文译名）
            3. 新名彼此不得重复，也不得与已是汉字的名字重复
            4. 只输出 JSON，key 必须与待改名清单中的旧名完全一致（不得做大小写或空格变体），value 为新汉字名

            JSON 格式：
            {
              "旧名1": "新汉字名1",
              "旧名2": "新汉字名2"
            }

            请仅输出 JSON，不要包含其他文本。
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
     *
     * @param description 角色描述
     * @param type        角色类型（player/npc）
     * @return 解析后的 CharacterCard 对象
     */
    public CharacterCard generateCharacter(String description, String type) {
        String prompt = String.format(CHARACTER_GEN_PROMPT, description, type, type);
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
     * LLM 批量生成「旧名 → 新汉字名」映射（工坊一键中文化改名用）。
     * <p>
     * 单次调用、无 advisor 的 workshop 纯净 client。本方法只负责调用与解析，
     * <b>不做任何校验也不写库</b>；覆盖完整性、CJK 含量、重名与冲突校验由
     * {@code WorkshopService.localizeNames} 在事务前统一执行。
     *
     * @param cards 待改名的角色卡（已筛出含拉丁字母的名字）
     * @param world 世界观（提供文化风格依据）
     * @return 旧名 → 新名映射（保序，已剔除空白项）；入参为空时返回空映射（不调 LLM）
     * @throws RuntimeException LLM 调用或 JSON 解析失败
     */
    public Map<String, String> renameToChinese(List<CharacterCard> cards, WorldSetting world) {
        if (cards == null || cards.isEmpty()) {
            return Map.of();
        }
        String listing = cards.stream()
                .map(c -> String.format("- %s（%s，身份：%s）",
                        c.getName(),
                        "player".equals(c.getType()) ? "玩家角色" : "NPC",
                        blankTo(c.getIdentity(), "未设定")))
                .collect(Collectors.joining("\n"));
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

    private String formatWorld(WorldSetting world) {
        return String.format("名称=%s, 时代=%s, 描述=%s, 规则=%s, 氛围=%s",
                world.getName(), world.getEra(), world.getSettingDesc(),
                world.getRules(), world.getAtmosphere());
    }

    private String formatCard(CharacterCard card) {
        return String.format("名称=%s, 身份=%s, 性格=%s, 背景=%s, 动机=%s",
                card.getName(), card.getIdentity(), card.getPersonality(),
                card.getBackground(), card.getMotivation());
    }
}
