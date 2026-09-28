package com.luyu.agent.tenancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantFileMigration} 幂等迁移测试（tasks 4.5/4.6）：
 * 搬入 default 租户、源目录移除、重跑 no-op、目标冲突跳过不覆盖。
 */
class TenantFileMigrationTest {

    @TempDir
    Path temp;

    private Path legacyMemories;
    private Path legacyRpgSaves;
    private Path tenantsRoot;

    @BeforeEach
    void setUp() {
        legacyMemories = temp.resolve("legacy-memories");
        legacyRpgSaves = temp.resolve("legacy-rpg-saves");
        tenantsRoot = temp.resolve("tenants");
    }

    private TenantFileMigration migration() {
        return new TenantFileMigration(legacyMemories, legacyRpgSaves, tenantsRoot.resolve("default"));
    }

    private void writeFile(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void 存量目录搬入default租户且源目录移除() throws IOException {
        writeFile(legacyMemories, "user/profile.md", "存量全局记忆");
        writeFile(legacyRpgSaves, "gs-001/npc_lin.md", "存量存档记忆");

        migration().migrateAll();

        assertThat(Files.readString(tenantsRoot.resolve("default/memories/user/profile.md")))
                .isEqualTo("存量全局记忆");
        assertThat(Files.readString(tenantsRoot.resolve("default/rpg-saves/gs-001/npc_lin.md")))
                .isEqualTo("存量存档记忆");
        assertThat(Files.exists(legacyMemories)).isFalse();
        assertThat(Files.exists(legacyRpgSaves)).isFalse();
    }

    @Test
    void 迁移源不存在时为空操作() {
        migration().migrateAll();

        assertThat(Files.exists(tenantsRoot)).isFalse();
    }

    @Test
    void 重跑幂等不报错不重复() throws IOException {
        writeFile(legacyMemories, "a.md", "内容");
        migration().migrateAll();

        // 第二次：源已删 → 空操作
        migration().migrateAll();

        assertThat(Files.readString(tenantsRoot.resolve("default/memories/a.md"))).isEqualTo("内容");
    }

    @Test
    void 目标同名子项已存在时跳过不覆盖() throws IOException {
        writeFile(legacyMemories, "a.md", "旧内容");
        migration().migrateAll();

        // 人工在源目录重建同名文件（模拟中断后重跑或人工干预）
        writeFile(legacyMemories, "a.md", "新内容");
        migration().migrateAll();

        // 不覆盖：目标保持首迁内容，源目录因非空而保留
        assertThat(Files.readString(tenantsRoot.resolve("default/memories/a.md")))
                .isEqualTo("旧内容");
        assertThat(Files.readString(legacyMemories.resolve("a.md"))).isEqualTo("新内容");
    }

    @Test
    void 中断恢复场景部分迁移可续() throws IOException {
        // 模拟首次迁移中途失败：a 已搬入目标，b 尚在源目录
        writeFile(legacyMemories, "a.md", "A");
        writeFile(legacyMemories, "b.md", "B");
        Files.createDirectories(tenantsRoot.resolve("default/memories"));
        Files.writeString(tenantsRoot.resolve("default/memories/a.md"), "A");

        migration().migrateAll();

        // b 续迁完成；a 因目标已存在而跳过（保留在源目录，人工裁决）
        assertThat(Files.readString(tenantsRoot.resolve("default/memories/b.md"))).isEqualTo("B");
        assertThat(Files.readString(legacyMemories.resolve("a.md"))).isEqualTo("A");
        assertThat(Files.readAllBytes(legacyMemories.resolve("a.md")))
                .isEqualTo(Files.readAllBytes(tenantsRoot.resolve("default/memories/a.md")));
    }
}
