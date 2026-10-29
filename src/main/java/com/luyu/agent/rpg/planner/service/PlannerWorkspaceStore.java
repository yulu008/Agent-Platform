package com.luyu.agent.rpg.planner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.luyu.agent.rpg.planner.model.DraftItemStatus;
import com.luyu.agent.rpg.planner.model.PipelineManifest;
import com.luyu.agent.rpg.planner.model.PipelineStage;
import com.luyu.agent.tenancy.TenantPaths;

/**
 * run 工作区文件树访问层（add-planner-pipeline design D3）。
 * <p>
 * 每个租户的 run 根为 {@code ~/.agent/tenants/<tid>/planner-runs/}（经
 * {@link TenantPaths#plannerRunsDir()} 单一真源），单 run 目录结构：
 * <pre>
 * &lt;runId&gt;/
 *   manifest.yaml   ← 阶段状态与摘要索引真源（本组件读写）
 *   source.md       ← 原始输入（text 原文 / ip_name IP 名）
 *   pages/          ← ip_name 模式 HTML 资料页双文件（planner-html-source D5）
 *   01-ip-analysis.md / 02-world.md / 03-characters/ ... ← 阶段产物（任务 3 写入）
 * </pre>
 * <p>
 * <b>防穿越</b>：runId 是路径段（来自 URL 路径变量），拼路径前必须过白名单校验
 * （{@code [A-Za-z0-9_-]{1,64}}，与 {@code TenantPaths}/{@code RpgSaveMemoryController}
 * 同标准）——否则 {@code ..%2F} 之类值会逃逸出租户 run 根。
 * <p>
 * YAML 序列化用 SnakeYAML（spring-boot-starter 既有 classpath 依赖）：dump 用默认
 * 构造（只输出基本类型 Map/List），load 用 {@link SafeConstructor}（禁任意对象反序列化）。
 * Yaml 实例非线程安全，每次调用现构造（本组件低频调用，无性能问题）。
 * <p>
 * root 以 Supplier 注入：生产环境指向 {@code TenantPaths.plannerRunsDir()}（请求期
 * 读 TenantContext），测试注入临时目录，不写真家目录。
 */
@Component
public class PlannerWorkspaceStore {

    private static final Logger log = LoggerFactory.getLogger(PlannerWorkspaceStore.class);

    /** manifest 文件名（run 工作区根内） */
    public static final String MANIFEST_FILE = "manifest.yaml";

    /** 原始输入文件名（text 原文 / ip_name IP 名） */
    public static final String SOURCE_FILE = "source.md";

    /** ip_name 模式 HTML 资料页子目录（planner-html-source design D5：双文件落盘） */
    public static final String PAGES_DIR = "pages";

    /** 资料页文件名模式（page-01.txt / page-01.html……按序号排序即按页序） */
    private static final Pattern PAGE_FILE = Pattern.compile("^page-(\\d{2})\\.(txt|html)$");

    /** 资料页合并分隔行前缀（S1 虚拟原文页间边界，design D6） */
    static final String PAGE_SEPARATOR_PREFIX = "===== 资料页 ";

    /** 候选文件名模式（candidate-01.md……按序号排序即按生成序，tasks 3.6） */
    private static final Pattern CANDIDATE_FILE = Pattern.compile("^candidate-(\\d{2})\\.md$");

    /** 候选 id 白名单（candidate-01……与文件名去扩展名一致，tasks 4.1 逐卡操作定位） */
    private static final Pattern SAFE_CANDIDATE_ID = Pattern.compile("^candidate-\\d{2}$");

    /** 版本快照子目录（位于阶段产物目录内：02-world/versions/、03-characters/versions/，tasks 4.1） */
    public static final String VERSIONS_DIR = "versions";

    /** 版本快照文件名模式（v01.md……版本号即文件名序，tasks 4.1 版本树） */
    private static final Pattern VERSION_FILE = Pattern.compile("^v(\\d{2,})\\.md$");

    /** runId 白名单：单段安全字符（UUID 恰好符合；防路径穿越） */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final Supplier<Path> plannerRunsRoot;

    public PlannerWorkspaceStore() {
        this(() -> TenantPaths.plannerRunsDir());
    }

    /** 测试用构造：注入自定义 run 根（如 @TempDir）。 */
    PlannerWorkspaceStore(Supplier<Path> plannerRunsRoot) {
        this.plannerRunsRoot = plannerRunsRoot;
    }

    /** 当前租户的指定 run 目录（runId 先过白名单校验，非法抛 IllegalArgumentException）。 */
    public Path runDir(String runId) {
        if (runId == null || !SAFE_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("非法的 run 标识: " + runId);
        }
        return plannerRunsRoot.get().resolve(runId);
    }

    /** manifest 文件路径。 */
    public Path manifestPath(String runId) {
        return runDir(runId).resolve(MANIFEST_FILE);
    }

    // ==================== 初始化与写入 ====================

    /**
     * 初始化 run 工作区目录（建目录 + 写 source.md；planner-html-source D5 写序
     * source.md → pages 双文件 → manifest → 库表行——manifest 挪后由调用方显式
     * {@link #writeManifest}，本方法只负责目录初始化单步）。
     * 失败抛 IllegalStateException——调用方（PipelineRunService）据此中止创建，
     * 库表尚未落行，不产生「库里有 run 但工作区残缺」的脏数据。
     */
    public void initRunDir(String runId, String sourceContent) {
        Path dir = runDir(runId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(SOURCE_FILE),
                    sourceContent == null ? "" : sourceContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("run 工作区初始化失败: " + dir, e);
        }
    }

    /**
     * 写 manifest（全量覆盖）。
     * <p>
     * D3 写序约定：manifest 先写、库表后同步——本方法是 manifest 侧唯一写入口，
     * 由 PipelineRunService 单点编排。
     */
    public void writeManifest(String runId, PipelineManifest manifest) {
        Path path = manifestPath(runId);
        try {
            String yaml = new Yaml().dump(manifest.toMap());
            Files.writeString(path, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("manifest 写入失败: " + path, e);
        }
    }

    /**
     * 写一份 HTML 资料页双文件（planner-html-source design D5）：原始字节落
     * {@code pages/page-NN.html}（用户资产，回查真源）、清洗文本落
     * {@code pages/page-NN.txt}（S1 生成原料）。任一写失败抛
     * IllegalStateException——创建写序「先工作区后库表」，调用方据此中止
     * 创建不产生脏 run（与 initRun 同契约）。文件名服务端生成（防穿越）。
     *
     * @param index 页序号（从 1 起，与 manifest sourcePages 列表序一致）
     */
    public void writeSourcePage(String runId, int index, byte[] htmlBytes, String cleanedText) {
        Path dir = runDir(runId).resolve(PAGES_DIR);
        Path rawPath = dir.resolve(String.format("page-%02d.html", index));
        Path txtPath = dir.resolve(String.format("page-%02d.txt", index));
        try {
            Files.createDirectories(dir);
            Files.write(rawPath, htmlBytes == null ? new byte[0] : htmlBytes);
            Files.writeString(txtPath, cleanedText == null ? "" : cleanedText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("资料页写入失败: " + rawPath, e);
        }
    }

    /**
     * 读全部资料页清洗文本按页序合并（design D6 S1 虚拟原文）：页间以
     * 分隔行「===== 资料页 NN =====」区分（忠实拆解的资料页出处锚点）。
     * 无 pages 目录或零文件返回 null（调用方据此判定「资料页缺失」快速失败）。
     */
    public String readMergedSourcePages(String runId) {
        List<String> texts = readSourcePageTexts(runId);
        if (texts == null || texts.isEmpty()) {
            return null;
        }
        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                merged.append('\n');
            }
            merged.append(PAGE_SEPARATOR_PREFIX).append(String.format("%02d", i + 1)).append(" =====\n");
            merged.append(texts.get(i));
            if (!texts.get(i).endsWith("\n")) {
                merged.append('\n');
            }
        }
        return merged.toString();
    }

    /** 读全部资料页清洗文本（按页号升序；无 pages 目录返回 null）。 */
    List<String> readSourcePageTexts(String runId) {
        Path dir = runDir(runId).resolve(PAGES_DIR);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var files = Files.list(dir)) {
            List<Path> pages = files
                    .filter(p -> isPageFile(p, "txt"))
                    .sorted()
                    .toList();
            List<String> texts = new ArrayList<>(pages.size());
            for (Path p : pages) {
                texts.add(Files.readString(p, StandardCharsets.UTF_8));
            }
            return texts;
        } catch (IOException e) {
            throw new IllegalStateException("资料页读取失败: " + dir, e);
        }
    }

    private static boolean isPageFile(Path path, String extension) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return PAGE_FILE.matcher(name).matches() && name.endsWith("." + extension);
    }

    /**
     * S1 生成的有效原料（planner-html-source design D6，分档与 ip_name 语境统一入口）：
     * <ul>
     *   <li>text 模式：source.md 原文（现状不变）</li>
     *   <li>ip_name 模式：IP 名头（语境锚点）+ pages 合并虚拟原文；
     *       无 pages 落盘（scanUrls 时代存量 run）返回 null——调用方据此
     *       快速失败「资料页缺失，请删除重建」（tasks 4.3 存量防御）</li>
     * </ul>
     */
    public String readS1Material(String runId, String sourceMode) {
        if (!com.luyu.agent.rpg.planner.model.SourceMode.IP_NAME.code().equals(sourceMode)) {
            return readSource(runId);
        }
        String ipName = readSource(runId);
        String merged = readMergedSourcePages(runId);
        if (merged == null || merged.isBlank()) {
            return null;
        }
        return "目标 IP：" + (ipName == null ? "" : ipName.strip()) + "\n\n" + merged;
    }

    // ==================== 删除（planner-run-delete tasks 1.1） ====================

    /**
     * 递归删除整个 run 工作区目录（manifest、原始输入、阶段产物、版本快照、
     * research/notes 断点资料全清——spec「run 硬删除」）。目录不存在静默成功
     * （幂等：重复删除与孤儿目录重清均安全）。单文件删除失败（Windows 文件
     * 锁等）中断整树并抛 IllegalStateException，由调用方
     * （PipelineRunService.deleteRun）降级 warn——库表行已删保证功能面干净，
     * 残留部分下次重删可回收。
     */
    public void deleteRunDir(String runId) {
        Path dir = runDir(runId);
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            // 反序删除：子文件先于父目录（Comparator 全限定写法对齐既有惯例）
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new IllegalStateException("run 工作区文件删除失败: " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("run 工作区目录删除失败: " + dir, e);
        }
        log.info("run 工作区目录已删除: {}", dir);
    }

    // ==================== 阶段产物（tasks 3.1，生成引擎写入） ====================

    /**
     * 写阶段产物全稿（Markdown 阶段：{@code <runId>/<dir>.md}，如 01-ip-analysis.md）。
     * 全量覆盖：上轮全稿由生成器在重生成前读入 prompt（D5），当前版即最新版。
     *
     * @throws IllegalStateException 写入失败（调用方据此置阶段 FAILED）
     */
    public void writeStageArtifact(String runId, PipelineStage stage, String content) {
        Path path = runDir(runId).resolve(stage.dir() + ".md");
        try {
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("阶段产物写入失败: " + path, e);
        }
    }

    /**
     * 读阶段产物全稿（上一轮草稿；不存在返回 null）。
     * 供返工时注入「上轮全稿」（D5）与确认门读稿（任务 4）。
     */
    public String readStageArtifact(String runId, PipelineStage stage) {
        Path path = runDir(runId).resolve(stage.dir() + ".md");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("阶段产物读取失败: " + path, e);
        }
    }

    /**
     * 删除阶段产物全稿（幂等：不存在即 no-op）。S4.5 幕级回退解锁时抹掉
     * 拼接全图——下游衔接跨幕边标记待重拼（story-graph-authoring 2.6：
     * 图失效待下次整稿通过重拼，readStageArtifact 返回 null 即待重拼）。
     */
    public void deleteStageArtifact(String runId, PipelineStage stage) {
        Path path = runDir(runId).resolve(stage.dir() + ".md");
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("阶段产物删除失败: " + path, e);
        }
    }

    // ==================== 候选集产物（tasks 3.6，L2 阶段：S3/S5 每候选一文件） ====================

    /**
     * 写阶段候选集（L2 阶段：{@code <runId>/<dir>/candidate-NN.md}）。
     * 返工重生成是整轮覆盖（D5）：先清空目录内既有候选文件再写入新集——
     * 被毙候选只活在 manifest 状态与返工 prompt 的排除表里，文件树恒为当前版。
     * 文件名即候选 id（candidate-01…任务 4 逐卡操作的稳定标识）。
     *
     * @throws IllegalStateException 写入失败（调用方据此置阶段 FAILED）
     */
    public void writeStageCandidates(String runId, PipelineStage stage, List<String> candidates) {
        Path dir = runDir(runId).resolve(stage.dir());
        try {
            Files.createDirectories(dir);
            // 整轮覆盖：清掉上一轮候选（含上轮多出的尾部序号；只删 candidate-*.md）
            try (var files = Files.list(dir)) {
                for (Path p : files.filter(f -> CANDIDATE_FILE.matcher(fileName(f)).matches()).toList()) {
                    Files.delete(p);
                }
            }
            for (int i = 0; i < candidates.size(); i++) {
                Path path = dir.resolve(String.format("candidate-%02d.md", i + 1));
                Files.writeString(path, candidates.get(i) == null ? "" : candidates.get(i),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("阶段候选集写入失败: " + dir, e);
        }
    }

    /**
     * 追加候选集（planner-s3-batch-generate tasks 2.4：S3 分批产出 append 语义）：
     * id 从目录内既有最大序号顺延（无既有从 1 起），不删除既有候选文件、
     * 不重置既有候选的逐卡状态——与 {@link #writeStageCandidates}（整轮覆盖）
     * 互斥互补：返工走覆盖、分批追加走本方法。
     * 序号解析对多位数字容错（candidate-100 等既有惯例两位正则外的溢出形态，
     * 防 append 时 id 冲突覆盖他人文件）。
     *
     * @return 追加写入的候选 id 列表（与入参候选一一对应，供 manifest 记账对齐）
     * @throws IllegalStateException 写入失败（调用方据此置阶段 FAILED）
     */
    public List<String> appendStageCandidates(String runId, PipelineStage stage, List<String> candidates) {
        Path dir = runDir(runId).resolve(stage.dir());
        try {
            Files.createDirectories(dir);
            int maxIndex = 0;
            try (var files = Files.list(dir)) {
                for (Path p : files.toList()) {
                    String name = fileName(p);
                    if (!name.startsWith("candidate-") || !name.endsWith(".md")) {
                        continue;
                    }
                    try {
                        maxIndex = Math.max(maxIndex,
                                Integer.parseInt(name.substring("candidate-".length(), name.length() - ".md".length())));
                    } catch (NumberFormatException ignored) {
                        // 非纯数字序号形态（防御）：跳过不参与顺延计算
                    }
                }
            }
            List<String> ids = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                String id = String.format("candidate-%02d", maxIndex + 1 + i);
                Files.writeString(dir.resolve(id + ".md"),
                        candidates.get(i) == null ? "" : candidates.get(i),
                        StandardCharsets.UTF_8);
                ids.add(id);
            }
            return ids;
        } catch (IOException e) {
            throw new IllegalStateException("阶段候选集追加写入失败: " + dir, e);
        }
    }

    /**
     * 读阶段候选集（按序号升序 = 生成序；无目录或无候选返回空列表）。
     * 供返工「上轮全稿」聚合（D5）、下游上游确认稿聚合（D7）与确认门逐卡渲染（任务 4）。
     */
    public List<String> readStageCandidates(String runId, PipelineStage stage) {
        Path dir = runDir(runId).resolve(stage.dir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            List<Path> candidates = files
                    .filter(p -> CANDIDATE_FILE.matcher(fileName(p)).matches())
                    .sorted()
                    .toList();
            List<String> contents = new ArrayList<>(candidates.size());
            for (Path p : candidates) {
                contents.add(Files.readString(p, StandardCharsets.UTF_8));
            }
            return contents;
        } catch (IOException e) {
            throw new IllegalStateException("阶段候选集读取失败: " + dir, e);
        }
    }

    // ==================== 链式块笔记（planner-s1-chained-refinement tasks 2.1，S1 text 断点续跑真源） ====================

    /** S1 text 链式提炼块笔记子目录（位于 01-ip-analysis/ 下） */
    public static final String NOTES_DIR = "notes";

    /** 块笔记文件名模式（chunk-01.md、chunk-02.md……序号即块序） */
    private static final Pattern CHUNK_FILE = Pattern.compile("^chunk-(\\d{2,})\\.md$");

    /** 已落盘非空块笔记（index 为块序号，与切块器 Chunk.index 对齐）。 */
    public record ChunkNote(int index, String content) {}

    /**
     * 写一块笔记（{@code 01-ip-analysis/notes/chunk-NN.md}，幂等覆盖写）。
     * 断点续跑以目录内非空文件为准（spec「块级断点续跑」）：失败重试跳过已
     * 落盘块，同块重试覆盖写不产生重复文件。
     *
     * @throws IllegalStateException 写入失败（调用方据此置阶段 FAILED）
     */
    public void writeChunkNote(String runId, int index, String content) {
        Path dir = notesDir(runId);
        Path path = dir.resolve(String.format("chunk-%02d.md", index));
        try {
            Files.createDirectories(dir);
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("块笔记写入失败: " + path, e);
        }
    }

    /**
     * 读单块笔记（不存在返回 null）。供单块状态检查与测试断言。
     */
    public String readChunkNote(String runId, int index) {
        Path path = notesDir(runId).resolve(String.format("chunk-%02d.md", index));
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("块笔记读取失败: " + path, e);
        }
    }

    /**
     * 读全部已落盘且非空的块笔记（序号升序 = 块序；空文件不视为完成——半途
     * 落盘的空块重试时重生成）。无目录返回空列表（旧 run 零迁移：现场切分照
     * 常执行）。供断点续跑序号对齐与汇总轮全量注入。
     */
    public List<ChunkNote> readChunkNotes(String runId) {
        Path dir = notesDir(runId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            List<Path> notes = new ArrayList<>();
            for (Path p : files.toList()) {
                if (CHUNK_FILE.matcher(fileName(p)).matches()) {
                    notes.add(p);
                }
            }
            // 按块序号数字排序（文件名字典序在序号超两位时会错序，对齐 listVersionSnapshots）
            notes.sort(java.util.Comparator.comparingInt(PlannerWorkspaceStore::chunkIndexOf));
            List<ChunkNote> result = new ArrayList<>(notes.size());
            for (Path p : notes) {
                String content = Files.readString(p, StandardCharsets.UTF_8);
                if (!content.isBlank()) {
                    result.add(new ChunkNote(chunkIndexOf(p), content));
                }
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("块笔记读取失败: " + dir, e);
        }
    }

    private Path notesDir(String runId) {
        return runDir(runId).resolve(PipelineStage.S1_IP_ANALYSIS.dir()).resolve(NOTES_DIR);
    }

    private static int chunkIndexOf(Path path) {
        java.util.regex.Matcher matcher = CHUNK_FILE.matcher(fileName(path));
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    // ==================== 版本快照（tasks 4.1，门操作版本树真源） ====================

    /**
     * 写版本快照（{@code <runId>/<dir>/versions/vNN.md}）：每轮生成完成与每次
     * 人工编辑保存时落一份整稿快照（L1 全文 / L2 候选聚合），保留 v1→vN 全历史
     * （spec planner-confirm-gates「版本树与 diff 呈现」；回退解锁的原确认版本
     * 存档也在此目录回看）。文件名 vNN 即版本号，不随当前版覆盖。
     *
     * @throws IllegalStateException 写入失败（调用方据此置阶段 FAILED）
     */
    public void writeVersionSnapshot(String runId, PipelineStage stage, int version, String fullText) {
        Path dir = versionsDir(runId, stage);
        Path path = dir.resolve(String.format("v%02d.md", version));
        try {
            Files.createDirectories(dir);
            Files.writeString(path, fullText == null ? "" : fullText, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("版本快照写入失败: " + path, e);
        }
    }

    /**
     * 读版本快照（不存在返回 null）。
     * 供版本历史回看与相邻版本 diff（tasks 4.6）。
     */
    public String readVersionSnapshot(String runId, PipelineStage stage, int version) {
        Path path = versionsDir(runId, stage).resolve(String.format("v%02d.md", version));
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("版本快照读取失败: " + path, e);
        }
    }

    /**
     * 全部版本号（升序；无快照返回空列表）。
     * 按数字排序（文件名字典序在版本号超两位时会错序）。
     */
    public List<Integer> listVersionSnapshots(String runId, PipelineStage stage) {
        Path dir = versionsDir(runId, stage);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.map(p -> {
                        java.util.regex.Matcher matcher = VERSION_FILE.matcher(fileName(p));
                        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("版本快照目录读取失败: " + dir, e);
        }
    }

    // ==================== 单候选覆盖写（tasks 4.1，L2 逐卡编辑） ====================

    /**
     * 单候选覆盖写（{@code <runId>/<dir>/candidate-NN.md}）：L2 门逐卡编辑落盘
     * （整轮重生成走 writeStageCandidates 整轮覆盖；本方法只改单卡）。
     * candidateId 先过白名单校验（id 来自 URL 路径，防穿越）。
     */
    public void writeCandidateItem(String runId, PipelineStage stage, String candidateId, String content) {
        if (candidateId == null || !SAFE_CANDIDATE_ID.matcher(candidateId).matches()) {
            throw new IllegalArgumentException("非法的候选标识: " + candidateId);
        }
        Path dir = runDir(runId).resolve(stage.dir());
        Path path = dir.resolve(candidateId + ".md");
        try {
            Files.createDirectories(dir);
            Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("候选写入失败: " + path, e);
        }
    }

    /**
     * 读单候选内容（不存在返回 null；id 白名单校验同写侧）。
     * 供 L2 语义全稿聚合（按 manifest 状态过滤，tasks 4.3）与确认门逐卡渲染。
     */
    public String readCandidateItem(String runId, PipelineStage stage, String candidateId) {
        if (candidateId == null || !SAFE_CANDIDATE_ID.matcher(candidateId).matches()) {
            throw new IllegalArgumentException("非法的候选标识: " + candidateId);
        }
        Path path = runDir(runId).resolve(stage.dir()).resolve(candidateId + ".md");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("候选读取失败: " + path, e);
        }
    }

    /**
     * 读阶段语义全稿（L2，tasks 4.3）：按调用方传入的 manifest 候选状态行
     * 过滤被毙候选后按序聚合（D7 下游确认稿天然隔离被毙候选；返工「上轮全稿」
     * 同口径）。文件缺失的候选跳过（manifest 与文件树同写不变式下的防御）。
     * 无有效候选返回 null。
     */
    public String readStageFullText(String runId, PipelineStage stage,
                                    List<PipelineManifest.CandidateState> candidates) {
        List<String> parts = new ArrayList<>(candidates.size());
        for (PipelineManifest.CandidateState candidate : candidates) {
            if (DraftItemStatus.REJECTED.name().equals(candidate.getStatus())) {
                continue;
            }
            String content = readCandidateItem(runId, stage, candidate.getId());
            if (content != null) {
                parts.add(content);
            }
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private Path versionsDir(String runId, PipelineStage stage) {
        return runDir(runId).resolve(stage.dir()).resolve(VERSIONS_DIR);
    }

    private static String fileName(Path path) {
        String name = path.getFileName().toString();
        return name == null ? "" : name;
    }

    // ==================== 读取 ====================

    /**
     * 读 manifest。文件不存在返回 null（run 从未创建过工作区）；
     * YAML 解析失败抛 IllegalStateException（manifest 损坏必须显式失败，
     * 静默当不存在会让状态机在缺失真源的情况下盲目推进）。
     */
    @SuppressWarnings("unchecked")
    public PipelineManifest readManifest(String runId) {
        Path path = manifestPath(runId);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(content);
            if (!(loaded instanceof Map)) {
                throw new IllegalStateException("manifest 结构非法（顶层非映射）: " + path);
            }
            return PipelineManifest.fromMap((Map<String, Object>) loaded);
        } catch (IOException e) {
            throw new IllegalStateException("manifest 读取失败: " + path, e);
        }
    }

    /** 读原始输入（text 原文 / ip_name IP 名）。文件不存在返回 null。 */
    public String readSource(String runId) {
        Path path = runDir(runId).resolve(SOURCE_FILE);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("原始输入读取失败: " + path, e);
        }
    }
}
