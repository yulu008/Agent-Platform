package com.luyu.agent.rpg.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyu.agent.rpg.model.GameState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 概率检定器。
 * <p>
 * 生成随机数与触发器 probability 字段比较。
 * fear 影响公式：{@code effective = base * (1 - fear)}，
 * fear 值从 NPC 动态动机中读取。
 */
@Component
public class ProbabilityRoller {

    private static final Logger log = LoggerFactory.getLogger(ProbabilityRoller.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 执行概率检定。
     *
     * @param baseProbability 触发器基础概率（0.0 ~ 1.0）
     * @param gameState       当前游戏状态
     * @param npcId           关联 NPC 的 ID（用于读取 fear 值）
     * @return true 表示检定通过（触发）
     */
    public boolean roll(double baseProbability, GameState gameState, String npcId) {
        double effectiveProb = calculateEffectiveProbability(baseProbability, gameState, npcId);
        double roll = ThreadLocalRandom.current().nextDouble();
        boolean triggered = roll < effectiveProb;
        log.debug("概率检定: base={}, effective={}, roll={}, triggered={}",
                baseProbability, effectiveProb, roll, triggered);
        return triggered;
    }

    /**
     * 计算有效概率（考虑 fear 影响）。
     * <p>
     * 公式：effective = base * (1 - fear)
     * fear 值范围 [0.0, 1.0]，从 npc_states JSON 中读取。
     */
    public double calculateEffectiveProbability(double baseProbability, GameState gameState, String npcId) {
        double fear = extractFear(gameState.getNpcStates(), npcId);
        double effective = baseProbability * (1.0 - fear);
        // clamp to [0.0, 1.0]
        return Math.max(0.0, Math.min(1.0, effective));
    }

    /**
     * 从 npc_states JSON 中提取指定 NPC 的 fear 值。
     * <p>
     * npc_states 格式：
     * <pre>{"盗贼-001": {"status": "idle", "motives": {"greed": 0.8, "fear": 0.2, "curiosity": 0.3}}}</pre>
     */
    private double extractFear(String npcStatesJson, String npcId) {
        if (npcStatesJson == null || npcId == null) {
            return 0.0;
        }
        try {
            JsonNode npcStates = mapper.readTree(npcStatesJson);
            JsonNode npc = npcStates.path(npcId);
            if (npc.isMissingNode()) {
                return 0.0;
            }
            JsonNode motives = npc.path("motives");
            if (motives.isMissingNode()) {
                return 0.0;
            }
            return motives.path("fear").asDouble(0.0);
        } catch (Exception e) {
            log.warn("提取 fear 值失败: npcId={}, error={}", npcId, e.getMessage());
            return 0.0;
        }
    }
}
