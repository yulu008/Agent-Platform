package com.luyu.agent.rpg.planner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 策划管线配置（前缀 rpg.planner，add-planner-pipeline design D8/D11）。
 * <p>
 * {@code enabled=false} 即整体停用（对齐 worldbook/lorebook 开关惯例）：
 * 表与工作区文件保留不动，只断管线端点。
 */
@ConfigurationProperties(prefix = "rpg.planner")
public class RpgPlannerProperties {

    /** 管线总开关（默认 true） */
    private boolean enabled = true;

    /**
     * S3 批容量 / S5 候选数量上限（超出由 prompt 约束截断，防上下文膨胀）。
     * <p>
     * 语义随 planner-s3-batch-generate 转变：S3 分批生成时为<b>每批容量</b>（名册
     * 按序切块，总候选数由批数累计不受总上限截断；批间以已产出人名索引防重），
     * 存量 run 行为无突变（下次动作用新语义）；S5 分支候选总数上限语义不变。
     */
    private int maxCandidates = 20;

    /**
     * S3 批间并行度（planner-s3-batch-generate D3，默认 2）：多批 LLM 调用合并
     * 单 SSE 流的并行路数。置 1 即串行回退（配置级回滚路径）；无界并行有
     * planner 模型 API 并发与配额双风险，上限置 1-3 为宜。
     */
    private int batchConcurrency = 2;

    /**
     * S3 批 LLM 流瞬时网络故障自动重试次数（默认 1）：流中断（读空闲超时 / 连接
     * 重置等 IOException 族，run 5ffd6d5b 批 10 okhttp timeout 杀流的生产教训）
     * 时原批重发，重试耗尽才走失败冻结（已成功批次保留）。置 0 即回退旧行为
     * （配置级回滚路径）。业务性错误（切卡失败 / 全重名 / 配额超限）不重试。
     */
    private int batchNetworkRetries = 1;

    /**
     * ip_name 模式创建时可上传的 HTML 资料页数量上限（超出 400，防 prompt 膨胀；
     * scanUrls 时代 scan-url-limit 的语义接替，planner-html-source design D2）
     */
    private int maxSourcePages = 5;

    /** ip_name 模式单份 HTML 资料页字节上限（默认 2MB，超出 413） */
    private long maxPageBytes = 2 * 1024 * 1024;

    // ---- S1 text 链式提炼（planner-s1-chained-refinement design D2/D7）----

    /**
     * S1 text 链式提炼触发阈值（字符数，默认 1 万）：≤ 阈值维持单流直塞（零增量），
     * > 阈值走「分块 → 逐块笔记 → 汇总」链式。调超大值即整体回退单流（配置级回滚）。
     */
    private int chainThresholdChars = 10_000;

    /** 单块字数上限（默认 1.2 万）：`#` 章超限章内二次切分、无结构兜底切块的粒度 */
    private int chunkMaxChars = 12_000;

    /** text 原文总字数硬上限（默认 50 万）：创建时超出 400 拒绝，防极端 payload/落盘/moderation 扫描 */
    private int maxSourceChars = 500_000;

    /**
     * 汇总轮笔记总量防御上限（默认 10 万）：超出置 FAILED 报可读错误引导拆 run，
     * 不静默截断（按 10:1 笔记压缩比推算 50 万字原文 ≈ 5 万字笔记，理论不可达）。
     */
    private int notesTotalMaxChars = 100_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxCandidates() {
        return maxCandidates;
    }

    public void setMaxCandidates(int maxCandidates) {
        this.maxCandidates = maxCandidates;
    }

    public int getBatchConcurrency() {
        return batchConcurrency;
    }

    public void setBatchConcurrency(int batchConcurrency) {
        this.batchConcurrency = batchConcurrency;
    }

    public int getBatchNetworkRetries() {
        return batchNetworkRetries;
    }

    public void setBatchNetworkRetries(int batchNetworkRetries) {
        this.batchNetworkRetries = batchNetworkRetries;
    }

    public int getMaxSourcePages() {
        return maxSourcePages;
    }

    public void setMaxSourcePages(int maxSourcePages) {
        this.maxSourcePages = maxSourcePages;
    }

    public long getMaxPageBytes() {
        return maxPageBytes;
    }

    public void setMaxPageBytes(long maxPageBytes) {
        this.maxPageBytes = maxPageBytes;
    }

    public int getChainThresholdChars() {
        return chainThresholdChars;
    }

    public void setChainThresholdChars(int chainThresholdChars) {
        this.chainThresholdChars = chainThresholdChars;
    }

    public int getChunkMaxChars() {
        return chunkMaxChars;
    }

    public void setChunkMaxChars(int chunkMaxChars) {
        this.chunkMaxChars = chunkMaxChars;
    }

    public int getMaxSourceChars() {
        return maxSourceChars;
    }

    public void setMaxSourceChars(int maxSourceChars) {
        this.maxSourceChars = maxSourceChars;
    }

    public int getNotesTotalMaxChars() {
        return notesTotalMaxChars;
    }

    public void setNotesTotalMaxChars(int notesTotalMaxChars) {
        this.notesTotalMaxChars = notesTotalMaxChars;
    }
}
