package com.luyu.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * default 租户引导账号配置（multi-tenant-column-isolation 遗留项：design L116「提供一次性引导」）。
 * <p>
 * 存量数据（会话 / RPG 行 / 记忆文件）升级时统一归入 default 租户，但注册接口生成的
 * 租户 ID 是 {@code t-<uuid>}，常规途径注册不出 default 租户账号——故由
 * {@code DefaultTenantBootstrap} 启动时按本配置幂等创建。
 * <p>
 * 默认值仅供本地开发期使用，生产环境必须经环境变量覆盖：
 * {@code AUTH_BOOTSTRAP_EMAIL} / {@code AUTH_BOOTSTRAP_PASSWORD}
 * （与 {@link AuthProperties} 的 secret 同一惯例）。
 */
@ConfigurationProperties(prefix = "auth.bootstrap")
public class AuthBootstrapProperties {

    /** default 租户管理员登录邮箱 */
    private String email = "admin@default.local";

    /** default 租户管理员初始密码（本地默认值，登录后应立即修改） */
    private String password = "123456";

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
