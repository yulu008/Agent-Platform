package com.luyu.agent.rpg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RPG 存档级长期记忆（GM 笔记本）配置，绑定 application.yml 中 {@code rpg.memory} 结构。
 * <p>
 * 前缀刻意用顶层 {@code rpg.memory} 而非 {@code agent.rpg.memory}：项目既有的 RPG 配置
 * （{@code rpg.jdbc.*}，见 {@link RpgSchemaInitializer}）已占据顶层 {@code rpg:} 命名空间，
 * 沿用同一根可避免 RPG 配置分裂在两处。{@code agent:} 根在本项目中语义是「模型与 agent 装配」
 * （{@code agent.models.*}），与 RPG 玩法参数无关。
 */
@ConfigurationProperties(prefix = "rpg.memory")
public class RpgMemoryProperties {

    /** 默认提醒间隔：每 5 轮一次 */
    public static final int DEFAULT_REMIND_EVERY_TURNS = 5;

    /**
     * 每 N 轮由引擎向 GM 追加一次「整理笔记」提醒。
     * <p>
     * 设为 0 或负数即完全关闭提醒（GM 仍可自主写入，只是不再有周期性触发）。
     */
    private int remindEveryTurns = DEFAULT_REMIND_EVERY_TURNS;

    public int getRemindEveryTurns() {
        return remindEveryTurns;
    }

    public void setRemindEveryTurns(int remindEveryTurns) {
        this.remindEveryTurns = remindEveryTurns;
    }

    /**
     * 判断给定轮次是否应追加记忆整理提醒。
     * <p>
     * 集中在此处而非调用点做取模，是为了让「{@code <= 0} 表示关闭」这条规则只有一份实现，
     * 同时天然规避 {@code remindEveryTurns == 0} 时的除零异常。
     *
     * @param turn 本轮轮次（从 1 开始）
     * @return true 表示本轮应在增量 prompt 末尾追加提醒
     */
    public boolean shouldRemind(int turn) {
        return remindEveryTurns > 0 && turn > 0 && turn % remindEveryTurns == 0;
    }
}
