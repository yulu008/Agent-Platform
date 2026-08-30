package com.luyu.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片上传服务：处理 Base64 图片的解码、存储和临时文件管理。
 * <p>
 * 图片临时存储在 ./data/images/ 目录下，按 session 隔离。
 * 服务启动时会自动清理该目录下的旧文件。
 */
@Service
public class ImageUploadService {

    private static final Logger log = LoggerFactory.getLogger(ImageUploadService.class);

    /** 图片存储根目录 */
    private static final String IMAGES_DIR = "data/images";

    /** Base64 Data URL 正则匹配 */
    private static final Pattern BASE64_PATTERN = Pattern.compile(
            "^data:([a-zA-Z0-9]+/[a-zA-Z0-9-.+]+);base64,(.*)$");

    /** 支持的图片 MIME 类型映射到扩展名 */
    private static final java.util.Map<String, String> MIME_TO_EXT = java.util.Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(IMAGES_DIR);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("创建图片存储目录: {}", dir.toAbsolutePath());
        }
        // 清理旧文件
        cleanupOldFiles(dir);
    }

    /**
     * 保存 Base64 图片到临时文件。
     *
     * @param base64Data Base64 Data URL（如 data:image/png;base64,iVBORw0...）
     * @param sessionId  会话 ID，用于文件命名隔离
     * @return 保存后的本地文件路径
     * @throws IllegalArgumentException 如果 base64 格式不正确
     * @throws IOException             如果文件写入失败
     */
    public String saveBase64Image(String base64Data, String sessionId) throws IOException {
        return saveBase64Image(base64Data, sessionId, -1);
    }

    /**
     * 保存 Base64 图片到临时文件（带消息索引）。
     *
     * @param base64Data Base64 Data URL（如 data:image/png;base64,iVBORw0...）
     * @param sessionId  会话 ID，用于文件命名隔离
     * @param messageIndex 消息在会话中的索引，用于历史回显时匹配
     * @return 保存后的本地文件路径
     * @throws IllegalArgumentException 如果 base64 格式不正确
     * @throws IOException             如果文件写入失败
     */
    public String saveBase64Image(String base64Data, String sessionId, int messageIndex) throws IOException {
        if (base64Data == null || base64Data.isBlank()) {
            throw new IllegalArgumentException("图片 Base64 数据不能为空");
        }

        Matcher matcher = BASE64_PATTERN.matcher(base64Data.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("图片 Base64 格式不正确，期望格式: data:image/png;base64,...");
        }

        String mimeType = matcher.group(1);
        String base64Content = matcher.group(2);

        String ext = MIME_TO_EXT.get(mimeType.toLowerCase());
        if (ext == null) {
            throw new IllegalArgumentException("不支持的图片类型: " + mimeType);
        }

        // 解码 Base64
        byte[] imageBytes;
        try {
            imageBytes = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base64 解码失败: " + e.getMessage(), e);
        }

        // 生成文件名（包含消息索引，用于历史回显匹配）
        String indexSuffix = messageIndex >= 0 ? "_" + messageIndex : "";
        String filename = sessionId + indexSuffix + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + ext;
        Path filePath = Paths.get(IMAGES_DIR, filename);

        // 写入文件
        Files.write(filePath, imageBytes);
        log.info("保存图片: {} (size={} bytes, mime={})", filePath.toAbsolutePath(), imageBytes.length, mimeType);

        return filePath.toString();
    }

    /**
     * 从本地文件路径解析 MIME 类型。
     */
    public MimeType resolveMimeType(String filePath) {
        if (filePath == null) return MimeTypeUtils.IMAGE_PNG;
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MimeTypeUtils.IMAGE_JPEG;
        } else if (lower.endsWith(".webp")) {
            return MimeType.valueOf("image/webp");
        } else if (lower.endsWith(".gif")) {
            return MimeType.valueOf("image/gif");
        }
        return MimeTypeUtils.IMAGE_PNG;
    }

    /**
     * 查找指定会话和消息索引对应的图片文件。
     *
     * @param sessionId    会话 ID
     * @param messageIndex 消息索引
     * @return 图片文件路径，找不到返回 null
     */
    public String findImageForMessage(String sessionId, int messageIndex) {
        try {
            Path dir = Paths.get(IMAGES_DIR);
            if (!Files.exists(dir)) return null;

            String prefix = sessionId + "_" + messageIndex + "_";
            return Files.list(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            log.warn("查找图片失败: sessionId={}, index={}", sessionId, messageIndex, e);
            return null;
        }
    }

    /**
     * 读取指定会话和消息索引对应的图片，转为 Base64 DataURL。
     *
     * @param sessionId    会话 ID
     * @param messageIndex 消息索引
     * @return Base64 DataURL（如 data:image/png;base64,xxx），找不到返回 null
     */
    public String readImageAsBase64(String sessionId, int messageIndex) {
        String imagePath = findImageForMessage(sessionId, messageIndex);
        if (imagePath == null) return null;

        try {
            Path path = Paths.get(imagePath);
            if (!Files.exists(path)) return null;

            byte[] bytes = Files.readAllBytes(path);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            MimeType mimeType = resolveMimeType(imagePath);
            return "data:" + mimeType + ";base64," + base64;
        } catch (IOException e) {
            log.warn("读取图片失败: sessionId={}, index={}", sessionId, messageIndex, e);
            return null;
        }
    }

    /**
     * 清理旧文件（启动时执行）。
     */
    private void cleanupOldFiles(Path dir) {
        try {
            java.util.List<Path> files = Files.list(dir)
                    .filter(Files::isRegularFile)
                    .toList();
            for (Path file : files) {
                Files.deleteIfExists(file);
                log.debug("清理旧图片文件: {}", file.getFileName());
            }
            if (!files.isEmpty()) {
                log.info("清理 {} 个旧图片文件", files.size());
            }
        } catch (IOException e) {
            log.warn("清理旧图片文件失败: {}", e.getMessage());
        }
    }
}
