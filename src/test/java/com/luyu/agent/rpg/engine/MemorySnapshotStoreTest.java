package com.luyu.agent.rpg.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemorySnapshotStore} 记忆目录快照/还原的单元测试（真实临时目录，无 mock）。
 * <p>
 * 钉住回溯记忆回退的核心行为：新建的记忆消失、被修改的记忆恢复旧文、索引随目录还原、
 * 快照区放在存档目录之外、保留窗口只留最近 4 份。
 */
class MemorySnapshotStoreTest {

    private static final String GS_ID = "gs-1";

    @TempDir
    Path tempDir;

    private Path savesRoot;
    private Path snapshotsRoot;
    private MemorySnapshotStore store;

    @BeforeEach
    void setUp() {
        savesRoot = tempDir.resolve("saves");
        snapshotsRoot = tempDir.resolve("snapshots");
        store = new MemorySnapshotStore(savesRoot, snapshotsRoot);
    }

    private Path memoryFile(String name, String content) throws IOException {
        Path file = savesRoot.resolve(GS_ID).resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void 快照还原后新建记忆消失修改记忆恢复旧文() throws IOException {
        // turn=2 末的记忆状态：npc_atao.md 存在，MEMORY.md 有两条索引
        memoryFile("npc_atao.md", "旧内容：巷口小丫");
        memoryFile("MEMORY.md", "- [npc_atao](npc_atao.md)\n- [lore](lore.md)\n");
        store.snapshot(GS_ID, 2);

        // 第 3 轮：修改既有记忆 + 新增记忆 + 索引变动
        memoryFile("npc_atao.md", "新内容：剧透未来的改动");
        memoryFile("npc_xuanhai.md", "第 3 轮新写的记忆");
        memoryFile("MEMORY.md", "- [npc_atao](npc_atao.md)\n- [npc_xuanhai](npc_xuanhai.md)\n");

        // 回溯第 3 轮：还原到 turn=2 快照
        store.restore(GS_ID, 2);

        assertThat(savesRoot.resolve(GS_ID).resolve("npc_atao.md"))
                .hasContent("旧内容：巷口小丫");
        assertThat(savesRoot.resolve(GS_ID).resolve("npc_xuanhai.md"))
                .doesNotExist();
        assertThat(savesRoot.resolve(GS_ID).resolve("MEMORY.md"))
                .hasContent("- [npc_atao](npc_atao.md)\n- [lore](lore.md)\n");
    }

    @Test
    void 快照区放在存档目录之外() throws IOException {
        memoryFile("npc_atao.md", "内容");
        store.snapshot(GS_ID, 1);

        // 快照落在 snapshotsRoot 下，不在存档记忆目录内（记忆弹窗看不到快照区）
        assertThat(snapshotsRoot.resolve(GS_ID).resolve("turn-1").resolve("npc_atao.md"))
                .hasContent("内容");
        assertThat(savesRoot.resolve(GS_ID).resolve("turn-1")).doesNotExist();
    }

    @Test
    void 无记忆目录时快照静默跳过还原按空记忆处理() throws IOException {
        // 存档尚无记忆目录（新开局未写过记忆）
        store.snapshot(GS_ID, 0);

        assertThat(snapshotsRoot.resolve(GS_ID).resolve("turn-0")).doesNotExist();

        // 无快照目录时还原 = 清空当前存档目录（该轮记忆为空的语义）
        memoryFile("npc_future.md", "后续轮次写的记忆");
        store.restore(GS_ID, 0);
        assertThat(savesRoot.resolve(GS_ID).resolve("npc_future.md")).doesNotExist();
    }

    @Test
    void 按轮次删除快照只删指定轮及之后() throws IOException {
        memoryFile("npc.md", "内容");
        for (int turn = 1; turn <= 5; turn++) {
            store.snapshot(GS_ID, turn);
        }

        store.deleteFromTurn(GS_ID, 3);

        List<String> remaining;
        try (var stream = Files.list(snapshotsRoot.resolve(GS_ID))) {
            remaining = stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(remaining).containsExactly("turn-1", "turn-2");
    }

    @Test
    void 同turn重复快照覆盖旧份() throws IOException {
        memoryFile("npc_atao.md", "第一次快照");
        store.snapshot(GS_ID, 2);
        memoryFile("npc_atao.md", "重新生成后第二次快照");
        store.snapshot(GS_ID, 2);

        // 连续重新生成对同 turn 重复快照：后一份覆盖前一份
        assertThat(snapshotsRoot.resolve(GS_ID).resolve("turn-2").resolve("npc_atao.md"))
                .hasContent("重新生成后第二次快照");
    }

    @Test
    void 保留窗口清理只删最旧的快照() throws IOException {
        memoryFile("npc.md", "内容");
        for (int turn = 1; turn <= 6; turn++) {
            store.snapshot(GS_ID, turn);
        }

        store.prune(GS_ID, MemorySnapshotStore.KEEP_COUNT);

        List<String> remaining;
        try (var stream = Files.list(snapshotsRoot.resolve(GS_ID))) {
            remaining = stream.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(remaining).containsExactly("turn-3", "turn-4", "turn-5", "turn-6");
    }
}
