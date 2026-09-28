package com.luyu.agent.config;

import com.luyu.agent.governance.GovernedTaskRepository;
import com.luyu.agent.governance.RateLimitingSubagentExecutor;
import com.luyu.agent.logger.SubagentAuditLogger;
import com.luyu.agent.tenancy.TenantScopedTaskCallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.tools.SmartWebFetchTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentExecutor;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;

import com.luyu.agent.governance.ModelAwareSubagentExecutor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org .springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 子Agent编排装配
 * 装配 TaskTool：加载 markdown 子Agent定义 + 构建带 skills 的 ClaudeSubagentType（模式B）
 * + 限流装饰器（RateLimitingSubagentExecutor）+ 受治理任务仓库（GovernedTaskRepository）。
 * agents 目录为空或不存在时跳过装配，不阻断应用启动。
 */
@Configuration
@EnableConfigurationProperties(AgentUtilsProperties.class)
public class SubagentConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SubagentConfiguration.class);

    private final ChatClient.Builder chatClientBuilder;
    private final AgentUtilsProperties properties;
    private final ResourceLoader resourceLoader;
    private final SubagentAuditLogger auditLogger;

    public SubagentConfiguration(@Lazy ChatClient.Builder chatClientBuilder, AgentUtilsProperties properties,
                                 ResourceLoader resourceLoader, SubagentAuditLogger auditLogger) {
        this.chatClientBuilder = chatClientBuilder;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.auditLogger = auditLogger;
    }

    /**
     * 创建 TaskTool 工具回调（非 Bean，由 ChatController 显式调用）
     * agents 目录为空或不存在时返回 null，不阻断应用启动
     * 不注册为 ToolCallback bean 以避免 toolCallbackResolver 急切创建导致的循环依赖
     */
    public ToolCallback createTaskToolCallback() {
        String agentsRoot = properties.agentsRoot();
        log.info("SubagentConfiguration TaskTool 装配中，agentsRoot={}", agentsRoot);

        // 1. 加载子Agent markdown 定义
        List<SubagentReference> refs = loadSubagentReferences(agentsRoot);
        if (refs.isEmpty()) {
            log.warn("agents 目录无可用子Agent定义 (agentsRoot={})，跳过 TaskTool 装配", agentsRoot);
            return null;
        }
        log.info("加载到 {} 个子Agent定义", refs.size());

        // 2. 构建带 skills 的 ClaudeSubagentType（模式B：子Agent自带技能）
        SubagentType claudeType = buildClaudeSubagentType();
        log.info("ClaudeSubagentType 装配成功，kind={}", claudeType.kind());

        // 3. 限流装饰器包装
        int maxParallelism = properties.subagent().maxParallelism();
        Duration lifespan = properties.subagent().lifespan();
        List<SubagentType> limited = RateLimitingSubagentExecutor.wrapAll(
                List.of(claudeType), maxParallelism, lifespan, auditLogger);
        log.info("子Agent限流参数: maxParallelism={} lifespan={}", maxParallelism, lifespan);

        // 4. 装配 TaskTool
        GovernedTaskRepository repo = new GovernedTaskRepository(lifespan, auditLogger);
        try {
            ToolCallback callback = TaskTool.builder()
                    .subagentTypes(limited)
                    .subagentReferences(refs)
                    .taskRepository(repo)
                    .build();
            log.info("TaskTool 装配成功，主Agent可通过 task 工具委派子Agent");
            // 跳1（tasks 6.4 / design D5）：包装 task 回调，调用期从 toolContext 恢复租户上下文，
            // 供跳2（RateLimitingSubagentExecutor）跨虚拟线程透传，最终子代理用量归账到发起租户。
            return new TenantScopedTaskCallback(callback);
        } catch (Exception e) {
            log.warn("TaskTool 装配失败，跳过", e);
            return null;
        }
    }

    /**
     * 加载子Agent markdown 定义（.claude/agents/ 风格）
     * 优先用文件系统路径扫描（dev 模式 classpath 解析到 target/classes），回退到 Resource 方式。
     * 注意：不直接使用 ClaudeSubagentReferences.fromRootDirectory()，因其生成的 URI 为绝对路径，
     * 在 Windows 下 ClaudeSubagentResolver 使用 DefaultResourceLoader 解析时缺少 "file:" 前缀导致失败。
     * 此处手动扫描并创建带 "file:" 前缀 URI 的 SubagentReference 以兼容 Windows。
     */
    private List<SubagentReference> loadSubagentReferences(String agentsRoot) {
        // 优先：文件系统目录扫描（dev 模式 classpath 解析到 target/classes）
        Resource resource = resourceLoader.getResource(agentsRoot);
        if (resource.exists()) {
            try {
                File dir = resource.getFile();
                if (dir.isDirectory()) {
                    List<SubagentReference> refs = scanAgentMarkdownFiles(dir.toPath());
                    if (!refs.isEmpty()) {
                        return refs;
                    }
                }
            } catch (Exception e) {
                log.debug("agentsRoot 无法解析为文件系统目录，尝试 classpath 提取: {}", e.getMessage());
            }
        }
        // 回退：JAR 内 classpath —— 提取到临时目录后扫描加载
        File extractedDir = extractClasspathDirToTemp(agentsRoot);
        if (extractedDir == null) {
            log.warn("agents 目录无可用子Agent定义 (agentsRoot={})", agentsRoot);
            return List.of();
        }
        try {
            List<SubagentReference> refs = scanAgentMarkdownFiles(extractedDir.toPath());
            log.info("从临时目录加载到 {} 个子Agent定义", refs.size());
            return refs;
        } catch (Exception e) {
            log.warn("子Agent定义加载失败 (agentsRoot={}): {}", agentsRoot, e.getMessage());
            return List.of();
        }
    }

    /**
     * 扫描目录下所有 .md 文件，创建带 "file:" 前缀 URI 的 SubagentReference。
     * ClaudeSubagentResolver 内部使用 DefaultResourceLoader.getResource(uri) 读取文件，
     * "file:" 前缀确保在 Windows/Linux 下均能正确解析为文件系统资源。
     */
    private List<SubagentReference> scanAgentMarkdownFiles(Path rootDir) throws IOException {
        List<SubagentReference> refs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .forEach(p -> {
                    String fileUri = p.toAbsolutePath().toUri().toString();
                    refs.add(new SubagentReference(fileUri, "CLAUDE"));
                });
        }
        return refs;
    }

    /**
     * 构建带 skills 的 ClaudeSubagentType（模式B）
     * 子Agent在隔离上下文加载并执行技能，复用 skillsRoot 资源
     * 内置 SmartWebFetchTool 被替换为自定义版本（关闭域名安全检查），
     * 避免每次 webFetch 调用都向不可达的 claude.ai/api/web/domain_info 发起 HTTP 请求。
     */
    private SubagentType buildClaudeSubagentType() {
        // chatClientBuilder 是 @Lazy 代理，直接传递而非 clone() 可避免在 bean 创建期间触发 ChatClient.Builder 的实例化
        // 代理在子Agent实际执行时才解析为真实的 ChatClient.Builder 单例，此时所有 bean 已完成初始化
        ClaudeSubagentType.Builder builder = ClaudeSubagentType.builder()
                .chatClientBuilder("default", chatClientBuilder);
        // 模式B：子Agent自带 skills，复用 skillsRoot 资源
        File skillsDir = extractClasspathDirToTemp(properties.skillsRoot());
        if (skillsDir != null) {
            builder.skillsResource(new FileSystemResource(skillsDir));
            log.info("子Agent已赋予 skills 资源: {}", properties.skillsRoot());
        } else {
            log.warn("skills 根目录不存在: {}，子Agent不带技能", properties.skillsRoot());
        }
        return replaceWebFetchTool(builder.build());
    }

    /**
     * 替换 ClaudeSubagentExecutor 中的内置 SmartWebFetchTool，
     * 关闭域名安全检查（domainSafetyCheck=false），避免每次 webFetch 调用
     * 都向 https://claude.ai/api/web/domain_info 发起不可达的 HTTP 请求。
     * 同时用 ModelAwareSubagentExecutor 替换原始执行器，修复内置子Agent
     * （Bash/Plan）的 model: default 导致非 Claude API 返回 403 的问题。
     *
     * 实现方式：反射读取 ClaudeSubagentExecutor 的私有 tools/chatClientBuilderMap/skillsDirectories 字段，
     * 用 SmartWebFetchTool.builder(chatClient).domainSafetyCheck(false).build() 替换默认实例，
     * 重建执行器（ModelAwareSubagentExecutor）。反射失败时降级为原始 SubagentType，不影响系统运行。
     */
    @SuppressWarnings("unchecked")
    private SubagentType replaceWebFetchTool(SubagentType defaultType) {
        try {
            var defaultExecutor = defaultType.executor();
            if (!(defaultExecutor instanceof ClaudeSubagentExecutor claudeExecutor)) {
                log.warn("执行器非 ClaudeSubagentExecutor，跳过 webFetch 替换: {}",
                        defaultExecutor.getClass().getName());
                return defaultType;
            }

            // 1. 反射读取三个私有字段
            Field toolsField = ClaudeSubagentExecutor.class.getDeclaredField("tools");
            toolsField.setAccessible(true);
            List<ToolCallback> defaultTools = (List<ToolCallback>) toolsField.get(claudeExecutor);

            Field builderMapField = ClaudeSubagentExecutor.class.getDeclaredField("chatClientBuilderMap");
            builderMapField.setAccessible(true);
            Map<String, ChatClient.Builder> builderMap =
                    (Map<String, ChatClient.Builder>) builderMapField.get(claudeExecutor);

            Field skillsDirsField = ClaudeSubagentExecutor.class.getDeclaredField("skillsDirectories");
            skillsDirsField.setAccessible(true);
            List<String> skillsDirs = (List<String>) skillsDirsField.get(claudeExecutor);

            // 2. 创建自定义 SmartWebFetchTool（关闭域名安全检查）
            ChatClient webFetchClient = chatClientBuilder.build();
            SmartWebFetchTool customWebFetch = SmartWebFetchTool.builder(webFetchClient)
                    .domainSafetyCheck(false)
                    .build();
            ToolCallback[] customCallbacks = ToolCallbacks.from(customWebFetch);
            String webFetchName = customCallbacks[0].getToolDefinition().name();

            // 3. 替换工具列表：移除同名默认工具，加入自定义版本
            List<ToolCallback> modifiedTools = new ArrayList<>();
            boolean replaced = false;
            for (ToolCallback tc : defaultTools) {
                if (tc.getToolDefinition().name().equals(webFetchName)) {
                    log.info("替换内置 webFetch 工具: name={} → 自定义(无域名安全检查)", webFetchName);
                    replaced = true;
                } else {
                    modifiedTools.add(tc);
                }
            }
            if (!replaced) {
                log.warn("未找到名为 {} 的内置工具，可用工具: {}", webFetchName,
                        defaultTools.stream()
                                .map(tc -> tc.getToolDefinition().name())
                                .toList());
            }
            modifiedTools.addAll(Arrays.asList(customCallbacks));

            // 4. 重建执行器（ModelAwareSubagentExecutor 修复 model=default 导致的 403）
            ModelAwareSubagentExecutor customExecutor = new ModelAwareSubagentExecutor(
                    builderMap, modifiedTools, skillsDirs);
            log.info("执行器重建完成: 工具数 {}→{}，域名安全检查已关闭，model=default 覆盖已修复",
                    defaultTools.size(), modifiedTools.size());

            return new SubagentType(defaultType.resolver(), customExecutor);
        } catch (Exception e) {
            log.warn("SmartWebFetchTool 自定义替换失败，降级为默认行为: {}", e.getMessage());
            return defaultType;
        }
    }

    /**
     * 将 classpath 目录资源解析为文件系统目录
     * 优先返回文件系统路径（dev 模式 classpath 解析到 target/classes）；
     * 回退：JAR 内 classpath 用 PathMatchingResourcePatternResolver 扫描并提取到临时目录
     *
     * @param location 资源路径（如 classpath:skills 或 classpath:agents）
     * @return 文件系统目录，或 null（不存在/为空）
     */
    private File extractClasspathDirToTemp(String location) {
        // 优先：文件系统路径
        Resource resource = resourceLoader.getResource(location);
        if (resource.exists()) {
            try {
                File dir = resource.getFile();
                if (dir.isDirectory()) {
                    return dir;
                }
            } catch (Exception e) {
                log.debug("{} 无法解析为文件系统目录，尝试 classpath 扫描", location);
            }
        }
        // 回退：JAR 内 classpath —— 扫描并提取到临时目录
        try {
            String baseDir = location.replace("classpath:", "").replace("classpath*:", "").replaceFirst("^/", "");
            ResourcePatternResolver patternResolver = new PathMatchingResourcePatternResolver(resourceLoader);
            Resource[] resources = patternResolver.getResources("classpath*:" + baseDir + "/**/*");
            Path tempDir = Files.createTempDirectory("subagent-" + baseDir.replace("/", "-"));
            tempDir.toFile().deleteOnExit();
            int count = 0;
            for (Resource res : resources) {
                String url = res.getURL().toString();
                if (url.endsWith("/")) {
                    continue; // 跳过目录
                }
                int idx = url.indexOf(baseDir + "/");
                if (idx >= 0) {
                    String relativePath = url.substring(idx + baseDir.length() + 1);
                    Path target = tempDir.resolve(relativePath);
                    Files.createDirectories(target.getParent());
                    Files.copy(res.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
            if (count == 0) {
                return null;
            }
            log.info("从 classpath 提取 {} 个文件到临时目录: {}", count, tempDir);
            return tempDir.toFile();
        } catch (Exception e) {
            log.warn("classpath 目录提取失败 (location={}): {}", location, e.getMessage());
            return null;
        }
    }
}
