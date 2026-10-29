package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开场白服务。
 * <p>
 * 加载工坊生成的开场白模板，执行变量插值（从 WorldSetting、CharacterCard、GameState
 * 读取值替换 {@code {{}}} 占位符），返回插值后的纯叙述文本。
 * <p>
 * 命定人物模式（preset-destined-figures design D4）：playerStates 携带 figure 键的开局
 * 走 LLM 个性化开场白（人物身份 + 史实处境 + 1556 稻生合战前夕格局），失败降级模板路径。
 */
@Component
public class OpeningNarrationService {

    private static final Logger log = LoggerFactory.getLogger(OpeningNarrationService.class);

    /** 匹配 {{placeholder}} 格式的占位符 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");
    
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgGameStateRepository stateRepo;
    private final WorkshopLlmGenerator llmGenerator;

    public OpeningNarrationService(RpgWorldSettingRepository worldRepo,
                                    RpgCharacterCardRepository charRepo,
                                    RpgGameStateRepository stateRepo,
                                    WorkshopLlmGenerator llmGenerator) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.stateRepo = stateRepo;
        this.llmGenerator = llmGenerator;
    }

    /**
     * 生成开场白。
     *
     * @param gameStateId 游戏状态 ID
     * @return 插值后的开场白文本，或 null（模板未配置）
     */
    public String generateOpening(String gameStateId) {
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            log.warn("游戏状态未找到: {}", gameStateId);
            return null;
        }

        WorldSetting world = worldRepo.findById(gs.getWorldId());
        if (world == null) {
            log.warn("世界未找到: worldId={}", gs.getWorldId());
            return null;
        }

        CharacterCard player = charRepo.findById(gs.getPlayerCharId());

        // 命定人物模式（preset-destined-figures design D4 / tasks 6.1）：LLM 个性化开场白，
        // 人物身份与史实处境入 prompt；异常/空产出降级模板路径，人物局不因 LLM 故障阻断开局
        if (!readPlayerState(gs, "figure_key").isBlank()) {
            String personalized = tryPersonalizedOpening(world, player, gs);
            if (personalized != null) {
                return personalized;
            }
        }

        if (world.getOpeningTemplate() == null || world.getOpeningTemplate().isBlank()) {
            log.warn("开场白模板未配置: worldId={}", gs.getWorldId());
            return null;
        }

        return interpolate(world.getOpeningTemplate(), world, player, gs);
    }

    /**
     * 命定人物个性化开场白（tasks 6.1）：figure 三元组从 playerStates 读取，
     * 人物名与史实处境入 prompt。生成结果不缓存（每次开局现场生成）；
     * LLM 异常或空产出返回 null，由调用方降级模板路径。
     */
    private String tryPersonalizedOpening(WorldSetting world, CharacterCard player, GameState gs) {
        try {
            String text = llmGenerator.generatePersonalizedOpening(world, player,
                    readPlayerState(gs, "figure_display_name"),
                    readPlayerState(gs, "figure_era_note"));
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("命定人物个性化开场白生成失败，降级模板路径: gameStateId={}, error={}",
                    gs.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 执行变量插值。
     * <p>
     * 支持的占位符：
     * <ul>
     *   <li>{@code {{world_name}}} - 世界名称</li>
     *   <li>{@code {{era}}} - 时代</li>
     *   <li>{@code {{atmosphere}}} - 氛围</li>
     *   <li>{@code {{setting_desc}}} - 世界描述</li>
     *   <li>{@code {{player_name}}} - 玩家名</li>
     *   <li>{@code {{player_identity}}} - 玩家身份</li>
     *   <li>{@code {{player_background}}} - 玩家背景</li>
     *   <li>{@code {{current_location}}} - 当前位置</li>
     *   <li>{@code {{turn_count}}} - 当前轮次</li>
     *   <li>{@code {{constellation}}} - 星座/星辰名（从 playerStates 读取）</li>
     *   <li>{@code {{star_rank}}} - 星阶（从 playerStates 读取）</li>
     *   <li>{@code {{faction}}} - 阵营（从 playerStates 读取）</li>
     *   <li>{@code {{zodiac_traits}}} - 星座特质（从 playerStates 读取）</li>
     * </ul>
     */
    private String interpolate(String template, WorldSetting world,
                               CharacterCard player, GameState gs) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = resolvePlaceholder(placeholder, world, player, gs);
            matcher.appendReplacement(result, replacement != null ? replacement : "");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String resolvePlaceholder(String key, WorldSetting world,
                                       CharacterCard player, GameState gs) {
        return switch (key) {
            case "world_name" -> world.getName();
            case "era" -> world.getEra();
            case "atmosphere" -> world.getAtmosphere();
            case "setting_desc" -> world.getSettingDesc();
            case "player_name" -> player != null ? player.getName() : "";
            case "player_identity" -> player != null ? player.getIdentity() : "";
            case "player_background" -> player != null ? player.getBackground() : "";
            case "current_location" -> gs.getCurrentLocation();
            case "turn_count" -> String.valueOf(gs.getTurnCount());
            case "constellation" -> readPlayerState(gs, "constellation");
            case "star_rank" -> readPlayerState(gs, "star_rank");
            case "faction" -> readPlayerState(gs, "faction");
            case "zodiac_traits" -> readPlayerState(gs, "zodiac_traits");
            default -> {
                log.debug("未知占位符: {}", key);
                yield null;
            }
        };
    }

    /**
     * 从 GameState.playerStates JSON 中读取星命字段。
     * <p>
     * playerStates 格式如 {"constellation":"白羊座","star_rank":"Gold",...}。
     * 缺失时返回空字符串，不抛异常。
     */
    private String readPlayerState(GameState gs, String key) {
        String playerStates = gs.getPlayerStates();
        if (playerStates == null || playerStates.isBlank()) {
            return "";
        }
        try {
            JsonNode root = JSON.readTree(playerStates);
            JsonNode node = root.path(key);
            if (node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.asText("");
        } catch (Exception e) {
            log.warn("读取 playerStates 失败: key={}, error={}", key, e.getMessage());
            return "";
        }
    }
}
