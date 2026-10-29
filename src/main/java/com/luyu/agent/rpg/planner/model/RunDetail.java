package com.luyu.agent.rpg.planner.model;

import java.util.ArrayList;
import java.util.List;

/**
 * run 详情端点视图（库表元数据 + manifest 阶段细粒度状态的合成渲染）。
 * <p>
 * 支撑跨天恢复（spec planner-pipeline-run「run 跨天持久化与恢复」）：前端拿到
 * 各阶段确认状态、版本、摘要与返工历史后，还原到离开时的阶段与操作能力。
 * 时间戳统一为 ISO 字符串（避免 Jackson LocalDateTime 序列化配置差异）。
 */
public class RunDetail {

    private String id;
    private String name;
    /** text / ip_name */
    private String sourceMode;
    /** faithful / remix */
    private String styleMode;
    /** PipelineRunStatus.name() */
    private String status;
    /** PipelineStage.code()（S1..S6） */
    private String currentStage;
    private String createdAt;
    private String updatedAt;
    /** 原始输入预览（前 200 字符，详情页提醒输入了什么；不回传全文） */
    private String sourcePreview;
    /** ip_name 模式用户指定扫描网页（已退役：读侧兼容旧 run；未提供为 null） */
    private List<String> sourceUrls;
    /** ip_name 模式 HTML 资料页名（page-01……详情页「资料页 N 份」回显，planner-html-source D5；未提供为 null） */
    private List<String> sourcePages;
    /** 六阶段有序视图 */
    private List<StageView> stages = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(String currentStage) {
        this.currentStage = currentStage;
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

    public String getSourcePreview() {
        return sourcePreview;
    }

    public void setSourcePreview(String sourcePreview) {
        this.sourcePreview = sourcePreview;
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

    public List<StageView> getStages() {
        return stages;
    }

    public void setStages(List<StageView> stages) {
        this.stages = stages;
    }

    /** 单阶段渲染项（manifest StageState + PipelineStage 静态属性的合成）。 */
    public static class StageView {

        /** S1..S6 */
        private String code;
        /** 展示标签（如「人物设定」） */
        private String label;
        /** L1 / L2（确认门分级） */
        private String gate;
        /** PipelineStageStatus.name() */
        private String status;
        private int version;
        /** 确认稿摘要行（未确认为 null） */
        private String summary;
        /** 返工反馈历史（L2 门） */
        private List<PipelineManifest.ReRound> rounds = new ArrayList<>();
        /** L2 候选卡片集（S3/S5；L1 阶段恒为空） */
        private List<CandidateView> candidates = new ArrayList<>();
        /**
         * S1 链式提炼派生标记（planner-s1-chained-refinement tasks 3.3）：text 模式
         * 且原文超链式阈值——前端 L1 确认门据此渲染链式返工窗（仅重生汇总轮）；
         * 其余阶段/单流 run 恒 false（非链式 S1 门行为零改动）。
         */
        private boolean chained;
        /**
         * 上游已变重算提示（重算检测）：上游确认稿版本高于本阶段生成基线
         * （生成后上游解锁重编过）——前端渲染「重算本阶段」按钮；旧 run 无
         * 基线/上游未确认/未变时恒 false。
         */
        private boolean upstreamChanged;
        /** 内容上游阶段码（S1/S6 无上游为 null；重算提示文案用） */
        private String upstreamCode;
        /**
         * S3 名册剩余人名数（planner-s3-batch-generate tasks 5.1）：S1 名册人名
         * − S3 已产出未毙卡标题人名——前端「补一批/全量生成」按钮提示与 confirm
         * 预估批数用；非 S3 / S1 无名册（remix / 旧稿）恒 null（按钮不渲染，
         * 补批守卫兑底拒绝）。
         */
        private Integer rosterRemaining;
        /** 批容量（rpg.planner.max-candidates；confirm 预估批数用；非 S3 恒 null） */
        private Integer batchCapacity;

        public boolean isChained() {
            return chained;
        }

        public void setChained(boolean chained) {
            this.chained = chained;
        }

        public boolean isUpstreamChanged() {
            return upstreamChanged;
        }

        public void setUpstreamChanged(boolean upstreamChanged) {
            this.upstreamChanged = upstreamChanged;
        }

        public String getUpstreamCode() {
            return upstreamCode;
        }

        public void setUpstreamCode(String upstreamCode) {
            this.upstreamCode = upstreamCode;
        }

        public Integer getRosterRemaining() {
            return rosterRemaining;
        }

        public void setRosterRemaining(Integer rosterRemaining) {
            this.rosterRemaining = rosterRemaining;
        }

        public Integer getBatchCapacity() {
            return batchCapacity;
        }

        public void setBatchCapacity(Integer batchCapacity) {
            this.batchCapacity = batchCapacity;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getGate() {
            return gate;
        }

        public void setGate(String gate) {
            this.gate = gate;
        }

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

        public List<PipelineManifest.ReRound> getRounds() {
            return rounds;
        }

        public void setRounds(List<PipelineManifest.ReRound> rounds) {
            this.rounds = rounds;
        }

        public List<CandidateView> getCandidates() {
            return candidates;
        }

        public void setCandidates(List<CandidateView> candidates) {
            this.candidates = candidates;
        }
    }

    /**
     * L2 候选卡片渲染项（tasks 6.6，S3/S5）：manifest 候选状态行 + 工作区
     * 候选文件 payload 全文的合成。payload 全量内联（候选数×几百字可控）——
     * 卡片集预览/编辑零额外请求，跨天恢复与实时操作同源。
     * 文件缺失（返工整轮覆盖窗口等）时 payload 为 null，前端只渲染状态行。
     */
    public static class CandidateView {
        /** 候选 id（candidate-01，与候选文件名对应） */
        private String id;
        /** 卡片标题（同时是返工排除表记忆键） */
        private String title;
        /** DraftItemStatus.name()（PENDING/CONFIRMED/REJECTED） */
        private String status;
        /** 候选全文（Markdown；REJECTED 候选也可能有——确认后可改毙） */
        private String payload;

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

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }
    }
}
