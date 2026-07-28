package com.algogyeyak.auth.config;

import com.algogyeyak.auth.handler.OAuth2AuthenticationFailureHandler;
import com.algogyeyak.auth.handler.OAuth2AuthenticationSuccessHandler;
import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtAuthenticationFilter;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import com.algogyeyak.auth.oauth.CustomOAuth2UserService;
import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.response.ApiError;
import com.algogyeyak.global.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final CookieAuthorizationRequestRepository cookieAuthorizationRequestRepository;
    private final JwtProvider jwtProvider;
    private final AccessTokenRevocationService accessTokenRevocationService;

    // 이 클래스 내부에서만 쓰는 단순 직렬화 용도라 Boot이 자동 구성하는 ObjectMapper 빈에 의존하지 않는다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/oauth2/**", "/login/**",
                                "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health", "/h2-console/**",
                                "/auth/logout", "/auth/refresh",
                                "/auth/signup", "/auth/login", "/auth/dev-login"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                // H2 콘솔은 내부적으로 프레임(iframe)을 사용하는데, 기본 X-Frame-Options: DENY가 이를 막는다.
                // 로컬 개발 편의를 위한 설정이라 H2 콘솔 경로에 한해 sameOrigin으로 완화한다.
                .headers(headers -> headers.frameOptions(frameOptions -> frameOptions.sameOrigin()))
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestRepository(cookieAuthorizationRequestRepository))
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler)
                )
                // 이 백엔드는 서버 렌더링 보호 페이지 없이 REST 엔드포인트 + OAuth2 로그인 리다이렉트만 제공한다.
                // OAuth2 로그인 관련 경로는 위에서 permitAll이라 이 진입점을 타지 않으므로, 미인증 시 항상
                // 로그인 페이지 리다이렉트 대신 공통 응답 포맷(ApiResponse)의 401을 반환하면 된다.
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) -> {
                            ErrorCode errorCode = resolveAuthFailureErrorCode(request);
                            response.setStatus(errorCode.getStatus().value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            ApiError error = ApiError.of(errorCode.getCode(), errorCode.getMessage());
                            response.getOutputStream().write(
                                    objectMapper.writeValueAsBytes(ApiResponse.failure(error)));
                        })
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, accessTokenRevocationService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // JwtAuthenticationFilter는 인증에 실패해도 예외를 던지지 않고 SecurityContext를 비운 채
    // 다음 필터로 넘기기만 하므로, "왜" 실패했는지(토큰 없음/무효/만료)는 필터가 남겨둔 요청
    // 속성으로만 여기까지 전달된다. 필터가 아무 이유도 남기지 못한 경우(이론상 나머지 코드
    // 경로)를 대비해 UNAUTHORIZED를 기본값으로 둔다.
    private ErrorCode resolveAuthFailureErrorCode(HttpServletRequest request) {
        Object reason = request.getAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE);
        return reason instanceof ErrorCode errorCode ? errorCode : ErrorCode.UNAUTHORIZED;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
