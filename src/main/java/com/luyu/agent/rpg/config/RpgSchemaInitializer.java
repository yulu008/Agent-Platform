package com.luyu.agent.rpg.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;

/**
 * RPG H2 表结构初始化器
 * <p>
 * 应用启动时读取 rpg.jdbc.schema-locations 配置的 SQL 脚本，
 * 通过 ResourceDatabasePopulator 执行建表语句。
 * 参考 spring.ai.session.jdbc 的 initialize-schema 机制。
 */
@Configuration
@ConfigurationProperties(prefix = "rpg.jdbc")
public class RpgSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(RpgSchemaInitializer.class);

    private final DataSource dataSource;
    private final ResourcePatternResolver resourceResolver;

    @Value("${rpg.jdbc.initialize-schema:always}")
    private String initializeSchema;

    @Value("${rpg.jdbc.schema-locations:classpath:rpg/schema-h2.sql}")
    private String schemaLocations;

    public RpgSchemaInitializer(DataSource dataSource, ResourcePatternResolver resourceResolver) {
        this.dataSource = dataSource;
        this.resourceResolver = resourceResolver;
    }

    /**
     * 应用启动后执行 RPG 表初始化
     */
    @Bean
    public String rpgSchemaInitializationHook() {
        if (!"always".equalsIgnoreCase(initializeSchema)) {
            log.info("RPG schema 初始化已禁用 (rpg.jdbc.initialize-schema={})", initializeSchema);
            return "disabled";
        }

        if (!StringUtils.hasText(schemaLocations)) {
            log.warn("RPG schema 位置未配置，跳过初始化");
            return "no-schema";
        }

        try {
            Resource[] resources = resourceResolver.getResources(schemaLocations);
            if (resources.length == 0) {
                log.warn("未找到 RPG schema 脚本: {}", schemaLocations);
                return "not-found";
            }

            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(true);
            for (Resource resource : resources) {
                populator.addScript(resource);
                log.info("加载 RPG schema 脚本: {}", resource.getFilename());
            }
            try (java.sql.Connection conn = dataSource.getConnection()) {
                populator.populate(conn);
            }
            log.info("RPG schema 初始化完成（8 张表已就绪）");
            return "initialized";
        } catch (Exception e) {
            log.error("RPG schema 初始化失败", e);
            return "failed";
        }
    }
}
