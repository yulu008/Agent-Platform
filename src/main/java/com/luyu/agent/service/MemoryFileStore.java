package com.luyu.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆文件存储组件（按 root 参数化）。
 *
 * 承载「带 YAML frontmatter 的 Markdown 记忆文件」的读写能力，供两处复用：
 * <ul>
 *   <li>{@link MemoryService}：root = {@code ~/.agent/memories}（全局记忆，主聊天用）</li>
 *   <li>{@code RpgSaveMemoryController}：root = {@code ~/.agent/rpg-saves/<gameStateId>}（存档级 GM 笔记本）</li>
 * </ul>
 *
 * 文件格式（与 {@code resources/rpg/gm-memory-prompt.md} 的约定一致）：
 * <pre>
 * ---
 * name: 林掌柜
 * description: 当铺老板，记得 PC 典当过一枚家传玉佩
 * type: npc_memory
 * ---
 * 正文内容...
 * </pre>
 *
 * <b>防穿越</b>：{@link #resolveSafe(String)} 逐段拒绝 {@code ..} 与绝对路径，
 * 并在归一化后校验结果仍位于 root 内。逐段拒绝是必要的——root 已含存档 ID 时，
 * {@code gs-001/../gs-002/x.md} 归一化后仍在存档总根内，仅靠 startsWith 会放行跨存档越狱
 * （同 {@code SaveScopedMemoryCallback} 的处理理由）。
 */
public class MemoryFileStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileStore.class);

    /** 索引文件名（不作为记忆条目返回） */
    public static final String INDEX_FILE = "MEMORY.md";

    /** 记忆文件扩展名（列表扫描与写入都只认它） */
    private static final String MD_SUFFIX = ".md";

    /** frontmatter 中由本组件管理的字段；其余字段（scope/turn 等）在更新时原样保留 */
    private static final List<String> MANAGED_FIELDS = List.of("name", "description", "type");

    private final Path root;

    /**
     * @param root 记忆文件根目录；构造时即做绝对化与归一化，后续校验都以它为基准
     */
    public MemoryFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /**
     * 确保 root 目录存在（幂等）。创建失败只记 warn：
     * 后续 {@link #list()} 对不存在的目录返回空列表，不影响启动。
     */
    public void ensureRootExists() {
        try {
            if (!Files.exists(root)) {
                Files.createDirectories(root);
                log.info("记忆目录已创建: {}", root);
            }
        } catch (IOException e) {
            log.warn("记忆目录创建失败: {}", root, e);
        }
    }

    // ===== 查询 =====

    /**
     * 递归列出 root 下所有记忆文件的元数据（排除 {@value #INDEX_FILE}）。
     *
     * @return 每条含 fileName（相对路径，正斜杠）、name、description、type；root 不存在时返回空列表
     */
    public List<Map<String, String>> list() {
        List<Map<String, String>> memories = new ArrayList<>();
        if (!Files.exists(root)) {
            return memories;
        }
        scan(root, memories);
        return memories;
    }

    /**
     * 读取指定记忆文件的完整内容（frontmatter + 正文）。
     *
     * @param fileName 相对 root 的文件路径
     * @return 含 fileName、name、description、type、content 的有序 Map；路径非法或文件不存在返回 null
     */
    public Map<String, String> read(String fileName) {
        Path filePath = resolveSafe(fileName);
        if (filePath == null || !Files.exists(filePath)) {
            return null;
        }
        try {
            return parseMemoryFile(relativeName(filePath), Files.readString(filePath));
        } catch (IOException e) {
            log.error("读取记忆文件失败: {}", fileName, e);
            return null;
        }
    }

    /**
     * 判断相对路径是否合法且对应文件已存在。
     */
    public boolean exists(String fileName) {
        Path filePath = resolveSafe(fileName);
        return filePath != null && Files.exists(filePath);
    }

    // ===== 写入 =====

    /**
     * 写入记忆文件：序列化 frontmatter（name/description/type）+ 正文。
     *
     * 若目标文件已存在，其 frontmatter 中<b>非</b>本组件管理的字段（如 GM 写的 {@code scope}、{@code turn}）
     * 会被保留在原位置，只覆盖 name/description/type 三项——否则 UI 编辑一次就会抹掉 GM 的元数据。
     *
     * @param fileName    相对 root 的文件路径，必须以 {@code .md} 结尾
     * @param name        一行标题
     * @param description 一行描述（写入索引行）
     * @param type        记忆类型
     * @param content     正文（可为空）
     * @return 实际写入的绝对路径
     * @throws IllegalArgumentException 路径非法（穿越/绝对路径）或扩展名不是 .md
     * @throws IOException              落盘失败
     */
    public Path write(String fileName, String name, String description, String type, String content)
            throws IOException {
        Path target = resolveSafe(fileName);
        if (target == null) {
            throw new IllegalArgumentException("非法的记忆文件路径: " + fileName);
        }
        if (!target.getFileName().toString().toLowerCase().endsWith(MD_SUFFIX)) {
            throw new IllegalArgumentException("记忆文件名必须以 .md 结尾: " + fileName);
        }

        Map<String, String> frontmatter = existingFrontmatter(target);
        frontmatter.put("name", oneLine(name));
        frontmatter.put("description", oneLine(description));
        frontmatter.put("type", oneLine(type));

        StringBuilder text = new StringBuilder("---\n");
        frontmatter.forEach((key, value) ->
                text.append(key).append(": ").append(quoteYaml(value)).append('\n'));
        text.append("---\n\n");
        if (content != null && !content.isBlank()) {
            text.append(content.strip()).append('\n');
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, text.toString());
        log.info("记忆文件已写入: {}", target);
        return target;
    }

    /**
     * 删除指定记忆文件。
     *
     * @return true=删除成功，false=路径非法或文件不存在
     */
    public boolean delete(String fileName) {
        Path filePath = resolveSafe(fileName);
        if (filePath == null || !Files.exists(filePath)) {
            return false;
        }
        try {
            Files.delete(filePath);
            log.info("记忆文件已删除: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("删除记忆文件失败: {}", fileName, e);
            return false;
        }
    }

    // ===== 索引维护（调用方按 best-effort 语义使用） =====

    /**
     * 向 {@value #INDEX_FILE} 追加一行索引 {@code - [slug](file) — description}。
     * 已存在指向该文件的索引行时直接返回（幂等）。
     */
    public void appendIndexLine(String fileName, String description) throws IOException {
        String link = "](" + fileName + ")";
        Path index = root.resolve(INDEX_FILE);
        String existing = Files.exists(index) ? Files.readString(index) : "";
        if (existing.contains(link)) {
            return;
        }
        StringBuilder text = new StringBuilder(existing);
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        text.append(formatIndexLine(fileName, description)).append('\n');
        Files.createDirectories(root);
        Files.writeString(index, text.toString());
    }

    /**
     * 从 {@value #INDEX_FILE} 移除指向该文件的索引行。
     *
     * @return true=有行被移除，false=索引不存在或无匹配行
     */
    public boolean removeIndexLine(String fileName) throws IOException {
        Path index = root.resolve(INDEX_FILE);
        if (!Files.exists(index)) {
            return false;
        }
        String link = "](" + fileName + ")";
        List<String> original = Files.readAllLines(index);
        List<String> kept = original.stream().filter(line -> !line.contains(link)).toList();
        if (kept.size() == original.size()) {
            return false;
        }
        Files.writeString(index, kept.isEmpty() ? "" : String.join("\n", kept) + "\n");
        return true;
    }

    /**
     * 索引行文本（与 GM 记忆规范的示例格式一致）。
     */
    public static String formatIndexLine(String fileName, String description) {
        String head = "- [" + slugOf(fileName) + "](" + fileName + ")";
        String desc = oneLine(description);
        return desc.isEmpty() ? head : head + " — " + desc;
    }

    // ===== 路径安全 =====

    /**
     * 安全解析相对路径。
     *
     * @return 规范化后的绝对路径；路径为空、含 {@code ..} 段、为绝对路径或逃出 root 时返回 null
     */
    public Path resolveSafe(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String relative = fileName.trim().replace('\\', '/');
        if (relative.startsWith("/")) {
            log.warn("检测到绝对路径: {}", fileName);
            return null;
        }
        while (relative.startsWith("./")) {
            relative = relative.substring(2);
        }
        if (relative.isBlank()) {
            return null;
        }
        for (String segment : relative.split("/")) {
            if ("..".equals(segment)) {
                log.warn("检测到路径穿越尝试: {}", fileName);
                return null;
            }
            if (segment.isBlank()) {
                log.warn("路径含空段: {}", fileName);
                return null;
            }
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            log.warn("路径越出记忆根目录: {}", fileName);
            return null;
        }
        return resolved;
    }

    // ===== 内部实现 =====

    private void scan(Path currentDir, List<Map<String, String>> result) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    scan(entry, result);
                } else if (entry.toString().endsWith(MD_SUFFIX)
                        && !entry.getFileName().toString().equals(INDEX_FILE)) {
                    result.add(parseFrontmatter(relativeName(entry), Files.readString(entry)));
                }
            }
        } catch (IOException e) {
            log.warn("扫描记忆目录失败: {}", currentDir, e);
        }
    }

    /** 绝对路径 → 相对 root 的正斜杠路径（列表与详情返回的 fileName 形式） */
    private String relativeName(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** 读取已存在文件的 frontmatter（保序，便于保留 scope/turn 等非管理字段） */
    private Map<String, String> existingFrontmatter(Path file) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return frontmatter;
        }
        try {
            String raw = Files.readString(file);
            String block = extractFrontmatter(raw);
            if (block != null) {
                parseYamlSimple(block, frontmatter);
            }
        } catch (IOException e) {
            log.warn("读取既有 frontmatter 失败，按新建处理: {}", file, e);
        }
        return frontmatter;
    }

    private Map<String, String> parseFrontmatter(String fileName, String raw) {
        Map<String, String> meta = blankMeta(fileName);
        String frontmatter = extractFrontmatter(raw);
        if (frontmatter != null) {
            parseYamlSimple(frontmatter, meta);
        }
        return meta;
    }

    private Map<String, String> parseMemoryFile(String fileName, String raw) {
        Map<String, String> result = blankMeta(fileName);
        result.put("content", "");
        String frontmatter = extractFrontmatter(raw);
        if (frontmatter != null) {
            parseYamlSimple(frontmatter, result);
            result.put("content", extractContent(raw));
        } else {
            // 无 frontmatter，整个文件作为内容
            result.put("content", raw);
        }
        return result;
    }

    private Map<String, String> blankMeta(String fileName) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fileName", fileName);
        for (String field : MANAGED_FIELDS) {
            meta.put(field, "");
        }
        return meta;
    }

    /** 提取 frontmatter 内容（首尾 --- 之间的部分），无则返回 null */
    private static String extractFrontmatter(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith("---")) {
            return null;
        }
        int secondDash = trimmed.indexOf("---", 3);
        if (secondDash < 0) {
            return null;
        }
        return trimmed.substring(3, secondDash).strip();
    }

    /** 提取 frontmatter 之后的正文；无 frontmatter 时返回原文 */
    private static String extractContent(String raw) {
        String trimmed = raw.strip();
        if (!trimmed.startsWith("---")) {
            return raw;
        }
        int secondDash = trimmed.indexOf("---", 3);
        if (secondDash < 0) {
            return raw;
        }
        return trimmed.substring(secondDash + 3).strip();
    }

    /** 简单 YAML 键值对解析（仅支持 key: value 格式） */
    private static void parseYamlSimple(String yaml, Map<String, String> target) {
        for (String line : yaml.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
                // 本组件写入时统一加双引号并转义，读回时需反向还原才能往返一致
                value = unescape(value.substring(1, value.length() - 1));
            } else if (value.length() > 1 && value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }
            target.put(key, value);
        }
    }

    /** 单遍还原 {@code \\} 转义（{@code \\"} → {@code "}、{@code \\\\} → {@code \}），不支持其他转义序列 */
    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                sb.append(value.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 折叠为单行（frontmatter 与索引行都不允许换行） */
    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ").strip();
    }

    /** 统一加双引号并转义，避免值里的冒号/井号/引号破坏 frontmatter 解析 */
    private static String quoteYaml(String value) {
        String safe = oneLine(value).replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + safe + "\"";
    }

    /** 文件名去掉目录与 .md 后缀，作为索引行的链接文字 */
    private static String slugOf(String fileName) {
        String path = fileName.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        return last.toLowerCase().endsWith(MD_SUFFIX)
                ? last.substring(0, last.length() - MD_SUFFIX.length())
                : last;
    }
}
