package com.luyu.agent.moderation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 内容审查装配（design D5 / task 1.4）。
 * <p>
 * 启动时从 {@code moderation.wordlist-location} 指向的资源加载敏感词库，构建
 * {@link ContentModerationClient} bean。加载失败或词库为空时<b>不阻断启动</b>：
 * 记录错误日志并返回空词库 client（空词库无任何命中，等价于放行），与「本地词库
 * 无网络失败态、词库为空即放行」的语义一致。
 */
@Configuration
@EnableConfigurationProperties(ModerationProperties.class)
public class ModerationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModerationConfiguration.class);

    @Bean
    public ContentModerationClient contentModerationClient(ModerationProperties properties,
                                                           ResourceLoader resourceLoader) {
        String location = properties.getWordlistLocation();
        List<String> lines = readWordlist(location, resourceLoader);
        Map<ModerationCategory, List<String>> wordsByCategory = WordListModerationClient.parse(lines);
        WordListModerationClient client = new WordListModerationClient(wordsByCategory);
        if (client.isEmpty()) {
            log.error("内容审查词库为空或加载失败，按无命中放行处理: location={}", location);
        } else {
            log.info("内容审查词库已加载: location={}, categories={}", location, wordsByCategory.keySet());
        }
        return client;
    }

    /**
     * 读取词库资源的全部行；资源缺失或 IO 异常时返回空列表（不抛出，避免阻断启动）。
     */
    private List<String> readWordlist(String location, ResourceLoader resourceLoader) {
        List<String> lines = new ArrayList<>();
        if (location == null || location.isBlank()) {
            log.error("未配置内容审查词库路径 moderation.wordlist-location");
            return lines;
        }
        try {
            Resource resource = resourceLoader.getResource(location);
            if (!resource.exists()) {
                log.error("内容审查词库资源不存在: location={}", location);
                return lines;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
        } catch (Exception e) {
            log.error("加载内容审查词库失败: location={}", location, e);
        }
        return lines;
    }
}
