package com.algogyeyak.global.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * {@link RequestIdLoggingFilter}를 Spring Security 필터체인과 무관한 일반 서블릿 필터로 등록한다 -
 * SecurityConfig 안에서 addFilterBefore로 걸면 그 체인이 매칭하는 경로에만 적용되지만, 요청 ID는
 * 모든 경로(actuator, h2-console 등 포함)의 로그에 다 붙어야 하고 Security 필터들보다도 먼저
 * 실행돼야 한다.
 */
@Configuration
public class RequestIdFilterConfig {

    @Bean
    public FilterRegistrationBean<RequestIdLoggingFilter> requestIdLoggingFilter() {
        FilterRegistrationBean<RequestIdLoggingFilter> registration = new FilterRegistrationBean<>(new RequestIdLoggingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
