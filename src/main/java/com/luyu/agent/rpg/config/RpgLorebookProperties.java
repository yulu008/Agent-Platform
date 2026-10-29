package com.luyu.agent.rpg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 设定集配置（star-lorebook），绑定 {@code rpg.lorebook} 结构。
 * <p>
 * 前缀沿用顶层 {@code rpg:} 命名空间（同 {@link RpgMemoryProperties} 的取舍理由）：
 * RPG 玩法参数不与 {@code agent:}（模型装配）混根。
 */
@ConfigurationProperties(prefix = "rpg.lorebook")
public class RpgLorebookProperties {

    /**
     * 设定集总开关（默认 true）。
     * <p>
     * 控制关键词触发扫描与「设定集·已揭示」注入（引擎侧）以及名录只读端点的可用性
     * （控制层）。设为 false 即整体停用——回滚路径：表与数据保留不动，只断注入。
     * 开局抢星的 runtime 落档不在此开关管辖（那是存档级不变量，非注入行为）。
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
