package com.luyu.agent.rpg.controller;

import com.luyu.agent.rpg.config.RpgSavePaths;
import com.luyu.agent.service.MemoryFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 存档级记忆 REST API（RPG 页「记忆管理」弹窗的后端）。
 * <p>
 * 读写 {@code ~/.agent/rpg-saves/<gameStateId>/} 下带 YAML frontmatter 的记忆文件，
 * 即 GM 用 {@code GmMemory*} 四工具维护的那本存档笔记本。与全局记忆端点
 * {@code /api/memories}（只查/删，root 为 {@code ~/.agent/memories}）互不相通。
 * <p>
 * 每请求以存档目录为 root 现构造 {@link MemoryFileStore}，因此存档之间天然隔离；
 * 穿越校验既逐段拒绝 {@code ..} 与绝对路径，也做归一化后的 startsWith 复核。
 */
@RestController
@RequestMapping("/rpg/saves/{gameStateId}/memories")
public class RpgSaveMemoryController {

    private static final Logger log = LoggerFactory.getLogger(RpgSaveMemoryController.class);

    /**
     * 存档 ID 白名单：只允许单段安全字符。
     * <p>
     * gameStateId 由前端 {@code crypto.randomUUID()} 生成，但它是路径段并被拼进文件根目录，
     * 故必须自行校验——否则 {@code ..%2F..} 之类的值会把 root 指到存档总根之外。
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final String ERR = "error";

    // ==================== 查询 ====================

    /**
     * 列出该存档的全部记忆条目（不含 MEMORY.md 索引本身）。
     */
    @GetMapping
    public ResponseEntity<Object> list(@PathVariable String gameStateId) {
        MemoryFileStore store = storeFor(gameStateId);
        if (store == null) {
            return badId(gameStateId);
        }
        List<Map<String, String>> memories = store.list();
        return ResponseEntity.ok(memories);
    }

    /**
     * 读取单条记忆详情（frontmatter 字段 + 正文）。
     *
     * @return 400=路径非法，404=文件不存在
     */
    @GetMapping("/detail")
    public ResponseEntity<Object> detail(@PathVariable String gameStateId,
                                         @RequestParam(name = "file", required = false) String file) {
        MemoryFileStore store = storeFor(gameStateId);
        if (store == null) {
            return badId(gameStateId);
        }
        if (store.resolveSafe(file) == null) {
            return badFile(file);
        }
        Map<String, String> detail = store.read(file);
        if (detail == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(ERR, "记忆文件不存在: " + file));
        }
        return ResponseEntity.ok(detail);
    }

    // ==================== 写入 ====================

    /**
     * 新建记忆文件。已存在同名文件时返回 409，避免误覆盖 GM 写的内容（编辑请走 PUT）。
     */
    @PostMapping
    public ResponseEntity<Object> create(@PathVariable String gameStateId,
                                        @RequestBody Map<String, String> body) {
        MemoryFileStore store = storeFor(gameStateId);
        if (store == null) {
            return badId(gameStateId);
        }
        String file = trimmed(body, "file");
        String name = trimmed(body, "name");
        if (file == null || name == null) {
            return ResponseEntity.badRequest().body(Map.of(ERR, "file 与 name 不能为空"));
        }
        if (store.resolveSafe(file) == null) {
            return badFile(file);
        }
        if (store.exists(file)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(ERR, "记忆文件已存在，请改用更新: " + file));
        }
        return write(store, file, body, true);
    }

    /**
     * 更新记忆文件（文件不存在时按新建落盘）。
     * <p>
     * frontmatter 中 GM 写的非管理字段（scope/turn）由存储组件保留；
     * 仅当这次写入实际创建了文件时才补索引行，已存在文件的索引行保持 GM 的措辞与排序不动。
     */
    @PutMapping
    public ResponseEntity<Object> update(@PathVariable String gameStateId,
                                        @RequestBody Map<String, String> body) {
        MemoryFileStore store = storeFor(gameStateId);
        if (store == null) {
            return badId(gameStateId);
        }
        String file = trimmed(body, "file");
        String name = trimmed(body, "name");
        if (file == null || name == null) {
            return ResponseEntity.badRequest().body(Map.of(ERR, "file 与 name 不能为空"));
        }
        if (store.resolveSafe(file) == null) {
            return badFile(file);
        }
        return write(store, file, body, !store.exists(file));
    }

    /**
     * 删除记忆文件，并 best-effort 移除 MEMORY.md 中对应的索引行。
     *
     * @return 400=路径非法，404=文件不存在，204=删除成功
     */
    @DeleteMapping
    public ResponseEntity<Object> delete(@PathVariable String gameStateId,
                                        @RequestParam(name = "file", required = false) String file) {
        MemoryFileStore store = storeFor(gameStateId);
        if (store == null) {
            return badId(gameStateId);
        }
        if (store.resolveSafe(file) == null) {
            return badFile(file);
        }
        if (!store.delete(file)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(ERR, "记忆文件不存在: " + file));
        }
        try {
            store.removeIndexLine(file);
        } catch (IOException | RuntimeException e) {
            // 索引失步不影响主操作：GM 侧按其记忆规范会自愈索引
            log.warn("移除索引行失败（记忆文件已删除）: {}/{}", gameStateId, file, e);
        }
        return ResponseEntity.noContent().build();
    }

    // ==================== 内部方法 ====================

    /**
     * 落盘并回读详情；索引行按 best-effort 追加。
     *
     * @param isNew 本次写入是否等于新建（决定是否补索引行）
     */
    private ResponseEntity<Object> write(MemoryFileStore store, String file,
                                        Map<String, String> body, boolean isNew) {
        String description = body.getOrDefault("description", "");
        try {
            store.write(file,
                    body.get("name"),
                    description,
                    body.getOrDefault("type", ""),
                    body.getOrDefault("content", ""));
        } catch (IllegalArgumentException e) {
            String reason = e.getMessage() == null ? "非法的记忆文件路径" : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of(ERR, reason));
        } catch (IOException | RuntimeException e) {
            log.error("写入记忆文件失败: {}", file, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(ERR, "写入记忆文件失败: " + e.getMessage()));
        }
        if (isNew) {
            try {
                store.appendIndexLine(file, description);
            } catch (IOException | RuntimeException e) {
                log.warn("追加索引行失败（记忆文件已写入）: {}", file, e);
            }
        }
        Map<String, String> detail = store.read(file);
        return ResponseEntity.ok(detail != null ? detail : Map.of("fileName", file));
    }

    /**
     * 构造该存档的存储组件；gameStateId 非法时返回 null（调用方转 400）。
     */
    private MemoryFileStore storeFor(String gameStateId) {
        if (gameStateId == null || !SAFE_ID.matcher(gameStateId).matches()) {
            return null;
        }
        return new MemoryFileStore(saveDirOf(gameStateId));
    }

    /**
     * 存档根目录解析（单独成方法，便于单测替换为临时目录）。
     */
    protected Path saveDirOf(String gameStateId) {
        return RpgSavePaths.saveDir(gameStateId);
    }

    private ResponseEntity<Object> badId(String gameStateId) {
        log.warn("非法的存档标识被拒绝: {}", gameStateId);
        return ResponseEntity.badRequest().body(Map.of(ERR, "非法的存档标识"));
    }

    private ResponseEntity<Object> badFile(String file) {
        return ResponseEntity.badRequest()
                .body(Map.of(ERR, "非法的文件路径（须为存档内的相对 .md 路径）: " + file));
    }

    private static String trimmed(Map<String, String> body, String key) {
        if (body == null) {
            return null;
        }
        String value = body.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
