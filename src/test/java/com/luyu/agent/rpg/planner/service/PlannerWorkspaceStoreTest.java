package com.luyu.agent.rpg.planner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.luyu.agent.rpg.planner.model.DraftItemStatus;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PlannerWorkspaceStore} 的工作区文件树单测（add-planner-pipeline
 * tasks 3.6/4.1/4.3 + planner-html-source tasks 2.4）。
 * <p>
 * 工作区走真实文件（@TempDir 注入测试构造，不写真家目录）。钉住五组契约：
 * <ul>
 *   <li><b>资料页双文件落盘（planner-html-source design D5）</b>：
 *       {@code writeSourcePage} 原始字节落 {@code pages/page-NN.html}
 *       （用户资产回查真源）、清洗文本落 {@code pages/page-NN.txt}（S1
 *       生成原料），序号 {@code %02d} 按文件名排序即页序；</li>
 *   <li><b>合并虚拟原文与原料分派（D6）</b>：页间「===== 资料页 NN =====」
 *       分隔行；无 pages 目录或零 txt 文件返回 null——调用方据此快速失败
 *       「资料页缺失」；{@code readS1Material} text 模式读 source.md 原文
 *       （sourceMode null 防御与 "text" 码同路）、ip_name 模式 = IP 名头
 *       + 合并原文、无 pages 返回 null（scanUrls 时代存量 run 防御）；</li>
 *   <li><b>候选集（tasks 3.6）</b>：{@code candidate-NN.md} 按序号落盘
 *       读取按序；返工重生成整轮覆盖（清掉上轮尾部序号，文件树恒为当前版）；
 *       无目录读取返回空列表；</li>
 *   <li><b>版本快照与单候选（tasks 4.1）</b>：{@code versions/vNN.md}
 *       保留 v1→vN 全历史不随当前版覆盖、版本号列表按数字升序；单候选
 *       覆盖写只改指定卡；候选 id 白名单（URL 路径变量防穿越）；</li>
 *   <li><b>语义全稿（tasks 4.3）</b>：按 manifest 候选状态过滤被毙候选
 *       后按序聚合（D7 下游确认稿隔离被毙候选）；文件缺失候选跳过；
 *       无有效候选返回 null。</li>
 * </ul>
 */
class PlannerWorkspaceStoreTest {

    private static final String RUN_ID = "run-store-1";

    private static final byte[] HTML_BYTES =
            "<html><body><h1>三体</h1></body></html>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private PlannerWorkspaceStore store;

    @BeforeEach
    void setUp() {
        store = new PlannerWorkspaceStore(() -> tempDir);
    }

    // ===== 资料页双文件落盘（planner-html-source D5） =====

    @Test
    void 资料页双文件落盘原始字节与清洗文本分置() throws IOException {
        store.writeSourcePage(RUN_ID, 1, HTML_BYTES, "# 三体");

        Path pages = tempDir.resolve(RUN_ID).resolve("pages");
        // 原始字节原样落盘（用户资产，回查真源）——字节级精确断言
        assertThat(Files.readAllBytes(pages.resolve("page-01.html"))).isEqualTo(HTML_BYTES);
        // 清洗文本 UTF-8 落盘（S1 生成原料）
        assertThat(Files.readString(pages.resolve("page-01.txt"), StandardCharsets.UTF_8))
                .isEqualTo("# 三体");
    }

    @Test
    void 多资料页按序号落盘读取按页序合并() {
        store.writeSourcePage(RUN_ID, 1, HTML_BYTES, "文本一");
        store.writeSourcePage(RUN_ID, 2, HTML_BYTES, "文本二");

        // 页间分隔行（design D6）：块级边界双换行，末页文本不足换行补齐
        assertThat(store.readMergedSourcePages(RUN_ID))
                .isEqualTo("===== 资料页 01 =====\n文本一\n\n===== 资料页 02 =====\n文本二\n");
    }

    @Test
    void 无pages目录合并读取返回null() {
        // 「资料页缺失」信号：调用方（StageGenerationService）据此快速失败
        assertThat(store.readMergedSourcePages(RUN_ID)).isNull();
    }

    @Test
    void pages目录仅html无txt合并读取返回null() throws IOException {
        // 半残防御：双文件写序被外因打断（仅 html 落盘）——txt 面零文件同样判缺失
        Path pages = tempDir.resolve(RUN_ID).resolve("pages");
        Files.createDirectories(pages);
        Files.write(pages.resolve("page-01.html"), HTML_BYTES);

        assertThat(store.readMergedSourcePages(RUN_ID)).isNull();
    }

    // ===== readS1Material 分派（planner-html-source D6） =====

    @Test
    void text模式原料读source原文() {
        store.initRunDir(RUN_ID, "原文内容");

        // sourceMode 传 null（manifest 未设的防御形态）与 "text" 码均走 readSource
        assertThat(store.readS1Material(RUN_ID, null)).isEqualTo("原文内容");
        assertThat(store.readS1Material(RUN_ID, "text")).isEqualTo("原文内容");
    }

    @Test
    void ipName模式无资料页原料返回null() {
        // scanUrls 时代存量 run 防御（tasks 4.3）：无 pages 落盘 → null
        store.initRunDir(RUN_ID, "三体");

        assertThat(store.readS1Material(RUN_ID, "ip_name")).isNull();
    }

    @Test
    void ipName模式原料为IP名头加资料页合并文本() {
        // source.md 存 IP 名（strip 后进头行），原料 = 语境锚点头 + 合并虚拟原文
        store.initRunDir(RUN_ID, "  三体  ");
        store.writeSourcePage(RUN_ID, 1, HTML_BYTES, "# 三体\n\n面壁计划");

        assertThat(store.readS1Material(RUN_ID, "ip_name"))
                .isEqualTo("目标 IP：三体\n\n===== 资料页 01 =====\n# 三体\n\n面壁计划\n");
    }

    // ===== 防穿越 =====

    @Test
    void writeSourcePage非法runId拼路径前拒绝() {
        assertThatThrownBy(() -> store.writeSourcePage("../evil", 1, HTML_BYTES, "文本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法的 run 标识");
    }

    // ===== 候选集（add-planner-pipeline tasks 3.6） =====

    @Test
    void 候选集写入按序号落盘读取按序往返() {
        store.writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("人物一", "人物二", "人物三"));

        // 文件名即候选 id（candidate-01…逐卡操作稳定标识），按序号升序 = 生成序
        assertThat(store.readStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS))
                .containsExactly("人物一", "人物二", "人物三");
        assertThat(tempDir.resolve(RUN_ID).resolve("03-characters").resolve("candidate-02.md"))
                .exists();
    }

    @Test
    void 候选集返工重生成整轮覆盖清掉上轮尾部() {
        store.writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("上轮一", "上轮二", "上轮三"));
        // 返工重生成是新集（上轮 3 候选 → 本轮 2）：旧候选文件清空，文件树恒为当前版
        store.writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("本轮一", "本轮二"));

        assertThat(store.readStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS))
                .containsExactly("本轮一", "本轮二");
        assertThat(tempDir.resolve(RUN_ID).resolve("03-characters").resolve("candidate-03.md"))
                .doesNotExist();
    }

    @Test
    void 无候选目录读取返回空列表() {
        assertThat(store.readStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS)).isEmpty();
    }

    // ===== 版本快照（tasks 4.1） =====

    @Test
    void 版本快照保留全历史读取往返不随当前版覆盖() {
        store.writeVersionSnapshot(RUN_ID, PipelineStage.S2_WORLD_SETTING, 1, "世界 v1");
        store.writeVersionSnapshot(RUN_ID, PipelineStage.S2_WORLD_SETTING, 2, "世界 v2");

        // v1→vN 全历史保留（回退解锁的原确认版本存档回看）
        assertThat(store.readVersionSnapshot(RUN_ID, PipelineStage.S2_WORLD_SETTING, 1))
                .isEqualTo("世界 v1");
        assertThat(store.readVersionSnapshot(RUN_ID, PipelineStage.S2_WORLD_SETTING, 2))
                .isEqualTo("世界 v2");
        // 不存在的版本返回 null
        assertThat(store.readVersionSnapshot(RUN_ID, PipelineStage.S2_WORLD_SETTING, 3)).isNull();
    }

    @Test
    void 版本号列表按数字升序无快照返回空列表() {
        assertThat(store.listVersionSnapshots(RUN_ID, PipelineStage.S2_WORLD_SETTING)).isEmpty();

        // 文件名字典序在版本号超两位时会错序（v10 < v2），按数字排序对齐
        for (int v : new int[]{2, 10, 1}) {
            store.writeVersionSnapshot(RUN_ID, PipelineStage.S2_WORLD_SETTING, v, "稿 " + v);
        }
        assertThat(store.listVersionSnapshots(RUN_ID, PipelineStage.S2_WORLD_SETTING))
                .containsExactly(1, 2, 10);
    }

    // ===== 单候选覆盖写（tasks 4.1） =====

    @Test
    void 单候选覆盖写只改指定卡其余不动() {
        store.writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("卡一原", "卡二原", "卡三原"));

        // L2 门逐卡编辑落盘：整轮重生成走 writeStageCandidates，本方法只改单卡
        store.writeCandidateItem(RUN_ID, PipelineStage.S3_CHARACTERS, "candidate-02", "卡二改");

        assertThat(store.readCandidateItem(RUN_ID, PipelineStage.S3_CHARACTERS, "candidate-02"))
                .isEqualTo("卡二改");
        assertThat(store.readCandidateItem(RUN_ID, PipelineStage.S3_CHARACTERS, "candidate-01"))
                .isEqualTo("卡一原");
    }

    @Test
    void 非法候选标识写读双侧白名单拒绝() {
        // id 来自 URL 路径（防穿越）；一位数不符合 candidate-NN 两位模式
        assertThatThrownBy(() -> store.writeCandidateItem(
                RUN_ID, PipelineStage.S3_CHARACTERS, "../evil", "内容"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法的候选标识");
        assertThatThrownBy(() -> store.readCandidateItem(
                RUN_ID, PipelineStage.S3_CHARACTERS, "candidate-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法的候选标识");
    }

    @Test
    void 单候选读取不存在返回null() {
        assertThat(store.readCandidateItem(RUN_ID, PipelineStage.S3_CHARACTERS, "candidate-01"))
                .isNull();
    }

    // ===== 候选追加（planner-s3-batch-generate tasks 2.4，spec「候选追加不重置」） =====

    @Test
    void 候选追加落盘id顺延既有卡不动() {
        store.writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("## 人物 1：甲", "## 人物 2：乙"));

        List<String> ids = store.appendStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("## 人物 3：丙", "## 人物 4：丁"));

        // id 从既有最大序号顺延（与 manifest 候选行一一对应）
        assertThat(ids).containsExactly("candidate-03", "candidate-04");
        // 读取含追加卡（生成序）；既有卡内容不动（追加不覆盖整轮）
        assertThat(store.readStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS))
                .containsExactly("## 人物 1：甲", "## 人物 2：乙", "## 人物 3：丙", "## 人物 4：丁");
        assertThat(store.readCandidateItem(RUN_ID, PipelineStage.S3_CHARACTERS, "candidate-01"))
                .isEqualTo("## 人物 1：甲");
    }

    @Test
    void 候选追加序号按数字容差多位数顺延() throws IOException {
        // 预置 100 号卡（长名册多批场景）：顺延按数字序而非文件名字典序
        Path dir = tempDir.resolve(RUN_ID).resolve(PipelineStage.S3_CHARACTERS.dir());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("candidate-100.md"), "## 人物 100：百号");

        assertThat(store.appendStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("## 人物 101：新卡")))
                .containsExactly("candidate-101");
        assertThat(dir.resolve("candidate-101.md")).exists();
    }

    // ===== 语义全稿聚合（tasks 4.3） =====

    @Test
    void 语义全稿聚合过滤被毙候选按序拼接() {
        store.writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS,
                List.of("候选一", "候选二", "候选三"));

        // 02 被毙：D7 下游确认稿天然隔离被毙候选（返工「上轮全稿」同口径）
        String fullText = store.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS, List.of(
                new PipelineManifest.CandidateState("candidate-01", "甲", DraftItemStatus.PENDING.name()),
                new PipelineManifest.CandidateState("candidate-02", "乙", DraftItemStatus.REJECTED.name()),
                new PipelineManifest.CandidateState("candidate-03", "丙", DraftItemStatus.CONFIRMED.name())));

        assertThat(fullText).isEqualTo("候选一\n\n候选三");
    }

    @Test
    void 语义全稿跳过文件缺失候选无有效部分返回null() {
        // manifest 有 id 但文件缺失（防御：跳过不炸）
        String partial = store.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS, List.of(
                new PipelineManifest.CandidateState("candidate-01", "甲", DraftItemStatus.PENDING.name()),
                new PipelineManifest.CandidateState("candidate-02", "乙", DraftItemStatus.PENDING.name())));
        // 零文件 → 无有效部分 null
        assertThat(partial).isNull();

        // 只有 candidate-01 落盘：跳过缺失的 02，仅聚合存在的
        store.writeCandidateItem(RUN_ID, PipelineStage.S3_CHARACTERS, "candidate-01", "仅此一份");
        assertThat(store.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS, List.of(
                new PipelineManifest.CandidateState("candidate-01", "甲", DraftItemStatus.PENDING.name()),
                new PipelineManifest.CandidateState("candidate-02", "乙", DraftItemStatus.PENDING.name()))))
                .isEqualTo("仅此一份");
    }

    @Test
    void 语义全稿全被毙返回null() {
        store.writeStageCandidates(RUN_ID, PipelineStage.S3_CHARACTERS, List.of("候选一", "候选二"));

        assertThat(store.readStageFullText(RUN_ID, PipelineStage.S3_CHARACTERS, List.of(
                new PipelineManifest.CandidateState("candidate-01", "甲", DraftItemStatus.REJECTED.name()),
                new PipelineManifest.CandidateState("candidate-02", "乙", DraftItemStatus.REJECTED.name()))))
                .isNull();
    }
}
