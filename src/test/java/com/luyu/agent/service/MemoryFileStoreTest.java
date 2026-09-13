package com.luyu.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemoryFileStore} 单元测试。
 * <p>
 * 本组件被全局记忆（{@code MemoryService}，root={@code ~/.agent/memories}）与存档级 GM 笔记本
 * （{@code RpgSaveMemoryController}，root={@code ~/.agent/rpg-saves/<gameStateId>}）共用，
 * 因此这里钉住两类契约：
 * <ol>
 *   <li><b>返回结构不变</b>：列表条目为 fileName/name/description/type，详情多一个 content ——
 *       重构自 {@code MemoryService} 时 memory.html 与 {@code /api/memories} 必须零感知。</li>
 *   <li><b>路径安全</b>：逐段拒绝 {@code ..}、绝对路径与空段，同时允许子目录与 {@code ./} 前缀。</li>
 * </ol>
 */
class MemoryFileStoreTest {

    @TempDir
    Path root;

    private MemoryFileStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryFileStore(root);
    }

    private static String frontmatter(String name, String description, String type, String body) {
        return "---\nname: " + name + "\ndescription: " + description
                + "\ntype: " + type + "\n---\n\n" + body + "\n";
    }

    // ==================== 返回结构契约 ====================

    @Test
    void 列表条目含四个元数据字段且排除索引文件() throws Exception {
        Files.writeString(root.resolve("a.md"), frontmatter("甲", "描述甲", "npc_memory", "正文甲"));
        Files.writeString(root.resolve("MEMORY.md"), "- [a](a.md) — 描述甲\n");

        List<Map<String, String>> list = store.list();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).keySet())
                .containsExactly("fileName", "name", "description", "type");
        assertThat(list.get(0))
                .containsEntry("fileName", "a.md")
                .containsEntry("name", "甲")
                .containsEntry("description", "描述甲")
                .containsEntry("type", "npc_memory");
    }

    @Test
    void 子目录文件的fileName用正斜杠相对路径() throws Exception {
        Path nested = root.resolve("npc");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("lin.md"), frontmatter("林掌柜", "当铺老板", "npc_memory", "正文"));

        assertThat(store.list()).singleElement()
                .satisfies(meta -> assertThat(meta).containsEntry("fileName", "npc/lin.md"));
        assertThat(store.read("npc/lin.md")).containsEntry("name", "林掌柜");
    }

    @Test
    void 详情比列表多一个content字段() throws Exception {
        Files.writeString(root.resolve("a.md"), frontmatter("甲", "描述甲", "world_lore", "正文甲"));

        Map<String, String> detail = store.read("a.md");

        assertThat(detail.keySet())
                .containsExactly("fileName", "name", "description", "type", "content");
        assertThat(detail).containsEntry("content", "正文甲");
    }

    @Test
    void 无frontmatter的文件整体作为正文() throws Exception {
        Files.writeString(root.resolve("plain.md"), "只是随手记的一段话");

        Map<String, String> detail = store.read("plain.md");

        assertThat(detail)
                .containsEntry("content", "只是随手记的一段话")
                .containsEntry("name", "");
    }

    @Test
    void 读取不存在或非法路径返回null() {
        assertThat(store.read("nope.md")).isNull();
        assertThat(store.read("../outside.md")).isNull();
        assertThat(store.read("  ")).isNull();
        assertThat(store.read(null)).isNull();
    }

    @Test
    void root不存在时列表为空且不抛异常() {
        MemoryFileStore missing = new MemoryFileStore(root.resolve("not-created"));

        assertThat(missing.list()).isEmpty();
    }

    // ==================== 写入与索引 ====================

    @Test
    void 写入生成frontmatter加正文并可回读() throws Exception {
        Path written = store.write("lore_market.md", "市集传闻", "东市有人在收玉",
                "world_lore", "第一段\n第二段");

        assertThat(Files.readString(written))
                .startsWith("---\n")
                .contains("name: \"市集传闻\"")
                .contains("description: \"东市有人在收玉\"")
                .contains("type: \"world_lore\"")
                .contains("第一段\n第二段");
        assertThat(store.read("lore_market.md"))
                .containsEntry("name", "市集传闻")
                .containsEntry("type", "world_lore");
    }

    @Test
    void 写入自动创建子目录并拒绝非md扩展名() throws Exception {
        store.write("sub/deep/x.md", "深层记忆", "", "world_lore", "正文");
        assertThat(Files.exists(root.resolve("sub/deep/x.md"))).isTrue();
        assertThat(store.list()).singleElement()
                .satisfies(meta -> assertThat(meta).containsEntry("fileName", "sub/deep/x.md"));

        assertThatThrownBy(() -> store.write("note.txt", "名字", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".md");
        assertThatThrownBy(() -> store.write("../evil.md", "名字", "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 值里的引号与冒号不破坏frontmatter往返() throws Exception {
        store.write("tricky.md", "带\"引号\"的名字", "描述: 含冒号 # 与井号", "npc_memory", "正文");

        assertThat(store.read("tricky.md"))
                .containsEntry("name", "带\"引号\"的名字")
                .containsEntry("description", "描述: 含冒号 # 与井号");
    }

    @Test
    void 换行被折叠为单行以保住frontmatter格式() throws Exception {
        store.write("multiline.md", "第一行\n第二行", "描述", "npc_memory", "正文");

        assertThat(store.read("multiline.md")).containsEntry("name", "第一行 第二行");
    }

    @Test
    void 索引追加幂等且移除只删匹配行() throws Exception {
        store.appendIndexLine("a.md", "描述甲");
        store.appendIndexLine("b.md", "描述乙");
        // 重复追加同一文件不应产生第二行
        store.appendIndexLine("a.md", "描述甲改");

        Path index = root.resolve(MemoryFileStore.INDEX_FILE);
        assertThat(Files.readAllLines(index)).containsExactly(
                "- [a](a.md) — 描述甲",
                "- [b](b.md) — 描述乙");

        assertThat(store.removeIndexLine("a.md")).isTrue();
        assertThat(Files.readAllLines(index)).containsExactly("- [b](b.md) — 描述乙");
        assertThat(store.removeIndexLine("a.md")).isFalse();
    }

    @Test
    void 索引文件不存在时移除返回false() throws Exception {
        assertThat(store.removeIndexLine("a.md")).isFalse();

        store.appendIndexLine("a.md", "");
        // 无描述时不写多余的破折号
        assertThat(Files.readAllLines(root.resolve(MemoryFileStore.INDEX_FILE)))
                .containsExactly("- [a](a.md)");
    }

    // ==================== 路径安全 ====================

    @Test
    void 上跳段绝对路径与空段被拒绝() {
        assertThat(store.resolveSafe("../other/x.md")).isNull();
        assertThat(store.resolveSafe("sub/../../x.md")).isNull();
        assertThat(store.resolveSafe("/etc/passwd.md")).isNull();
        assertThat(store.resolveSafe("sub//x.md")).isNull();
        assertThat(store.resolveSafe("")).isNull();
        assertThat(store.resolveSafe(".")).isNull();
        // 盘符形式在 Windows 上是绝对路径会被拒；在 POSIX 下它只是个普通子目录名，
        // 两种平台都必须保证结果不越出 root
        Path drive = store.resolveSafe("C:/windows/x.md");
        assertThat(drive == null || drive.startsWith(store.root())).isTrue();
    }

    @Test
    void 点斜杠前缀与子目录被放行() {
        assertThat(store.resolveSafe("./a.md")).isEqualTo(root.resolve("a.md"));
        assertThat(store.resolveSafe("sub/a.md")).isEqualTo(root.resolve("sub/a.md"));
        assertThat(store.resolveSafe("a.md")).isEqualTo(root.resolve("a.md"));
    }

    @Test
    void 反斜杠写法同样受校验() {
        assertThat(store.resolveSafe("..\\other\\x.md")).isNull();
        assertThat(store.resolveSafe("sub\\a.md")).isEqualTo(root.resolve("sub/a.md"));
    }

    @Test
    void 删除对不存在文件返回false且对已存在文件生效() throws Exception {
        assertThat(store.delete("nope.md")).isFalse();
        assertThat(store.delete("../outside.md")).isFalse();

        Files.writeString(root.resolve("a.md"), frontmatter("甲", "", "", "正文"));
        assertThat(store.delete("a.md")).isTrue();
        assertThat(Files.exists(root.resolve("a.md"))).isFalse();
        assertThat(store.exists("a.md")).isFalse();
    }

    @Test
    void 确保root存在是幂等的() {
        Path target = root.resolve("created");
        MemoryFileStore fresh = new MemoryFileStore(target);

        fresh.ensureRootExists();
        fresh.ensureRootExists();

        assertThat(Files.isDirectory(target)).isTrue();
    }
}
