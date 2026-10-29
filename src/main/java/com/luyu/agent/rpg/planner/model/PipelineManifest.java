package com.luyu.agent.rpg.planner.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * run 工作区 {@code manifest.yaml} 的内存模型（add-planner-pipeline design D3/D7）。
 * <p>
 * manifest 是文件树内的阶段状态与摘要索引<b>唯一真源</b>：库表 {@code rpg_pipeline_run}
 * 只存列表页要查的 run 元数据，阶段细粒度状态（版本、摘要、返工历史）全部在这里，
 * 由 PipelineRunService 单点写入并同步库表（先写 manifest 后同步库表）。
 * <p>
 * 序列化格式（SnakeYAML dump 的有序 Map，键序即声明序）：
 * <pre>
 * runId: &lt;uuid&gt;
 * name: 冥星武将录拆解
 * sourceMode: text          # SourceMode.code()
 * styleMode: faithful       # StyleMode.code()
 * sourceFile: source.md     # 原始输入文件（text 原文 / ip_name IP 名）
 * sourceUrls:               # 旧 run 扫描网页（读侧兼容，planner-html-source D5；新 run 不写）
 *   - https://example.com/a
 * sourcePages:              # ip_name HTML 资料页名（非空才写，旧 manifest 无此键）
 *   - page-01
 *   - page-02
 * currentStage: S1          # PipelineStage.code()
 * status: CREATED           # PipelineRunStatus.name()
 * createdAt: '2026-10-20T10:00:00'
 * updatedAt: '2026-10-20T10:00:00'
 * stages:
 *   S1: {status: NOT_STARTED, version: 0, summary: null, rounds: [],
 *        confirmedVersion: null, candidates: []}
 *   S3: {status: AWAITING_CONFIRM, version: 2, summary: null, rounds: [...],
 *        confirmedVersion: null, upstreamVersion: 1,
 *        candidates: [{id: candidate-01, title: 人物 1：甲, status: PENDING}, ...],
 *        batches: [{index: 1, names: [严嵩, 乌彩蝶], status: DONE}, ...]}
 * </pre>
 * <p>
 * S3 批次记账（planner-s3-batch-generate D2）：{@code batches} 只在 S3 分批
 * 生成动作（首轮分叉 / 全量生成 / 补一批）触达时写入，单流路径与旧 run 恒无此键
 *（读侧自然空，零迁移）；幂等键为批内人名清单而非行号（S1 名册编辑后行号漂移）。
 * <p>
 * 候选级状态（L2 阶段 S3/S5）与 confirmedVersion（确认存档标记）见
 * {@link StageState}——门操作（tasks 4）写入。
 * <p>
 * D7 上下文传递：{@code summary} 是确认稿摘要行（名称+关键词+一句话概括），
 * 下游阶段 prompt 只注入摘要行而非全文——manifest 只反映 confirmed 版本，
 * 天然隔离被毙候选，防上游未采纳细节污染下游。
 */
public class PipelineManifest {

    private String runId;
    private String name;
    /** SourceMode.code()（text / ip_name） */
    private String sourceMode;
    /** StyleMode.code()（faithful / remix） */
    private String styleMode;
    /** 原始输入文件名（工作区 run 目录内相对路径） */
    private String sourceFile;
    /** ip_name 模式用户指定扫描网页（已退役：读侧兼容旧 run；新 run 恒 null，planner-html-source D8） */
    private List<String> sourceUrls;
    /** ip_name 模式 HTML 资料页名（page-01……与 pages/ 目录文件名序一致；text 模式/旧 run 为 null） */
    private List<String> sourcePages;
    /** PipelineStage.code()（S1..S6） */
    private String currentStage;
    /** PipelineRunStatus.name() */
    private String status;
    private String createdAt;
    private String updatedAt;
    /** 阶段细粒度状态（S1..S6 全量，创建时即初始化） */
    private final LinkedHashMap<String, StageState> stages = new LinkedHashMap<>();

    public PipelineManifest() {
        for (PipelineStage stage : PipelineStage.values()) {
            stages.put(stage.code(), new StageState());
        }
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getStyleMode() {
        return styleMode;
    }

    public void setStyleMode(String styleMode) {
        this.styleMode = styleMode;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public List<String> getSourceUrls() {
        return sourceUrls;
    }

    public void setSourceUrls(List<String> sourceUrls) {
        this.sourceUrls = sourceUrls;
    }

    public List<String> getSourcePages() {
        return sourcePages;
    }

    public void setSourcePages(List<String> sourcePages) {
        this.sourcePages = sourcePages;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 指定阶段的细粒度状态（S 码；未知码抛 IllegalArgumentException 防脏数据静默通过） */
    public StageState stage(String stageCode) {
        StageState state = stages.get(stageCode);
        if (state == null) {
            throw new IllegalArgumentException("manifest 中不存在的阶段: " + stageCode);
        }
        return state;
    }

    /** 全部阶段状态（S1..S6 有序视图，供详情渲染与守卫遍历） */
    public Map<String, StageState> stages() {
        return stages;
    }

    // ==================== 序列化 ====================

    /** 转为 SnakeYAML dump 的有序 Map（键序与文件格式声明一致）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("runId", runId);
        root.put("name", name);
        root.put("sourceMode", sourceMode);
        root.put("styleMode", styleMode);
        root.put("sourceFile", sourceFile);
        // sourceUrls 非空才写：新 run 恒 null（不再写此键）；存量 run 读到什么写回什么
        //（fromMap → toMap 往返保留，阶段状态更新不丢旧 run 的兼容读数据）
        if (sourceUrls != null && !sourceUrls.isEmpty()) {
            root.put("sourceUrls", new ArrayList<>(sourceUrls));
        }
        // sourcePages 非空才写：ip_name 新写侧真源（旧 manifest 无此键，读侧自然忽略）
        if (sourcePages != null && !sourcePages.isEmpty()) {
            root.put("sourcePages", new ArrayList<>(sourcePages));
        }
        root.put("currentStage", currentStage);
        root.put("status", status);
        root.put("createdAt", createdAt);
        root.put("updatedAt", updatedAt);
        Map<String, Object> stagesMap = new LinkedHashMap<>();
        stages.forEach((code, state) -> stagesMap.put(code, state.toMap()));
        root.put("stages", stagesMap);
        return root;
    }

    /**
     * 从 SnakeYAML load 出的 Map 还原。核心字段缺失或类型异常抛
     * {@link IllegalStateException}（manifest 损坏必须显式失败，不能静默当新 run）。
     */
    public static PipelineManifest fromMap(Map<String, Object> raw) {
        PipelineManifest manifest = new PipelineManifest();
        manifest.runId = requireStr(raw, "runId");
        manifest.name = requireStr(raw, "name");
        manifest.sourceMode = requireStr(raw, "sourceMode");
        manifest.styleMode = requireStr(raw, "styleMode");
        manifest.sourceFile = str(raw.get("sourceFile"));
        Object urlsRaw = raw.get("sourceUrls");
        if (urlsRaw instanceof List<?> urlList) {
            for (Object item : urlList) {
                if (item != null) {
                    if (manifest.sourceUrls == null) {
                        manifest.sourceUrls = new ArrayList<>();
                    }
                    manifest.sourceUrls.add(String.valueOf(item));
                }
            }
        }
        Object pagesRaw = raw.get("sourcePages");
        if (pagesRaw instanceof List<?> pageList) {
            for (Object item : pageList) {
                if (item != null) {
                    if (manifest.sourcePages == null) {
                        manifest.sourcePages = new ArrayList<>();
                    }
                    manifest.sourcePages.add(String.valueOf(item));
                }
            }
        }
        manifest.currentStage = requireStr(raw, "currentStage");
        manifest.status = requireStr(raw, "status");
        manifest.createdAt = str(raw.get("createdAt"));
        manifest.updatedAt = str(raw.get("updatedAt"));

        Object stagesRaw = raw.get("stages");
        if (stagesRaw instanceof Map<?, ?> stagesMap) {
            for (PipelineStage stage : PipelineStage.values()) {
                Object stageRaw = stagesMap.get(stage.code());
                if (stageRaw instanceof Map<?, ?> stageMap) {
                    manifest.stages.put(stage.code(), StageState.fromMap(stageMap));
                }
            }
        }
        return manifest;
    }

    private static String requireStr(Map<String, Object> raw, String key) {
        String value = str(raw.get(key));
        if (value == null) {
            throw new IllegalStateException("manifest 缺失核心字段: " + key);
        }
        return value;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intOr(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    // ==================== 阶段状态 ====================

    /**
     * 单阶段细粒度状态（manifest 内）。
     * <p>
     * 状态转换合法性由 {@code PipelineStateMachine} 校验（NOT_STARTED→RUNNING→
     * AWAITING_CONFIRM→CONFIRMED；FAILED/CONFIRMED 仅可显式重入或回退解锁）。
     * {@code version} 是阶段产物版本号（初值 0，首轮生成后为 1，返工/编辑 +1）；
     * {@code rounds} 记录返工反馈历史（L2 门多轮返工的审计线索，D4/D5）；
     * {@code confirmedVersion} 是最后整稿通过时的版本号（null=未确认过；
     * 回退解锁后保留——原确认版本存档标记，历史确认记录不可删除，tasks 4.5）；
     * {@code candidates} 是 L2 候选级状态（S3/S5；L1 阶段恒空）——每轮整轮重生成
     * 后重置 PENDING（D5），被毙候选的 title 记忆在此（候选文件被覆盖后排除表
     * 仍可从 manifest 取）；{@code upstreamVersion} 是本阶段最近一次生成收口时的
     * 上游版本基线（上游解锁后编辑/返工会递增 version，下游据此识别「上游已变」
     * 提示重算；S1 无上游与旧 run 均为 null）；{@code batches} 是 S3 分批生成的
     * 批次进度记账（planner-s3-batch-generate D2：断点续跑真源——重试跳过 DONE
     * 批；幂等键批内人名清单，名册编辑后对不上的批整体重跑）。
     */
    public static class StageState {

        /** PipelineStageStatus.name()（字符串存枚举码，对齐 PipelineRun 惯例） */
        private String status = PipelineStageStatus.NOT_STARTED.name();
        private int version;
        /** 确认稿摘要行（D7；确认后写入，未确认为 null） */
        private String summary;
        private List<ReRound> rounds = new ArrayList<>();
        /** 最后整稿通过时的版本号（null=未确认过；回退解锁后保留存档标记，tasks 4.5） */
        private Integer confirmedVersion;
        /** 最近一次生成收口时的上游版本基线（S1 无上游/旧 run 为 null；上游再变即高于此值） */
        private Integer upstreamVersion;
        /** L2 候选级状态行（tasks 4.1；L1 阶段恒空） */
        private List<CandidateState> candidates = new ArrayList<>();
        /** S3 分批生成批次进度（planner-s3-batch-generate D2；单流路径与旧 run 恒空） */
        private List<BatchState> batches = new ArrayList<>();

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public int getVersion() {
            return version;
        }

        public void setVersion(int version) {
            this.version = version;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public List<ReRound> getRounds() {
            return rounds;
        }

        public void setRounds(List<ReRound> rounds) {
            this.rounds = rounds;
        }

        public Integer getConfirmedVersion() {
            return confirmedVersion;
        }

        public void setConfirmedVersion(Integer confirmedVersion) {
            this.confirmedVersion = confirmedVersion;
        }

        public Integer getUpstreamVersion() {
            return upstreamVersion;
        }

        public void setUpstreamVersion(Integer upstreamVersion) {
            this.upstreamVersion = upstreamVersion;
        }

        public List<CandidateState> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<CandidateState> candidates) {
            this.candidates = candidates == null ? new ArrayList<>() : candidates;
        }

        public List<BatchState> getBatches() {
            return batches;
        }

        public void setBatches(List<BatchState> batches) {
            this.batches = batches == null ? new ArrayList<>() : batches;
        }

        /** 按 id 定位候选状态行（未知 id 返回 null）。 */
        public CandidateState candidate(String candidateId) {
            return candidates.stream()
                    .filter(c -> c.getId().equals(candidateId))
                    .findFirst().orElse(null);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("version", version);
            map.put("summary", summary);
            List<Object> roundsList = new ArrayList<>();
            rounds.forEach(round -> roundsList.add(round.toMap()));
            map.put("rounds", roundsList);
            map.put("confirmedVersion", confirmedVersion);
            // 基线非 null 才写（S1 无上游/旧 run 无此键，读侧自然按 null 处理）
            if (upstreamVersion != null) {
                map.put("upstreamVersion", upstreamVersion);
            }
            List<Object> candidatesList = new ArrayList<>();
            candidates.forEach(c -> candidatesList.add(c.toMap()));
            map.put("candidates", candidatesList);
            // 批次记账非空才写（单流路径与旧 run 无此键，读侧自然按空处理）
            if (!batches.isEmpty()) {
                List<Object> batchesList = new ArrayList<>();
                batches.forEach(b -> batchesList.add(b.toMap()));
                map.put("batches", batchesList);
            }
            return map;
        }

        static StageState fromMap(Map<?, ?> raw) {
            StageState state = new StageState();
            String status = str(raw.get("status"));
            state.status = status == null ? PipelineStageStatus.NOT_STARTED.name() : status;
            state.version = intOr(raw.get("version"), 0);
            state.summary = str(raw.get("summary"));
            Object roundsRaw = raw.get("rounds");
            if (roundsRaw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> roundMap) {
                        state.rounds.add(ReRound.fromMap(roundMap));
                    }
                }
            }
            Object confirmedRaw = raw.get("confirmedVersion");
            state.confirmedVersion = confirmedRaw instanceof Number number
                    ? number.intValue() : null;
            Object upstreamRaw = raw.get("upstreamVersion");
            state.upstreamVersion = upstreamRaw instanceof Number upstreamNumber
                    ? upstreamNumber.intValue() : null;
            Object candidatesRaw = raw.get("candidates");
            if (candidatesRaw instanceof List<?> candidateList) {
                for (Object item : candidateList) {
                    if (item instanceof Map<?, ?> candidateMap) {
                        state.candidates.add(CandidateState.fromMap(candidateMap));
                    }
                }
            }
            Object batchesRaw = raw.get("batches");
            if (batchesRaw instanceof List<?> batchList) {
                for (Object item : batchList) {
                    if (item instanceof Map<?, ?> batchMap) {
                        state.batches.add(BatchState.fromMap(batchMap));
                    }
                }
            }
            return state;
        }
    }

    /**
     * L2 候选级状态行（tasks 4.1，S3/S5）：id 与候选文件名对应（candidate-01），
     * title 供卡片标题与返工排除表（被毙候选的记忆真源——候选文件被返工整轮
     * 覆盖后，排除表仍可从 manifest 取，D5），status 为 DraftItemStatus.name()。
     */
    public static class CandidateState {

        private String id;
        private String title;
        private String status;

        public CandidateState() {
        }

        public CandidateState(String id, String title, String status) {
            this.id = id;
            this.title = title;
            this.status = status;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("title", title);
            map.put("status", status);
            return map;
        }

        static CandidateState fromMap(Map<?, ?> raw) {
            CandidateState candidate = new CandidateState();
            candidate.id = str(raw.get("id"));
            candidate.title = str(raw.get("title"));
            candidate.status = str(raw.get("status"));
            return candidate;
        }
    }

    /**
     * S3 分批生成批次进度行（planner-s3-batch-generate D2，design）。
     * <p>
     * 幂等键是批内人名清单（names）而非行号：S1 名册在 L1 编辑后行号漂移，
     * 重试时以人名清单对账——对不上的批整体重跑（宁重复不漏人）；对得上的
     * DONE 批跳过（断点续跑不重复耗 token）。status：PENDING（待执行）/
     * DONE（已落盘）/ FAILED（执行中断点冻结）。
     */
    public static class BatchState {

        /** 批序号（从 1 起，同一次编排内递增） */
        private int index;
        /** 批内名册人名清单（幂等键；顺序即名册序） */
        private List<String> names = new ArrayList<>();
        /** PENDING / DONE / FAILED（字符串存码，对齐本类惯例） */
        private String status = "PENDING";

        public BatchState() {
        }

        public BatchState(int index, List<String> names, String status) {
            this.index = index;
            this.names = names == null ? new ArrayList<>() : names;
            this.status = status;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public List<String> getNames() {
            return names;
        }

        public void setNames(List<String> names) {
            this.names = names == null ? new ArrayList<>() : names;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("names", new ArrayList<>(names));
            map.put("status", status);
            return map;
        }

        static BatchState fromMap(Map<?, ?> raw) {
            BatchState batch = new BatchState();
            batch.index = intOr(raw.get("index"), 0);
            Object namesRaw = raw.get("names");
            if (namesRaw instanceof List<?> nameList) {
                for (Object item : nameList) {
                    if (item != null) {
                        batch.names.add(String.valueOf(item));
                    }
                }
            }
            String status = str(raw.get("status"));
            batch.status = status == null ? "PENDING" : status;
            return batch;
        }
    }

    /** 一次返工轮记录（D5：反馈文本 + 轮次 + 时间）。 */
    public static class ReRound {

        private int round;
        private String feedback;
        private String at;

        public ReRound() {
        }

        public ReRound(int round, String feedback, String at) {
            this.round = round;
            this.feedback = feedback;
            this.at = at;
        }

        public int getRound() {
            return round;
        }

        public void setRound(int round) {
            this.round = round;
        }

        public String getFeedback() {
            return feedback;
        }

        public void setFeedback(String feedback) {
            this.feedback = feedback;
        }

        public String getAt() {
            return at;
        }

        public void setAt(String at) {
            this.at = at;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("round", round);
            map.put("feedback", feedback);
            map.put("at", at);
            return map;
        }

        static ReRound fromMap(Map<?, ?> raw) {
            ReRound round = new ReRound();
            round.round = intOr(raw.get("round"), 0);
            round.feedback = str(raw.get("feedback"));
            round.at = str(raw.get("at"));
            return round;
        }
    }
}
