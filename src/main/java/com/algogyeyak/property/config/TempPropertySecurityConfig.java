package com.algogyeyak.property.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * [임시] 매물(Property) API 개발/수동 테스트용 시큐리티 설정.
 *
 * - securityMatcher로 /properties/** 요청에만 적용되는 별도 체인으로 스코프를 좁혀서,
 *   B1(이현범)이 merge한 실제 SecurityConfig(anyRequest().authenticated() 체인)와
 *   "any request" 매칭 충돌(UnreachableFilterChainException)이 나지 않게 한다.
 * - @Order(1)로 이 체인을 먼저 평가하게 해서, /properties/**는 여기서 permitAll로 처리되고
 *   나머지 모든 요청은 실제 SecurityConfig의 체인으로 넘어간다.
 * - 매물 CRUD API(등록/조회/수정/삭제) 개발이 끝나고 PR 올리기 직전에 반드시 삭제할 것.
 */
@Configuration
public class TempPropertySecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain tempPropertySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/properties/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }
}
