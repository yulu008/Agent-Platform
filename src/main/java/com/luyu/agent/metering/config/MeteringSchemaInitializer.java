package com.luyu.agent.metering.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 计量表 H2 结构初始化器（tenant-token-metering）。
 * <p>
 * 应用启动时执行 {@code classpath:metering/schema-h2.sql} 建 4 张计量表
 * （tenant_usage_daily / token_usage_log / tenant_quota_policy / pricing_policy），
 * 机制与 {@code RpgSchemaInitializer} 一致：{@link ResourceDatabasePopulator} 直连 Connection，
 * 不经 {@code TenantGuardedJdbcTemplate}，故 DDL 天然豁免租户守卫。
 * <p>
 * 以 {@code @Bean} 钩子在容器初始化期完成建表，早于任何运行时计量写入；
 * {@code continueOnError=true} + {@code CREATE TABLE IF NOT EXISTS} 保证幂等重跑。
 */
@Configuration
@EnableConfigurationProperties(MeteringProperties.class)
public class MeteringSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(MeteringSchemaInitializer.class);

    private static final String SCHEMA_LOCATION = "classpath:metering/schema-h2.sql";

    private final DataSource dataSource;
    private final ResourcePatternResolver resourceResolver;

    public MeteringSchemaInitializer(DataSource dataSource, ResourcePatternResolver resourceResolver) {
        this.dataSource = dataSource;
        this.resourceResolver = resourceResolver;
    }

    @Bean
    public String meteringSchemaInitializationHook() {
        try {
            Resource[] resources = resourceResolver.getResources(SCHEMA_LOCATION);
            if (resources.length == 0) {
                log.warn("未找到计量 schema 脚本: {}", SCHEMA_LOCATION);
                return "not-found";
            }
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(true);
            for (Resource resource : resources) {
                populator.addScript(resource);
            }
            try (java.sql.Connection conn = dataSource.getConnection()) {
                populator.populate(conn);
            }
            log.info("计量 schema 初始化完成（4 张表已就绪）");
            return "initialized";
        } catch (Exception e) {
            log.error("计量 schema 初始化失败", e);
            return "failed";
        }
    }
}
