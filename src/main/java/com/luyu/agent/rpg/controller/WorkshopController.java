package com.luyu.agent.rpg.controller;

import com.luyu.agent.metering.QuotaExceededException;
import com.luyu.agent.metering.QuotaGuard;
import com.luyu.agent.moderation.ContentModerationGate;
import com.luyu.agent.moderation.ModerationResult;
import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.config.RpgWorldbookProperties;
import com.luyu.agent.rpg.model.*;
import com.luyu.agent.rpg.repository.RpgDestinedFigureRepository;
import com.luyu.agent.rpg.service.CharacterOriginService;
import com.luyu.agent.rpg.service.WorkshopLlmGenerator;
import com.luyu.agent.rpg.service.WorkshopService;
import com.luyu.agent.rpg.story.engine.StoryRunEngine;
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
    private final CharacterOriginService originService;
    private final ContentModerationGate moderationGate;
    private final QuotaGuard quotaGuard;
    private final RpgWorldbookProperties worldbookProperties;
    private final RpgLorebookProperties lorebookProperties;
    private final RpgDestinedFigureRepository destinedFigureRepo;
    private final StoryRunEngine storyRunEngine;

    public WorkshopController(WorkshopService workshopService,
                              WorkshopLlmGenerator llmGenerator,
                              CharacterOriginService originService,
                              ContentModerationGate moderationGate,
                              QuotaGuard quotaGuard,
                              RpgWorldbookProperties worldbookProperties,
                              RpgLorebookProperties lorebookProperties,
                              RpgDestinedFigureRepository destinedFigureRepo,
                              StoryRunEngine storyRunEngine) {
        this.workshopService = workshopService;
        this.llmGenerator = llmGenerator;
        this.originService = originService;
        this.moderationGate = moderationGate;
        this.quotaGuard = quotaGuard;
        this.worldbookProperties = worldbookProperties;
        this.lorebookProperties = lorebookProperties;
        this.destinedFigureRepo = destinedFigureRepo;
        this.storyRunEngine = storyRunEngine;
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
     * 条件级联软删除世界观（workshop-soft-delete-cascade D3）：
     * 有存档 409 + 具体原因；无存档单事务级联软删全部子数据；不存在 404；成功 204。
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
     * 预设向导步骤②三件套的 LLM 星命改写（preset-llm-identity-trio）。
     * <p>
     * 治理三段式与 /generate-character 同形：moderation 复查 name（推演时已审过，
     * 此处复查防绕过）→ 配额硬拒 → 调 LLM。不喂用户当前三件套（design D2），
     * 成功返回 {identity, personality, background}，解析失败返回错误 JSON（前端保留原值）。
     * <p>
     * 命定人物模式（preset-destined-figures tasks 6.2）：可选 figureKey + worldId
     * 按库查档案（未命中 400，同 /character-origin 惯例）；人物模式 prompt 附人物名与
     * 档案底稿，姓名覆盖为档案人物名；figureKey 缺省时行为零改动。
     */
    @PostMapping("/generate-origin-trio")
    public ResponseEntity<?> generateOriginTrio(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String constellation = (String) request.get("constellation");
        if (name == null || name.isBlank() || constellation == null || constellation.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name, constellation 不能为空"));
        }

        // 命定人物档案解析（preset-destined-figures tasks 6.2）：figureKey 非空时按 worldId 查库，
        // 未命中（非法值/未物化）一律 400；姓名覆盖为档案人物名（与 /character-origin 惯例一致，
        // 下文 moderation 复查的是后端真源人物名）
        DestinedFigure figure = null;
        String figureKey = (String) request.get("figureKey");
        if (figureKey != null && !figureKey.isBlank()) {
            String figureWorldId = (String) request.get("worldId");
            if (figureWorldId == null || figureWorldId.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "figureKey 非空时 worldId 不能为空"));
            }
            figure = findDestinedFigure(figureWorldId, figureKey);
            if (figure == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "未知的命定人物: " + figureKey));
            }
            name = figure.getDisplayName();
        }
        // 输入内容审查闸门：星命字段均为后端确定性产物，自由文本只有 name 一个面
        ModerationResult moderation = moderationGate.check(name);
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
            String gender = (String) request.get("gender");
            String starRank = (String) request.get("starRank");
            String faction = (String) request.get("faction");
            String zodiacTraits = (String) request.get("zodiacTraits");
            List<String> techniques = null;
            if (request.get("techniques") instanceof List<?> raw) {
                techniques = raw.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
            }
            WorkshopLlmGenerator.OriginTrio trio = llmGenerator.generateOriginTrio(
                    name, gender, constellation, starRank, faction, zodiacTraits, techniques, figure);
            return ResponseEntity.ok(Map.of(
                    "identity", trio.identity(),
                    "personality", trio.personality(),
                    "background", trio.background()));
        } catch (Exception e) {
            log.error("生成三件套失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() == null ? "生成三件套失败" : e.getMessage()));
        }
    }

    /**
     * 条件级联软删除角色（workshop-soft-delete-cascade D4）：被存档引用为玩家 409 + 原因；
     * 否则级联软删其关系与关联触发器；不存在 404；成功 204。
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

    /** 软删除地点：成功 204、不存在或已删 404。 */
    @DeleteMapping("/location/{id}")
    public ResponseEntity<Object> deleteLocation(@PathVariable String id) {
        return workshopService.deleteLocation(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ==================== 关系 ====================

    @PostMapping("/relationship")
    public ResponseEntity<Relationship> saveRelationship(@RequestBody Relationship rel) {
        return ResponseEntity.ok(workshopService.saveRelationship(rel));
    }

    /**
     * 关系列表：传 worldId 列某世界全部关系（三件套列表用，前端富化两端角色名）；
     * 传 charId 维持旧契约（双向查该角色关系）。两参数均缺时 400。
     */
    @GetMapping("/relationships")
    public ResponseEntity<List<Relationship>> getRelationships(
            @RequestParam(required = false) String charId,
            @RequestParam(required = false) String worldId) {
        if (worldId != null && !worldId.isBlank()) {
            return ResponseEntity.ok(workshopService.listRelationshipsByWorld(worldId));
        }
        if (charId != null && !charId.isBlank()) {
            return ResponseEntity.ok(workshopService.getRelationships(charId));
        }
        return ResponseEntity.badRequest().build();
    }

    /** 软删除关系：成功 204、不存在或已删 404。 */
    @DeleteMapping("/relationship/{id}")
    public ResponseEntity<Object> deleteRelationship(@PathVariable String id) {
        return workshopService.deleteRelationship(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
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

    /** 软删除触发器：成功 204、不存在或已删 404；对进行中存档立即停止触发。 */
    @DeleteMapping("/trigger/{id}")
    public ResponseEntity<Object> deleteTrigger(@PathVariable String id) {
        return workshopService.deleteTrigger(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // ==================== 世界书 ====================

    /**
     * 保存单条世界书条目（含 id 且库内存在则更新，否则新建）。
     * <p>
     * {@code rpg.worldbook.enabled=false} 时整体停用（503），对齐 {@code StarLorebookController} 回滚路径。
     */
    @PostMapping("/worldbook")
    public ResponseEntity<?> saveWorldbookEntry(@RequestBody WorldbookEntry entry) {
        ResponseEntity<?> disabled = worldbookDisabled();
        if (disabled != null) {
            return disabled;
        }
        if (entry.getWorldId() == null || entry.getWorldId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "worldId 不能为空"));
        }
        return ResponseEntity.ok(workshopService.saveWorldbookEntry(entry));
    }

    /** 列某世界的全部世界书条目（含停用，供工坊编辑）。 */
    @GetMapping("/worldbook")
    public ResponseEntity<?> listWorldbookEntries(@RequestParam String worldId) {
        ResponseEntity<?> disabled = worldbookDisabled();
        if (disabled != null) {
            return disabled;
        }
        return ResponseEntity.ok(workshopService.listWorldbookEntries(worldId));
    }

    /** 删除单条世界书条目：成功 204、不存在或跨租户 404。 */
    @DeleteMapping("/worldbook/{id}")
    public ResponseEntity<?> deleteWorldbookEntry(@PathVariable String id) {
        ResponseEntity<?> disabled = worldbookDisabled();
        if (disabled != null) {
            return disabled;
        }
        return workshopService.deleteWorldbookEntry(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * LLM 批量生成世界书条目草稿（<b>不落库</b>）。
     * <p>
     * 治理三段式与 {@code /generate-world} 同形：moderation 复查 keywords → 配额硬拒 → 调 LLM。
     * 返回草稿数组供前端预填编辑区，用户逐条确认后经 {@code POST /worldbook} 保存。
     * 传入 {@code worldId} 时自动拼世界背景（名称/时代/氛围）供 LLM 对齐文化风格。
     */
    @PostMapping("/generate-worldbook")
    public ResponseEntity<?> generateWorldbook(@RequestBody Map<String, String> request) {
        ResponseEntity<?> disabled = worldbookDisabled();
        if (disabled != null) {
            return disabled;
        }
        String keywords = request.get("keywords");
        if (keywords == null || keywords.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "keywords 不能为空"));
        }
        // 输入内容审查闸门（input-content-moderation）：调模型生成前同步审查提示词
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
            String worldContext = null;
            String worldId = request.get("worldId");
            if (worldId != null && !worldId.isBlank()) {
                WorldSetting world = workshopService.getWorld(worldId);
                if (world != null) {
                    worldContext = String.format("名称=%s, 时代=%s, 氛围=%s",
                            world.getName(), world.getEra(), world.getAtmosphere());
                }
            }
            List<WorldbookEntry> drafts = llmGenerator.generateWorldbookEntries(keywords, worldContext);
            return ResponseEntity.ok(drafts);
        } catch (Exception e) {
            log.error("生成世界书条目失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage() == null ? "生成世界书条目失败" : e.getMessage()));
        }
    }

    /**
     * 世界书开关停用检查：{@code enabled=false} 时返回 503 结构体，否则 null（对齐 {@code StarLorebookController}）。
     */
    private ResponseEntity<?> worldbookDisabled() {
        if (!worldbookProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "世界书功能已停用（rpg.worldbook.enabled=false）"));
        }
        return null;
    }

    // ==================== 冥星模板（工坊冥星轨，workshop-soft-delete-cascade D8） ====================

    /**
     * 工坊冥星轨列表：某世界的冥星模板行（预设世界同样只读展示）。
     * 读端点不门控（对齐 LorebookSetController 按轨惯例），预设只读由前端降级 + 写端点 400 共同保证。
     */
    @GetMapping("/star")
    public List<StarIncarnation> listStarIncarnations(@RequestParam String worldId) {
        return workshopService.listStarIncarnations(worldId);
    }

    /**
     * 命定人物名录（preset-destined-figures）：某世界可扮演的历史人物档案。
     * 只读（预设向导「命定身份」下拉数据源），无写端点——档案由 md 物化维护；
     * 读端点不门控，对齐 /star 惯例。
     */
    @GetMapping("/destined-figures")
    public List<DestinedFigure> listDestinedFigures(@RequestParam String worldId) {
        return destinedFigureRepo.findByWorldId(worldId);
    }

    /**
     * 保存冥星模板行（含 id 且模板行存在则更新，否则新建）。
     * <p>
     * 写端点受 {@code rpg.lorebook.enabled} 门控（关闭 503）；预设世界（preset- 前缀）
     * 冥星轨由 md 物化维护，写操作 400。
     */
    @PostMapping("/star")
    public ResponseEntity<?> saveStarIncarnation(@RequestBody StarIncarnation incarnation) {
        ResponseEntity<?> disabled = lorebookDisabled();
        if (disabled != null) {
            return disabled;
        }
        if (incarnation.getWorldId() == null || incarnation.getWorldId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "worldId 不能为空"));
        }
        try {
            return ResponseEntity.ok(workshopService.saveStarIncarnation(incarnation));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 软删除冥星模板行：预设世界 400；不存在/set 行/已删 404；成功 204。
     * 存档 set 副本快照不受影响（模板层删除不回写 set）。
     */
    @DeleteMapping("/star/{id}")
    public ResponseEntity<?> deleteStarIncarnation(@PathVariable String id) {
        ResponseEntity<?> disabled = lorebookDisabled();
        if (disabled != null) {
            return disabled;
        }
        try {
            return workshopService.deleteStarIncarnation(id)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 设定集开关停用检查（按轨门控，文案对齐 {@code LorebookSetController}）：
     * {@code rpg.lorebook.enabled=false} 时返回 503 结构体，否则 null。
     */
    private ResponseEntity<?> lorebookDisabled() {
        if (!lorebookProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "设定集功能已停用（rpg.lorebook.enabled=false）"));
        }
        return null;
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

    // ==================== 角色起源（星命计算）====================

    /**
     * 星命推演端点：接收生辰信息和阵营偏好，返回确定性计算结果。
     * <p>
     * 星命计算纯本地（无 LLM），但端点仍走审查闸门和配额检查，与工坊其他端点一致。
     * 在 TenantContext 中执行，确保多租户隔离。
     * <p>
     * 命定人物模式（preset-destined-figures）：可选 {@code figureKey}（kagetora/harunobu/
     * nobunaga/nobukatsu）+ {@code worldId} 按库查档案——预绑人物跳漏斗取档案星命、
     * 自由推演人物（信胜）走现行漏斗；非法 figureKey 400；缺省路径零改动。
     *
     * @param request 包含 name, gender, birthDate(yyyy-MM-dd), timeIndex(0-11), faction(saint/specter/godwarrior)，
     *                可选 figureKey + worldId（人物模式必带，下拉数据源同 /destined-figures）
     * @return OriginResult 星命计算结果
     */
    @PostMapping("/character-origin")
    public ResponseEntity<?> characterOrigin(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String gender = (String) request.get("gender");
        String birthDateStr = (String) request.get("birthDate");
        Object timeIndexObj = request.get("timeIndex");
        String faction = (String) request.get("faction");
        String figureKey = (String) request.get("figureKey");
        String figureWorldId = (String) request.get("worldId");

        if (name == null || name.isBlank()
                || birthDateStr == null || birthDateStr.isBlank()
                || faction == null || faction.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name, birthDate, faction 不能为空"));
        }

        // 命定人物档案解析（preset-destined-figures）：figureKey 非空时按 worldId 查库，
        // 未命中（非法值/未物化）一律 400，不进星命计算
        DestinedFigure figure = null;
        if (figureKey != null && !figureKey.isBlank()) {
            if (figureWorldId == null || figureWorldId.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "figureKey 非空时 worldId 不能为空"));
            }
            figure = findDestinedFigure(figureWorldId, figureKey);
            if (figure == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "未知的命定人物: " + figureKey));
            }
        }

        // 输入内容审查闸门：审查姓名等用户输入
        ModerationResult moderation = moderationGate.check(name);
        if (moderation.blocked()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("moderation", true, "error", moderationGate.refusalMessage()));
        }

        // 配额检查：与现有工坊端点一致
        ResponseEntity<?> quotaReject = quotaRejection();
        if (quotaReject != null) {
            return quotaReject;
        }

        try {
            java.time.LocalDate birthDate = java.time.LocalDate.parse(birthDateStr);
            int timeIndex = 6; // 默认午时
            if (timeIndexObj instanceof Number n) {
                timeIndex = n.intValue();
            }
            if (timeIndex < 0 || timeIndex > 11) {
                return ResponseEntity.badRequest().body(Map.of("error", "timeIndex 必须在 0-11 范围内"));
            }
            if (gender == null || gender.isBlank()) {
                gender = "male";
            }

            OriginResult result = originService.calculate(name, gender, birthDate, timeIndex, faction,
                    parseRollSeed(request.get("rollSeed")), figure);
            return ResponseEntity.ok(result);
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "birthDate 格式应为 yyyy-MM-dd"));
        } catch (Exception e) {
            log.error("星命推演失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "星命推演失败"));
        }
    }

    /**
     * 按 worldId + figureKey 查命定人物档案（preset-destined-figures）：
     * 租户隔离由 repo 层 SQL 过滤保证；未命中返回 null（调用方判 400）。
     */
    private DestinedFigure findDestinedFigure(String worldId, String figureKey) {
        return destinedFigureRepo.findByWorldId(worldId).stream()
                .filter(f -> figureKey.equals(f.getFigureKey()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析可选 rollSeed（rpg-cosmo-sense）：缺失、非整数或负数均回落 0，不报错。
     */
    private static int parseRollSeed(Object raw) {
        if (raw instanceof Number n) {
            int seed = n.intValue();
            return Math.max(0, seed);
        }
        return 0;
    }

    // ==================== 开始冒险 ====================

    @PostMapping("/start")
    public ResponseEntity<Object> startGame(@RequestBody Map<String, Object> request) {
        String worldId = (String) request.get("worldId");
        String playerCharId = (String) request.get("playerCharId");
        String sessionId = (String) request.get("sessionId");
        if (worldId == null || playerCharId == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            // 星命注入：若请求中包含出生信息，则重新计算星命并注入 playerStates
            OriginResult originResult = null;
            String birthDateStr = (String) request.get("birthDate");
            String faction = (String) request.get("faction");
            Object timeIndexObj = request.get("timeIndex");
            String name = (String) request.get("name");
            String gender = (String) request.get("gender");
            if (birthDateStr != null && !birthDateStr.isBlank()
                    && faction != null && !faction.isBlank()
                    && timeIndexObj instanceof Number n) {
                java.time.LocalDate birthDate = java.time.LocalDate.parse(birthDateStr);
                int timeIndex = n.intValue();
                if (name == null || name.isBlank()) name = "玩家";
                if (gender == null || gender.isBlank()) gender = "male";
                // 命定人物（preset-destined-figures tasks 7.4）：figureKey 非空时按本世界查档案，
                // 7 参 calculate 落 figure 三元组——playerStates/GM 星命行/信长 claim 联动都靠它。
                // 未命中 400（与 /character-origin 一致：人物局静默降级会让玩家以为在演信长实际未绑定）
                String figureKey = (String) request.get("figureKey");
                DestinedFigure figure = null;
                if (figureKey != null && !figureKey.isBlank()) {
                    figure = findDestinedFigure(worldId, figureKey);
                    if (figure == null) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "未知的命定人物: " + figureKey));
                    }
                }
                // rollSeed 透传（rpg-cosmo-sense）：与 /character-origin 同 seed，保证展示值 = 落档值
                originResult = originService.calculate(name, gender, birthDate, timeIndex, faction,
                        parseRollSeed(request.get("rollSeed")), figure);
            }

            // 世界书来源（add-rpg-lorebook-sets D4）：缺省 = 世界默认模板；非空 = 继承指定 set
            String inheritSetId = (String) request.get("inheritSetId");
            GameState gs = workshopService.startGame(worldId, playerCharId, sessionId, originResult, inheritSetId);
            // 故事图开局物化（story-graph-runtime tasks 4.1）：请求带 storyGraphId 时模板图
            // 物化为运行副本（story_run），入口节点 on_enter 结算初始 playerStates
            // （IAE 走下方 400 catch：图不存在/未整稿，对齐「玩家角色不存在」语义）；
            // 缺省不触引擎——无图 world 行为零增量（spec 钉子）
            String storyGraphId = (String) request.get("storyGraphId");
            if (storyGraphId != null && !storyGraphId.isBlank()) {
                storyRunEngine.bootstrapForGame(gs, storyGraphId);
            }
            return ResponseEntity.ok(gs);
        } catch (IllegalArgumentException e) {
            // 玩家角色不存在或不属于该世界
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "birthDate 格式应为 yyyy-MM-dd"));
        }
    }

    // ==================== 存档列表 ====================

    @GetMapping("/game-states")
    public List<GameState> listGameStates() {
        return workshopService.listGameStates();
    }
}
