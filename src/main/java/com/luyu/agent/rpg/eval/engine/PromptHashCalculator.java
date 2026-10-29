package com.luyu.agent.rpg.eval.engine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * GM 提示词版本指纹计算器（content-eval tasks 2.1，design D8）。
 * <p>
 * 聚合 GM 生成链路的全部 classpath 提示词资源（system prompt 模板、叙事规范、
 * 记忆规范、增量注入块、软条件），SHA-256 前 16 位十六进制——同一资源版本的
 * 进程内任意次计算结果一致；任一资源变更即指纹变化（评估报告按指纹分组统计
 * 「哪版提示词生成的内容崩了」）。
 * <p>
 * 防御语义：classpath 资源进程内只读，结果惰性算一次即缓存；任一资源缺失/
 * 读取失败返回 {@code null} 并 warn（调用方跳过附着，不抛不阻断——与
 * {@code EvalSchemaInitializer} 加列失败同哲学）。
 * <p>
 * 注意：GM <b>模型名不参与 hash</b>（指纹只锚提示词版本）；模型信息由
 * metering UsageRecord 独立承载，动态层评估时与 prompt_hash 并列组装
 * target_fingerprint。
 */
@Component
public class PromptHashCalculator {

    private static final Logger log = LoggerFactory.getLogger(PromptHashCalculator.class);

    /**
     * GM 提示词资源清单（固定顺序，指纹稳定的前提）。
     * <p>
     * 与 GmContextAssembler / RpgMemoryPromptAdvisor 运行时注入的资源一致；
     * 新增 GM 级提示词资源时应同步本清单（指纹随内容变化，跨版本可比性
     * 由「资源版本指纹分组」语义保证，不追求跨清单稳定）。
     */
    static final List<String> RESOURCE_PATHS = List.of(
            "classpath:rpg/gm-system-prompt.md",
            "classpath:rpg/gm-narrative-rules.md",
            "classpath:rpg/gm-memory-prompt.md",
            "classpath:rpg/gm-incremental-prompt.md",
            "classpath:rpg/soft-condition-prompt.md");

    private final ResourcePatternResolver resourceResolver;

    /** 惰性缓存（volatile 双检）：classpath 资源只读，进程内不变 */
    private volatile String cached;

    public PromptHashCalculator(ResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    /**
     * 当前资源版本的指纹；资源缺失/读取失败返回 {@code null}
     * （调用方跳过附着，防御不抛）。
     */
    public String currentHash() {
        String hash = cached;
        if (hash == null) {
            synchronized (this) {
                if (cached == null) {
                    List<Resource> resources = new ArrayList<>();
                    for (String path : RESOURCE_PATHS) {
                        try {
                            Resource[] found = resourceResolver.getResources(path);
                            if (found.length == 0) {
                                log.warn("GM 提示词资源缺失，版本指纹不可用: {}", path);
                                return null;
                            }
                            resources.add(found[0]);
                        } catch (Exception e) {
                            log.warn("GM 提示词资源解析失败，版本指纹不可用: {}", path, e);
                            return null;
                        }
                    }
                    cached = hashOf(resources);
                }
                hash = cached;
            }
        }
        return hash;
    }

    /**
     * 纯函数：资源序列 → SHA-256 前 16 位 hex（包内可见供单测钉语义）。
     * <p>
     * 拼接规则：每份资源「路径标记 + 内容字节」顺序串联（防同名位移/顺序对调
     * 碰撞）；hex 小写。
     */
    static String hashOf(List<Resource> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < resources.size(); i++) {
                // 序号标记参与 digest：顺序对调 → 指纹变化
                digest.update(("#" + i + "\n").getBytes(StandardCharsets.UTF_8));
                byte[] content = readAll(resources.get(i));
                digest.update(String.valueOf(content.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                digest.update(content);
            }
            byte[] full = digest.digest();
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", full[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            log.warn("版本指纹计算失败", e);
            return null;
        }
    }

    private static byte[] readAll(Resource resource) throws Exception {
        try (InputStream in = resource.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
