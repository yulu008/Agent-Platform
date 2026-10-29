package com.luyu.agent.rpg.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link WorldCoverStorageService} 的 best-effort 删除与租户化存储语义单测
 * （world-card-gallery tasks 10.4 + works-view-and-world-publish 1.4/1.5/1.6）。
 * <p>
 * 钉住三组契约：
 * <ol>
 *   <li><b>best-effort 删除</b>：删除世界时 {@code WorkshopService.deleteWorld} 不因
 *       文件缺失/入参为空而抛异常回滚删库事务——「缺失仅静默/warn」；</li>
 *   <li><b>租户私有区隔离（1.4）</b>：store 落 {@code data/covers/{tenantId}/}，read
 *       双轨（租户子目录优先、miss 回落根目录存量裸文件），跨租户读不到他租户文件；</li>
 *   <li><b>共享快照区闭环（1.5 / design D6）</b>：发布复制私有封面 →
 *       {@code shared/{packId}.{ext}}，删源私有文件后共享副本独立存活（复制而非引用）；
 *       导入复制到接收方租户区新文件名，各导入各的互不影响；存量裸文件归位幂等（1.6）。</li>
 * </ol>
 * 不依赖 Spring 容器与 {@code @PostConstruct init()}：store/read/copy 均自建目录；
 * 落盘在真实持久区（data/covers），测试自清理避免残留。
 */
class WorldCoverStorageServiceTest {

    private static final Path COVERS = Paths.get("data/covers");
    private static final Path SHARED = COVERS.resolve("shared");

    private final WorldCoverStorageService storage = new WorldCoverStorageService();

    @Test
    void 删除不存在的封面文件静默成功不抛异常() {
        // 文件缺失仅 warn：deleteIfExists 返回 false，不外抛，不阻断世界删除事务
        assertThatCode(() -> storage.delete("t-test", "definitely-missing-" + System.nanoTime() + ".png"))
                .doesNotThrowAnyException();
    }

    @Test
    void 删除null或空白或非法租户与文件名直接返回不触碰磁盘() {
        assertThatCode(() -> storage.delete("t-test", null)).doesNotThrowAnyException();
        assertThatCode(() -> storage.delete("t-test", "   ")).doesNotThrowAnyException();
        // 非法租户标识（穿越/空白）同样静默
        assertThatCode(() -> storage.delete(null, "x.png")).doesNotThrowAnyException();
        assertThatCode(() -> storage.delete("../evil", "x.png")).doesNotThrowAnyException();
    }

    @Test
    void 读取非法租户或穿越文件名返回empty() {
        // 路径穿越防护：含分隔符或 .. 一律拒绝，不落到磁盘解析
        assertThat(storage.read(null, "x.png")).isEmpty();
        assertThat(storage.read("../evil", "x.png")).isEmpty();
        assertThat(storage.read("t-test", null)).isEmpty();
        assertThat(storage.read("t-test", "")).isEmpty();
        assertThat(storage.read("t-test", "../etc/passwd")).isEmpty();
        assertThat(storage.read("t-test", "a/b.png")).isEmpty();
        assertThat(storage.read("t-test", "a\\b.png")).isEmpty();
        // 合法但不存在的文件名同样是 empty（供伺服端点回落预设兜底）
        assertThat(storage.read("t-test", "no-such-cover-" + System.nanoTime() + ".png")).isEmpty();
    }

    @Test
    void 随包封面路径拼接符合约定() {
        assertThat(WorldCoverStorageService.bundledCoverPath("adventure"))
                .isEqualTo("rpg/preset-adventure-cover.png");
    }

    @Test
    void 读取随包封面adventure资产存在返回非空字节() {
        // rpg/preset-adventure-cover.png 随包内置（tasks 4.1），伺服兜底与物化落默认图共用
        byte[] bundled = storage.readBundled("adventure");
        assertThat(bundled).isNotNull();
        assertThat(bundled.length).isGreaterThan(0);
        // 未知预设键无对应资产，返回 null（前端渐变兜底）
        assertThat(storage.readBundled("no-such-preset")).isNull();
    }

    @Test
    void 扩展名解析ContentType回落png() {
        assertThat(storage.resolveMimeType("x.jpg").toString()).isEqualTo("image/jpeg");
        assertThat(storage.resolveMimeType("x.jpeg").toString()).isEqualTo("image/jpeg");
        assertThat(storage.resolveMimeType("x.webp").toString()).isEqualTo("image/webp");
        // gif 精确返回（world-cover-gif-support），不再回落 png
        assertThat(storage.resolveMimeType("x.gif").toString()).isEqualTo("image/gif");
        assertThat(storage.resolveMimeType("x.png").toString()).isEqualTo("image/png");
        // 未知/无扩展名回落 png，避免伺服端点 contentType 为 null
        assertThat(storage.resolveMimeType("x").toString()).isEqualTo("image/png");
        assertThat(storage.resolveMimeType(null).toString()).isEqualTo("image/png");
    }

    @Test
    void 存储空字节或非法租户抛IllegalArgument() {
        assertThatCode(() -> storage.store("t-test", new byte[0], "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> storage.store("t-test", null, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> storage.store("../evil", new byte[]{1}, "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 存储非白名单MIME抛IllegalArgument() {
        // MIME 白名单外（bmp/svg/可执行）一律拒，防落盘非图片资产；gif 已放行（world-cover-gif-support）
        assertThatCode(() -> storage.store("t-test", new byte[]{1, 2, 3}, "image/bmp"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> storage.store("t-test", new byte[]{1, 2, 3}, "application/octet-stream"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 存储gif白名单放行落盘gif扩展并自清理() throws IOException {
        // world-cover-gif-support：image/gif（含带 charset 参数的归一）落盘 {uuid}.gif
        byte[] gif = new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0};
        String withParam = storage.store("t-test", gif, "image/gif;charset=binary");
        assertThat(withParam).endsWith(".gif");
        assertThat(storage.read("t-test", withParam)).isPresent();
        String plain = storage.store("t-test", gif, "image/gif");
        assertThat(plain).endsWith(".gif");
        assertThat(storage.read("t-test", plain)).isPresent();
        // 落盘在真实持久区（data/covers/t-test），测试自清理避免残留
        storage.delete("t-test", withParam);
        storage.delete("t-test", plain);
    }

    @Test
    void read返回值可安全判空() {
        // 编译期契约：read 返回 Optional 而非裸 Path，调用方被迫处理缺失分支
        Optional<?> result = storage.read("t-test", "anything.png");
        assertThat(result).isNotNull();
    }

    // ==================== 租户私有区隔离（1.4，tasks 6.5） ====================

    @Test
    void 跨租户读不到他租户私有区文件() throws IOException {
        byte[] png = new byte[]{'P', 1, 2, 3};
        String cover = storage.store("t-a", png, "image/png");

        // 落在租户子目录（data/covers/t-a/），本租户双轨读命中
        assertThat(storage.read("t-a", cover)).isPresent();
        // 他租户读不到（子目录 miss + 根目录同名裸文件也不存在 → empty）
        assertThat(storage.read("t-b", cover)).isEmpty();

        storage.delete("t-a", cover);
    }

    // ==================== 共享快照区闭环（1.5 / D6，tasks 6.5） ====================

    @Test
    void 发布封面复制到共享区删源后仍存活且导入落接收方目录() throws IOException {
        byte[] png = new byte[]{'P', 9, 9};
        String privateCover = storage.store("t-a", png, "image/png");

        // 发布复制：私有区 → shared/{packId}.{ext}（复制而非引用，design D6）
        String sharedName = storage.copyToShared("t-a", privateCover, "pack-itest");
        assertThat(sharedName).isEqualTo("pack-itest.png");
        assertThat(storage.readShared("pack-itest.png")).isPresent();

        // 发布方删源世界清私有封面：共享副本独立存活（橱窗照常出图）
        storage.delete("t-a", privateCover);
        assertThat(storage.readShared("pack-itest.png")).isPresent();
        assertThat(storage.read("t-a", privateCover)).isEmpty();

        // 导入复制：共享区 → 接收方租户区新 {uuid} 文件名（各导入各的，多副本独立）
        String imported = storage.copyFromShared("pack-itest.png", "t-b");
        assertThat(imported).isNotBlank().isNotEqualTo("pack-itest.png");
        assertThat(storage.read("t-b", imported)).isPresent();
        // 接收方副本对发布方租户不可见（私有区隔离）
        assertThat(storage.read("t-a", imported)).isEmpty();

        // 自清理（shared 区无公开删除 API——业务上撤销只软下架不删文件，测试直接删）
        storage.delete("t-b", imported);
        Files.deleteIfExists(SHARED.resolve("pack-itest.png"));
    }

    @Test
    void 共享区复制源缺失降级null不抛异常() {
        // 发布封面源文件缺失（发布方本地已删）：观感资产降级 null，不阻断发布
        assertThat(storage.copyToShared("t-a", "missing-" + System.nanoTime() + ".png", "pack-x")).isNull();
        // 导入时共享区文件缺失：接收方降级无封面，不抛
        assertThat(storage.copyFromShared("missing-" + System.nanoTime() + ".png", "t-b")).isNull();
        // 非法包 id / 租户 / 文件名（穿越防护）同样降级 null
        assertThat(storage.copyToShared("t-a", "a.png", "../evil")).isNull();
        assertThat(storage.copyFromShared("../evil", "t-b")).isNull();
        assertThat(storage.readShared("../evil")).isEmpty();
    }

    // ==================== 存量归位幂等（1.6，tasks 6.5） ====================

    @Test
    void 存量裸文件归位租户子目录幂等可重跑() throws IOException {
        String legacyName = "legacy-" + System.nanoTime() + ".png";
        Path legacy = COVERS.resolve(legacyName);
        Files.write(legacy, new byte[]{'L', 1});
        try {
            // 首跑：根目录裸文件 → data/covers/t-a/，归位后根目录不再列出
            assertThat(storage.relocateLegacy("t-a", legacyName)).isTrue();
            assertThat(storage.read("t-a", legacyName)).isPresent();
            assertThat(storage.listLegacyFiles()).doesNotContain(legacyName);
            // 幂等重跑：源已不存在返回 false，不覆盖不抛
            assertThat(storage.relocateLegacy("t-a", legacyName)).isFalse();
            assertThat(storage.relocateLegacy("t-b", legacyName)).isFalse();
        } finally {
            storage.delete("t-a", legacyName);
            Files.deleteIfExists(legacy);
        }
    }
}
