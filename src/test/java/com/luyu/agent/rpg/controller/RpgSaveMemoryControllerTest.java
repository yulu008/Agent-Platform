package com.luyu.agent.rpg.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RpgSaveMemoryController} 单元测试。
 * <p>
 * 存档根目录通过 {@code saveDirOf} 接缝替换为 JUnit 临时目录，其余逻辑（frontmatter 序列化、
 * 防穿越校验、MEMORY.md 索引 best-effort 同步）全部走真实实现，因此这些用例同时钉住了
 * {@link com.luyu.agent.service.MemoryFileStore} 在存档 root 下的行为。
 * <p>
 * 重点覆盖两类容易静默失效的语义：
 * <ol>
 *   <li><b>越狱拦截</b>：{@code ../<其他存档>/x.md} 归一化后仍在存档总根内，
 *       只有逐段拒绝 {@code ..} 才拦得住（同 SaveScopedMemoryCallback 的理由）。</li>
 *   <li><b>索引 best-effort</b>：索引写坏了也不能让主操作失败，否则弹窗会显示「保存失败」
 *       而文件其实已落盘，用户再点一次就撞上 409。</li>
 * </ol>
 */
class RpgSaveMemoryControllerTest {

    private static final String GS_A = "gs-aaa";
    private static final String GS_B = "gs-bbb";

    @TempDir
    Path tempRoot;

    private RpgSaveMemoryController controller;

    /** 只替换存档根目录解析，其余行为与被测实现一致 */
    private final class TestController extends RpgSaveMemoryController {
        @Override
        protected Path saveDirOf(String gameStateId) {
            return tempRoot.resolve(gameStateId);
        }
    }

    @BeforeEach
    void setUp() {
        controller = new TestController();
    }

    private Path saveDir(String gameStateId) {
        return tempRoot.resolve(gameStateId);
    }

    private static Map<String, String> body(String file, String name, String description,
                                           String type, String content) {
        Map<String, String> m = new HashMap<>();
        m.put("file", file);
        m.put("name", name);
        m.put("description", description);
        m.put("type", type);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> mapOf(ResponseEntity<Object> response) {
        return (Map<String, String>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> entryAt(List<?> list, int index) {
        return (Map<String, String>) list.get(index);
    }

    // ==================== 创建与 frontmatter 往返 ====================

    @Test
    void 创建记忆后详情读回字段一致() throws Exception {
        ResponseEntity<Object> created = controller.create(GS_A, body("npc_lin.md", "林掌柜",
                "当铺老板，记着那枚玉佩", "npc_memory",
                "事实：PC 典当过一枚家传玉佩。\n**为什么重要：** 是条待回收的线索。"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapOf(created))
                .containsEntry("fileName", "npc_lin.md")
                .containsEntry("name", "林掌柜")
                .containsEntry("description", "当铺老板，记着那枚玉佩")
                .containsEntry("type", "npc_memory");
        assertThat(mapOf(created).get("content"))
                .contains("典当过一枚家传玉佩")
                .contains("**为什么重要：**");

        // 往返一致：详情端点读回的就是创建时返回的那份
        assertThat(mapOf(controller.detail(GS_A, "npc_lin.md"))).isEqualTo(mapOf(created));

        String raw = Files.readString(saveDir(GS_A).resolve("npc_lin.md"));
        assertThat(raw).startsWith("---\n")
                .contains("name: \"林掌柜\"")
                .contains("type: \"npc_memory\"");
    }

    @Test
    void 更新时保留GM写的scope与turn字段() throws Exception {
        Path dir = saveDir(GS_A);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("npc_lin.md"),
                "---\nname: 林掌柜\ndescription: 旧描述\ntype: npc_memory\nscope: npc_lin\nturn: 12\n---\n\n旧正文\n");

        ResponseEntity<Object> updated = controller.update(GS_A,
                body("npc_lin.md", "林掌柜", "新描述", "npc_memory", "新正文"));

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String raw = Files.readString(dir.resolve("npc_lin.md"));
        assertThat(raw)
                .contains("description: \"新描述\"")
                .contains("scope: \"npc_lin\"")
                .contains("turn: \"12\"")
                .contains("新正文")
                .doesNotContain("旧正文");
    }

    @Test
    void 更新不存在的文件时按新建落盘() {
        ResponseEntity<Object> res = controller.update(GS_A,
                body("lore_market.md", "市集传闻", "东市有人在收玉", "world_lore", "正文"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.exists(saveDir(GS_A).resolve("lore_market.md"))).isTrue();
    }

    // ==================== 参数与路径校验 ====================

    @Test
    void 穿越路径与绝对路径被拒绝且不发生任何写入() {
        assertThat(controller.create(GS_A,
                body("../" + GS_B + "/evil.md", "越狱", "", "npc_memory", "x")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.detail(GS_A, "/etc/passwd.md").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.delete(GS_A, "../../memories/x.md").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(Files.exists(saveDir(GS_B))).isFalse();
        assertThat(Files.exists(tempRoot.resolve("evil.md"))).isFalse();
    }

    @Test
    void 非法存档标识被拒绝() {
        assertThat(controller.list("..").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.list("gs/other").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.detail("..", "x.md").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 缺少file或name返回400() {
        assertThat(controller.create(GS_A, body("x.md", "  ", "", "npc_memory", "")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.update(GS_A, body("", "名字", "", "npc_memory", "")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 非md扩展名返回400() {
        // 列表扫描只认 .md，放行其他扩展名会造出「保存成功却看不见」的幽灵条目
        assertThat(controller.create(GS_A, body("note.txt", "名字", "", "npc_memory", "")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 重复创建同名文件返回409() {
        controller.create(GS_A, body("npc_lin.md", "林掌柜", "", "npc_memory", "正文"));

        ResponseEntity<Object> again = controller.create(GS_A,
                body("npc_lin.md", "另一个名字", "", "npc_memory", "另一份正文"));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(Files.exists(saveDir(GS_A).resolve("npc_lin.md"))).isTrue();
    }

    // ==================== 列表与存档隔离 ====================

    @Test
    void 列表仅含本存档的记忆且排除索引文件() {
        controller.create(GS_A, body("a.md", "A 的记忆", "", "world_lore", "a"));
        controller.create(GS_B, body("b.md", "B 的记忆", "", "world_lore", "b"));

        List<?> listA = (List<?>) controller.list(GS_A).getBody();

        // MEMORY.md 已由索引同步写出，但不算记忆条目
        assertThat(listA).hasSize(1);
        assertThat(entryAt(listA, 0))
                .containsEntry("fileName", "a.md")
                .containsEntry("name", "A 的记忆")
                .containsEntry("type", "world_lore");
    }

    @Test
    void 存档目录不存在时列表为空() {
        assertThat((List<?>) controller.list(GS_A).getBody()).isEmpty();
    }

    // ==================== 索引 best-effort 同步 ====================

    @Test
    void 创建后索引补行且删除后索引行移除() throws Exception {
        controller.create(GS_A, body("npc_lin.md", "林掌柜", "当铺老板", "npc_memory", "正文"));

        Path index = saveDir(GS_A).resolve("MEMORY.md");
        assertThat(Files.readString(index)).contains("- [npc_lin](npc_lin.md) — 当铺老板");

        assertThat(controller.delete(GS_A, "npc_lin.md").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(saveDir(GS_A).resolve("npc_lin.md"))).isFalse();
        assertThat(Files.readString(index)).doesNotContain("npc_lin.md");
    }

    @Test
    void 详情与删除对不存在文件返回404() {
        assertThat(controller.detail(GS_A, "nope.md").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.delete(GS_A, "nope.md").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 索引位置不可读写时主操作仍成功() throws Exception {
        // 用目录占住 MEMORY.md 的位置：索引读写必然抛 IOException，主操作必须不受影响
        Path dir = saveDir(GS_A);
        Files.createDirectories(dir.resolve("MEMORY.md"));

        ResponseEntity<Object> created = controller.create(GS_A,
                body("npc_lin.md", "林掌柜", "当铺老板", "npc_memory", "正文"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.exists(dir.resolve("npc_lin.md"))).isTrue();

        assertThat(controller.delete(GS_A, "npc_lin.md").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(Files.exists(dir.resolve("npc_lin.md"))).isFalse();
    }
}
