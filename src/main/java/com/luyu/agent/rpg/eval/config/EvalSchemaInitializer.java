package com.luyu.agent.rpg.eval.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * 评估表 H2 结构初始化器（content-eval tasks 1.1）。
 * <p>
 * 两段时机（各对齐既有先例）：
 * <ol>
 *   <li><b>容器初始化期</b>（{@code @Bean} 钩子）：执行
 *       {@code classpath:rpg/eval-schema-h2.sql} 建 rpg_eval_run /
 *       rpg_eval_record 两表——机制与 {@code MeteringSchemaInitializer} 一致：
 *       {@link ResourceDatabasePopulator} 直连 Connection（不经
 *       {@code TenantGuardedJdbcTemplate}，DDL 天然豁免租户守卫），
 *       {@code continueOnError=true} + {@code CREATE TABLE IF NOT EXISTS}
 *       保证幂等重跑，早于任何运行时评估写入。</li>
 *   <li><b>ApplicationReady 期</b>（{@link #addEventPromptHashColumn}）：为
 *       {@code AI_SESSION_EVENT} 加 NULLABLE 列 {@code prompt_hash}——该表由
 *       spring-ai-session 的 initialize-schema 创建，容器期尚不存在，必须监听
 *       {@link ApplicationReadyEvent} 保证建表之后执行（对齐
 *       {@code AiSessionTenantBackfill} 的时机惯例）；幂等
 *       {@code ADD COLUMN IF NOT EXISTS}，失败记 error 不阻断启动
 *       （H2 单文件库可备份重试）。存量事件保持 NULL 不迁移（spec「存量
 *       事件 NULL 容忍」：动态层评估按 metering 时间关联拼近似指纹）。</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(EvalSchemaInitializer.class);

    private static final String SCHEMA_LOCATION = "classpath:rpg/eval-schema-h2.sql";

    /** AI_SESSION_EVENT 加列 DDL（幂等；spring-ai-session 建表后才可执行） */
    private static final String ADD_EVENT_HASH_DDL =
            "ALTER TABLE AI_SESSION_EVENT ADD COLUMN IF NOT EXISTS prompt_hash VARCHAR(16)";

    private final DataSource dataSource;
    private final ResourcePatternResolver resourceResolver;

    public EvalSchemaInitializer(DataSource dataSource, ResourcePatternResolver resourceResolver) {
        this.dataSource = dataSource;
        this.resourceResolver = resourceResolver;
    }

    @Bean
    public String evalSchemaInitializationHook() {
        try {
            Resource[] resources = resourceResolver.getResources(SCHEMA_LOCATION);
            if (resources.length == 0) {
                log.warn("未找到评估 schema 脚本: {}", SCHEMA_LOCATION);
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
            log.info("评估 schema 初始化完成（rpg_eval_run / rpg_eval_record 2 张表已就绪）");
            return "initialized";
        } catch (Exception e) {
            log.error("评估 schema 初始化失败", e);
            return "failed";
        }
    }

    /**
     * AI_SESSION_EVENT 加 prompt_hash 列（版本指纹附着，tasks 2.x 的落库前提）。
     * <p>
     * 幂等可重跑；失败不阻断启动（重启重试即可，列缺失只影响新事件带 hash，
     * 不影响任何既有读写路径——存量读取方全部防御 NULL）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void addEventPromptHashColumn() {
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(ADD_EVENT_HASH_DDL);
            log.info("AI_SESSION_EVENT.prompt_hash 列已就绪（幂等）");
        } catch (Exception e) {
            log.error("AI_SESSION_EVENT 加列失败（不影响启动，可重启重试）", e);
        }
    }
}
