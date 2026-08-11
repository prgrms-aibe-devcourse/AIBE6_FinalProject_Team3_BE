package com.algogyeyak.auth.controller;

import com.algogyeyak.auth.jwt.JwtProvider;
import com.algogyeyak.auth.oauth.CookieUtils;
import com.algogyeyak.auth.service.LocalAuthService;
import com.algogyeyak.auth.service.SessionLogoutService;
import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// AuthControllerTest의 startupFailsWhenDevLoginEnabledWithoutSecret()은 이미 뜬 컨텍스트에서
// AuthController.validateDevLoginConfig()를 reflection으로 직접 호출한다 - 그 방식은 메서드 자체의
// 로직만 검증할 뿐, "@PostConstruct가 실제로 붙어 있어서 Spring이 기동 시점에 이 메서드를 호출한다"는
// 계약은 증명하지 못한다(애너테이션이 실수로 빠져도 그 테스트는 여전히 통과한다). 이 테스트는
// ApplicationContextRunner로 실제 컨텍스트를 새로 띄워봐서 그 계약 자체를 검증한다. Redis/Testcontainers
// 없이도 돌아가도록 AuthController의 나머지 의존성은 전부 mock으로 대체한다.
class AuthControllerDevLoginStartupTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(AuthController.class, () -> new AuthController(
                    mock(CookieUtils.class),
                    mock(UserRepository.class),
                    mock(JwtProvider.class),
                    mock(RefreshTokenService.class),
                    mock(LocalAuthService.class),
                    mock(SessionLogoutService.class)));

    @Test
    void failsToStartWhenDevLoginEnabledWithoutSecret() {
        contextRunner
                .withPropertyValues(
                        "app.dev-login.enabled=true",
                        "app.dev-login.email=admin@algogyeyak.local",
                        "app.dev-login.secret=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWhenDevLoginEnabledWithSecret() {
        contextRunner
                .withPropertyValues(
                        "app.dev-login.enabled=true",
                        "app.dev-login.email=admin@algogyeyak.local",
                        "app.dev-login.secret=some-secret")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void startsWhenDevLoginDisabledEvenWithoutSecret() {
        contextRunner
                .withPropertyValues(
                        "app.dev-login.enabled=false",
                        "app.dev-login.email=admin@algogyeyak.local",
                        "app.dev-login.secret=")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
