package com.luyu.agent.rpg.controller;

import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工坊 REST API。
 * <p>
 * 提供世界观/角色卡/地点/关系/触发器的 CRUD 端点，
 * 以及 LLM 模板式生成端点。
 */
@RestController
@RequestMapping("/rpg/workshop")
public class WorkshopController {

    private static final Logger log = LoggerFactory.getLogger(WorkshopController.class);

    private final WorkshopService workshopService;
    private final WorkshopLlmGenerator llmGenerator;
    private final ContentModerationGate moderationGate;
    private final QuotaGuard quotaGuard;

    public WorkshopController(WorkshopService workshopService,
                              WorkshopLlmGenerator llmGenerator,
                              ContentModerationGate moderationGate,
                              QuotaGuard quotaGuard) {
        this.workshopService = workshopService;
        this.llmGenerator = llmGenerator;
        this.moderationGate = moderationGate;
        this.quotaGuard = quotaGuard;
    }

    /**
     * 配额硬拒（tenant-token-metering / design D8）：工坊同步生成前的事前检查。
     * 超限返回 402 + {error} 友好提示（前端已有 errJson.error 分支呈现）；未超限返回 null；
     * 配额开关关闭时 QuotaGuard 直接放行（返回 null）。
     */
    private ResponseEntity<?> quotaRejection() {
        try {
            quotaGuard.check(TenantContext.getTenantId());
            return null;
        } catch (QuotaExceededException qe) {
            log.info("配额超限拒绝工坊生成: tenant={}", TenantContext.getTenantId());
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", qe.getMessage()));
        }
    }

    // ==================== 世界观 ====================

    @PostMapping("/world")
    public ResponseEntity<WorldSetting> saveWorld(@RequestBody WorldSetting world) {
        return ResponseEntity.ok(workshopService.saveWorld(world));
    }

    @GetMapping("/worlds")
    public List<WorldSetting> listWorlds() {
        return workshopService.listWorlds();
    }

    @GetMapping("/world/{id}")
    public ResponseEntity<WorldSetting> getWorld(@PathVariable String id) {
        WorldSetting world = workshopService.getWorld(id);
        return world != null ? ResponseEntity.ok(world) : ResponseEntity.notFound().build();
    }

    /**
     * 阻塞式删除世界观：存在子数据时 409 + 具体原因；不存在 404；成功 204。
     */
    @DeleteMapping("/world/{id}")
    public ResponseEntity<Object> deleteWorld(@PathVariable String id) {
        try {
            workshopService.deleteWorld(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/generate-world")
    public ResponseEntity<?> generateWorld(@RequestBody Map<String, String> request) {
        String keywords = request.get("keywords");
        if (keywords == null || keywords.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // 输入内容审查闸门（input-content-moderation）：调模型生成前同步审查提示词，
        // 命中返回拒答结构体而非生成结果（spec「命中处置」「工坊端点命中拒答」）。
        ModerationResult moderation = moderationGate.check(keywords);
        if (moderation.blocked()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("moderation", true, "error", moderationGate.refusalMessage()));
        }
        // 配额硬拒：调模型生成前事前检查（tenant-token-metering / design D8）
        ResponseEntity<?> quotaReject = quotaRejection();
        if (quotaReject != null) {
            return quotaReject;
        }
        try {
            WorldSetting world = llmGenerator.generateWorld(keywords);
            return ResponseEntity.ok(world);
        } catch (Exception e) {
            log.error("生成世界观失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 角色卡 ====================

    @PostMapping("/character")
    public ResponseEntity<CharacterCard> saveCharacter(@RequestBody CharacterCard card) {
        return ResponseEntity.ok(workshopService.saveCharacter(card));
    }

    @GetMapping("/characters")
    public List<CharacterCard> listCharacters(@RequestParam String worldId,
                                                @RequestParam(required = false) String type) {
        if (type != null && "npc".equals(type)) {
            return workshopService.listNpcs(worldId);
        }
        return workshopService.listAllCharacters(worldId);
    }

    @PostMapping("/generate-character")
    public ResponseEntity<?> generateCharacter(@RequestBody Map<String, String> request) {
        String description = request.get("description");
        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // 输入内容审查闸门（input-content-moderation）：调模型生成前同步审查提示词，
        // 命中返回拒答结构体而非生成结果（spec「命中处置」「工坊端点命中拒答」）。
        ModerationResult moderation = moderationGate.check(description);
        if (moderation.blocked()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("moderation", true, "error", moderationGate.refusalMessage()));
        }
        // 配额硬拒：调模型生成前事前检查（tenant-token-metering / design D8）
        ResponseEntity<?> quotaReject = quotaRejection();
        if (quotaReject != null) {
            return quotaReject;
        }
        try {
            // 创建时不区分玩家/NPC，类型固定 npc（运行时由 startGame 的 playerCharId 确定玩家）
            CharacterCard card = llmGenerator.generateCharacter(description);
            return ResponseEntity.ok(card);
        } catch (Exception e) {
            log.error("生成角色卡失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 阻塞式删除角色：被关系/触发器/存档引用时 409 + 具体原因；不存在 404；成功 204。
     */
    @DeleteMapping("/character/{id}")
    public ResponseEntity<Object> deleteCharacter(@PathVariable String id) {
        try {
            workshopService.deleteCharacter(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 地点 ====================

    @PostMapping("/location")
    public ResponseEntity<Location> saveLocation(@RequestBody Location location) {
        return ResponseEntity.ok(workshopService.saveLocation(location));
    }

    @GetMapping("/locations")
    public List<Location> listLocations(@RequestParam String worldId) {
        return workshopService.listLocations(worldId);
    }

    // ==================== 关系 ====================

    @PostMapping("/relationship")
    public ResponseEntity<Relationship> saveRelationship(@RequestBody Relationship rel) {
        return ResponseEntity.ok(workshopService.saveRelationship(rel));
    }

    @GetMapping("/relationships")
    public List<Relationship> getRelationships(@RequestParam String charId) {
        return workshopService.getRelationships(charId);
    }

    // ==================== 触发器 ====================

    @PostMapping("/trigger")
    public ResponseEntity<Trigger> saveTrigger(@RequestBody Trigger trigger) {
        return ResponseEntity.ok(workshopService.saveTrigger(trigger));
    }

    @GetMapping("/triggers")
    public List<Trigger> listTriggers(@RequestParam String worldId) {
        return workshopService.listTriggers(worldId);
    }

    // ==================== 开场白 ====================

    @PostMapping("/generate-opening")
    public ResponseEntity<Object> generateOpening(@RequestBody Map<String, String> request) {
        String worldId = request.get("worldId");
        String playerCharId = request.get("playerCharId");
        if (worldId == null || playerCharId == null) {
            return ResponseEntity.badRequest().build();
        }
        WorldSetting world = workshopService.getWorld(worldId);
        CharacterCard player = workshopService.getCharacter(playerCharId);
        if (world == null || player == null) {
            return ResponseEntity.notFound().build();
        }
        // 配额硬拒：调模型生成前事前检查（tenant-token-metering / design D8）
        ResponseEntity<?> quotaReject = quotaRejection();
        if (quotaReject != null) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                    .body(quotaReject.getBody());
        }
        try {
            String template = llmGenerator.generateOpeningTemplate(world, player);
            workshopService.updateOpeningTemplate(worldId, template);
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            log.error("生成开场白失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ==================== 一键中文化改名 ====================

    /**
     * 把该世界含罗马音/英文的角色名改为汉字名，同批把引用 key 迁移到角色卡 ID
     * （npcStates key、trigger.npcId、relationship 两端）。
     * <p>
     * LLM 映射未通过校验矩阵时返回 500 且四张表零写入；无待改名字时返回 200 与空清单。
     */
    @PostMapping("/localize-names")
    public ResponseEntity<Object> localizeNames(@RequestParam String worldId) {
        if (worldId == null || worldId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "worldId 不能为空"));
        }
        try {
            return ResponseEntity.ok(workshopService.localizeNames(worldId));
        } catch (IllegalArgumentException e) {
            log.warn("一键中文化改名: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("一键中文化改名失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() == null ? "改名失败" : e.getMessage()));
        }
    }

    // ==================== 开始冒险 ====================

    @PostMapping("/start")
    public ResponseEntity<Object> startGame(@RequestBody Map<String, String> request) {
        String worldId = request.get("worldId");
        String playerCharId = request.get("playerCharId");
        String sessionId = request.get("sessionId");
        if (worldId == null || playerCharId == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            GameState gs = workshopService.startGame(worldId, playerCharId, sessionId);
            return ResponseEntity.ok(gs);
        } catch (IllegalArgumentException e) {
            // 玩家角色不存在或不属于该世界
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== 存档列表 ====================

    @GetMapping("/game-states")
    public List<GameState> listGameStates() {
        return workshopService.listGameStates();
    }
}
