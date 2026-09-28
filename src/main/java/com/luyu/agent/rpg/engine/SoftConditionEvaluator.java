package com.luyu.agent.rpg.engine;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.rpg.model.EventLog;
import com.luyu.agent.rpg.model.Trigger;
import com.luyu.agent.rpg.repository.RpgEventLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 软条件评估器。
 * <p>
 * 对硬条件已通过的触发器，自动调用 compaction 角色模型评估软条件
 * （自然语言描述如"玩家最近行为表现出财富"），返回是否满足。
 * <p>
 * 引擎在步骤②.5 自动调用，不依赖 GM 手动调用工具。
 */
@Component
public class SoftConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SoftConditionEvaluator.class);

    private static final String SOFT_CONDITION_PROMPT = """
            你是一个 RPG 游戏状态评估助手。请根据以下游戏事件历史，判断是否满足给定的软条件描述。

            软条件描述：%s

            最近事件历史：
            %s

            请仅回答 "true" 或 "false"。如果事件历史不足以判断，默认回答 "false"。
            """;

    private final ChatClientRegistry chatClientRegistry;
    private final RpgEventLogRepository eventLogRepo;

    public SoftConditionEvaluator(ChatClientRegistry chatClientRegistry,
                                   RpgEventLogRepository eventLogRepo) {
        this.chatClientRegistry = chatClientRegistry;
        this.eventLogRepo = eventLogRepo;
    }

    /**
     * 评估触发器的软条件。
     *
     * @param trigger    触发器定义（包含 soft_condition 描述）
     * @param gameStateId 游戏状态 ID
     * @return true 表示软条件满足
     */
    public boolean evaluate(Trigger trigger, String gameStateId) {
        String softCondition = trigger.getSoftCondition();
        if (softCondition == null || softCondition.isBlank()) {
            // 无软条件，默认通过
            return true;
        }

        // 查询最近 5 轮事件
        List<EventLog> recentEvents = eventLogRepo.findRecentByGameStateId(gameStateId, 5);
        String eventsText = recentEvents.stream()
                .map(e -> "[" + e.getTurn() + "] " + e.getEventType() + ": " + e.getContent())
                .collect(Collectors.joining("\n"));

        String prompt = String.format(SOFT_CONDITION_PROMPT, softCondition, eventsText);

        try {
            ChatClient compactionClient = chatClientRegistry.forRole("compaction");
            String response = compactionClient.prompt()
                    .user(prompt)
                    .advisors(a -> a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, "soft_condition"))
                    .call()
                    .content();
            boolean result = parseBooleanResponse(response);
            log.debug("软条件评估: trigger={}, condition={}, result={}",
                    trigger.getId(), softCondition, result);
            return result;
        } catch (Exception e) {
            log.warn("软条件评估失败，默认不通过: trigger={}, error={}", trigger.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * 解析 LLM 返回的布尔值。
     */
    private boolean parseBooleanResponse(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        String trimmed = response.trim().toLowerCase();
        return trimmed.contains("true") && !trimmed.contains("false");
    }
}
