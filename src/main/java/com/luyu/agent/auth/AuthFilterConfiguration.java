package com.luyu.agent.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * JWT 认证过滤器注册。
 * <p>
 * 注册在最高优先级（{@link Ordered#HIGHEST_PRECEDENCE}），确保在所有其他过滤器之前执行。
 */
@Configuration
public class AuthFilterConfiguration {

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtUtil jwtUtil) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new JwtAuthFilter(jwtUtil));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("jwtAuthFilter");
        return registration;
    }
}
