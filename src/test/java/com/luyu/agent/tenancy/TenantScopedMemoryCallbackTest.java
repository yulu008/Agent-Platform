package com.luyu.agent.tenancy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.AutoMemoryTools;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantScopedMemoryCallback} 集成测试（tasks 4.6）：主聊天 Memory* 工具
 * 挂<b>真实</b> {@code AutoMemoryTools} 底座（root = 临时租户总根），钉住三件事：
 * <ol>
 *   <li>无 tid 调用拒绝执行且<b>零写入</b>（file-isolation spec）</li>
 *   <li>带 tid 调用落盘到 {@code <root>/<tid>/memories/}（design D6 层级）</li>
 *   <li>穿越路径（{@code ../他租户/}）拒绝且不产生任何文件</li>
 * </ol>
 */
class TenantScopedMemoryCallbackTest {

    private static final String FILE_TEXT = "---\nname: 测试记忆\ndescription: 一行描述\ntype: user\n---\n\n正文内容\n";
    private static final String INPUT = "{\"path\":\"user/x.md\",\"fileText\":\""
            + FILE_TEXT.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";

    @TempDir
    Path tenantsRoot;

    private ToolCallback memoryCreate;

    @BeforeEach
    void setUp() {
        AutoMemoryTools tools = AutoMemoryTools.builder()
                .memoriesDir(tenantsRoot.toString())
                .build();
        memoryCreate = Arrays.stream(ToolCallbacks.from(tools))
                .filter(c -> c.getToolDefinition().name().equals("MemoryCreate"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("AutoMemoryTools 未提供 MemoryCreate"));
    }

    private TenantScopedMemoryCallback wrapped() {
        return new TenantScopedMemoryCallback(memoryCreate);
    }

    private static ToolContext contextOf(String tenantId) {
        return new ToolContext(Map.of(TenantContext.TOOL_CONTEXT_KEY, tenantId));
    }

    private static long fileCount(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void 无tid调用拒绝执行且零写入() throws IOException {
        String result = wrapped().call(INPUT);

        assertThat(result).contains("缺少租户标识");
        assertThat(fileCount(tenantsRoot)).isZero();
    }

    @Test
    void 无ToolContext重载同样拒绝且零写入() throws IOException {
        String result = wrapped().call(INPUT);

        assertThat(result).contains("缺少租户标识");
        assertThat(fileCount(tenantsRoot)).isZero();
    }

    @Test
    void 带tid调用落盘到租户memories子目录() throws IOException {
        String result = wrapped().call(INPUT, contextOf("t-a"));

        assertThat(result).doesNotContain("工具调用失败");
        // design D6 层级：<root>/<tid>/memories/<path>
        assertThat(Files.isRegularFile(tenantsRoot.resolve("t-a").resolve("memories").resolve("user/x.md")))
                .as("记忆文件应落在 t-a/memories/user/x.md")
                .isTrue();
        // 不允许在租户总根直接散落孤儿文件
        try (Stream<Path> children = Files.list(tenantsRoot)) {
            assertThat(children.toList()).containsExactly(tenantsRoot.resolve("t-a"));
        }
    }

    @Test
    void 穿越路径被拒且不产生任何文件() throws IOException {
        String input = INPUT.replace("user/x.md", "../t-b/memories/evil.md");
        String result = wrapped().call(input, contextOf("t-a"));

        assertThat(result).contains("工具调用失败").contains("..");
        assertThat(fileCount(tenantsRoot)).isZero();
        assertThat(Files.exists(tenantsRoot.resolve("t-b"))).isFalse();
    }

    @Test
    void 非法租户ID被拒且零写入() throws IOException {
        String result = wrapped().call(INPUT, contextOf("../evil"));

        assertThat(result).contains("工具调用失败");
        assertThat(fileCount(tenantsRoot)).isZero();
    }

    @Test
    void 不同租户落盘互不混杂() throws IOException {
        wrapped().call(INPUT, contextOf("t-a"));
        wrapped().call(INPUT.replace("user/x.md", "y.md"), contextOf("t-b"));

        assertThat(Files.isRegularFile(tenantsRoot.resolve("t-a/memories/user/x.md"))).isTrue();
        assertThat(Files.isRegularFile(tenantsRoot.resolve("t-b/memories/y.md"))).isTrue();
    }
}
