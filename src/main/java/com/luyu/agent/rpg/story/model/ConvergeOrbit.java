package com.luyu.agent.rpg.story.model;

/**
 * 幕出口收敛轨道（story-graph-authoring design D4：工程性收束）。
 * <p>
 * 幕 k 结束时世界态 SHALL 命中本轨道表中的某一条（轨道闸，finalizeTurn
 * 结算点后引擎求值）；不命中任何轨道 → 下一幕入口物化注入收敛指令。
 * 幕 k+1 的 {@code entryGuard} 即这些轨道的引用（边界契约，design D2）。
 */
public class ConvergeOrbit {

    /** 轨道名（展示与下一幕入口引用键，如「信任线」「怀疑线」） */
    private String orbit;

    /** 轨道守卫（命中判定纯引擎求值并记录进存档事件日志） */
    private GuardClause guard;

    public ConvergeOrbit() {
    }

    public ConvergeOrbit(String orbit, GuardClause guard) {
        this.orbit = orbit;
        this.guard = guard;
    }

    public String getOrbit() { return orbit; }
    public void setOrbit(String orbit) { this.orbit = orbit; }
    public GuardClause getGuard() { return guard; }
    public void setGuard(GuardClause guard) { this.guard = guard; }
}
