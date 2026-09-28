package com.luyu.agent.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内容审查配置属性，绑定 application.yml 中 {@code moderation} 结构。
 * <p>
 * {@code enabled=false} 时闸门整体旁路（直接放行），用于一键回滚到无审查旧行为（design D5）。
 */
@ConfigurationProperties(prefix = "moderation")
public class ModerationProperties {

    /** 审查总开关，默认开启。 */
    private boolean enabled = true;

    /** 敏感词库资源位置，支持 classpath: 与 file: 前缀。 */
    private String wordlistLocation = "classpath:moderation/sensitive-words.txt";

    /** 命中拒答时返回给用户的统一话术（不透露具体命中类别）。 */
    private String refusalMessage = "抱歉，您的输入包含不适宜的内容，我无法处理。请调整后重试。";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWordlistLocation() {
        return wordlistLocation;
    }

    public void setWordlistLocation(String wordlistLocation) {
        this.wordlistLocation = wordlistLocation;
    }

    public String getRefusalMessage() {
        return refusalMessage;
    }

    public void setRefusalMessage(String refusalMessage) {
        this.refusalMessage = refusalMessage;
    }
}
