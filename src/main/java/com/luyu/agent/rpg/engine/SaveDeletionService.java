package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgGameStateSnapshotRepository;
import com.luyu.agent.rpg.repository.RpgLorebookSetRepository;
import com.luyu.agent.rpg.repository.RpgStarIncarnationRuntimeRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import com.luyu.agent.tenancy.TenantPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * 存档删除编排服务（rpg-save-card-list design D3）。
 * <p>
 * 全量级联清理，顺序与 RollbackService 同构：
 * <ol>
 *   <li>前置校验：存档存在（404）、回合互斥（409，事务内复查一次堵竞态窗口）</li>
 *   <li>读 sessionId（删除前先取出，主行删后无处可查）</li>
 *   <li>DB 事务：硬删 {@code AI_SESSION_EVENT} 全部事件 + 连壳删 {@code AI_SESSION} 行
 *       （separate-rpg-chat-sessions D4：不留幽灵空壳，原 bump event_version 随壳删除移除）
 *       + 删附属表（event_log /
 *       trigger_runtime / star_runtime / snapshot）+ 主行最后删</li>
 *   <li>事务提交后文件清理：{@code rpg-saves/<id>/} 与 {@code rpg-saves/.snapshots/<id>/}；
 *       文件 IO 不进事务，失败记 WARN 降级不回滚——DB 真源已删，残留目录不参与任何查询，
 *       避免用户面对"删了又复活"的幽灵存档</li>
 * </ol>
 * 角色卡 player 类型不降级（角色可能被多个存档使用，类型归工坊管理）。
 * <p>
 * 异常约定对齐 WorkshopService 阻塞式删除：
 * {@link IllegalArgumentException} = 存档不存在（404），{@link IllegalStateException} = 回合进行中（409）。
 */
@Service
public class SaveDeletionService {

    private static final Logger log = LoggerFactory.getLogger(SaveDeletionService.class);

    /**
     * 存档 ID 白名单：只允许单段安全字符。
     * <p>
     * gameStateId 会被拼进文件根目录作为路径段，必须自行校验
     * （与 RpgSaveMemoryController 一致），否则 {@code ..%2F..} 之类的值会指到存档总根之外。
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JdbcTemplate jdbcTemplate;
    private final RpgGameStateRepository stateRepo;
    private final RpgEventLogRepository eventLogRepo;
    private final RpgTriggerRuntimeRepository triggerRuntimeRepo;
    private final RpgStarIncarnationRuntimeRepository starRuntimeRepo;
    private final RpgGameStateSnapshotRepository snapshotRepo;
    private final RpgLorebookSetRepository lorebookSetRepo;
    private final TurnGuardService turnGuardService;
    private final TransactionTemplate transactionTemplate;
    /** 固定根注入（测试用）；null = 按当前请求租户解析（design D6 / tasks 4.4） */
    private final Path savesRootOverride;

    @Autowired
    public SaveDeletionService(JdbcTemplate jdbcTemplate,
                               RpgGameStateRepository stateRepo,
                               RpgEventLogRepository eventLogRepo,
                               RpgTriggerRuntimeRepository triggerRuntimeRepo,
                               RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                               RpgGameStateSnapshotRepository snapshotRepo,
                               RpgLorebookSetRepository lorebookSetRepo,
                               TurnGuardService turnGuardService,
                               PlatformTransactionManager transactionManager) {
        this(jdbcTemplate, stateRepo, eventLogRepo, triggerRuntimeRepo, starRuntimeRepo, snapshotRepo,
                lorebookSetRepo, turnGuardService, transactionManager, null);
    }

    /**
     * 测试用构造器：替换存档记忆根目录（快照根 = <savesRoot>/.snapshots；null 则按请求租户解析）。
     */
    SaveDeletionService(JdbcTemplate jdbcTemplate,
                        RpgGameStateRepository stateRepo,
                        RpgEventLogRepository eventLogRepo,
                        RpgTriggerRuntimeRepository triggerRuntimeRepo,
                        RpgStarIncarnationRuntimeRepository starRuntimeRepo,
                        RpgGameStateSnapshotRepository snapshotRepo,
                        RpgLorebookSetRepository lorebookSetRepo,
                        TurnGuardService turnGuardService,
                        PlatformTransactionManager transactionManager,
                        Path savesRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.stateRepo = stateRepo;
        this.eventLogRepo = eventLogRepo;
        this.triggerRuntimeRepo = triggerRuntimeRepo;
        this.starRuntimeRepo = starRuntimeRepo;
        this.snapshotRepo = snapshotRepo;
        this.lorebookSetRepo = lorebookSetRepo;
        this.turnGuardService = turnGuardService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.savesRootOverride = savesRoot;
    }

    /** 存档根：固定注入优先，否则按当前请求租户解析 {@code tenants/<tid>/rpg-saves}。 */
    private Path savesRoot() {
        return savesRootOverride != null ? savesRootOverride : TenantPaths.rpgSavesDir();
    }

    /**
     * 删除存档并全量级联清理。
     *
     * @param gameStateId 存档 ID
     * @return 删除的会话事件数（供前端提示）
     * @throws IllegalArgumentException 存档不存在或 ID 非法（404）
     * @throws IllegalStateException 该存档有回合正在进行（409）
     */
    public int deleteSave(String gameStateId) {
        if (gameStateId == null || !SAFE_ID.matcher(gameStateId).matches()) {
            throw new IllegalArgumentException("非法的存档 ID: " + gameStateId);
        }
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            throw new IllegalArgumentException("存档不存在: " + gameStateId);
        }
        // 回合互斥（只读判断，不持锁）：删除是瞬时操作；事务内再复查一次堵竞态窗口
        if (turnGuardService.isInTurn(gameStateId)) {
            throw new IllegalStateException("该存档当前有回合正在进行，请等待其完成或中止后再删除");
        }

        String sessionId = gs.getSessionId();
        int[] removedEvents = {0};
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 事务内复查：入口校验与事务提交之间可能恰好有新回合进入
                if (turnGuardService.isInTurn(gameStateId)) {
                    throw new IllegalStateException("该存档当前有回合正在进行，请等待其完成或中止后再删除");
                }
                // session 事件与壳同事务清理（separate-rpg-chat-sessions D4：GameState 与
                // session 1:1 固定绑定，清理范围确定；壳行删除不留幽灵空壳，
                // 原 bump event_version 随壳删除失去意义一并移除）
                if (sessionId != null && !sessionId.isBlank()) {
                    removedEvents[0] = jdbcTemplate.update(
                            "DELETE FROM AI_SESSION_EVENT WHERE session_id = ?", sessionId);
                    jdbcTemplate.update(
                            "DELETE FROM AI_SESSION WHERE id = ?",
                            sessionId);
                }
                // 附属表清理（含设定集·已揭示的存档级 runtime 行，star-lorebook）
                eventLogRepo.deleteByGameStateId(gameStateId);
                triggerRuntimeRepo.deleteByGameStateId(gameStateId);
                starRuntimeRepo.deleteByGameStateId(gameStateId);
                snapshotRepo.deleteByGameStateId(gameStateId);
                // 世界书副本不级联删（add-rpg-lorebook-sets D7）：仅解除存档引用使其孤儿化，
                // set 及双轨条目行保留，可被后续开局继承或手动删除
                lorebookSetRepo.orphanizeByGameStateId(gameStateId);
                // 主行最后删：事务中途失败时残留行仍能定位孤儿数据
                stateRepo.deleteById(gameStateId);
            });
        } catch (IllegalStateException e) {
            // 事务内互斥复查命中：仍是 409 语义，原样上抛
            throw e;
        } catch (Exception e) {
            log.error("删档 DB 事务失败: gameStateId={}, sessionId={}", gameStateId, sessionId, e);
            // 500 语义（非互斥拒绝）：不能用 IllegalStateException，Controller 会把它映射成 409
            throw new RuntimeException("删档失败: " + e.getMessage(), e);
        }

        // 事务提交后文件清理：失败 WARN 降级（DB 已删，残留目录不参与查询）
        cleanupFilesQuietly(gameStateId);

        log.info("存档已删除: gameStateId={}, sessionId={}, removedEvents={}, removedRows={}",
                gameStateId, sessionId, removedEvents[0],
                gs.getTurnCount() != null ? gs.getTurnCount() : 0);
        return removedEvents[0];
    }

    /**
     * 清理存档的记忆目录与记忆快照目录，失败仅记 WARN（不回滚 DB、不改变删除成功语义）。
     */
    private void cleanupFilesQuietly(String gameStateId) {
        try {
            deleteDir(savesRoot().resolve(gameStateId));
            deleteDir(savesRoot().resolve(".snapshots").resolve(gameStateId));
        } catch (Exception e) {
            log.warn("删档后文件清理失败（DB 已删、文件残留，不参与查询，可手动清理）: gameStateId={}，"
                    + "记忆目录/快照目录可能残留", gameStateId, e);
        }
    }

    /**
     * 递归删除目录（含自身）。目录不存在时静默跳过。包级可见以便单测重写模拟失败。
     */
    void deleteDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}

