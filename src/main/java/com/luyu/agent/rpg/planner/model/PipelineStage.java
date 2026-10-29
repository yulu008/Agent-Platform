package com.luyu.agent.rpg.planner.model;

/**
 * 管线阶段（add-planner-pipeline design D2，story-graph-authoring D9 插入 S4.5）。
 * <p>
 * 阶段顺序由代码状态机决定而非模型自驱（D1）；每个阶段携带
 * 工作区目录名（产物物化位置）、确认门分级（L1 编辑门 / L2 返工门）
 * 与展示标签。世界观与人物拆为两个阶段——对齐创作者确认节奏
 * （「今天世界观、明天人物」），gate 划分跟着确认节奏走。
 * <p>
 * S4.5 是结构层插入阶段（story-graph-authoring spec：S4 整稿通过解锁、
 * S4.5 未通过阻塞 S5），产物为逐幕骨架候选（每幕一候选文件）；
 * 生成走幕级专用编排而非通用 generateStage（同 S6 不经生成端点先例）。
 * <b>内容上游（{@link #upstream()}）与顺序前驱（{@link #prev()}分离</b>：
 * S5 的注入上游仍锚定 S4 剧情结构（spec「S5 及后续阶段的输入输出不受
 * 影响」），S4.5 只改顺序拓扑不改内容依赖。
 */
public enum PipelineStage {

    /** S1 IP 拆解：text 模式多轮提炼 / ip_name 模式受限搜索循环 */
    S1_IP_ANALYSIS("S1", "01-ip-analysis", GateLevel.L1, "IP 拆解"),

    /** S2 世界观：世界观表单 + 通用轨世界书条目草稿 */
    S2_WORLD_SETTING("S2", "02-world", GateLevel.L1, "世界观设定"),

    /** S3 人物：候选级草稿集（人物卡 + 命定档案），L2 返工门 */
    S3_CHARACTERS("S3", "03-characters", GateLevel.L2, "人物设定"),

    /** S4 剧情结构：主线大纲 + 叙事规则草稿（S4.5 幕大纲的直接上游） */
    S4_PLOT_STRUCTURE("S4", "04-plot", GateLevel.L1, "剧情结构"),

    /** S4.5 故事图骨架：逐幕生成小契约 JSON（幕级候选，L2 返工门；D2 分幕生成单位） */
    S4P5_STORY_GRAPH("S4.5", "04.5-story-graph", GateLevel.L2, "故事图骨架"),

    /** S5 分支设计：候选级草稿集（触发词/注入内容/轨位/优先级），L2 返工门 */
    S5_BRANCH_DESIGN("S5", "05-branches", GateLevel.L2, "分支设计"),

    /** S6 成稿安装：全包只读预览 + install 物化 */
    S6_INSTALL("S6", "06-final", GateLevel.L1, "成稿安装");

    private final String code;
    private final String dir;
    private final GateLevel gate;
    private final String label;

    PipelineStage(String code, String dir, GateLevel gate, String label) {
        this.code = code;
        this.dir = dir;
        this.gate = gate;
        this.label = label;
    }

    /** 库表/manifest 中存储的阶段码（S1..S6） */
    public String code() {
        return code;
    }

    /** 工作区文件树内的产物目录名 */
    public String dir() {
        return dir;
    }

    /** 确认门分级 */
    public GateLevel gate() {
        return gate;
    }

    /** 展示标签 */
    public String label() {
        return label;
    }

    /** 下一阶段；S6 为末阶段返回 null */
    public PipelineStage next() {
        PipelineStage[] values = values();
        int idx = ordinal() + 1;
        return idx < values.length ? values[idx] : null;
    }

    /** 上一阶段（顺序前驱：状态机推进与 currentStep 回退定位）；S1 为首阶段返回 null */
    public PipelineStage prev() {
        int idx = ordinal() - 1;
        return idx >= 0 ? values()[idx] : null;
    }

    /**
     * 内容上游（D7 直接上游确认稿注入来源）：默认顺序前驱；S5 显式锚定 S4——
     * S4.5 是结构层插入阶段（纯骨架 JSON），S5 的语义输入仍是剧情结构确认稿
     * （story-graph-generation spec「S5 及后续阶段的输入输出不受影响」）。
     */
    public PipelineStage upstream() {
        if (this == S5_BRANCH_DESIGN) {
            return S4_PLOT_STRUCTURE;
        }
        return prev();
    }

    /** 按 S 码解析；未知码抛 IllegalArgumentException（防脏数据静默通过） */
    public static PipelineStage fromCode(String code) {
        for (PipelineStage stage : values()) {
            if (stage.code.equals(code)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("未知的管线阶段码: " + code);
    }

    /** 确认门分级（L1 编辑门 / L2 返工门，design D4） */
    public enum GateLevel {
        /** 编辑门：草稿可改 + 整稿通过 */
        L1,
        /** 返工门：候选级状态 + 多轮反馈返工 + 版本树 */
        L2
    }
}
