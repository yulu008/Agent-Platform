package com.luyu.agent.tenancy;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 租户守卫装配（design D5）：守卫版 JdbcTemplate 作为唯一/主 bean。
 * <p>
 * Boot 的 JdbcTemplate 自动配置以 {@code @ConditionalOnMissingBean(JdbcOperations.class)}
 * 为条件——此处显式定义后自动配置 back off，全部既有消费者注入到的即守卫版；
 * 单测可自行 new 原生 JdbcTemplate 绕过（守卫是防线不是依赖）。
 */
@Configuration
public class TenantGuardConfiguration {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new TenantGuardedJdbcTemplate(dataSource);
    }
}
