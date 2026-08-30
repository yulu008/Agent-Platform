package com.luyu.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ripgrep 搜索工具
 * 封装 ripgrep 命令行工具，用于在代码库中搜索字段定义
 */
@Component
public class RipgrepSearchTool {

    private static final Logger log = LoggerFactory.getLogger(RipgrepSearchTool.class);

    /**
     * 搜索字段在代码库中的位置
     *
     * @param fieldName  字段英文名
     * @param tableName  表英文名
     * @param codeBasePath 代码库根路径
     * @return 搜索结果列表
     */
    public List<SearchResult> searchField(String fieldName, String tableName, String codeBasePath) {
        List<SearchResult> results = new ArrayList<>();

        try {
            // 构建 ripgrep 命令
            // 支持多种搜索模式：字段名、表名+字段名组合
            ProcessBuilder pb = new ProcessBuilder(
                    "rg",
                    "-n",        // 显示行号
                    "-i",        // 忽略大小写
                    "--type",    // 指定文件类型
                    "java,cobol,sql",
                    fieldName,   // 搜索关键词
                    codeBasePath
            );

            Process process = pb.start();

            // 读取输出
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                results = reader.lines()
                        .map(this::parseSearchResult)
                        .filter(result -> result != null)
                        .collect(Collectors.toList());
            }

            process.waitFor();

        } catch (Exception e) {
            log.error("ripgrep 搜索失败: fieldName={}, codeBasePath={}", fieldName, codeBasePath, e);
        }

        return results;
    }

    /**
     * 批量搜索多个字段
     *
     * @param fieldNames   字段名列表
     * @param codeBasePath 代码库根路径
     * @return 字段名 → 搜索结果列表的映射
     */
    public Map<String, List<SearchResult>> batchSearchFields(List<String> fieldNames, String codeBasePath) {
        Map<String, List<SearchResult>> resultMap = new HashMap<>();

        for (String fieldName : fieldNames) {
            List<SearchResult> results = searchField(fieldName, null, codeBasePath);
            resultMap.put(fieldName, results);
        }

        return resultMap;
    }

    /**
     * 解析 ripgrep 输出的一行结果
     *
     * @param line ripgrep 输出的一行
     * @return 解析后的 SearchResult
     */
    private SearchResult parseSearchResult(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        try {
            // ripgrep 默认输出格式：文件路径:行号:匹配内容
            // 例如：src/main/java/com/example/Order.java:45:    private String status;
            String[] parts = line.split(":", 3);
            if (parts.length < 3) {
                return null;
            }

            String filePath = parts[0];
            int lineNum = Integer.parseInt(parts[1]);
            String matchText = parts[2].trim();

            return new SearchResult(filePath, lineNum, matchText);

        } catch (Exception e) {
            log.warn("解析 ripgrep 输出失败: {}", line);
            return null;
        }
    }

    /**
     * 搜索结果
     */
    public record SearchResult(String filePath, int lineNum, String matchText) {
    }
}
