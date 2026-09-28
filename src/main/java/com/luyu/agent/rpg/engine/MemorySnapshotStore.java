package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.tenancy.TenantPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 存档记忆目录快照组件（回溯的记忆回退层）。
 * <p>
 * 每轮 {@code prepareTurn} 开始时把 {@code ~/.agent/tenants/<tid>/rpg-saves/<gsId>/}
 * 整目录复制到 {@code ~/.agent/tenants/<tid>/rpg-saves/.snapshots/<gsId>/turn-<N>/}
 * （刻意放在存档目录之外，记忆弹窗按存档目录列文件，天然看不到快照区）；
 * 回溯时清空存档目录后把快照复制回去，
 * 新建的记忆消失、被修改的记忆恢复旧文、MEMORY.md 索引随目录一同还原。
 * <p>
 * 存档根按当前请求租户解析（design D6 / tasks 4.4）：调用方须处于租户上下文
 * （prepareTurn 在请求线程；finalizeTurn / RollbackService 经 TurnContext 或请求线程恢复）。
 * 测试可经双参构造器注入固定根。
 * <p>
 * 记忆文件是原地覆写、无历史副本，frontmatter 的 {@code turn} 由 GM 自报不可靠、
 * 文件 mtime 随修改刷新——时间戳启发式只能删不能还原，故快照是唯一的真回退手段。
 * <p>
 * 保留窗口与状态快照一致：最近 {@value #KEEP_COUNT} 份。
 */
@Component
public class MemorySnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(MemorySnapshotStore.class);

    /** 快照保留份数：与 RpgGameStateSnapshotRepository.KEEP_COUNT 一致（当前轮 + 3 轮） */
    public static final int KEEP_COUNT = 4;

    /** 快照子目录命名：turn-<N> */
    private static final Pattern TURN_DIR = Pattern.compile("turn-(\\d+)");

    /** 固定根注入（测试用）；null = 按当前请求租户懒解析 */
    private final Path savesRootOverride;
    private final Path snapshotsRootOverride;

    public MemorySnapshotStore() {
        this(null, null);
    }

    /**
     * 测试用构造器：替换存档根目录与快照根目录（null 则按请求租户解析）。
     */
    MemorySnapshotStore(Path savesRoot, Path snapshotsRoot) {
        this.savesRootOverride = savesRoot;
        this.snapshotsRootOverride = snapshotsRoot;
    }

    /** 存档根：固定注入优先，否则按当前请求租户解析 {@code tenants/<tid>/rpg-saves}。 */
    private Path savesRoot() {
        return savesRootOverride != null ? savesRootOverride : TenantPaths.rpgSavesDir();
    }

    /** 快照根：固定注入优先，否则 = 存档根/.snapshots（与原布局同构）。 */
    private Path snapshotsRoot() {
        return snapshotsRootOverride != null ? snapshotsRootOverride : savesRoot().resolve(".snapshots");
    }

    private Path saveDir(String gameStateId) {
        return savesRoot().resolve(RpgSavePaths.requireSafeGameStateId(gameStateId));
    }

    /**
     * 创建记忆目录快照。存档记忆目录不存在时静默跳过（空记忆无需快照，
     * 回溯还原到空目录语义等价）。
     *
     * @param gameStateId 存档 ID（来自库内记录，已受信任）
     * @param turn        快照对应的轮次（= 本轮 N-1）
     * @throws IOException 复制失败
     */
    public void snapshot(String gameStateId, int turn) throws IOException {
        Path dir = saveDir(gameStateId);
        if (!Files.isDirectory(dir)) {
            log.debug("存档 {} 无记忆目录，跳过记忆快照", gameStateId);
            return;
        }
        Path target = snapshotDir(gameStateId, turn);
        deleteRecursively(target);
        copyTree(dir, target);
    }

    /**
     * 还原记忆目录到指定轮次的快照：清空现有目录后整目录复制回。
     * <p>
     * 快照目录不存在时视为“该轮记忆为空”（快照时存档尚无记忆目录），清空当前存档目录即可，
     * 与空目录快照语义等价；DB 快照存在性由调用方在回滚前校验。
     *
     * @param gameStateId 存档 ID
     * @param turn        目标轮次（= 回溯轮 N-1）
     * @throws IOException 还原失败（文件系统错误）
     */
    public void restore(String gameStateId, int turn) throws IOException {
        Path source = snapshotDir(gameStateId, turn);
        Path dir = saveDir(gameStateId);
        if (!Files.isDirectory(source)) {
            log.info("记忆快照不存在，按空记忆还原: gameStateId={}, turn={}", gameStateId, turn);
            deleteRecursively(dir);
            return;
        }
        deleteRecursively(dir);
        copyTree(source, dir);
    }

    /**
     * 清理保留窗口外的最旧记忆快照，仅保留最近 keepCount 份。
     */
    public void prune(String gameStateId, int keepCount) {
        Path gsRoot = snapshotsRoot().resolve(RpgSavePaths.requireSafeGameStateId(gameStateId));
        List<Path> turnDirs;
        try (Stream<Path> stream = Files.list(gsRoot)) {
            turnDirs = stream
                    .filter(Files::isDirectory)
                    .filter(p -> TURN_DIR.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(this::turnOf).reversed())
                    .toList();
        } catch (IOException e) {
            // 快照区不存在或读取失败：没有可清理的
            return;
        }
        for (int i = keepCount; i < turnDirs.size(); i++) {
            try {
                deleteRecursively(turnDirs.get(i));
            } catch (IOException e) {
                log.warn("清理记忆快照失败: {}", turnDirs.get(i), e);
            }
        }
    }

    /**
     * 删除该存档的全部记忆快照（预留：存档删除时的级联清理）。
     */
    public void deleteAll(String gameStateId) {
        try {
            deleteRecursively(snapshotsRoot().resolve(RpgSavePaths.requireSafeGameStateId(gameStateId)));
        } catch (IOException e) {
            log.warn("删除记忆快照失败: gameStateId={}", gameStateId, e);
        }
    }

    /**
     * 删除某轮及之后的全部记忆快照（回溯用：与 DB 快照 deleteFromTurn 对应，
     * 被回溯轮次之后的时间线作废，避免重演后回溯到旧时间线记忆）。
     */
    public void deleteFromTurn(String gameStateId, int turn) {
        Path gsRoot = snapshotsRoot().resolve(RpgSavePaths.requireSafeGameStateId(gameStateId));
        List<Path> turnDirs;
        try (Stream<Path> stream = Files.list(gsRoot)) {
            turnDirs = stream
                    .filter(Files::isDirectory)
                    .filter(p -> TURN_DIR.matcher(p.getFileName().toString()).matches())
                    .filter(p -> turnOf(p) >= turn)
                    .toList();
        } catch (IOException e) {
            // 快照区不存在或读取失败：没有可清理的
            return;
        }
        for (Path dir : turnDirs) {
            try {
                deleteRecursively(dir);
            } catch (IOException e) {
                log.warn("清理记忆快照失败: {}", dir, e);
            }
        }
    }

    private Path snapshotDir(String gameStateId, int turn) {
        return snapshotsRoot().resolve(RpgSavePaths.requireSafeGameStateId(gameStateId))
                .resolve("turn-" + turn);
    }

    private int turnOf(Path dir) {
        var m = TURN_DIR.matcher(dir.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    /** 递归删除目录树（目录不存在时为 no-op）。 */
    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.delete(p);
            }
        }
    }

    /** 递归复制目录树（目标父目录自动创建，已有同名文件覆盖）。 */
    private void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path p : stream.toList()) {
                Path dest = target.resolve(source.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 快照根目录（测试与诊断用）。
     */
    Path getSnapshotsRoot() {
        return snapshotsRoot();
    }
}
