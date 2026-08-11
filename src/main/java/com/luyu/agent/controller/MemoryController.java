package com.luyu.agent.controller;

import com.luyu.agent.chat.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 记忆管理 REST API
 *
 * 提供记忆文件的列表、详情、删除端点，供前端管理 UI 调用。
 * 路径前缀：/api/memories
 *
 * 文件名通过查询参数 file 传递（可能包含子路径，如 user/user-background.md）
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 获取所有记忆文件列表
     *
     * @return 记忆条目数组 [{name, description, type, fileName}]
     */
    @GetMapping
    public List<Map<String, String>> listMemories() {
        return memoryService.listMemories();
    }

    /**
     * 查看指定记忆文件的完整内容
     *
     * @param file 文件相对路径（如 user/user-background.md）
     * @return 记忆详情 {name, description, type, content}，不存在返回 404
     */
    @GetMapping("/detail")
    public ResponseEntity<Map<String, String>> getMemoryDetail(@RequestParam String file) {
        Map<String, String> detail = memoryService.readMemory(file);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    /**
     * 删除指定记忆文件
     *
     * @param file 文件相对路径
     * @return 204 成功，404 文件不存在
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteMemory(@RequestParam String file) {
        boolean deleted = memoryService.deleteMemory(file);
        if (deleted) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
