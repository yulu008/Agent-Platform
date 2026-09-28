package com.luyu.agent.rpg.engine;

import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import com.luyu.agent.rpg.repository.RpgGameStateRepository;
import com.luyu.agent.rpg.repository.RpgGameStateSnapshotRepository;
import com.luyu.agent.rpg.repository.RpgTriggerRuntimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SaveDeletionService} 的单元测试（纯 Mockito + 临时目录）。
 * <p>
 * 钉住删档编排的核心行为：全量级联清理顺序（session 事件 → 版本 bump →
 * 附属表 → 主行最后删）、404/409 语义、事务内互斥复查、文件清理成功与失败降级。
 */
class SaveDeletionServiceTest {

    private static final String GS_ID = "gs-1";
    private static final String SESSION_ID = "sess-1";

    private JdbcTemplate jdbcTemplate;
    private RpgGameStateRepository stateRepo;
    private RpgEventLogRepository eventLogRepo;
    private RpgTriggerRuntimeRepository triggerRuntimeRepo;
    private RpgGameStateSnapshotRepository snapshotRepo;
    private TurnGuardService turnGuardService;
    private SaveDeletionService service;

    @TempDir
    Path savesRoot;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        stateRepo = mock(RpgGameStateRepository.class);
        eventLogRepo = mock(RpgEventLogRepository.class);
        triggerRuntimeRepo = mock(RpgTriggerRuntimeRepository.class);
        snapshotRepo = mock(RpgGameStateSnapshotRepository.class);
        turnGuardService = mock(TurnGuardService.class);
        when(turnGuardService.isInTurn(GS_ID)).thenReturn(false);

        GameState gs = new GameState();
        gs.setId(GS_ID);
        gs.setSessionId(SESSION_ID);
        gs.setWorldId("w1");
        gs.setPlayerCharId("c1");
        gs.setTurnCount(5);
        when(stateRepo.findById(GS_ID)).thenReturn(gs);

        service = new SaveDeletionService(jdbcTemplate, stateRepo, eventLogRepo,
                triggerRuntimeRepo, snapshotRepo, turnGuardService,
                mock(PlatformTransactionManager.class), savesRoot);
    }

    // ==================== 全量级联清理 ====================

    @Test
    void 删除成功按序全量清理且文件目录被删() throws IOException {
        when(jdbcTemplate.update(contains("AI_SESSION_EVENT"), eq(SESSION_ID))).thenReturn(7);
        // 记忆目录 + 快照目录（含嵌套内容）
        Path memoryDir = savesRoot.resolve(GS_ID);
        Files.createDirectories(memoryDir.resolve("sub"));
        Files.writeString(memoryDir.resolve("npc_1.md"), "记忆");
        Path snapshotDir = savesRoot.resolve(".snapshots").resolve(GS_ID);
        Files.createDirectories(snapshotDir);
        Files.writeString(snapshotDir.resolve("turn-3"), "快照");

        int removed = service.deleteSave(GS_ID);

        assertThat(removed).isEqualTo(7);
        InOrder inOrder = inOrder(jdbcTemplate, eventLogRepo, triggerRuntimeRepo, snapshotRepo, stateRepo);
        inOrder.verify(jdbcTemplate).update(contains("AI_SESSION_EVENT"), eq(SESSION_ID));
        inOrder.verify(jdbcTemplate).update(contains("event_version"), eq(SESSION_ID));
        inOrder.verify(eventLogRepo).deleteByGameStateId(GS_ID);
        inOrder.verify(triggerRuntimeRepo).deleteByGameStateId(GS_ID);
        inOrder.verify(snapshotRepo).deleteByGameStateId(GS_ID);
        // 主行最后删
        inOrder.verify(stateRepo).deleteById(GS_ID);
        assertThat(memoryDir).doesNotExist();
        assertThat(snapshotDir).doesNotExist();
    }

    @Test
    void 无session关联时跳过会话清理仍删DB与文件() {
        GameState orphan = new GameState();
        orphan.setId(GS_ID);
        orphan.setSessionId(null);
        orphan.setWorldId("w1");
        orphan.setPlayerCharId("c1");
        when(stateRepo.findById(GS_ID)).thenReturn(orphan);

        int removed = service.deleteSave(GS_ID);

        assertThat(removed).isZero();
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
        verify(stateRepo).deleteById(GS_ID);
    }

    // ==================== 404 / 409 ====================

    @Test
    void 存档不存在时抛IllegalArgument且零副作用() {
        when(stateRepo.findById(GS_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.deleteSave(GS_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("存档不存在");

        verify(stateRepo, never()).deleteById(any());
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
    }

    @Test
    void 非法ID直接拒绝() {
        assertThatThrownBy(() -> service.deleteSave("../evil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法");

        verify(stateRepo, never()).findById(any());
    }

    @Test
    void 回合进行中入口即拒绝且零副作用() {
        when(turnGuardService.isInTurn(GS_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteSave(GS_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回合正在进行");

        verify(stateRepo, never()).deleteById(any());
        verify(eventLogRepo, never()).deleteByGameStateId(any());
    }

    @Test
    void 事务内互斥复查命中时拒绝且未删主行() {
        // 第一次（入口校验）false，第二次（事务内复查）true
        AtomicInteger calls = new AtomicInteger();
        when(turnGuardService.isInTurn(GS_ID))
                .thenAnswer(inv -> calls.incrementAndGet() < 2);

        assertThatThrownBy(() -> service.deleteSave(GS_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回合正在进行");

        // 复查位于事务首步：任何删除都未发生（事务回滚语义）
        verify(stateRepo, never()).deleteById(any());
        verify(eventLogRepo, never()).deleteByGameStateId(any());
        verify(jdbcTemplate, never()).update(anyString(), (Object) any());
    }

    // ==================== 文件清理降级 ====================

    @Test
    void 文件清理失败时降级为成功返回() throws IOException {
        SaveDeletionService failing = new SaveDeletionService(jdbcTemplate, stateRepo, eventLogRepo,
                triggerRuntimeRepo, snapshotRepo, turnGuardService,
                mock(PlatformTransactionManager.class), savesRoot) {
            @Override
            void deleteDir(Path dir) throws IOException {
                throw new IOException("模拟文件系统故障");
            }
        };

        Integer removed = failing.deleteSave(GS_ID);

        // DB 清理照常完成，文件失败不改变删除成功语义
        assertThat(removed).isZero();
        verify(stateRepo).deleteById(GS_ID);
    }

    // ==================== DB 事务失败 ====================

    @Test
    void 事务中途失败整体失败并上抛RuntimeException() {
        doThrow(new RuntimeException("DB 连接中断"))
                .when(triggerRuntimeRepo).deleteByGameStateId(GS_ID);

        assertThatThrownBy(() -> service.deleteSave(GS_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("删档失败");

        // 主行未删（事务回滚语义），文件清理也不该执行——GS_ID 目录未创建，无从验证，主行断言已足够
        verify(stateRepo, never()).deleteById(GS_ID);
    }
}
