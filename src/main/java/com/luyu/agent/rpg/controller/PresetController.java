package com.luyu.agent.rpg.controller;

import com.luyu.agent.rpg.service.PresetWorldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 预设世界 REST API（preset-rpg-entry）。
 * <p>
 * 与工坊端点的差异：物化是纯本地运算（解析 classpath md + 数据库写入），
 * 既不消耗 token 也不把用户文本送进模型，因此<b>刻意不套</b> {@code QuotaGuard}
 * 与 {@code ContentModerationGate}（design D10）。
 * <p>
 * 租户上下文由 {@code JwtTenantFilter} 在请求进入前建立（其白名单不含 {@code /rpg}），
 * 本控制器直接依赖 {@code TenantContext.requireTenantId()} 的隐式取用。
 */
@RestController
@RequestMapping("/rpg/preset")
public class PresetController {

    private static final Logger log = LoggerFactory.getLogger(PresetController.class);

    /** 目前唯一的预设键；请求体未显式指定时兜底，前端 POST 空体即可开局 */
    private static final String DEFAULT_PRESET_KEY = "adventure";

    private final PresetWorldService presetWorldService;

    public PresetController(PresetWorldService presetWorldService) {
        this.presetWorldService = presetWorldService;
    }

    /**
     * 惰性物化预设世界到当前租户（幂等 upsert）。
     * <p>
     * 请求: POST /rpg/preset/materialize，体为 {@code {"presetKey":"adventure"}}（可省略）<br>
     * 响应: {@code {"worldId":"preset-adventure:<tenantId>","worldName":"圣斗士星矢·圣战之始"}}
     * <p>
     * 每次调用都全量重写该世界行（含开场白模板），使用户改完 md 后
     * {@code process-resources} + 重新点卡片即可生效。
     */
    @PostMapping("/materialize")
    public ResponseEntity<?> materialize(@RequestBody(required = false) Map<String, String> request) {
        String requested = request != null ? request.get("presetKey") : null;
        String presetKey = (requested != null && !requested.isBlank()) ? requested : DEFAULT_PRESET_KEY;
        try {
            PresetWorldService.PresetWorld world = presetWorldService.materialize(presetKey);
            return ResponseEntity.ok(Map.of("worldId", world.worldId(), "worldName", world.worldName()));
        } catch (IllegalArgumentException e) {
            // 预设键不在白名单内（用户手改 URL 参数）
            log.warn("预设物化被拒: preset={}, reason={}", presetKey, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            // 源文缺失或读取失败：不落残缺世界行，前端提示后回落首页
            log.error("预设世界物化失败: preset={}", presetKey, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "预设世界加载失败"));
        }
    }
}
