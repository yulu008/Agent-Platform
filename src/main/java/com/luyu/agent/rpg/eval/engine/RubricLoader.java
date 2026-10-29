package com.luyu.agent.rpg.eval.engine;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * rubric 判据资源加载器（content-eval tasks 5.1，design D7）。
 * <p>
 * {@code rpg/eval-character-rubric.md}（classpath，对齐 {@code gm-narrative-rules.md}
 * 惯例）：content() 供 judge prompt 注入判据全文；version() = 内容 SHA-256
 * 前 16 位（复用 {@link PromptHashCalculator#hashOf} 同包纯函数）——rubric
 * 任何改动即换版，作为 judge 指纹组成二（「哪版判据打的分」可追溯）。
 * <p>
 * 防御语义（对齐 PromptHashCalculator / HookAssertionChecker 惯例）：classpath
 * 资源进程内只读，惰性加载一次缓存；资源缺失/读取失败 content/version 返回
 * {@code null} 并 warn（judge prompt 缺判据段、指纹缺 rubric 组成，不抛不阻断）。
 */
@Component
public class RubricLoader {

    private static final Logger log = LoggerFactory.getLogger(RubricLoader.class);

    static final String RESOURCE_PATH = "rpg/eval-character-rubric.md";

    /** 惰性缓存（volatile 双检）：content 与 version 同源同生共死 */
    private volatile Loaded loaded;

    private record Loaded(String content, String version) {
    }

    /** 判据全文（judge prompt 注入）；资源缺失返回 null */
    public String content() {
        return loaded().content();
    }

    /** rubric 版本指纹（SHA-256 前 16 位 hex）；资源缺失返回 null */
    public String version() {
        return loaded().version();
    }

    private Loaded loaded() {
        Loaded cached = loaded;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (loaded != null) {
                return loaded;
            }
            Loaded result = load();
            loaded = result;
            return result;
        }
    }

    private Loaded load() {
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Resource resource = new ClassPathResource(RESOURCE_PATH);
            String version = PromptHashCalculator.hashOf(List.of(resource));
            if (version == null) {
                log.warn("rubric 版本指纹计算失败（judge 指纹缺 rubric 组成）: {}", RESOURCE_PATH);
            }
            return new Loaded(content, version);
        } catch (Exception e) {
            log.warn("rubric 资源加载失败（judge prompt 将缺判据段）: {}", RESOURCE_PATH, e);
            return new Loaded(null, null);
        }
    }
}
