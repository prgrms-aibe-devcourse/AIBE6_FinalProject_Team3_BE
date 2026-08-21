package com.algogyeyak.auth.config;

import com.algogyeyak.auth.handler.OAuth2AuthenticationFailureHandler;
import com.algogyeyak.auth.handler.OAuth2AuthenticationSuccessHandler;
import com.algogyeyak.auth.jwt.AccessTokenRevocationService;
import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.jwt.UserAuthStatusCacheService;
import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import com.algogyeyak.auth.oauth.CustomOAuth2UserService;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// CORS_ALLOWED_ORIGINS는 env 자체가 없으면 application-prod.yml의 placeholder fail-fast로 이미
// 막힌다. 하지만 ""/공백/","처럼 값은 있지만 파싱 후 남는 origin이 없는 경우는 그 검사를 그대로
// 통과해버려, SecurityConfig.validateAllowedOrigins()(@PostConstruct)가 실제로 이 경우를 잡아내는지
// 별도로 검증한다. AuthControllerDevLoginStartupTest와 동일하게 ApplicationContextRunner로 실제
// 컨텍스트를 띄워 "@PostConstruct가 기동 시점에 정말 호출된다"는 계약까지 확인한다.
class SecurityConfigAllowedOriginsStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            // app.metrics.scrape-token은 이 클래스의 다른 @PostConstruct(validateMetricsScrapeToken)가
            // 검사하는 별개 속성이다 - 여기서 검증하려는 게 allowed-origins 하나뿐임을 분명히 하기
            // 위해 유효한 값으로 고정해, 컨텍스트 실패가 오직 allowed-origins 때문임을 보장한다.
            .withPropertyValues("app.metrics.scrape-token=test-scrape-token")
            .withBean(SecurityConfig.class, () -> new SecurityConfig(
                    mock(CustomOAuth2UserService.class),
                    mock(OAuth2AuthenticationSuccessHandler.class),
                    mock(OAuth2AuthenticationFailureHandler.class),
                    mock(CookieAuthorizationRequestRepository.class),
                    mock(JwtProvider.class),
                    mock(AccessTokenRevocationService.class),
                    mock(UserRepository.class),
                    mock(UserAuthStatusCacheService.class)));

    @Test
    void failsToStartWhenAllowedOriginsIsBlank() {
        contextRunner
                .withPropertyValues("app.cors.allowed-origins=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWhenAllowedOriginsIsOnlyCommas() {
        contextRunner
                .withPropertyValues("app.cors.allowed-origins=, , ,")
                .run(context -> assertThat(context).hasFailed());
    }

    // 정상 케이스(유효한 origin 최소 1개)로 기동에 성공하는지는 이 테스트 클래스에서 검증하지 않는다 -
    // SecurityConfig는 @EnableWebSecurity가 있어 filterChain 빈이 ClientRegistrationRepository 등
    // 전체 Spring Security OAuth2 인프라를 요구하는데, 이 클래스가 쓰는 가벼운
    // ApplicationContextRunner(non-web, mock 의존성)로는 그 인프라를 갖출 수 없다. 정상 기동 자체는
    // 이미 애플리케이션 전체를 띄우는 다른 통합 테스트(test 프로필의 유효한 CORS_ALLOWED_ORIGINS로
    // 매번 기동)가 실질적으로 검증해준다.
}
