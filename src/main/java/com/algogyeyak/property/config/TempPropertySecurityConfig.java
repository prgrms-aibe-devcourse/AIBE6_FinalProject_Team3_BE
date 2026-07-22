package com.algogyeyak.property.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * [임시] 매물(Property) API 개발/수동 테스트용 시큐리티 설정.
 *
 * - /properties/** 경로만 인증 없이 허용, 나머지는 기존처럼 인증 필요.
 * - B1(이현범)이 실제 SecurityConfig(OAuth2/JWT)를 만들면 이 파일은 삭제하고,
 *   실제 인증에서 /properties/**를 인증 필요 경로로 되돌릴 것.
 * - 매물 CRUD API(등록/조회/수정/삭제) 개발이 끝나고 PR 올리기 직전에 반드시 삭제할 것.
 */
@Configuration
public class TempPropertySecurityConfig {

    @Bean
    public SecurityFilterChain tempPropertySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/properties/**").permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }
}
