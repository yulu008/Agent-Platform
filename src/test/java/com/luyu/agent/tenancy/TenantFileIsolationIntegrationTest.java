package com.luyu.agent.tenancy;

import com.luyu.agent.controller.MemoryController;
import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.service.MemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件记忆租户隔离集成测试（tasks 4.6，file-isolation spec 三条 Requirement 的对账）：
 * <ol>
 *   <li><b>记忆目录租户分层</b>：TenantPaths/RpgSavePaths 按请求租户解析；
 *       MemoryService 跨租户互不可见（列表/读取/删除）。</li>
 *   <li><b>记忆工具拒绝无租户调用</b>：租户上下文缺失时 MemoryService 快速失败
 *       （工具侧零写入断言见 {@link TenantScopedMemoryCallbackTest} /
 *       {@code SaveScopedMemoryCallbackTest}）。</li>
 *   <li><b>路径防穿越保持有效</b>：{@code ../他租户/memories/x.md} 形式的请求被拒 400
 *       （MemoryController 主聊天侧；RPG 侧既有 RpgSaveMemoryControllerTest 已钉住）。</li>
 * </ol>
 * 租户记忆根经 {@code memoriesDirOf} 接缝注入临时目录（生产公式
 * {@code tenants/<tid>/memories} 在接缝内镜像，主代码零侵入）。
 */
class TenantFileIsolationIntegrationTest {

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        TenantContext.set("u-a", "t-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 在临时目录内镜像生产公式 tenants/<tid>/memories 的 MemoryService。 */
    private MemoryService serviceWithSeam() {
        return new MemoryService() {
            @Override
            protected Path memoriesDirOf() {
                return temp.resolve("tenants").resolve(TenantContext.requireTenantId()).resolve("memories");
            }
        };
    }

    private void writeMemoryFile(String tenantId, String relative, String content) throws Exception {
        Path file = temp.resolve("tenants").resolve(tenantId).resolve("memories").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + relative + "\ndescription: d\ntype: user\n---\n\n" + content);
    }

    // ==================== Requirement 1: 记忆目录租户分层 ====================

    @Test
    void TenantPaths按请求租户解析且互不重叠() {
        assertThat(TenantPaths.memoriesDir())
                .isEqualTo(Paths.get(TenantPaths.ROOT, "t-a", "memories"));

        TenantContext.set("u-b", "t-b");
        assertThat(TenantPaths.memoriesDir())
                .isEqualTo(Paths.get(TenantPaths.ROOT, "t-b", "memories"))
                .isNotEqualTo(Paths.get(TenantPaths.ROOT, "t-a", "memories"));
        assertThat(TenantPaths.rpgSavesDir())
                .isEqualTo(Paths.get(TenantPaths.ROOT, "t-b", "rpg-saves"));
    }

    @Test
    void RpgSavePaths存档目录落在租户rpgsaves层() {
        assertThat(RpgSavePaths.saveDir("gs-001"))
                .isEqualTo(Paths.get(TenantPaths.ROOT, "t-a", "rpg-saves", "gs-001"));

        TenantContext.set("u-b", "t-b");
        // 同一存档 ID 在不同租户下指向不同物理目录（租户 + 存档双重隔离）
        assertThat(RpgSavePaths.saveDir("gs-001"))
                .isEqualTo(Paths.get(TenantPaths.ROOT, "t-b", "rpg-saves", "gs-001"))
                .isNotEqualTo(Paths.get(TenantPaths.ROOT, "t-a", "rpg-saves", "gs-001"));
    }

    @Test
    void 跨租户记忆互不可见() throws Exception {
        writeMemoryFile("t-a", "user/secret-a.md", "租户A的秘密");
        writeMemoryFile("t-b", "user/secret-b.md", "租户B的秘密");
        MemoryService service = serviceWithSeam();

        // 租户 A 视角：只见自己的
        List<Map<String, String>> listA = service.listMemories();
        assertThat(listA).extracting(m -> m.get("fileName")).containsExactly("user/secret-a.md");
        assertThat(service.readMemory("user/secret-b.md")).isNull();
        assertThat(service.deleteMemory("user/secret-b.md")).isFalse();

        // 租户 B 视角：只见自己的
        TenantContext.set("u-b", "t-b");
        List<Map<String, String>> listB = service.listMemories();
        assertThat(listB).extracting(m -> m.get("fileName")).containsExactly("user/secret-b.md");
        assertThat(service.readMemory("user/secret-a.md")).isNull();
        assertThat(service.deleteMemory("user/secret-a.md")).isFalse();
    }

    // ==================== Requirement 2: 无租户拒绝 ====================

    @Test
    void 租户上下文缺失时MemoryService快速失败() {
        TenantContext.clear();
        MemoryService service = serviceWithSeam();

        assertThatThrownBy(service::listMemories)
                .isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> service.readMemory("x.md"))
                .isInstanceOf(TenantContextMissingException.class);
    }

    // ==================== Requirement 3: 路径防穿越 ====================

    @Test
    void 穿越路径被TenantPaths白名单拒绝() {
        assertThatThrownBy(() -> TenantPaths.requireSafeTenantId("../evil"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantPaths.requireSafeTenantId("a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantPaths.requireSafeTenantId(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RpgSavePaths.requireSafeGameStateId(".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 主聊天记忆端点穿越路径返回400() {
        MemoryController controller = new MemoryController(serviceWithSeam());

        assertThat(controller.getMemoryDetail("../../t-b/memories/x.md").getStatusCode().value())
                .as("穿越到其他租户的记忆路径必须 400")
                .isEqualTo(400);
        assertThat(controller.deleteMemory("../../t-b/memories/x.md").getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.getMemoryDetail("/etc/passwd").getStatusCode().value())
                .as("绝对路径同样 400")
                .isEqualTo(400);
    }

    @Test
    void 合法但缺失的路径仍为404语义() throws Exception {
        writeMemoryFile("t-a", "user/a.md", "内容");
        MemoryController controller = new MemoryController(serviceWithSeam());

        // 合法相对路径、文件不存在 → 404（不与 400 混淆）
        assertThat(controller.getMemoryDetail("user/missing.md").getStatusCode().value())
                .isEqualTo(404);
        // 存在的文件 → 200
        assertThat(controller.getMemoryDetail("user/a.md").getStatusCode().value())
                .isEqualTo(200);
    }
}
