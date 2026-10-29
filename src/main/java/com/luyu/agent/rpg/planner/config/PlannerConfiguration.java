package com.luyu.agent.rpg.planner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 策划管线装配入口：注册 {@link RpgPlannerProperties}。
 * <p>
 * 管线各服务（run 生命周期 / 阶段生成 / 返工引擎 / 安装器）位于
 * {@code com.luyu.agent.rpg.planner} 各子包，由组件扫描装配；
 * 后续需要 Bean 化的公共件（如单阶段 RUNNING 守卫）在此集中声明。
 */
@Configuration
@EnableConfigurationProperties(RpgPlannerProperties.class)
public class PlannerConfiguration {
}
