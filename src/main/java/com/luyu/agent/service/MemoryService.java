package com.luyu.agent.service;

import com.luyu.agent.tenancy.TenantPaths;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 全局记忆文件管理服务（多租户版，tasks 4.2）
 *
 * root 按当前请求租户解析：{@code ~/.agent/tenants/<tenantId>/memories/}（design D6），
 * 是 {@link MemoryFileStore} 的薄委托：文件读写、frontmatter 解析与防穿越校验都在存储组件里，
 * 本类只保持既有公开签名不变（memory.html 与 {@code /api/memories} 零感知）。拆构造期固定 root
 * 为每请求构造（与 {@code RpgSaveMemoryController} 同模式），跨租户自然互不可见。
 *
 * 存档级 RPG 笔记本（{@code ~/.agent/tenants/<tid>/rpg-saves/<gameStateId>/}）不走本类，
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

    /**
     * 当前请求租户的记忆存储（root = 租户目录下 memories/）。
 * 租户上下文缺失即快速失败，绝不回落无租户根目录。
     */
    private MemoryFileStore currentStore() {
        MemoryFileStore store = new MemoryFileStore(memoriesDirOf());
        store.ensureRootExists();
        return store;
    }

    /**
     * 租户记忆根解析（单独成方法，便于单测替换为临时目录；同 RpgSaveMemoryController.saveDirOf 模式）。
     */
    protected Path memoriesDirOf() {
        return TenantPaths.memoriesDir();
    }

    /**
     * 校验相对路径是否合法（不越出本租户记忆根目录）；供 Controller 区分 400（非法）与 404（不存在）。
     */
    public boolean isValidPath(String fileName) {
        return currentStore().resolveSafe(fileName) != null;
    }

    /**
     * 列出所有记忆文件
     *
     * @return 记忆条目列表，每条包含 name、description、type、fileName（相对路径）
     */
    public List<Map<String, String>> listMemories() {
        return currentStore().list();
    }

    /**
     * 读取指定记忆文件的完整内容
     *
     * @param fileName 相对于 memoriesDir 的文件路径
     * @return 记忆详情（name、description、type、content），不存在返回 null
     */
    public Map<String, String> readMemory(String fileName) {
        return currentStore().read(fileName);
    }

    /**
     * 删除指定记忆文件
     *
     * @param fileName 相对于 memoriesDir 的文件路径
     * @return true=删除成功，false=文件不存在
     */
    public boolean deleteMemory(String fileName) {
        return currentStore().delete(fileName);
    }
}
