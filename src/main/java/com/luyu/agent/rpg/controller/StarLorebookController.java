package com.luyu.agent.rpg.controller;

import com.luyu.agent.rpg.config.RpgLorebookProperties;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.StarIncarnation;
import com.luyu.agent.rpg.model.StarIncarnationRuntime;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 冥星武将录只读 REST API（star-lorebook design D6）。
 * <p>
 * 名录修改一律回归 {@code preset-adventure.md} 真源 + 重新物化；
 * 本控制器<b>刻意只有 GET</b>，不提供任何写操作（spec「名录只读展示与接口」）。
 * 世界书面板与工坊世界页两处前端复用同一端点。
 * <p>
 * 租户上下文由 {@code JwtTenantFilter} 在请求进入前建立（白名单不含 {@code /rpg}），
 * 仓储层 SQL 强制 {@code tenant_id} 列过滤——与 {@code PresetController} 同款隐式取用，
 * 无显式租户参数（design 5.2：列隔离而非请求参数隔离）。
 * <p>
 * {@code rpg.lorebook.enabled=false} 时端点整体停用（503），对齐回滚路径：
 * 表与数据保留不动，只断注入与展示。
 */
@RestController
@RequestMapping("/rpg/lorebook")
public class StarLorebookController {

    private static final Logger log = LoggerFactory.getLogger(StarLorebookController.class);

    private final RpgStarIncarnationRepository starRepo;
    private final RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private final RpgGameStateRepository stateRepo;
    private final RpgLorebookProperties lorebookProperties;

    public StarLorebookController(RpgStarIncarnationRepository starRepo,
                                  RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                                  RpgGameStateRepository stateRepo,
                                  RpgLorebookProperties lorebookProperties) {
        this.starRepo = starRepo;
        this.starRuntimeRepo = starRuntimeRepo;
        this.stateRepo = stateRepo;
        this.lorebookProperties = lorebookProperties;
    }

    /**
     * 查询某世界的冥星武将录（只读，按 sort_order）。
     * <p>
     * 请求: GET /rpg/lorebook/{worldId}（可选 query: gameStateId）<br>
     * 响应: 名录条目列表，含 NPC 专属星标注（{@code rollable=false} 的天兽/天魔）；
     * 携带 gameStateId 时每条给出 activated / claimedByPlayer / activatedTurn
     * （未携带时 activated/claimedByPlayer 为 false、activatedTurn 为 null——
     * 世界静态视图无存档上下文，前端仅在存档视图下渲染抢星标注）。
     * <p>
     * 名录为空（世界未物化名录或自定义世界）返回 200 + 空列表，
     * 与「缺失小节不阻断物化」的降级精神一致；gameStateId 是用户输入需校验：
     * 存档不存在 404、存档不属于该世界 400。
     */
    @GetMapping("/{worldId}")
    public ResponseEntity<?> list(@PathVariable String worldId,
                                  @RequestParam(required = false) String gameStateId) {
        if (!lorebookProperties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "设定集功能已停用（rpg.lorebook.enabled=false）"));
        }

        // 可选存档状态合并：runtime 行以 star_name 为稳定键（物化重写抗断链，design 风险缓解）
        Map<String, StarIncarnationRuntime> runtimeByName = new HashMap<>();
        if (gameStateId != null && !gameStateId.isBlank()) {
            GameState gs = stateRepo.findById(gameStateId);
            if (gs == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "存档不存在: " + gameStateId));
            }
            if (!worldId.equals(gs.getWorldId())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "存档不属于该世界"));
            }
            for (StarIncarnationRuntime rt : starRuntimeRepo.findByGameStateId(gameStateId)) {
                if (rt.getStarName() != null) {
                    runtimeByName.put(rt.getStarName(), rt);
                }
            }
        }

        List<LorebookEntry> entries = new ArrayList<>();
        for (StarIncarnation incarnation : starRepo.findByWorldId(worldId)) {
            StarIncarnationRuntime rt = runtimeByName.get(incarnation.getStarName());
            entries.add(new LorebookEntry(
                    incarnation.getStarName(),
                    incarnation.getWarlordName(),
                    incarnation.getLore(),
                    incarnation.getAliases(),
                    Boolean.TRUE.equals(incarnation.getRollable()),
                    incarnation.getSortOrder(),
                    incarnation.getCategory(),
                    rt != null,
                    rt != null && Boolean.TRUE.equals(rt.getClaimedByPlayer()),
                    rt != null ? rt.getActivatedTurn() : null));
        }
        return ResponseEntity.ok(entries);
    }

    /**
     * 名录条目只读视图。
     *
     * @param rollable         是否可被玩家推演命中（天兽/天魔为 false——NPC 专属星）
     * @param category         条目自由分类（add-star-lorebook-category；NULL = 未分类）
     * @param activated        该存档已激活（sticky 常驻）；未提供 gameStateId 时为 false
     * @param claimedByPlayer  此局星归玩家（开局抢星命中）；未提供 gameStateId 时为 false
     * @param activatedTurn    激活轮次（开局抢星为 0）；未激活或未提供存档时为 null
     */
    public record LorebookEntry(String starName,
                                String warlordName,
                                String lore,
                                String aliases,
                                boolean rollable,
                                Integer sortOrder,
                                String category,
                                boolean activated,
                                boolean claimedByPlayer,
                                Integer activatedTurn) {}
}
