package com.luyu.agent.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;

/**
 * 用户认证表初始化器。
 * <p>
 * 应用启动时执行 {@code auth-schema-h2.sql} 创建 {@code users} 表。
 * 参考 {@code RpgSchemaInitializer} 的实现模式，与 Spring AI Session JDBC
 * 的 schema 初始化互不干扰。
 */
@Component
public class AuthSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(AuthSchemaInitializer.class);

    private static final String SCHEMA_LOCATION = "classpath:auth-schema-h2.sql";

    private final DataSource dataSource;
    private final ResourcePatternResolver resourceResolver;

    public AuthSchemaInitializer(DataSource dataSource, ResourcePatternResolver resourceResolver) {
        this.dataSource = dataSource;
        this.resourceResolver = resourceResolver;
    }

    @PostConstruct
    public void initialize() {
        Resource resource = resourceResolver.getResource(SCHEMA_LOCATION);
        if (!resource.exists()) {
            log.warn("认证表 schema 脚本不存在: {}", SCHEMA_LOCATION);
            return;
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(resource);
        populator.setContinueOnError(true);
        populator.execute(dataSource);
        log.info("认证表 schema 初始化完成: {}", SCHEMA_LOCATION);
    }
}
