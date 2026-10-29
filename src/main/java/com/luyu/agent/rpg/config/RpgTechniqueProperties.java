package com.luyu.agent.rpg.config;

import com.luyu.agent.rpg.model.TechniqueProficiency;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 招式技能树配置，绑定 application.yml 中 {@code rpg.technique} 结构。
 * <p>
 * 前缀沿用顶层 {@code rpg:} 根（同 {@link RpgMemoryProperties}），避免 RPG 配置分裂在两处。
 * 属性类自身不加 {@code @Component}，由 {@link RpgToolConfiguration} 的
 * {@code @EnableConfigurationProperties} 注册 —— 与 {@code AgentModelsProperties} 的做法一致。
 * <p>
 * {@code defaultUnlockThreshold} 在本变更（{@code rpg-technique-tree-data}）只用于渲染
 * 「需 X≥400」的文案；真正的领悟门禁判定由 {@code rpg-technique-gate} 消费同一配置，
 * 提前定义是为了让两个变更共用一个调参入口。
 */
@ConfigurationProperties(prefix = "rpg.technique")
public class RpgTechniqueProperties {

    /** 默认起手熟练度（精英档）：400 = 小成 */
    public static final int DEFAULT_INITIAL_PROFICIENCY_ELITE = 400;

    /** 默认起手熟练度（新人档）：200 = 入门 */
    public static final int DEFAULT_INITIAL_PROFICIENCY_ROOKIE = 200;

    /** 默认解锁阈值：400 = 前置须练到小成 */
    public static final int DEFAULT_UNLOCK_THRESHOLD = 400;

    /**
     * 精英档星阶（{@code Gold} / {@code Specter-TianGang} / {@code GodWarrior}）建档时，
     * 全部根节点的初始熟练度。
     * <p>
     * 叙事依据：黄金圣斗士是成熟战士，起手已通本系根基；天罡冥斗士如拉达曼迪斯、
     * 神斗士七人「出手即杀招」，同档。
     */
    private int initialProficiencyElite = DEFAULT_INITIAL_PROFICIENCY_ELITE;

    /**
     * 新人档星阶（{@code Silver} / {@code Bronze} / {@code Hidden} / {@code Specter-DiSha}）
     * 建档时，抽中那 1 个根节点的初始熟练度。
     * <p>
     * 叙事依据：青铜/白银是成长期主角，星矢开局只有天马流星拳且火候尚浅。
     */
    private int initialProficiencyRookie = DEFAULT_INITIAL_PROFICIENCY_ROOKIE;

    /**
     * 领悟后继招式时，前置招式需达到的熟练度阈值。
     * <p>
     * 调低→进阶更快、成长感更密集但树的分层被稀释；调高→每层都要打磨许久，
     * 配合 {@code rpg.technique.max-gain-per-turn} 决定「多少轮能悟一招」。
     */
    private int defaultUnlockThreshold = DEFAULT_UNLOCK_THRESHOLD;

    public int getInitialProficiencyElite() {
        return initialProficiencyElite;
    }

    public void setInitialProficiencyElite(int initialProficiencyElite) {
        this.initialProficiencyElite = TechniqueProficiency.clamp(initialProficiencyElite);
    }

    public int getInitialProficiencyRookie() {
        return initialProficiencyRookie;
    }

    public void setInitialProficiencyRookie(int initialProficiencyRookie) {
        this.initialProficiencyRookie = TechniqueProficiency.clamp(initialProficiencyRookie);
    }

    public int getDefaultUnlockThreshold() {
        return defaultUnlockThreshold;
    }

    public void setDefaultUnlockThreshold(int defaultUnlockThreshold) {
        this.defaultUnlockThreshold = TechniqueProficiency.clamp(defaultUnlockThreshold);
    }
}
