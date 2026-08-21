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

// METRICS_SCRAPE_TOKEN=(빈 값)은 env 자체가 없으면 걸리는 placeholder fail-fast(기본값 없는
// ${METRICS_SCRAPE_TOKEN})를 그대로 통과해버린다 - 값은 있지만 빈 문자열이라 정상적으로 주입되기
// 때문이다. SecurityConfig.validateMetricsScrapeToken()(@PostConstruct)이 그 경우를 실제로
// 잡아내는지, SecurityConfigAllowedOriginsStartupTest와 동일한 방식(ApplicationContextRunner로
// 실제 컨텍스트를 띄워 @PostConstruct가 기동 시점에 호출된다는 계약까지 확인)으로 검증한다.
class SecurityConfigMetricsScrapeTokenStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            // app.cors.allowed-origins는 이 클래스의 다른 @PostConstruct(validateAllowedOrigins)가
            // 검사하는 별개 속성이다 - 여기서 검증하려는 게 metrics-token 하나뿐임을 분명히 하기 위해
            // 유효한 값으로 고정해, 컨텍스트 실패가 오직 metrics-token 때문임을 보장한다.
            .withPropertyValues("app.cors.allowed-origins=http://localhost:3000")
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
    void failsToStartWhenMetricsScrapeTokenIsBlank() {
        contextRunner
                .withPropertyValues("app.metrics.scrape-token=")
                .run(context -> assertThat(context).hasFailed());
    }

    // 정상 케이스(비어있지 않은 토큰)로 기동에 성공하는지는 이 테스트 클래스에서 검증하지 않는다 -
    // SecurityConfigAllowedOriginsStartupTest와 동일한 이유(전체 Spring Security OAuth2 인프라가
    // 필요해 이 가벼운 ApplicationContextRunner로는 갖출 수 없음)로, 정상 기동은 이미 애플리케이션
    // 전체를 띄우는 다른 통합 테스트(test 프로필의 dev 기본 토큰으로 매번 기동)가 실질적으로
    // 검증해준다.
}
