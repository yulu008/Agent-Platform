package com.luyu.agent.rpg.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 存量封面租户归位迁移器（works-view-and-world-publish 1.6）。
 * <p>
 * 世界卡封面历史上落在 {@code data/covers/} 根目录（无租户语义）；本变更将私有封面
 * 改为 {@code data/covers/{tenantId}/} 租户区（design D6）。启动时按
 * {@code rpg_world_setting} 各物理行（含软删行）的 {@code tenant_id} 把根目录裸文件
 * 幂等挪入对应租户子目录；孤儿文件（不被任何行引用）留原位记日志清单——
 * 取侧双轨回落（{@link WorldCoverStorageService#read}）兼容未迁移位。
 * <p>
 * 幂等可重跑：目标位已存在即跳过，已在租户区的行零动作；失败不阻断启动（下次重跑）。
 * <p>
 * 归位读用带 {@code tenant_id} 列的原生 SQL——rpg_* 表守卫的字符串检查通过；
 * 启动线程无 TenantContext，故不走 tid() 过滤的 Repository 路径。
 */
@Component
public class WorldCoverTenantMigrator {

    private static final Logger log = LoggerFactory.getLogger(WorldCoverTenantMigrator.class);

    private final JdbcTemplate jdbcTemplate;
    private final WorldCoverStorageService coverStorage;

    public WorldCoverTenantMigrator(JdbcTemplate jdbcTemplate, WorldCoverStorageService coverStorage) {
        this.jdbcTemplate = jdbcTemplate;
        this.coverStorage = coverStorage;
    }

    /** ApplicationReadyEvent 确保在 RpgSchemaInitializer 建表之后执行 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            migrate();
        } catch (Exception e) {
            // 迁移失败不阻断启动：双轨读兼容未迁移位，下次启动幂等重跑
            log.warn("存量封面迁移中断（下次启动重跑）: reason={}", e.getMessage());
        }
    }

    /**
     * 按 rpg_world_setting 各行 tenant_id 归位根目录裸文件；孤儿留原位记日志清单。
     */
    private void migrate() throws IOException {
        // cover → 归属租户（文件名 uuid 全局唯一，正常单租户归属；查询含软删行——
        // 文件归位以物理行归属为准，软删世界的封面同样归位）
        Map<String, String> ownerOf = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT tenant_id, cover FROM rpg_world_setting WHERE cover IS NOT NULL", rs -> {
            String tenantId = rs.getString(1);
            String cover = rs.getString(2);
            if (tenantId != null && cover != null && !cover.isBlank()) {
                String existing = ownerOf.putIfAbsent(cover, tenantId);
                if (existing != null && !existing.equals(tenantId)) {
                    log.warn("封面文件被多租户行引用（罕见，按首个归属归位，其余靠双轨读兼容）: cover={}, tenants={}|{}",
                            cover, existing, tenantId);
                }
            }
        });

        int relocated = 0;
        for (Map.Entry<String, String> entry : ownerOf.entrySet()) {
            if (coverStorage.relocateLegacy(entry.getValue(), entry.getKey())) {
                relocated++;
            }
        }

        // 孤儿清单：根目录裸文件中不被任何行引用者留原位（不删不挪，双轨读兼容）
        Set<String> referenced = ownerOf.keySet();
        List<String> orphans = new ArrayList<>();
        for (String file : coverStorage.listLegacyFiles()) {
            if (!referenced.contains(file)) {
                orphans.add(file);
            }
        }
        if (relocated > 0) {
            log.info("存量封面归位完成: {} 个文件已挪入租户子目录", relocated);
        }
        if (!orphans.isEmpty()) {
            log.info("存量封面孤儿文件留原位（无世界行引用，双轨读兼容）: {}", orphans);
        }
    }
}
