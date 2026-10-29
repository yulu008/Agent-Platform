package com.luyu.agent.rpg.eval.engine;

import java.util.List;

import com.luyu.agent.config.ChatClientRegistry;
import com.luyu.agent.metering.MeteringAdvisor;
import com.luyu.agent.rpg.eval.model.EvalCase;
import com.luyu.agent.rpg.eval.model.JudgeJudgment;
import com.luyu.agent.rpg.eval.model.JudgeOutcome;
import com.luyu.agent.rpg.eval.model.L1Warning;
import com.luyu.agent.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * L2 judge 客户端（content-eval tasks 5.3，design D1/D4/D5）。
 * <p>
 * 编排单 EvalCase 的完整裁决链（对齐 MalformedToolCallSanitizer 三层防御
 * 哲学：防御解析 → 重试一次 → 降级隔离）：
 * <ol>
 *   <li>prompt 组装（{@link JudgePromptAssembler}：rubric + 设定包 + 叙述窗 +
 *       L1 预警）；</li>
 *   <li>{@code forRole("judge")} 调用，逐调用计量入 metering（call_type=judge，
 *       对齐 workshop/planner 惯例）；</li>
 *   <li>宽反序列化解析（{@link JudgeResponseParser}）；调用失败/输出畸形
 *       → 同 prompt 重试一次；</li>
 *   <li>二次失败 → case 级降级 invalid（{@link JudgeOutcome#invalid}），
 *       批跑不中断；</li>
 *   <li>证据核验（{@link EvidenceVerifier}）——解析成功后按子维度条目
 *       粒度挂 invalidReason。</li>
 * </ol>
 * 启用语义（D1）：未配置 judge 角色 = eval 未启用——judge/judgeModelName/
 * judgeFingerprint 抛 {@link EvalNotEnabledException}（配置指引文案），
 * 绝不 fallback 到 default 对话模型。
 */
@Component
public class JudgeClient {

    private static final Logger log = LoggerFactory.getLogger(JudgeClient.class);

    /** judge 角色名（agent.models 任一条目 roles 含此值即启用） */
    public static final String ROLE_JUDGE = "judge";

    private final ChatClientRegistry chatClientRegistry;
    private final JudgePromptAssembler promptAssembler;
    private final JudgeResponseParser responseParser;
    private final EvidenceVerifier evidenceVerifier;
    private final RubricLoader rubricLoader;

    public JudgeClient(ChatClientRegistry chatClientRegistry,
                       JudgePromptAssembler promptAssembler,
                       JudgeResponseParser responseParser,
                       EvidenceVerifier evidenceVerifier,
                       RubricLoader rubricLoader) {
        this.chatClientRegistry = chatClientRegistry;
        this.promptAssembler = promptAssembler;
        this.responseParser = responseParser;
        this.evidenceVerifier = evidenceVerifier;
        this.rubricLoader = rubricLoader;
    }

    /** judge 是否已配置（agent.models 任一条目 roles 含 judge；多条目装配序首个） */
    public boolean isJudgeConfigured() {
        return chatClientRegistry.roleModelName(ROLE_JUDGE) != null;
    }

    /** judge 模型名（指纹组成一）；未配置抛 {@link EvalNotEnabledException} */
    public String judgeModelName() {
        String model = chatClientRegistry.roleModelName(ROLE_JUDGE);
        if (model == null) {
            throw new EvalNotEnabledException();
        }
        return model;
    }

    /** judge 指纹 = 模型名 + rubric 版本（record 级「谁打的分」可追溯）；未配置抛异常 */
    public String judgeFingerprint() {
        String rubric = rubricLoader.version();
        return judgeModelName() + "/" + (rubric != null ? rubric : "rubric-unknown");
    }

    /**
     * 评一个 case：L1 预警注入 → 调用 → 解析（失败重试一次）→ 证据核验。
     * 不抛业务异常（调用/畸形全部降级为 outcome），未配置 judge 抛
     * {@link EvalNotEnabledException}（调用方预检，spec：不发起任何 LLM 调用）。
     */
    public JudgeOutcome judge(EvalCase c, List<L1Warning> l1Warnings) {
        if (!isJudgeConfigured()) {
            throw new EvalNotEnabledException();
        }
        String prompt = promptAssembler.assemble(c, l1Warnings);
        // 装配期捕获租户：批跑后台线程 ThreadLocal 不可靠时经显式 advisor param 归账
        // （MeteringAdvisor D3 优先级 param > ThreadLocal，对齐 SessionTitleGenerator 先例）
        String tenantId = TenantContext.getTenantId();

        Attempt first = attempt(prompt, tenantId);
        if (first.failure() == null) {
            return new JudgeOutcome(null, evidenceVerifier.verify(first.judgments(), c));
        }
        log.warn("judge 首次尝试失败（重试一次）: subject={}, failure={}",
                c.subject(), first.failure());
        Attempt second = attempt(prompt, tenantId);
        if (second.failure() == null) {
            return new JudgeOutcome(null, evidenceVerifier.verify(second.judgments(), c));
        }
        return JudgeOutcome.invalid("judge 调用/解析失败（重试一次仍失败）: " + second.failure());
    }

    // ==================== 私有辅助 ====================

    /** 一次调用 + 解析：成功 judgments 非空；失败 failure 非空（畸形/调用异常/空响应） */
    private Attempt attempt(String prompt, String tenantId) {
        String raw;
        try {
            raw = chatClientRegistry.forRole(ROLE_JUDGE)
                    .prompt()
                    .user(prompt)
                    .advisors(a -> {
                        a.param(MeteringAdvisor.CALL_TYPE_CONTEXT_KEY, ROLE_JUDGE);
                        if (tenantId != null && !tenantId.isBlank()) {
                            a.param(MeteringAdvisor.TENANT_CONTEXT_KEY, tenantId);
                        }
                    })
                    .call()
                    .content();
        } catch (RuntimeException e) {
            return new Attempt(null, "judge 调用失败: " + e.getMessage());
        }
        if (raw == null || raw.isBlank()) {
            return new Attempt(null, "judge 调用返回空响应");
        }
        try {
            return new Attempt(responseParser.parse(raw), null);
        } catch (JudgeParseException e) {
            return new Attempt(null, "judge 输出畸形: " + e.getMessage());
        }
    }

    private record Attempt(List<JudgeJudgment> judgments, String failure) {
    }
}
