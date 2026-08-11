package com.luyu.agent.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆文件管理服务
 *
 * 直接读写文件系统中的记忆文件（~/.agent/memories/），
 * 提供列表、详情、删除功能供前端管理 UI 使用。
 *
 * 记忆文件为带 YAML frontmatter 的 Markdown 格式：
 * ---
 * name: memory-name
 * description: 一行描述
 * type: user | feedback | project | reference
 * ---
 * 正文内容...
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 记忆文件根目录（与 AutoMemoryToolsAdvisor 一致） */
    private static final String MEMORIES_DIR =
            System.getProperty("user.home") + "/.agent/memories";

    /** 索引文件名（不作为记忆条目返回） */
    private static final String INDEX_FILE = "MEMORY.md";

    private final Path memoriesPath;

    public MemoryService() {
        this.memoriesPath = Paths.get(MEMORIES_DIR);
        ensureDirExists();
    }

    /**
     * 列出所有记忆文件
     *
     * @return 记忆条目列表，每条包含 name、description、type、fileName（相对路径）
     */
    public List<Map<String, String>> listMemories() {
        List<Map<String, String>> memories = new ArrayList<>();
        if (!Files.exists(memoriesPath)) {
            return memories;
        }
        scanMemories(memoriesPath, memoriesPath, memories);
        return memories;
    }

    /**
     * 读取指定记忆文件的完整内容
     *
     * @param fileName 相对于 memoriesDir 的文件路径
     * @return 记忆详情（name、description、type、content），不存在返回 null
     */
    public Map<String, String> readMemory(String fileName) {
        Path filePath = resolveSafe(fileName);
        if (filePath == null || !Files.exists(filePath)) {
            return null;
        }

        try {
            String raw = Files.readString(filePath);
            return parseMemoryFile(fileName, raw);
        } catch (IOException e) {
            log.error("读取记忆文件失败: {}", fileName, e);
            return null;
        }
    }

    /**
     * 删除指定记忆文件
     *
     * @param fileName 相对于 memoriesDir 的文件路径
     * @return true=删除成功，false=文件不存在
     */
    public boolean deleteMemory(String fileName) {
        Path filePath = resolveSafe(fileName);
        if (filePath == null || !Files.exists(filePath)) {
            return false;
        }

        try {
            Files.delete(filePath);
            log.info("记忆文件已删除: {}", fileName);
            return true;
        } catch (IOException e) {
            log.error("删除记忆文件失败: {}", fileName, e);
            return false;
        }
    }

    // ===== 私有方法 =====

    /**
     * 确保记忆目录存在
     */
    private void ensureDirExists() {
        try {
            if (!Files.exists(memoriesPath)) {
                Files.createDirectories(memoriesPath);
                log.info("记忆目录已创建: {}", memoriesPath);
            }
        } catch (IOException e) {
            log.warn("记忆目录创建失败: {}", memoriesPath, e);
        }
    }

    /**
     * 递归扫描目录下的 .md 文件（排除 MEMORY.md 索引文件）
     */
    private void scanMemories(Path baseDir, Path currentDir, List<Map<String, String>> result) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    scanMemories(baseDir, entry, result);
                } else if (entry.toString().endsWith(".md") && !entry.getFileName().toString().equals(INDEX_FILE)) {
                    String relativePath = baseDir.relativize(entry).toString().replace('\\', '/');
                    Map<String, String> meta = parseFrontmatter(relativePath, Files.readString(entry));
                    result.add(meta);
                }
            }
        } catch (IOException e) {
            log.warn("扫描记忆目录失败: {}", currentDir, e);
        }
    }

    /**
     * 安全解析文件路径（防止目录穿越攻击）
     *
     * @return 规范化后的绝对路径，若路径穿越返回 null
     */
    private Path resolveSafe(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        // 防止路径穿越：规范化后检查是否仍在 memoriesDir 内
        Path resolved = memoriesPath.resolve(fileName).normalize();
        if (!resolved.startsWith(memoriesPath)) {
            log.warn("检测到路径穿越尝试: {}", fileName);
            return null;
        }
        return resolved;
    }

    /**
     * 解析记忆文件 frontmatter（仅提取元数据）
     */
    private Map<String, String> parseFrontmatter(String fileName, String raw) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fileName", fileName);
        meta.put("name", "");
        meta.put("description", "");
        meta.put("type", "");

        String frontmatter = extractFrontmatter(raw);
        if (frontmatter != null) {
            parseYamlSimple(frontmatter, meta);
        }
        return meta;
    }

    /**
     * 解析记忆文件完整内容（frontmatter + 正文）
     */
    private Map<String, String> parseMemoryFile(String fileName, String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("fileName", fileName);
        result.put("name", "");
        result.put("description", "");
        result.put("type", "");
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

    /**
     * 提取 frontmatter 内容（--- 之间的部分）
     */
    private String extractFrontmatter(String raw) {
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

    /**
     * 提取 frontmatter 之后的正文内容
     */
    private String extractContent(String raw) {
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

    /**
     * 简单 YAML 键值对解析（仅支持 key: value 格式）
     */
    private void parseYamlSimple(String yaml, Map<String, String> target) {
        for (String line : yaml.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            // 去除引号
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            target.put(key, value);
        }
    }
}

