package com.luyu.agent.controller;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.tenancy.SessionTenantGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.RecursiveSummarizationCompactionStrategy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 手动上下文压缩端点
 * 
 * 允许用户主动触发对话历史的 LLM 摘要压缩，
 * 将旧消息归档并生成合成摘要轮次，释放上下文窗口空间。
 * 
 * 使用 SessionService.compact() 统一入口，内部自动处理：
 * - 事件读取
 * - 乐观并发控制（CAS）
 * - 归档写入
 */
@RestController
public class CompactionController {

    private static final Logger log = LoggerFactory.getLogger(CompactionController.class);

    private final SessionService sessionService;
    private final RecursiveSummarizationCompactionStrategy compactionStrategy;
    private final SessionTenantGuard sessionTenantGuard;

    public CompactionController(SessionService sessionService, ChatClientRegistry chatClientRegistry,
                                SessionTenantGuard sessionTenantGuard) {
        this.sessionService = sessionService;
        // 压缩摘要固定用云端 GLM 的纯净 client（forRole("compaction")），不受请求级 model 影响
        ChatClient compactionClient = chatClientRegistry.forRole("compaction");
        this.compactionStrategy = RecursiveSummarizationCompactionStrategy.builder(compactionClient)
                .maxEventsToKeep(10)
                .build();
        this.sessionTenantGuard = sessionTenantGuard;
    }

    /**
     * 手动触发上下文压缩
     *
     * @param sessionId 会话 ID
     * @return 压缩结果：{archivedCount, summaryPreview}
     */
    @PostMapping("/chat/sessions/{sessionId}/compact")
    public ResponseEntity<Map<String, Object>> compactSession(@PathVariable String sessionId) {
        // 跨租户会话压缩不可达（session-isolation spec）：404 且零副作用
        if (sessionTenantGuard.isForeign(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        try {
            // 使用 always-fire trigger 无条件触发压缩
            // SessionService.compact() 内部处理 CAS 和归档
            CompactionResult result = sessionService.compact(
                    sessionId,
                    req -> true,  // 无条件触发
                    compactionStrategy
            );

            int archivedCount = result.archivedEvents().size();

            if (archivedCount == 0) {
                return ResponseEntity.ok(Map.of(
                        "archivedCount", 0,
                        "summaryPreview", "对话轮次过少，无需压缩"
                ));
            }

            // 提取摘要预览（合成 assistant 消息）
            String summaryPreview = result.compactedEvents().stream()
                    .filter(SessionEvent::isSynthetic)
                    .filter(e -> e.getMessage() instanceof AssistantMessage)
                    .map(e -> e.getMessage().getText())
                    .findFirst()
                    .orElse("压缩完成");

            // 截断预览
            if (summaryPreview.length() > 100) {
                summaryPreview = summaryPreview.substring(0, 100) + "...";
            }

            log.info("手动压缩成功: sessionId={}, archivedCount={}, tokensSaved={}",
                    sessionId, archivedCount, result.tokensEstimatedSaved());

            return ResponseEntity.ok(Map.of(
                    "archivedCount", archivedCount,
                    "summaryPreview", summaryPreview
            ));

        } catch (Exception e) {
            log.error("压缩执行失败: sessionId={}", sessionId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "archivedCount", 0,
                    "summaryPreview", "压缩失败: " + e.getMessage()
            ));
        }
    }
}
