package com.luyu.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Ripgrep 文件搜索工具，暴露给主/子智能体调用。
 * <p>
 * 调用系统 ripgrep（rg）对指定目录进行高性能正则搜索，
 * 返回匹配行及文件路径+行号，供智能体定位代码出处。
 * <p>
 * 仅在 Linux 环境下注册（rg 在 Linux 服务器上通常已安装）。
 */
@Component
@ConditionalOnExpression("T(java.lang.System).getProperty('os.name').toLowerCase().contains('linux')")
public class RipgrepTool {

    private static final Logger log = LoggerFactory.getLogger(RipgrepTool.class);

    /** 默认最大返回行数，防止输出过大撑爆上下文 */
    private static final int DEFAULT_MAX_LINES = 100;

    /** 进程超时时间（秒） */
    private static final int TIMEOUT_SECONDS = 30;

    @Tool(description = "使用 ripgrep 在指定目录中搜索文件内容。支持两种输出模式：output_mode=\"content\" 返回匹配的文件路径、行号及内容（默认）；output_mode=\"files_with_matches\" 仅返回包含匹配的文件路径列表，用于预检阶段快速判断表名是否存在，节省 token。")
    public String search(
            @ToolParam(description = "搜索的正则表达式或关键字，如 COBOL-PIC|TABLE-NAME") String pattern,
            @ToolParam(description = "搜索的根目录路径，如 skill_output/src 或 C:/project/src") String directory,
            @ToolParam(description = "文件名过滤（glob），如 *.cob、*.java、*.txt。传空字符串则搜索所有文件") String fileGlob,
            @ToolParam(description = "输出模式：\"content\"（默认）返回匹配行及行号；\"files_with_matches\" 仅返回文件路径列表，用于预检阶段", required = false) String outputMode,
            @ToolParam(description = "最大返回行数/文件数，默认150，建议不超过200", required = false) Integer maxResults,
            @ToolParam(description = "是否启用大小写不敏感搜索，默认true（即默认不区分大小写）", required = false) Boolean caseInsensitive) {
        int limit = (maxResults != null && maxResults > 0) ? maxResults : DEFAULT_MAX_LINES;
        boolean filesOnly = "files_with_matches".equalsIgnoreCase(outputMode);
        log.info("[RipgrepTool] search: pattern={}, directory={}, fileGlob={}, outputMode={}, maxResults={}",
                pattern, directory, fileGlob, filesOnly ? "files_with_matches" : "content", limit);

        try {
            // 构建 rg 命令
            ProcessBuilder pb = new ProcessBuilder();
            pb.command().add("rg");

            if (filesOnly) {
                // 预检模式：只返回包含匹配的文件路径
                pb.command().add("--files-with-matches"); // 每文件找到第一个匹配即停止，速度快
            } else {
                // 内容模式：返回文件路径 + 行号 + 匹配行
                pb.command().add("--no-heading");
                pb.command().add("--line-number");
                pb.command().add("--with-filename");
                pb.command().add("--max-count");
                pb.command().add(String.valueOf(limit));
                pb.command().add("--max-columns");
                pb.command().add("200");
            }

            if (!Boolean.FALSE.equals(caseInsensitive)) {
                pb.command().add("--ignore-case");     // 默认大小写不敏感，传 false 可关闭
            }

            if (fileGlob != null && !fileGlob.isBlank()) {
                pb.command().add("--glob");
                pb.command().add(fileGlob);
            }

            pb.command().add(pattern);
            pb.command().add(directory);

            pb.redirectErrorStream(true);
            log.info("[RipgrepTool] 执行命令: {}", pb.command());

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            int lineCount = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < limit) {
                    output.append(line).append('\n');
                    lineCount++;
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "搜索超时（" + TIMEOUT_SECONDS + "s），请缩小搜索范围后重试";
            }

            int exitCode = process.exitValue();
            // rg 退出码：0=有匹配，1=无匹配，2=错误
            if (exitCode == 2) {
                String err = output.toString().trim();
                log.warn("[RipgrepTool] rg 报错: {}", err);
                return "搜索失败：" + err;
            }

            if (lineCount == 0) {
                return "未找到匹配结果（pattern=" + pattern + ", directory=" + directory + "）";
            }

            String result = output.toString();
            log.info("[RipgrepTool] 搜索完成，返回 {} 行", lineCount);
            return result;

        } catch (java.io.IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Cannot run program")) {
                log.error("[RipgrepTool] ripgrep 未安装或不在 PATH 中");
                return "错误：ripgrep (rg) 未安装。请先安装 ripgrep 并确保 rg 命令在系统 PATH 中可用。"
                        + "\n安装方式: choco install ripgrep (Windows) / brew install ripgrep (Mac) / apt install ripgrep (Linux)";
            }
            log.error("[RipgrepTool] 执行异常", e);
            return "搜索执行失败: " + msg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "搜索被中断";
        }
    }
}
