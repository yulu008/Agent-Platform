package com.luyu.agent.tenancy;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 存量记忆文件幂等迁移（design D6 / tasks 4.5）。
 * <p>
 * 启动时一次性把旧布局搬入 default 租户（与 DB 存量行回填 {@code tenant_id='default'}
 * 的归属口径一致）：
 * <pre>
 * ~/.agent/memories   → ~/.agent/tenants/default/memories/
 * ~/.agent/rpg-saves  → ~/.agent/tenants/default/rpg-saves/
 * </pre>
 * 幂等语义（可安全重跑、可从中断中恢复）：
 * <ul>
 *   <li>迁移源不存在 → 跳过（已迁移过或全新部署）；</li>
 *   <li>逐个子项搬入：目标同名子项已存在 → 跳过该子项并 WARN（不覆盖，人工裁决）；</li>
 *   <li>源目录搬空后删除（消除下次启动的空扫描；删除失败仅 DEBUG，不影响启动）。</li>
 * </ul>
 * <p>
 * 刻意用 {@code @PostConstruct} 在容器刷新期执行：先于 Web 服务器对外服务，
 * 避免首个请求读到尚未搬入的旧目录。跨盘 {@code Files.move} 退化为复制+删除，
 * 语义等价（H2 单文件部署场景源与目标同盘，实际为原子改名）。
 */
@Component
public class TenantFileMigration {

    private static final Logger log = LoggerFactory.getLogger(TenantFileMigration.class);

    private final Path legacyMemories;
    private final Path legacyRpgSaves;
    private final Path defaultTenantDir;

    /** 生产构造器：路径常量来自 {@link TenantPaths}（单一真源）。 */
    public TenantFileMigration() {
        this(Paths.get(TenantPaths.LEGACY_MEMORIES),
                Paths.get(TenantPaths.LEGACY_RPG_SAVES),
                Paths.get(TenantPaths.ROOT, TenantPaths.DEFAULT_TENANT));
    }

    /** 测试构造器：替换迁移源与 default 租户目录。 */
    TenantFileMigration(Path legacyMemories, Path legacyRpgSaves, Path defaultTenantDir) {
        this.legacyMemories = legacyMemories;
        this.legacyRpgSaves = legacyRpgSaves;
        this.defaultTenantDir = defaultTenantDir;
    }

    @PostConstruct
    void migrateAll() {
        migrateDir(legacyMemories, defaultTenantDir.resolve("memories"), "全局记忆");
        migrateDir(legacyRpgSaves, defaultTenantDir.resolve("rpg-saves"), "RPG 存档记忆");
    }

    /**
     * 把 source 目录的全部子项搬入 target（不整体 rename，逐项搬入保证幂等与可恢复）。
     * 源目录不存在时静默跳过；搬空后尝试删除源目录。
     */
    private void migrateDir(Path source, Path target, String label) {
        if (!Files.isDirectory(source)) {
            log.debug("{}存量目录不存在，跳过迁移: {}", label, source);
            return;
        }
        int moved = 0;
        int skipped = 0;
        try {
            Files.createDirectories(target);
            try (Stream<Path> children = Files.list(source)) {
                for (Path child : children.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                    Path dest = target.resolve(child.getFileName());
                    if (Files.exists(dest)) {
                        skipped++;
                        log.warn("{}迁移跳过（目标已存在，需人工裁决，不覆盖）: {} → 已存在 {}",
                                label, child, dest);
                        continue;
                    }
                    Files.move(child, dest);
                    moved++;
                }
            }
            tryDeleteIfEmpty(source, label);
            log.info("{}存量迁移完成: {} 项搬入 {}，{} 项跳过（目标已存在）", label, moved, target, skipped);
        } catch (IOException e) {
            // 迁移失败不阻断启动：default 租户记忆缺失可在人工处理后重启重试（幂等）
            log.error("{}存量迁移失败（可修复后重启重试，已搬入 {} 项不回滚）: {} → {}",
                    label, moved, source, target, e);
        }
    }

    /** 源目录已空则删除；非空（有跳过项）或删除失败都不影响迁移语义。 */
    private void tryDeleteIfEmpty(Path source, String label) {
        try (Stream<Path> children = Files.list(source)) {
            if (children.findAny().isPresent()) {
                return;
            }
            Files.delete(source);
            log.debug("{}存量目录已搬空并移除: {}", label, source);
        } catch (IOException e) {
            log.debug("移除空的存量目录失败（不影响迁移语义）: {}", source);
        }
    }
}
