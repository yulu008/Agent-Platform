package com.luyu.agent.tenancy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 静态租户守卫（design D5 双守卫之构建期防线）：扫描 {@code src/main/java} 全部 .java 源码，
 * 识别含 rpg_* 业务表名的 SQL 语句片段，同一语句内不含 {@code tenant_id} 即 fail（报文件与行号）。
 * <p>
 * 实现为词法级扫描而非逐行正则：剥离注释（避免 javadoc 中的表名误报）、提取字符串字面量
 * （SQL 都在字符串里）、以分号切分语句（字符串拼接跨行的 SQL 归入同一语句）。
 * 白名单：语句区间内的注释含 {@code tenant_guard_whitelist} 标记即豁免（DDL/迁移专用）。
 * <p>
 * 定位：防"新代码漏写过滤"（运行时守卫 {@link TenantGuardedJdbcTemplate} 防任何路径漏网，
 * 含动态拼接 SQL）。字符串级启发式足够——目标是把事故从静默变响亮，不是做 SQL 审计器。
 */
class TenantSqlStaticGuardTest {

    /** 与 TenantGuardedJdbcTemplate.GUARDED_TABLES 保持一致的守卫名单（复制以避免守卫类改动自掩护）。 */
    private static final List<String> GUARDED_TABLES = List.of(
            "rpg_world_setting", "rpg_location", "rpg_character_card", "rpg_relationship",
            "rpg_trigger_runtime", "rpg_trigger", "rpg_game_state_snapshot",
            "rpg_game_state", "rpg_event_log");

    /** SQL 语句片段识别：表名前必须紧跟 SQL 关键字（守卫名单数组等裸表名不误报）。 */
    private static final Pattern SQL_FRAGMENT = Pattern.compile(
            "\\b(FROM|INTO|UPDATE|TABLE|JOIN|DELETE)\\s+(rpg_[a-z_]+)", Pattern.CASE_INSENSITIVE);

    @Test
    void 全部rpg表SQL语句均含tenant_id过滤() {
        Path root = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(root))
                .as("源码根目录存在（surefire 工作目录应为项目 basedir）")
                .isTrue();

        List<String> violations = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(files.size()).as("扫描文件数应为正值").isPositive();

        for (Path file : files) {
            scanSource(read(file), file.toString(), violations);
        }
        assertThat(violations)
                .as("静态租户守卫违例（design D5）：涉及 rpg_* 表的 SQL 语句缺 tenant_id")
                .isEmpty();
    }

    @Test
    void 扫描器自检_无过滤SQL被识别() {
        List<String> violations = new ArrayList<>();
        scanSource("jdbcTemplate.update(\"DELETE FROM rpg_game_state WHERE id = ?\", id);",
                "SelfCheck.java", violations);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("SelfCheck.java").contains("rpg_game_state");
    }

    @Test
    void 扫描器自检_含tenant_id与白名单注释豁免() {
        List<String> violations = new ArrayList<>();
        // 有 tenant_id：不报
        scanSource("jdbcTemplate.query(\"SELECT * FROM rpg_game_state WHERE id = ? AND tenant_id = ?\", id, tid());",
                "Ok.java", violations);
        // 白名单注释豁免：不报
        scanSource("// tenant_guard_whitelist: DDL 迁移豁免\n"
                + "jdbcTemplate.execute(\"ALTER TABLE rpg_game_state ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64)\");",
                "Whitelisted.java", violations);
        // 拼接跨行 + 注释中的表名（javadoc）：不报
        scanSource("/** 对应 rpg_event_log 表 */\n"
                + "repo.update(\"DELETE FROM rpg_event_log \" + \"WHERE game_state_id = ? AND tenant_id = ?\", id, tid());",
                "Concat.java", violations);
        assertThat(violations).isEmpty();
    }

    // ---- 扫描器本体 ----

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 词法扫描单个源文件：按语句（分号切分）聚合字符串字面量与区间内注释，识别违例。
     */
    private static void scanSource(String src, String fileName, List<String> violations) {
        int len = src.length();
        int i = 0;
        int line = 1;

        StringBuilder stmtStrings = new StringBuilder();   // 当前语句全部字符串字面量拼接
        List<String> stmtComments = new ArrayList<>();    // 当前语句区间内的注释（白名单判断）
        StringBuilder commentText = new StringBuilder();
        boolean pendingStatementStart = true;
        int stmtStartLine = 1;

        while (i < len) {
            char c = src.charAt(i);
            char next = i + 1 < len ? src.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {            // 行注释
                int end = src.indexOf('\n', i);
                if (end < 0) {
                    end = len;
                }
                commentText.append(src, i, end);
                stmtComments.add(commentText.toString());
                commentText.setLength(0);
                i = end;
                continue;
            }
            if (c == '/' && next == '*') {            // 块注释 / javadoc
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? len : end + 2;
                commentText.append(src, i, end);
                stmtComments.add(commentText.toString());
                commentText.setLength(0);
                line += countChar(src, i, end, '\n');
                i = end;
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < len && src.charAt(i + 2) == '"') {
                // 文本块（"""..."""）
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? len : end + 3;
                if (pendingStatementStart) {
                    stmtStartLine = line;
                    pendingStatementStart = false;
                }
                stmtStrings.append(src, i + 3, Math.max(i + 3, end - 3));
                line += countChar(src, i, end, '\n');
                i = end;
                continue;
            }
            if (c == '"') {                            // 普通字符串字面量
                if (pendingStatementStart) {
                    stmtStartLine = line;
                    pendingStatementStart = false;
                }
                int j = i + 1;
                StringBuilder literal = new StringBuilder();
                while (j < len && src.charAt(j) != '"') {
                    if (src.charAt(j) == '\\' && j + 1 < len) {
                        j++;                            // 跳过转义字符
                    }
                    literal.append(src.charAt(j));
                    j++;
                }
                stmtStrings.append(' ').append(literal);
                i = j + 1;
                continue;
            }
            if (c == '\'') {                            // char 字面量（含转义）
                int j = i + 1;
                while (j < len && src.charAt(j) != '\'') {
                    if (src.charAt(j) == '\\' && j + 1 < len) {
                        j++;
                    }
                    j++;
                }
                i = j + 1;
                continue;
            }
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == ';') {                             // 语句结束 → 判定
                checkStatement(stmtStrings.toString(), stmtComments, fileName, stmtStartLine, violations);
                stmtStrings.setLength(0);
                stmtComments.clear();
                pendingStatementStart = true;
                i++;
                continue;
            }
            if (!Character.isWhitespace(c) && pendingStatementStart) {
                stmtStartLine = line;
                pendingStatementStart = false;
            }
            i++;
        }
        // 文件末尾无分号收尾的语句（不应出现，兜底判定）
        checkStatement(stmtStrings.toString(), stmtComments, fileName, stmtStartLine, violations);
    }

    /**
     * 语句级判定：SQL 片段（关键字 + rpg_* 表名）缺 tenant_id 且无白名单标记 → 违例。
     */
    private static void checkStatement(String sql, List<String> comments, String fileName,
                                       int line, List<String> violations) {
        if (sql.isBlank()) {
            return;
        }
        String lower = sql.toLowerCase();
        boolean touchesGuardedTable = GUARDED_TABLES.stream().anyMatch(lower::contains);
        if (!touchesGuardedTable || lower.contains("tenant_id")) {
            return;
        }
        if (!SQL_FRAGMENT.matcher(sql).find()) {
            return;                                     // 裸表名（如守卫名单数组），非 SQL 语句
        }
        boolean whitelisted = comments.stream().anyMatch(cm -> cm.contains("tenant_guard_whitelist"));
        if (whitelisted) {
            return;
        }
        violations.add(fileName + ":" + line + " SQL 缺 tenant_id 过滤: " + abbreviate(sql));
    }

    private static int countChar(String s, int from, int to, char c) {
        int n = 0;
        for (int k = from; k < to && k < s.length(); k++) {
            if (s.charAt(k) == c) {
                n++;
            }
        }
        return n;
    }

    private static String abbreviate(String sql) {
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }
}
