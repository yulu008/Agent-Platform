package com.luyu.agent.rpg.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 世界封面存储（world-card-gallery）：{@code data/covers/} 持久区的唯一读写入口。
 * <p>
 * 与聊天侧 {@code ImageUploadService}（data/images/ 临时区、启动即清空、不走 HTTP）
 * 刻意隔离：封面是用户资产，重启后必须仍可伺服，因此独立目录、独立服务、
 * <b>启动只确保目录存在、绝不清理任何文件</b>。
 * <p>
 * 三区结构（works-view-and-world-publish design D6）：
 * <ul>
 *   <li>{@code data/covers/{tenantId}/} 租户私有区——上传/物化落盘；</li>
 *   <li>{@code data/covers/shared/} 共享快照区——发布包封面（发布时复制而非引用发布方私有文件，
 *       发布方删世界清封面后共享文件独立存活）；</li>
 *   <li>{@code data/covers/} 根目录——存量裸文件（迁移过渡，取侧双轨回落兼容）。</li>
 * </ul>
 * <p>
 * 磁盘文件名一律服务端生成 {@code {uuid}.{ext}}，不接受任何客户端文件名（防路径穿越）。
 */
@Service
public class WorldCoverStorageService {

    private static final Logger log = LoggerFactory.getLogger(WorldCoverStorageService.class);

    /** 封面持久区根目录（与聊天临时图 data/images 隔离） */
    private static final String COVERS_DIR = "data/covers";

    /** 共享快照区子目录（works-view-and-world-publish D6：发布包封面） */
    private static final String SHARED_DIR = "shared";

    /** MIME 白名单 → 扩展名（上传校验与随包封面落盘共用）；gif 为动图封面放行（world-cover-gif-support） */
    private static final Map<String, String> MIME_TO_EXT = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    /** 随包默认封面 classpath 路径前缀：{@code rpg/preset-{key}-cover.png} */
    private static final String BUNDLED_COVER_PREFIX = "rpg/preset-";
    private static final String BUNDLED_COVER_SUFFIX = "-cover.png";

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(COVERS_DIR);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("创建封面存储目录: {}", dir.toAbsolutePath());
        }
        Files.createDirectories(Paths.get(COVERS_DIR, SHARED_DIR));
        // 刻意不清理：与 ImageUploadService 的启动清空语义相反，封面跨重启存活
    }

    /**
     * 落盘一张封面到租户私有区。
     *
     * @param tenantId 归属租户（目录名，服务端上下文供给）
     * @param bytes    图片字节
     * @param mimeType Content-Type（须在白名单内）
     * @return 落盘文件名 {@code {uuid}.{ext}}
     * @throws IllegalArgumentException MIME 不在白名单或字节为空
     */
    public String store(String tenantId, byte[] bytes, String mimeType) throws IOException {
        if (!isSafeName(tenantId)) {
            throw new IllegalArgumentException("非法租户标识");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("封面内容不能为空");
        }
        String ext = MIME_TO_EXT.get(normalizeMime(mimeType));
        if (ext == null) {
            throw new IllegalArgumentException("不支持的封面类型: " + mimeType);
        }
        String filename = UUID.randomUUID() + "." + ext;
        Path dir = tenantDir(tenantId);
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes);
        log.info("封面已落盘: {}/{} (size={} bytes, mime={})", tenantId, filename, bytes.length, mimeType);
        return filename;
    }

    /**
     * 解析封面文件路径（租户私有轨）。
     * <p>
     * 双轨过渡（works-view-and-world-publish 1.4）：租户子目录优先，
     * miss 回落根目录存量裸文件（1.6 启动迁移归位后此轨自然失活）。
     * 文件名来自 DB cover 列，仍做穿越防护：含路径分隔符或 {@code ..} 一律拒绝。
     *
     * @return 存在的常规文件路径；非法或两轨均未命中返回 empty
     */
    public Optional<Path> read(String tenantId, String filename) {
        if (!isSafeName(tenantId) || !isSafeName(filename)) {
            return Optional.empty();
        }
        Path tenantPath = tenantDir(tenantId).resolve(filename);
        if (Files.isRegularFile(tenantPath)) {
            return Optional.of(tenantPath);
        }
        Path legacy = Paths.get(COVERS_DIR, filename);
        return Files.isRegularFile(legacy) ? Optional.of(legacy) : Optional.empty();
    }

    /**
     * best-effort 删除（双轨：租户子目录 + 根目录存量裸文件）：文件缺失静默成功，
     * IO 失败仅 warn 不抛（不阻断世界删除事务）。shared 区不经此路径——
     * 发布方删世界清封面不触碰共享包文件（design D6）。
     */
    public void delete(String tenantId, String filename) {
        if (!isSafeName(tenantId) || !isSafeName(filename)) {
            return;
        }
        deleteQuietly(tenantDir(tenantId).resolve(filename));
        deleteQuietly(Paths.get(COVERS_DIR, filename));
    }

    /**
     * 把预设随包封面（classpath {@code rpg/preset-{key}-cover.png}）复制进持久区。
     * <p>
     * 供 materialize 首次 INSERT 落默认封面。资产缺失/IO 失败降级为 null
     * （封面只是观感资产，不阻断世界物化；前端仍有渐变兜底）。
     *
     * @return 落盘文件名；资产缺失返回 null
     */
    public String copyClasspathDefault(String tenantId, String presetKey) {
        ClassPathResource resource = new ClassPathResource(bundledCoverPath(presetKey));
        if (!resource.exists()) {
            log.warn("预设随包封面缺失，降级为无封面: preset={}", presetKey);
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            String filename = UUID.randomUUID() + ".png";
            Path dir = tenantDir(tenantId);
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            log.info("预设随包封面已落盘: preset={}, tenant={}, file={}", presetKey, tenantId, filename);
            return filename;
        } catch (IOException e) {
            log.warn("预设随包封面复制失败，降级为无封面: preset={}, reason={}", presetKey, e.getMessage());
            return null;
        }
    }

    /** 随包封面 classpath 路径（落盘与伺服兜底共用同一真源） */
    public static String bundledCoverPath(String presetKey) {
        return BUNDLED_COVER_PREFIX + presetKey + BUNDLED_COVER_SUFFIX;
    }

    /**
     * 读随包封面字节（world-card-gallery D6 伺服解析序的第二环）。
     *
     * @return 字节；资产缺失返回 null
     */
    public byte[] readBundled(String presetKey) {
        ClassPathResource resource = new ClassPathResource(bundledCoverPath(presetKey));
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("随包封面读取失败: preset={}, reason={}", presetKey, e.getMessage());
            return null;
        }
    }

    // ==================== 共享快照区（works-view-and-world-publish D6：发布/导入封面闭环） ====================

    /**
     * 发布复制：发布方私有封面 → 共享区快照 {@code shared/{packId}.{ext}}。
     * <p>
     * 复制而非引用（design D6）——发布方删世界清私有封面后共享文件独立存活；
     * 覆盖重发 REPLACE_EXISTING 原地刷。双轨读私有（租户子目录 → 根目录存量裸文件）。
     *
     * @return 共享区文件名；无封面/源缺失/IO 失败返回 null（观感资产不阻断发布，橱窗渐变兜底）
     */
    public String copyToShared(String tenantId, String cover, String packId) {
        if (!isSafeName(packId) || cover == null || !isSafeName(cover)) {
            return null;
        }
        Optional<Path> source = read(tenantId, cover);
        if (source.isEmpty()) {
            log.warn("发布封面源文件缺失，共享包降级为无封面: cover={}", cover);
            return null;
        }
        String sharedName = packId + "." + extOf(cover);
        try {
            Files.createDirectories(sharedDir());
            Files.copy(source.get(), sharedDir().resolve(sharedName), StandardCopyOption.REPLACE_EXISTING);
            log.info("封面已复制到共享区: {} -> shared/{}", cover, sharedName);
            return sharedName;
        } catch (IOException e) {
            log.warn("封面复制到共享区失败，共享包降级为无封面: reason={}", e.getMessage());
            return null;
        }
    }

    /** 共享区读取（橱窗/详情伺服用）；非法或未命中返回 empty */
    public Optional<Path> readShared(String filename) {
        if (!isSafeName(filename)) {
            return Optional.empty();
        }
        Path path = sharedDir().resolve(filename);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * 导入复制：共享区快照 → 接收方租户私有区（新 {@code {uuid}.{ext}} 文件名，
     * 各导入各的——多副本独立互不影响，覆盖更新/撤销零渗入）。
     *
     * @return 接收方新文件名；无源/IO 失败返回 null（观感资产不阻断导入）
     */
    public String copyFromShared(String sharedFilename, String destTenantId) {
        if (sharedFilename == null || !isSafeName(sharedFilename) || !isSafeName(destTenantId)) {
            return null;
        }
        Path source = sharedDir().resolve(sharedFilename);
        if (!Files.isRegularFile(source)) {
            log.warn("导入封面共享区文件缺失，接收方世界降级为无封面: {}", sharedFilename);
            return null;
        }
        String destName = UUID.randomUUID() + "." + extOf(sharedFilename);
        try {
            Path dir = tenantDir(destTenantId);
            Files.createDirectories(dir);
            Files.copy(source, dir.resolve(destName), StandardCopyOption.REPLACE_EXISTING);
            log.info("封面已导入到接收方租户区: shared/{} -> {}/{}", sharedFilename, destTenantId, destName);
            return destName;
        } catch (IOException e) {
            log.warn("封面导入复制失败，接收方世界降级为无封面: reason={}", e.getMessage());
            return null;
        }
    }

    // ==================== 存量归位（works-view-and-world-publish 1.6 启动迁移用） ====================

    /**
     * 根目录裸文件归位到租户子目录（幂等：目标已存在或源不存在返回 false，不覆盖不抛）。
     * 供 {@link WorldCoverTenantMigrator} 启动迁移调用，业务路径勿用。
     */
    boolean relocateLegacy(String tenantId, String filename) {
        if (!isSafeName(tenantId) || !isSafeName(filename)) {
            return false;
        }
        Path source = Paths.get(COVERS_DIR, filename);
        if (!Files.isRegularFile(source)) {
            return false;
        }
        try {
            Path dir = tenantDir(tenantId);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            if (Files.exists(target)) {
                return false;
            }
            Files.move(source, target);
            log.info("存量封面已归位: {} -> {}/{}", filename, tenantId, filename);
            return true;
        } catch (IOException e) {
            log.warn("存量封面归位失败（下次启动重跑）: {} -> {}, reason={}", filename, tenantId, e.getMessage());
            return false;
        }
    }

    /** 根目录直接子文件名清单（仅常规文件，不含子目录；孤儿扫描用） */
    List<String> listLegacyFiles() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Paths.get(COVERS_DIR))) {
            List<String> names = new ArrayList<>();
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    names.add(p.getFileName().toString());
                }
            }
            return names;
        }
    }

    /** 按文件名扩展名解析 Content-Type（伺服端点用）；gif 精确返回（world-cover-gif-support），未知扩展名回落 png */
    public MimeType resolveMimeType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MimeTypeUtils.IMAGE_JPEG;
        } else if (lower.endsWith(".webp")) {
            return MimeType.valueOf("image/webp");
        } else if (lower.endsWith(".gif")) {
            return MimeTypeUtils.IMAGE_GIF;
        }
        return MimeTypeUtils.IMAGE_PNG;
    }

    /** Content-Type 归一：小写并剥离参数（{@code image/png;charset=x} → {@code image/png}） */
    private static String normalizeMime(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        int semi = mimeType.indexOf(';');
        return (semi >= 0 ? mimeType.substring(0, semi) : mimeType).trim().toLowerCase();
    }

    // ==================== 路径工具（三区共用） ====================

    private static Path tenantDir(String tenantId) {
        return Paths.get(COVERS_DIR, tenantId);
    }

    private static Path sharedDir() {
        return Paths.get(COVERS_DIR, SHARED_DIR);
    }

    /** 名称安全校验（文件名/租户 id/包 id 共用）：null/空/含分隔符或 {@code ..} 一律拒绝（防穿越） */
    private static boolean isSafeName(String name) {
        return name != null && !name.isBlank()
                && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /** 扩展名提取（小写）；无扩展名回落 png（与 resolveMimeType 兜底一致） */
    private static String extOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "png" : filename.substring(dot + 1).toLowerCase();
    }

    private static void deleteQuietly(Path path) {
        try {
            if (Files.deleteIfExists(path)) {
                log.info("封面已删除: {}", path);
            }
        } catch (IOException e) {
            log.warn("封面删除失败（忽略）: {}, reason={}", path, e.getMessage());
        }
    }
}
