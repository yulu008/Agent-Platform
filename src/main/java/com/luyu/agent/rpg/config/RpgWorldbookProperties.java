package com.luyu.agent.rpg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通用世界书配置（add-rpg-worldbook），绑定 {@code rpg.worldbook} 结构。
 * <p>
 * 前缀沿用顶层 {@code rpg:} 命名空间（同 {@link RpgLorebookProperties}）：RPG 玩法参数
 * 不与 {@code agent:}（模型装配）混根。属性类不加 {@code @Component}，由 {@link RpgToolConfiguration}
 * 的 {@code @EnableConfigurationProperties} 注册。
 */
@ConfigurationProperties(prefix = "rpg.worldbook")
public class RpgWorldbookProperties {

    /**
     * 世界书总开关（默认 true）。
     * <p>
     * 控制关键词触发瞬时注入（引擎侧）与世界书 CRUD/生成端点的可用性（控制层）。
     * 设为 false 即整体停用——回滚路径：表与数据保留不动，只断注入与端点。
     */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
