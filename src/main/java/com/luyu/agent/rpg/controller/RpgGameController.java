package com.luyu.agent.rpg.controller;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.rpg.engine.GameLoopService;
import com.luyu.agent.rpg.engine.OpeningNarrationService;
import com.luyu.agent.rpg.engine.RpgHistoryCleaner;
import com.luyu.agent.rpg.engine.StateDeltaSanitizer;
import com.luyu.agent.rpg.model.GameState;
import com.luyu.agent.rpg.service.WorkshopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RPG 游戏流程 Controller。
 * <p>
 * - GET  /rpg: 返回 rpg.html 模板
 * - POST /rpg/game/start: 加载 GameState → 组装首轮 system prompt → 开场白 SSE 流式 → GM 接管
 * - POST /rpg/game/turn: 玩家输入 → GameLoopService 执行 → GM 叙述 SSE 流式（过滤 state_delta）
 * - POST /rpg/game/save: 存档
 * - POST /rpg/game/load: 读档
 * - GET  /rpg/game/state: 返回当前 GameState 供前端状态面板更新
 */
@RestController
@RequestMapping("/rpg")
public class RpgGameController {

    private static final Logger log = LoggerFactory.getLogger(RpgGameController.class);

    private final ChatClientRegistry chatClientRegistry;
    private final GameLoopService gameLoopService;
    private final OpeningNarrationService openingNarrationService;
    private final StateDeltaSanitizer stateDeltaSanitizer;
    private final WorkshopService workshopService;
    private final SessionService sessionService;
    private final RpgHistoryCleaner rpgHistoryCleaner;

    public RpgGameController(ChatClientRegistry chatClientRegistry,
                             GameLoopService gameLoopService,
                             OpeningNarrationService openingNarrationService,
                             StateDeltaSanitizer stateDeltaSanitizer,
                             WorkshopService workshopService,
                             SessionService sessionService,
                             RpgHistoryCleaner rpgHistoryCleaner) {
        this.chatClientRegistry = chatClientRegistry;
        this.gameLoopService = gameLoopService;
        this.openingNarrationService = openingNarrationService;
        this.stateDeltaSanitizer = stateDeltaSanitizer;
        this.workshopService = workshopService;
        this.sessionService = sessionService;
        this.rpgHistoryCleaner = rpgHistoryCleaner;
    }

    /**
     * 游戏开始：加载 GameState → 开场白 SSE 流式 → GM 接管。
     * <p>
     * 请求体: {"gameStateId":"xxx","sessionId":"yyy"}
     */
    @PostMapping(value = "/game/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> startGame(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        String sessionId = request.get("sessionId");

        // 生成开场白
        String opening = openingNarrationService.generateOpening(gameStateId);
        if (opening == null || opening.isBlank()) {
            opening = "（开场白未配置，故事直接开始。）";
        }

        // 组装首轮 system prompt
        String systemPrompt = gameLoopService.prepareTurn(gameStateId, "").systemPrompt();
        // 这里简化：首轮只展示开场白，GM 从第一轮玩家输入开始
        // 实际首轮注入在 prepareTurn 时完成

        // 流式推送开场白（逐字）
        Flux<ServerSentEvent<Object>> openingFlux = charStreamFlux(opening);

        // 结束标记
        return openingFlux.concatWith(Flux.just(
                ServerSentEvent.<Object>builder()
                        .data(Map.of("type", "opening_complete", "gameStateId", gameStateId))
                        .build(),
                ServerSentEvent.<Object>builder()
                        .data("[DONE]")
                        .build()
        ));
    }

    /**
     * 游戏回合：玩家输入 → GameLoopService → GM 叙述 SSE 流式。
     * <p>
     * 请求体: {"gameStateId":"xxx","sessionId":"yyy","message":"我走进客栈"}
     */
    @PostMapping(value = "/game/turn", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> gameTurn(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        String sessionId = request.get("sessionId");
        String message = request.get("message");

        // 步骤1-4: 准备轮次（触发器扫描、软条件评估、概率检定、GM 上下文组装）
        GameLoopService.TurnContext turnContext;
        try {
            turnContext = gameLoopService.prepareTurn(gameStateId, message);
        } catch (Exception e) {
            log.error("准备轮次失败: {}", e.getMessage(), e);
            return Flux.just(ServerSentEvent.<Object>builder()
                    .data(Map.of("error", "准备轮次失败: " + e.getMessage()))
                    .build());
        }

        // 获取 GM 专属 ChatClient（rpgSessionMemoryAdvisor，maxEvents=30，与主聊天隔离）
        ChatClient chatClient = chatClientRegistry.forRpg();

        // 拼接完整回复（用于 post-processing）
        StringBuilder fullResponse = new StringBuilder();

        // 构建 GM 请求
        Flux<ServerSentEvent<Object>> contentFlux;
        if (turnContext.firstRound() && turnContext.systemPrompt() != null) {
            // 首轮：注入 system prompt + 玩家输入
            contentFlux = chatClient.prompt()
                    .system(turnContext.systemPrompt())
                    .user(turnContext.userPrompt())
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                    .toolContext(turnContext.toolContext())
                    .stream()
                    .content()
                    .map(token -> {
                        fullResponse.append(token);
                        return ServerSentEvent.<Object>builder()
                                .data(Map.of("content", token))
                                .build();
                    })
                    .onErrorResume(error -> {
                        String errMsg = "GM 生成异常: " + error.getMessage();
                        log.error("GM SSE 流式异常: gameStateId={}", gameStateId, error);
                        return Flux.just(ServerSentEvent.<Object>builder()
                                .data(Map.of("error", errMsg))
                                .build());
                    });
        } else {
            // 后续轮：增量注入
            contentFlux = chatClient.prompt()
                    .user(turnContext.userPrompt())
                    .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionId))
                    .toolContext(turnContext.toolContext())
                    .stream()
                    .content()
                    .map(token -> {
                        fullResponse.append(token);
                        return ServerSentEvent.<Object>builder()
                                .data(Map.of("content", token))
                                .build();
                    })
                    .onErrorResume(error -> {
                        String errMsg = "GM 生成异常: " + error.getMessage();
                        log.error("GM SSE 流式异常: gameStateId={}", gameStateId, error);
                        return Flux.just(ServerSentEvent.<Object>builder()
                                .data(Map.of("error", errMsg))
                                .build());
                    });
        }

        // post-processing: 在流完成后执行
        return contentFlux
                .doOnComplete(() -> {
                    // 步骤6: state_delta 提取 + 状态更新 + 动机变更 + 事件日志
                    try {
                        gameLoopService.finalizeTurn(gameStateId, fullResponse.toString(), turnContext);
                    } catch (Exception e) {
                        log.error("finalizeTurn 失败: gameStateId={}", gameStateId, e);
                    }
                })
                .doOnCancel(() -> {
                    // 流式中止：保存已生成的部分
                    if (fullResponse.length() > 0) {
                        try {
                            gameLoopService.finalizeTurn(gameStateId, fullResponse.toString(), turnContext);
                        } catch (Exception e) {
                            log.error("中止后 finalizeTurn 失败: gameStateId={}", gameStateId, e);
                        }
                    }
                })
                .concatWith(Flux.just(
                        ServerSentEvent.<Object>builder()
                                .data("[DONE]")
                                .build()
                ));
    }

    /**
     * 存档。
     */
    @PostMapping("/game/save")
    public Map<String, Object> saveGame(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        GameState gs = workshopService.getGameState(gameStateId);
        if (gs == null) {
            return Map.of("error", "游戏状态未找到");
        }
        // GameState 已在 H2 中持久化，无需额外操作
        return Map.of("success", true, "gameStateId", gameStateId);
    }

    /**
     * 读档：加载已有 GameState 并关联新 session。
     */
    @PostMapping("/game/load")
    public Map<String, Object> loadGame(@RequestBody Map<String, String> request) {
        String gameStateId = request.get("gameStateId");
        String sessionId = request.get("sessionId");
        GameState gs = workshopService.getGameState(gameStateId);
        if (gs == null) {
            return Map.of("error", "存档未找到");
        }
        // 更新 session 关联
        workshopService.updateGameSession(gameStateId, sessionId);
        return Map.of("success", true,
                "gameStateId", gameStateId,
                "worldId", gs.getWorldId(),
                "turnCount", gs.getTurnCount());
    }

    /**
     * 查询当前 GameState 供前端状态面板更新。
     */
    @GetMapping("/game/state")
    public GameState getGameState(@RequestParam String gameStateId) {
        return workshopService.getGameState(gameStateId);
    }

    /**
     * 会话历史查询（对话式回放数据源）。
     * <p>
     * 请求: GET /rpg/game/history?sessionId=xxx<br>
     * 响应: [{"role":"user|assistant","content":"...","synthetic":true?},...]
     * <p>
     * 复用 {@link SessionService#getEvents}，在读取返回时做 read-time 清洗（不改写存储）：
     * <ul>
     *   <li>user：从包裹 prompt 抽取玩家行动（{@link RpgHistoryCleaner}）；合成用户提示不返回</li>
     *   <li>assistant：移除 state_delta 块，仅保留叙述正文</li>
     *   <li>tool_call / tool_response：整条过滤（隐藏 GM 的 get_ 工具调用，D10）</li>
     *   <li>synthetic：合成助手摘要作为一条 assistant 返回（synthetic=true）</li>
     * </ul>
     * 空 sessionId 或空会话返回空数组，不报错。
     */
    @GetMapping("/game/history")
    public List<Map<String, Object>> getHistory(@RequestParam String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return List.of();
        }
        List<SessionEvent> events = sessionService.getEvents(sessionId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionEvent event : events) {
            Message msg = event.getMessage();
            if (msg == null) {
                continue;
            }
            // 隐藏工具事件（D10）：GM 的 get_ 工具调用不回放
            if (msg instanceof ToolResponseMessage) {
                continue;
            }
            if (msg instanceof AssistantMessage am && am.hasToolCalls()) {
                continue;
            }

            boolean synthetic = event.isSynthetic();
            String content = msg.getText();

            if (msg instanceof UserMessage) {
                // 合成用户提示（压缩产物）不返回
                if (synthetic) {
                    continue;
                }
                String cleaned = rpgHistoryCleaner.cleanUserMessage(content);
                if (cleaned == null || cleaned.isEmpty()) {
                    continue;
                }
                result.add(historyEntry("user", cleaned, false));
            } else if (msg instanceof AssistantMessage) {
                // 叙述正文（合成摘要原样保留）
                String cleaned = synthetic
                        ? (content != null ? content.trim() : "")
                        : rpgHistoryCleaner.cleanAssistantMessage(content);
                if (cleaned == null || cleaned.isEmpty()) {
                    continue;
                }
                result.add(historyEntry("assistant", cleaned, synthetic));
            }
            // 其余类型（如 SystemMessage）不回放
        }
        return result;
    }

    /** 构建历史消息条目。 */
    private Map<String, Object> historyEntry(String role, String content, boolean synthetic) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", role);
        entry.put("content", content);
        if (synthetic) {
            entry.put("synthetic", true);
        }
        return entry;
    }

    // ==================== 辅助方法 ====================

    /**
     * 将文本转为逐字 SSE 流。
     */
    private Flux<ServerSentEvent<Object>> charStreamFlux(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        // 按字符流式推送（模拟逐字展示）
        return Flux.fromStream(() -> text.chars().mapToObj(c -> String.valueOf((char) c)))
                .map(token -> ServerSentEvent.<Object>builder()
                        .data(Map.of("content", token, "type", "opening"))
                        .build());
    }
}
