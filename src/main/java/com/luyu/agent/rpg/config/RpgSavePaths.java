package com.luyu.agent.rpg.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * RPG 存档记忆根目录的单一真源。
 * <p>
 * 此前该路径字面量分散在 {@code RpgToolConfiguration}（GmMemory* 工具的 root）中，
 * 一键中文化改名的笔记本跟随、存档记忆 REST 端点都要按 {@code <gameStateId>/} 子目录定位文件，
 * 三处各写一份极易漂移，故抽出本类。
 * <p>
 * 每个存档占一个子目录 {@code ~/.agent/rpg-saves/<gameStateId>/}，
 * 与主聊天的 {@code ~/.agent/memories} 完全隔离。
 */
public final class RpgSavePaths {

    /** RPG 存档记忆根目录 */
    public static final String ROOT = System.getProperty("user.home") + "/.agent/rpg-saves";

    /** 索引文件名（与 {@code AutoMemoryTools} 约定一致，不作为记忆条目） */
    public static final String INDEX_FILE = "MEMORY.md";

    private RpgSavePaths() {
    }

    /**
     * 某存档的记忆目录（不保证存在）。
     *
     * @param gameStateId 游戏状态 ID
     * @return {@code ~/.agent/rpg-saves/<gameStateId>}
     */
    public static Path saveDir(String gameStateId) {
        return Paths.get(ROOT, gameStateId);
    }
}
