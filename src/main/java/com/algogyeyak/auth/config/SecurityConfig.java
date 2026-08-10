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
import com.algogyeyak.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
    private final UserRepository userRepository;

    // 이 클래스 내부에서만 쓰는 단순 직렬화 용도라 Boot이 자동 구성하는 ObjectMapper 빈에 의존하지 않는다.
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    // CORS_ALLOWED_ORIGINS가 ""/공백/","처럼 값은 있지만 파싱 후 남는 origin이 없는 경우를 막는다 -
    // fail-fast(placeholder 필수화)는 env 자체가 없는 경우만 잡아주고, 이런 값은 그대로 통과시켜
    // CORS 설정이 빈 리스트로 조용히 뜬 채 모든 브라우저 인증 요청이 CORS 차단으로 실패하게 만든다.
    @PostConstruct
    void validateAllowedOrigins() {
        if (parseAllowedOrigins().isEmpty()) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins(CORS_ALLOWED_ORIGINS)에 유효한 origin이 하나도 없습니다: \"" + allowedOrigins + "\"");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CorsFilter *뒤에* 둬야 한다 - 앞에 두면 이 필터가 403으로 요청을 끊을 때 CorsFilter가
                // 아직 Access-Control-Allow-Origin 등을 응답에 붙이기 전이라, 브라우저가 그 403 응답을
                // CORS 위반으로 취급해 JS에서 아예 "Failed to fetch"로만 보이고 실제 403/에러 바디를
                // 읽을 수 없게 된다(실제 배포에서 원인 진단이 어려워짐 - 차단 자체는 어느 순서든 동일).
                .addFilterAfter(new CsrfHeaderFilter(parseAllowedOrigins()),
                        org.springframework.web.filter.CorsFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/oauth2/**", "/login/**",
                                "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health", "/h2-console/**",
                                "/auth/logout", "/auth/refresh",
                                "/auth/signup", "/auth/login", "/auth/dev-login",
                                "/auth/password-policy"
                        ).permitAll()
                        // 회원가입 화면(로그인 전)에서도 닉네임 중복 확인을 호출하므로 인증을 요구하지 않는다.
                        // 로그인된 사용자가 호출하면(프로필 수정 화면) UserController가 여전히 본인 제외
                        // 검사를 하도록 처리한다 - permitAll은 인증 여부만 면제할 뿐, 인증 정보 자체가
                        // 없어지는 건 아니다.
                        .requestMatchers("/users/nickname-check").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // Prometheus
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated()
                )
                // H2 콘솔은 내부적으로 프레임(iframe)을 사용하는데, 기본 X-Frame-Options: DENY가 이를 막는다.
                // frameOptions()로 전역 완화하면 실제로는 REST API 응답 전체가 sameOrigin으로 풀려버려
                // (이 앱은 서버 렌더링 HTML이 없어 위험은 낮지만) 의도("H2 콘솔 경로에 한해")와 실제
                // 동작이 어긋난다 - DelegatingRequestMatcherHeaderWriter로 /h2-console/** 요청에만
                // SAMEORIGIN을 적용하고, 나머지 모든 응답은 기본값인 DENY를 그대로 유지한다.
                .headers(headers -> headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                        PathPatternRequestMatcher.pathPattern("/h2-console/**"),
                        new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN))))
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
                            writeErrorResponse(response, errorCode);
                        })
                        // 명시적으로 지정하지 않으면 인증은 됐지만 권한이 부족한 요청(/admin/** 등
                        // hasRole 매처에 걸리는 경우)이 Boot의 기본 /error 포워드를 거치면서 우리
                        // 공통 응답 포맷이 아닌 응답이 나가거나, 상황에 따라 401로 잘못 보이는 등
                        // 일관성이 없었다 - FORBIDDEN(403)으로 명확히 고정한다.
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeErrorResponse(response, ErrorCode.FORBIDDEN))
                )
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, accessTokenRevocationService, userRepository), UsernamePasswordAuthenticationFilter.class);

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

    private void writeErrorResponse(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = ApiError.of(errorCode.getCode(), errorCode.getMessage());
        response.getOutputStream().write(objectMapper.writeValueAsBytes(ApiResponse.failure(error)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    // CORS(corsConfigurationSource)와 CsrfHeaderFilter의 Origin 폴백이 같은 origin 목록을 봐야
    // 하는데, 예전엔 각자 allowedOrigins.split(",")를 따로 처리해서 trim 여부가 어긋났다 -
    // "https://a.com, https://b.com"처럼 콤마 뒤에 공백을 넣는 흔한 표기에서, 한쪽은 " https://b.com"
    // (공백 포함, 매칭 안 됨)으로 등록되고 다른 쪽은 trim된 값으로 등록되는 식으로 갈라질 수 있었다.
    // 파싱을 여기 한 곳으로 모아 둘 다 항상 같은 리스트를 쓰게 한다.
    private List<String> parseAllowedOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .map(SecurityConfig::stripTrailingSlash)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

    // 브라우저가 보내는 Origin 헤더는 절대 트레일링 슬래시를 붙이지 않는다(스킴+호스트+포트뿐,
    // 경로가 없다) - CORS_ALLOWED_ORIGINS에 "https://app.example.com/"처럼 슬래시를 붙이는 흔한
    // 오타가 있으면, 값 자체는 비어있지 않으니 fail-fast에도 안 걸리면서 모든 정상 Origin과
    // 영원히 매칭되지 않아 CORS/CSRF가 전부 막히는 조용한 전체 장애가 된다.
    private static String stripTrailingSlash(String origin) {
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }
}
