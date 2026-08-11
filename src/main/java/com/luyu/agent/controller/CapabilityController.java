package com.luyu.agent.controller;

import com.luyu.agent.config.AgentUtilsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实时扫描 skills/ 和 agents/ 目录，解析 YAML frontmatter 中的 name + description，
 * 为前端 "/" 命令面板提供数据源。
 * 若skills数量较多，且执行任务时不显示指定skills，建议调整为通过embedding模型检索知识库中的skills
 */
@RestController
@EnableConfigurationProperties(AgentUtilsProperties.class)
public class CapabilityController {

    private static final Logger log = LoggerFactory.getLogger(CapabilityController.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "\\A---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^(name|description):\\s*(.+)$", Pattern.MULTILINE);

    private final ResourceLoader resourceLoader;
    private final AgentUtilsProperties properties;

    public CapabilityController(ResourceLoader resourceLoader, AgentUtilsProperties properties) {
        this.resourceLoader = resourceLoader;
        this.properties = properties;
    }

    /**
     * GET /api/capabilities
     * 返回 {"skills": [{"name":"...", "description":"..."}], "agents": [...]}
     */
    @GetMapping("/api/capabilities")
    public Map<String, List<Map<String, String>>> getCapabilities() {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        result.put("skills", scanSkills());
        result.put("agents", scanAgents());
        return result;
    }

    /**
     * 扫描 skills 目录下的 SKILL.md 文件
     */
    private List<Map<String, String>> scanSkills() {
        List<Map<String, String>> items = new ArrayList<>();
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
            Resource[] resources = resolver.getResources("classpath*:skills/**/SKILL.md");
            for (Resource res : resources) {
                Map<String, String> meta = parseFrontmatter(res);
                if (meta != null) {
                    items.add(meta);
                }
            }
        } catch (Exception e) {
            log.warn("扫描 skills 目录失败: {}", e.getMessage());
        }
        return items;
    }

    /**
     * 扫描 agents 目录下的 .md 文件
     */
    private List<Map<String, String>> scanAgents() {
        List<Map<String, String>> items = new ArrayList<>();
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(resourceLoader);
            Resource[] resources = resolver.getResources("classpath*:agents/*.md");
            for (Resource res : resources) {
                Map<String, String> meta = parseFrontmatter(res);
                if (meta != null) {
                    items.add(meta);
                }
            }
        } catch (Exception e) {
            log.warn("扫描 agents 目录失败: {}", e.getMessage());
        }
        return items;
    }

    /**
     * 解析 markdown 文件的 YAML frontmatter，提取 name 和 description
     */
    private Map<String, String> parseFrontmatter(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
                // frontmatter 在文件开头，读到第二个 --- 后即可停止
                if (content.length() > 2000) break;
            }

            Matcher fmMatcher = FRONTMATTER_PATTERN.matcher(content.toString());
            if (!fmMatcher.find()) {
                return null;
            }

            String frontmatter = fmMatcher.group(1);
            Map<String, String> meta = new LinkedHashMap<>();
            Matcher fieldMatcher = FIELD_PATTERN.matcher(frontmatter);
            while (fieldMatcher.find()) {
                meta.put(fieldMatcher.group(1), fieldMatcher.group(2).trim());
            }

            if (meta.containsKey("name")) {
                meta.putIfAbsent("description", "");
                return meta;
            }
        } catch (Exception e) {
            log.debug("解析 frontmatter 失败: {}", resource.getFilename());
        }
        return null;
    }
}
