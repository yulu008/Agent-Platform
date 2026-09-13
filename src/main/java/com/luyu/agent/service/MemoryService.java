package com.luyu.agent.service;

import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 全局记忆文件管理服务
 *
 * 对应 {@code ~/.agent/memories/} 这一个 root，是 {@link MemoryFileStore} 的薄委托：
 * 文件读写、frontmatter 解析与防穿越校验都在存储组件里，本类只负责固定 root 并保持
 * 既有公开签名不变（memory.html 与 {@code /api/memories} 零感知）。
 *
 * 存档级 RPG 笔记本（{@code ~/.agent/rpg-saves/<gameStateId>/}）不走本类，
 * 由 {@code RpgSaveMemoryController} 每请求构造独立 root 的 {@link MemoryFileStore}。
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

    /** 记忆文件根目录（与 AutoMemoryToolsAdvisor 一致） */
    private static final String MEMORIES_DIR =
            System.getProperty("user.home") + "/.agent/memories";

    private final MemoryFileStore store;

    public MemoryService() {
        this.store = new MemoryFileStore(Paths.get(MEMORIES_DIR));
        this.store.ensureRootExists();
    }

    /**
     * 列出所有记忆文件
     *
     * @return 记忆条目列表，每条包含 name、description、type、fileName（相对路径）
     */
    public List<Map<String, String>> listMemories() {
        return store.list();
    }

    /**
     * 读取指定记忆文件的完整内容
     *
     * @param fileName 相对于 memoriesDir 的文件路径
     * @return 记忆详情（name、description、type、content），不存在返回 null
     */
    public Map<String, String> readMemory(String fileName) {
        return store.read(fileName);
    }

    /**
     * 删除指定记忆文件
     *
     * @param fileName 相对于 memoriesDir 的文件路径
     * @return true=删除成功，false=文件不存在
     */
    public boolean deleteMemory(String fileName) {
        return store.delete(fileName);
    }
}
