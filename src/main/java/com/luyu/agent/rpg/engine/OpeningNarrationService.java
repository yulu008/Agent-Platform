package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.CharacterCard;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.WorldSetting;
import com.luyu.agent.rpg.repository.RpgCharacterCardRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgWorldSettingRepository;
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
 */
@Component
public class OpeningNarrationService {

    private static final Logger log = LoggerFactory.getLogger(OpeningNarrationService.class);

    /** 匹配 {{placeholder}} 格式的占位符 */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    private final RpgWorldSettingRepository worldRepo;
    private final RpgCharacterCardRepository charRepo;
    private final RpgGameStateRepository stateRepo;

    public OpeningNarrationService(RpgWorldSettingRepository worldRepo,
                                    RpgCharacterCardRepository charRepo,
                                    RpgGameStateRepository stateRepo) {
        this.worldRepo = worldRepo;
        this.charRepo = charRepo;
        this.stateRepo = stateRepo;
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
        if (world == null || world.getOpeningTemplate() == null || world.getOpeningTemplate().isBlank()) {
            log.warn("开场白模板未配置: worldId={}", gs.getWorldId());
            return null;
        }

        CharacterCard player = charRepo.findById(gs.getPlayerCharId());

        return interpolate(world.getOpeningTemplate(), world, player, gs);
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
            default -> {
                log.debug("未知占位符: {}", key);
                yield null;
            }
        };
    }
}
