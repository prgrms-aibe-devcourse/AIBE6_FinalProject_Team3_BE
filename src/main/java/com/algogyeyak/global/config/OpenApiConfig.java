package com.algogyeyak.global.config;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import org.springframework.context.annotation.Configuration;

// 브라우저 클라이언트는 httpOnly 쿠키(access_token/refresh_token)로 인증하지만,
// JwtAuthenticationFilter.resolveToken은 Authorization: Bearer 헤더도 동일하게 지원한다
// (헤더 우선, 없으면 쿠키 폴백 — Postman/Swagger 등 쿠키를 다루기 번거로운 클라이언트를 위한 경로).
// 세 가지 스킴을 모두 등록해 실제 지원 범위와 문서를 일치시킨다.
@Configuration
@SecuritySchemes({
        @SecurityScheme(
                name = "access_token",
                type = SecuritySchemeType.APIKEY,
                in = SecuritySchemeIn.COOKIE,
                paramName = "access_token"
        ),
        @SecurityScheme(
                name = "refresh_token",
                type = SecuritySchemeType.APIKEY,
                in = SecuritySchemeIn.COOKIE,
                paramName = "refresh_token"
        ),
        @SecurityScheme(
                name = "bearerAuth",
                type = SecuritySchemeType.HTTP,
                scheme = "bearer",
                bearerFormat = "JWT"
        )
})
public class OpenApiConfig {
}
