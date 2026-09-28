package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.model.GameStateSnapshot;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgGameStateSnapshotRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RollbackService} 的单元测试（纯 Mockito）。
 * <p>
 * 钉住回溯编排的核心行为：玩家/GM 锚点定位、3 轮上限边界、快照缺失拒绝、
 * 事件删除与版本号 bump、记忆还原与失败降级、副产数据清理。
 */
class RollbackServiceTest {

    private static final String SESSION_ID = "sess-1";
    private static final String GS_ID = "gs-1";

    private JdbcTemplate jdbcTemplate;
    private SessionService sessionService;
    private RpgGameStateRepository stateRepo;
    private RpgGameStateSnapshotRepository snapshotRepo;
    private RpgEventLogRepository eventLogRepo;
    private RpgTriggerRuntimeRepository triggerRuntimeRepo;
    private MemorySnapshotStore memorySnapshotStore;
    private RpgHistoryCleaner historyCleaner;
    private TransactionTemplate transactionTemplate;
    private TurnGuardService turnGuardService;
    private RollbackService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        sessionService = mock(SessionService.class);
        stateRepo = mock(RpgGameStateRepository.class);
        snapshotRepo = mock(RpgGameStateSnapshotRepository.class);
        eventLogRepo = mock(RpgEventLogRepository.class);
        triggerRuntimeRepo = mock(RpgTriggerRuntimeRepository.class);
        memorySnapshotStore = mock(MemorySnapshotStore.class);
        historyCleaner = mock(RpgHistoryCleaner.class);
        transactionTemplate = mock(TransactionTemplate.class);
        turnGuardService = new TurnGuardService();

        // 事务模板直接执行回调（真实事务行为由集成环境保证）
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // cleaner 默认行为：原样 trim 返回（首轮未包裹 prompt 的语义）
        when(historyCleaner.cleanUserMessage(anyString()))
                .thenAnswer(inv -> inv.getArgument(0) == null ? "" : ((String) inv.getArgument(0)).trim());

        service = new RollbackService(jdbcTemplate, sessionService, stateRepo, snapshotRepo,
                eventLogRepo, triggerRuntimeRepo, memorySnapshotStore, historyCleaner, transactionTemplate,
                turnGuardService);
    }

    // ==================== 事件构造辅助 ====================

    private static SessionEvent userEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new UserMessage(text)).timestamp(Instant.now()).build();
    }

    private static SessionEvent syntheticUserEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new UserMessage(text))
                .metadata(SessionEvent.METADATA_SYNTHETIC, true)
                .timestamp(Instant.now()).build();
    }

    private static SessionEvent assistantEvent(String id, String text) {
        return SessionEvent.builder().id(id).sessionId(SESSION_ID)
                .message(new AssistantMessage(text)).timestamp(Instant.now()).build();
    }

    private static GameState gameState(int turnCount) {
        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setTurnCount(turnCount);
        return gs;
    }

    private static GameStateSnapshot snapshot(int turn) {
        GameStateSnapshot snap = new GameStateSnapshot();
        snap.setGameStateId(GS_ID);
        snap.setTurn(turn);
        snap.setCurrentLocation("老地方");
        snap.setFlags("{\"见过盗贼\":true}");
        snap.setNpcStates("{\"盗贼-001\":{}}");
        snap.setPlayerStates("{\"money\":100, \"titles\":[\"燕云小剑客\"]}");
        return snap;
    }

    /** 两个完整轮次：u1→a1（第1轮）、u2→a2（第2轮）。 */
    private void stubTwoRounds() {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。"),
                userEvent("u2", "上楼"),
                assistantEvent("a2", "楼梯吱呀作响。")));
        when(eventLogRepo.findMaxPlayerActionTurn(GS_ID)).thenReturn(2);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(2));
        when(snapshotRepo.findByGameStateIdAndTurn(GS_ID, 1)).thenReturn(snapshot(1));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(SESSION_ID), eq(SESSION_ID), anyString())).thenReturn(2);
    }

    // ==================== 锚点定位 ====================

    @Test
    void 玩家锚点回溯到该轮之前() throws Exception {
        stubTwoRounds();

        RollbackService.RollbackResult result = service.backtrack(SESSION_ID, GS_ID, "u2");

        assertThat(result.removedCount()).isEqualTo(2);
        assertThat(result.rolledBackToTurn()).isEqualTo(1);
        assertThat(result.playerMessage()).isEqualTo("上楼");
        assertThat(result.warning()).isNull();

        // 会话事件硬删（锚点 user 事件的 seq 起）+ 同事务 bump 版本号
        verify(jdbcTemplate).update(containsSeqSubquery(), eq(SESSION_ID), eq(SESSION_ID), eq("u2"));
        verify(jdbcTemplate).update(eq("UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?"),
                eq(SESSION_ID));
        // 状态快照覆盖（turn_count 回退到快照轮次）
        verify(stateRepo).updateState(eq(GS_ID), eq("{\"盗贼-001\":{}}"), eq(1), eq("老地方"));
        verify(stateRepo).updateFlags(GS_ID, "{\"见过盗贼\":true}");
        // PC 结构化状态随快照还原（rpg-player-memory：快照是回溯真源）
        verify(stateRepo).updatePlayerStates(GS_ID, "{\"money\":100, \"titles\":[\"燕云小剑客\"]}");
        // 副产数据与过期快照清理
        verify(eventLogRepo).deleteFromTurn(GS_ID, 2);
        verify(triggerRuntimeRepo).deleteFromTurn(GS_ID, 2);
        verify(snapshotRepo).deleteFromTurn(GS_ID, 2);
        // 记忆目录还原（事务提交后）
        verify(memorySnapshotStore).deleteFromTurn(GS_ID, 2);
        verify(memorySnapshotStore).restore(GS_ID, 1);
    }

    @Test
    void GM锚点回退到同轮user事件() throws Exception {
        stubTwoRounds();

        RollbackService.RollbackResult result = service.backtrack(SESSION_ID, GS_ID, "a2");

        // 与玩家锚点 u2 语义同构：删同一轮起的事件、回滚同一快照
        assertThat(result.rolledBackToTurn()).isEqualTo(1);
        assertThat(result.playerMessage()).isEqualTo("上楼");
        verify(jdbcTemplate).update(containsSeqSubquery(), eq(SESSION_ID), eq(SESSION_ID), eq("u2"));
        verify(memorySnapshotStore).restore(GS_ID, 1);
    }

    @Test
    void 合成user事件不计入轮次推导() throws Exception {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "我走进客栈"),
                assistantEvent("a1", "老板娘抬头看了你一眼。"),
                userEvent("u2", "上楼"),
                assistantEvent("a2", "楼梯吱呀作响。"),
                syntheticUserEvent("syn1", "前情摘要")));
        when(eventLogRepo.findMaxPlayerActionTurn(GS_ID)).thenReturn(2);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(2));
        when(snapshotRepo.findByGameStateIdAndTurn(GS_ID, 1)).thenReturn(snapshot(1));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(SESSION_ID), eq(SESSION_ID), anyString())).thenReturn(2);

        // 若合成事件被计入，R 会多算 1 导致 anchorTurn=1、快照取 turn=0
        RollbackService.RollbackResult result = service.backtrack(SESSION_ID, GS_ID, "u2");

        assertThat(result.rolledBackToTurn()).isEqualTo(1);
        verify(snapshotRepo).findByGameStateIdAndTurn(GS_ID, 1);
    }

    @Test
    void 旧快照无玩家状态列值时置空不报错() throws Exception {
        stubTwoRounds();
        // 变更前旧快照：player_states 为 NULL，回溯后玩家状态置空（从空开始累积）
        GameStateSnapshot oldSnap = snapshot(1);
        oldSnap.setPlayerStates(null);
        when(snapshotRepo.findByGameStateIdAndTurn(GS_ID, 1)).thenReturn(oldSnap);

        RollbackService.RollbackResult result = service.backtrack(SESSION_ID, GS_ID, "u2");

        assertThat(result.rolledBackToTurn()).isEqualTo(1);
        verify(stateRepo).updatePlayerStates(GS_ID, null);
    }

    // ==================== 校验拒绝路径 ====================

    @Test
    void 锚点不存在时拒绝() {
        stubTwoRounds();

        assertThatThrownBy(() -> service.backtrack(SESSION_ID, GS_ID, "nope"))
                .isInstanceOf(RollbackService.BacktrackException.class)
                .hasMessageContaining("锚点不存在");
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void 回溯深度等于3轮时通过() throws Exception {
        // 三轮完整，锚定第 1 轮：depth = 3 - 1 + 1 = 3，恰好在上限内
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "一"), assistantEvent("a1", "1"),
                userEvent("u2", "二"), assistantEvent("a2", "2"),
                userEvent("u3", "三"), assistantEvent("a3", "3")));
        when(eventLogRepo.findMaxPlayerActionTurn(GS_ID)).thenReturn(3);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(3));
        when(snapshotRepo.findByGameStateIdAndTurn(GS_ID, 0)).thenReturn(snapshot(0));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class),
                eq(SESSION_ID), eq(SESSION_ID), anyString())).thenReturn(6);

        RollbackService.RollbackResult result = service.backtrack(SESSION_ID, GS_ID, "u1");

        assertThat(result.rolledBackToTurn()).isEqualTo(0);
        verify(memorySnapshotStore).restore(GS_ID, 0);
    }

    @Test
    void 回溯深度超过3轮时拒绝() throws Exception {
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "一"), assistantEvent("a1", "1"),
                userEvent("u2", "二"), assistantEvent("a2", "2"),
                userEvent("u3", "三"), assistantEvent("a3", "3"),
                userEvent("u4", "四"), assistantEvent("a4", "4")));
        when(eventLogRepo.findMaxPlayerActionTurn(GS_ID)).thenReturn(4);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(4));

        assertThatThrownBy(() -> service.backtrack(SESSION_ID, GS_ID, "u1"))
                .isInstanceOf(RollbackService.BacktrackException.class)
                .hasMessageContaining("回溯深度超限");
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(memorySnapshotStore, never()).restore(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 快照缺失时拒绝() throws Exception {
        stubTwoRounds();
        when(snapshotRepo.findByGameStateIdAndTurn(GS_ID, 1)).thenReturn(null);

        assertThatThrownBy(() -> service.backtrack(SESSION_ID, GS_ID, "u2"))
                .isInstanceOf(RollbackService.BacktrackException.class)
                .hasMessageContaining("快照");
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(memorySnapshotStore, never()).restore(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ==================== 降级与事务语义 ====================

    @Test
    void 记忆还原失败降级为部分成功警告() {
        stubTwoRounds();
        try {
            doThrow(new java.io.IOException("disk")).when(memorySnapshotStore).restore(GS_ID, 1);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }

        RollbackService.RollbackResult result = service.backtrack(SESSION_ID, GS_ID, "u2");

        // DB 回滚不受影响，warning 非空
        assertThat(result.warning()).isNotNull();
        verify(jdbcTemplate).update(eq("UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?"),
                eq(SESSION_ID));
    }

    @Test
    void 会话事件删除与版本号bump在同一事务回调内执行() {
        stubTwoRounds();

        service.backtrack(SESSION_ID, GS_ID, "u2");

        // 事务回调内的两个 JDBC 操作按序执行：先删事件，后 bump 版本号
        InOrder inOrder = inOrder(jdbcTemplate);
        inOrder.verify(jdbcTemplate).update(containsSeqSubquery(), eq(SESSION_ID), eq(SESSION_ID), eq("u2"));
        inOrder.verify(jdbcTemplate).update(eq("UPDATE AI_SESSION SET event_version = event_version + 1 WHERE id = ?"),
                eq(SESSION_ID));
    }

    /** 匹配含 seq 子查询的事件删除 SQL。 */
    private static String containsSeqSubquery() {
        return org.mockito.ArgumentMatchers.argThat(sql ->
                sql != null && sql.startsWith("DELETE FROM AI_SESSION_EVENT")
                        && sql.contains("seq >=") && sql.contains("(SELECT seq"));
    }

    // ==================== 回合互斥守卫（design D11） ====================

    @Test
    void 回合进行中回溯被拒且无任何副作用() throws Exception {
        stubTwoRounds();
        // 模拟回合进行中（SSE 断流但服务器端仍在收尾的竞态窗口）
        turnGuardService.tryAcquire(GS_ID);

        assertThatThrownBy(() -> service.backtrack(SESSION_ID, GS_ID, "u2"))
                .isInstanceOf(RollbackService.BacktrackException.class)
                .hasMessageContaining("当前有回合正在进行");

        // 会话/状态/记忆均不变：无任何删除、回滚、还原副作用
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(stateRepo, never()).updateState(any(), any(), any(), any());
        verify(memorySnapshotStore, never()).restore(anyString(), org.mockito.ArgumentMatchers.anyInt());
        // 守卫仍被回合持有（回溯拒绝不得释放他人持有的锁）
        assertThat(turnGuardService.isInTurn(GS_ID)).isTrue();

        // 回合终止（释放守卫）后回溯恢复正常
        turnGuardService.release(GS_ID);
        RollbackService.RollbackResult result = service.backtrack(SESSION_ID, GS_ID, "u2");
        assertThat(result.rolledBackToTurn()).isEqualTo(1);
    }

    @Test
    void 回溯拒绝路径守卫不泄漏() {
        // 深度超限（doBacktrack 内抛出）也必须走 finally 释放
        when(sessionService.getEvents(SESSION_ID)).thenReturn(List.of(
                userEvent("u1", "一"), assistantEvent("a1", "1"),
                userEvent("u2", "二"), assistantEvent("a2", "2"),
                userEvent("u3", "三"), assistantEvent("a3", "3"),
                userEvent("u4", "四"), assistantEvent("a4", "4")));
        when(eventLogRepo.findMaxPlayerActionTurn(GS_ID)).thenReturn(4);
        when(stateRepo.findById(GS_ID)).thenReturn(gameState(4));

        assertThatThrownBy(() -> service.backtrack(SESSION_ID, GS_ID, "u1"))
                .hasMessageContaining("回溯深度超限");
        assertThat(turnGuardService.isInTurn(GS_ID)).isFalse();
    }

    @Test
    void 成功回溯后守卫释放() {
        stubTwoRounds();

        service.backtrack(SESSION_ID, GS_ID, "u2");

        assertThat(turnGuardService.isInTurn(GS_ID)).isFalse();
    }
}
