package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.GameStateSnapshot;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgGameStateSnapshotRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.List;

/**
 * 会话回溯编排服务（design D5）。
 * <p>
 * 职责链：定位锚点轮次（位置删除法，D2）→ 校验 3 轮上限 → 校验快照存在 →
 * 事务内执行（硬删 {@code AI_SESSION_EVENT} + bump {@code AI_SESSION.event_version}（D4）+
 * 状态快照覆盖 {@code rpg_game_state} + 清理 event_log / trigger_runtime / 过期快照）→
 * 事务提交后还原记忆目录（文件 IO 不进 DB 事务，失败降级为部分成功警告）。
 * <p>
 * 回溯与重新生成共用本服务：两者后端语义同构（删同轮起事件 + 回滚同一快照），
 * 差异仅在前端后续动作（填输入框 vs 自动重发）。
 */
@Service
public class RollbackService {

    private static final Logger log = LoggerFactory.getLogger(RollbackService.class);

    /** 回溯深度上限：currentTurn - anchorTurn + 1 <= 3（前端置灰仅 UX，此处为唯一真源） */
    public static final int MAX_BACKTRACK_TURNS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final SessionService sessionService;
    private final RpgGameStateRepository stateRepo;
    private final RpgGameStateSnapshotRepository snapshotRepo;
    private final RpgEventLogRepository eventLogRepo;
    private final RpgTriggerRuntimeRepository triggerRuntimeRepo;
    private final MemorySnapshotStore memorySnapshotStore;
    private final RpgHistoryCleaner historyCleaner;
    private final TransactionTemplate transactionTemplate;
    private final TurnGuardService turnGuardService;

    public RollbackService(JdbcTemplate jdbcTemplate,
                           SessionService sessionService,
                           RpgGameStateRepository stateRepo,
                           RpgGameStateSnapshotRepository snapshotRepo,
                           RpgEventLogRepository eventLogRepo,
                           RpgTriggerRuntimeRepository triggerRuntimeRepo,
                           MemorySnapshotStore memorySnapshotStore,
                           RpgHistoryCleaner historyCleaner,
                           TransactionTemplate transactionTemplate,
                           TurnGuardService turnGuardService) {
        this.jdbcTemplate = jdbcTemplate;
        this.sessionService = sessionService;
        this.stateRepo = stateRepo;
        this.snapshotRepo = snapshotRepo;
        this.eventLogRepo = eventLogRepo;
        this.triggerRuntimeRepo = triggerRuntimeRepo;
        this.memorySnapshotStore = memorySnapshotStore;
        this.historyCleaner = historyCleaner;
        this.transactionTemplate = transactionTemplate;
        this.turnGuardService = turnGuardService;
    }

    /**
     * 回溯到锚点所在轮次之前：删除该轮起的全部会话事件，游戏状态与记忆回滚到 N-1 轮末快照。
     *
     * @param sessionId      会话 ID
     * @param gameStateId    存档 ID
     * @param anchorEventId  锚点事件 ID（玩家气泡=其 user 事件；GM 气泡=同轮 assistant 事件）
     * @return 回溯结果（含将回填/重发的玩家原文）
     * @throws BacktrackException 锚点不存在 / 超过 3 轮上限 / 快照缺失 / 回合进行中等可预期拒绝
     */
    public RollbackResult backtrack(String sessionId, String gameStateId, String anchorEventId) {
        // 回合互斥守卫（design D11）：回合进行中（含 SSE 断流但服务器端仍在收尾）拒绝回溯，
        // 防止删除窗口吞掉进行中回合事件、回滚被 finalizeTurn 覆盖；回溯持锁期间并发操作同理被拒
        if (!turnGuardService.tryAcquire(gameStateId)) {
            throw new BacktrackException("当前有回合正在进行，请等待其完成或中止后再回溯");
        }
        try {
            return doBacktrack(sessionId, gameStateId, anchorEventId);
        } finally {
            turnGuardService.release(gameStateId);
        }
    }

    private RollbackResult doBacktrack(String sessionId, String gameStateId, String anchorEventId) {
        // 1. 定位锚点事件
        List<SessionEvent> events = sessionService.getEvents(sessionId);
        int anchorIdx = indexOfEvent(events, anchorEventId);
        if (anchorIdx < 0) {
            throw new BacktrackException("回溯锚点不存在（该消息可能已被回溯删除，请刷新页面）");
        }

        // 2. GM 锚点回退到同轮 user 事件（D2：轮次结构 user → [tool…] → assistant 严格交替）
        int userIdx = resolveRoundUserIndex(events, anchorIdx);

        String rawUserContent = events.get(userIdx).getMessage().getText();
        String playerMessage = historyCleaner.cleanUserMessage(rawUserContent);

        // 3. 锚点轮次推导（位置删除法）：R = 锚点轮起的 user 事件数，N = 最后一轮 L - R + 1。
        //    L 取 event_log 的最大 player_action 轮次（含中止/失败但已 prepareTurn 的轮次，
        //    比 turn_count 更贴近会话事件末尾）。
        int anchorTurn = resolveAnchorTurn(sessionId, gameStateId, events, userIdx);

        // 4. 3 轮上限（后端强校验）
        GameState gs = stateRepo.findById(gameStateId);
        if (gs == null) {
            throw new BacktrackException("游戏状态未找到: " + gameStateId);
        }
        int currentTurn = gs.getTurnCount() != null ? gs.getTurnCount() : 0;
        int depth = currentTurn - anchorTurn + 1;
        if (depth > MAX_BACKTRACK_TURNS) {
            throw new BacktrackException(String.format(
                    "回溯深度超限：最多回溯 %d 轮（当前第 %d 轮，锚点在第 %d 轮）",
                    MAX_BACKTRACK_TURNS, currentTurn, anchorTurn));
        }

        // 5. 快照存在性（DB 快照为准；存量旧存档走此拒绝路径）
        GameStateSnapshot snap = snapshotRepo.findByGameStateIdAndTurn(gameStateId, anchorTurn - 1);
        if (snap == null) {
            throw new BacktrackException("该轮次没有可用的状态快照（旧存档需先推进一个新回合后才能回溯）");
        }

        // 6. 统计将删除的会话事件数（确认文案用）
        String anchorUserEventId = events.get(userIdx).getId();
        Integer removedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM AI_SESSION_EVENT WHERE session_id = ? AND seq >= " +
                        "(SELECT seq FROM AI_SESSION_EVENT WHERE session_id = ? AND id = ?)",
                Integer.class, sessionId, sessionId, anchorUserEventId);
        int removed = removedCount != null ? removedCount : 0;

        // 7. DB 事务：硬删事件 + bump 版本号 + 状态覆盖 + 清理副产数据与过期快照
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "DELETE FROM AI_SESSION_EVENT WHERE session_id = ? AND seq >= " +
                            "(SELECT seq FROM AI_SESSION_EVENT WHERE session_id = ? AND id = ?)",
                    sessionId, sessionId, anchorUserEventId);
            // event_version 是 compact 的 CAS 乐观锁版本，直删必须同步 bump（D4）
            jdbcTemplate.update(
                    "UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?",
                    sessionId);
            // 状态快照覆盖当前状态（turn_count 一并回退）
            stateRepo.updateState(gameStateId, snap.getNpcStates(), snap.getTurn(), snap.getCurrentLocation());
            stateRepo.updateFlags(gameStateId, snap.getFlags());
            // PC 结构化状态同样随快照还原；变更前旧快照该列为 NULL，传 null 即置空不报错
            stateRepo.updatePlayerStates(gameStateId, snap.getPlayerStates());
            eventLogRepo.deleteFromTurn(gameStateId, anchorTurn);
            triggerRuntimeRepo.deleteFromTurn(gameStateId, anchorTurn);
            // 被回溯轮次之后的时间线作废：对应快照一并清理，避免重演后回溯到旧时间线
            snapshotRepo.deleteFromTurn(gameStateId, anchorTurn);
        });

        // 8. 事务提交后还原记忆目录（失败降级为部分成功警告，不做反向补偿）
        String warning = null;
        try {
            memorySnapshotStore.deleteFromTurn(gameStateId, anchorTurn);
            memorySnapshotStore.restore(gameStateId, anchorTurn - 1);
        } catch (IOException e) {
            log.error("记忆目录还原失败（DB 已回滚）: gameStateId={}, turn={}", gameStateId, anchorTurn - 1, e);
            warning = "会话与游戏状态已回滚，但记忆还原失败；残留的未来记忆可在记忆管理中手动删除";
        }

        log.info("回溯完成: sessionId={}, gameStateId={}, anchorTurn={}, removed={}, rolledBackToTurn={}",
                sessionId, gameStateId, anchorTurn, removed, anchorTurn - 1);
        return new RollbackResult(removed, anchorTurn - 1, playerMessage, warning);
    }

    /** 锚点在事件列表中的下标（按事件 ID 匹配），不存在返回 -1。 */
    private int indexOfEvent(List<SessionEvent> events, String anchorEventId) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getId().equals(anchorEventId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 解析锚点所在轮次的 user 事件下标。
     * <ul>
     *   <li>玩家气泡锚点（非合成 user 事件）：即自身</li>
     *   <li>GM 气泡锚点（assistant 事件）：向前找最近的非合成 user 事件（跳过 tool 事件）</li>
     * </ul>
     */
    private int resolveRoundUserIndex(List<SessionEvent> events, int anchorIdx) {
        Message anchorMsg = events.get(anchorIdx).getMessage();
        if (anchorMsg instanceof UserMessage && !events.get(anchorIdx).isSynthetic()) {
            return anchorIdx;
        }
        if (anchorMsg instanceof AssistantMessage) {
            for (int i = anchorIdx - 1; i >= 0; i--) {
                SessionEvent e = events.get(i);
                if (e.getMessage() instanceof UserMessage && !e.isSynthetic()) {
                    return i;
                }
            }
            throw new BacktrackException("无法定位该 GM 消息所在轮次的玩家消息");
        }
        throw new BacktrackException("锚点事件类型不支持回溯");
    }

    /**
     * 锚点轮次推导（位置删除法，D2）：session 事件无轮次号，但轮次结构严格交替。
     * R = 锚点 user 事件起的非合成 user 事件数（= 锚点轮到末尾的轮数），
     * N = L - R + 1，其中 L 为 event_log 中最大 player_action 轮次（最后发起的回合）。
     */
    private int resolveAnchorTurn(String sessionId, String gameStateId, List<SessionEvent> events, int userIdx) {
        int roundsFromAnchor = 0;
        for (int i = userIdx; i < events.size(); i++) {
            SessionEvent e = events.get(i);
            if (e.getMessage() instanceof UserMessage && !e.isSynthetic()) {
                roundsFromAnchor++;
            }
        }
        int lastTurn = eventLogRepo.findMaxPlayerActionTurn(gameStateId);
        int anchorTurn = lastTurn - roundsFromAnchor + 1;
        if (roundsFromAnchor < 1 || anchorTurn < 1) {
            throw new BacktrackException("无法定位锚点轮次（会话事件与事件日志不一致）");
        }
        return anchorTurn;
    }

    /** 回溯结果。playerMessage 为锚点轮玩家原文（玩家场景回填输入框 / 重新生成场景自动重发）。 */
    public record RollbackResult(
            int removedCount,
            int rolledBackToTurn,
            String playerMessage,
            String warning       // 非空 = 部分成功（记忆还原失败）
    ) {}

    /** 回溯被拒绝的业务异常（Controller 映射为 400 + 明确文案）。 */
    public static class BacktrackException extends RuntimeException {
        public BacktrackException(String message) {
            super(message);
        }
    }
}
