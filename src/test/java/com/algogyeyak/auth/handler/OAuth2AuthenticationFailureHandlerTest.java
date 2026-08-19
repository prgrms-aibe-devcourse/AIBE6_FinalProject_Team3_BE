package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OAuth2AuthenticationFailureHandlerTest {

    private final CookieAuthorizationRequestRepository authorizationRequestRepository =
            mock(CookieAuthorizationRequestRepository.class);
    private final OAuth2AuthenticationFailureHandler handler =
            new OAuth2AuthenticationFailureHandler(authorizationRequestRepository);

    @Test
    void redirectsWithTheSpecificOAuth2ErrorCode() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 이 핸들러는 CustomOAuth2UserService가 만드는 코드가 무엇이든(email_conflict/
        // social_account_conflict 등, 아래 개별 테스트 참고) 그대로 전달해야 프론트가 사유별로
        // 다른 안내를 보여줄 수 있다 - 예전엔 코드와 무관하게 항상 oauth_login_failed로 덮어썼다.
        // 여기서는 그 메커니즘 자체(임의의 코드를 그대로 통과시키는지)만 확인하므로 실제 코드베이스가
        // 지금 쓰는 값과 무관한 예시 문자열을 쓴다 - 실제 존재하는 코드는 아래 개별 테스트가 각각 검증한다.
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("some_domain_specific_code"));

        handler.onAuthenticationFailure(request, response, exception);

        verify(authorizationRequestRepository).removeAuthorizationRequest(request, response);
        assertEquals("https://example.com/login?error=some_domain_specific_code", response.getRedirectedUrl());
    }

    @Test
    void fallsBackToGenericErrorCodeWhenExceptionIsNotAnOAuth2AuthenticationException() throws Exception {
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new BadCredentialsException("some other auth failure");

        handler.onAuthenticationFailure(request, response, exception);

        verify(authorizationRequestRepository).removeAuthorizationRequest(request, response);
        assertEquals("https://example.com/login?error=oauth_login_failed", response.getRedirectedUrl());
    }

    @Test
    void forwardsEmailConflictErrorCodeInsteadOfGenericOne() throws Exception {
        // CustomOAuth2UserService.createUser()의 복구 실패 분기가 만드는 email_conflict도
        // 마찬가지로 화면까지 그대로 전달돼야 한다.
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("email_conflict"));

        handler.onAuthenticationFailure(request, response, exception);

        assertEquals("https://example.com/login?error=email_conflict", response.getRedirectedUrl());
    }

    @Test
    void forwardsSocialAccountConflictErrorCodeInsteadOfGenericOne() throws Exception {
        // CustomOAuth2UserService.linkNewSocialAccount()가 uk_social_user_provider 위반을 감싸
        // 만드는 social_account_conflict도 화면까지 그대로 전달돼야 한다 - 화이트리스트에만 넣고
        // frontend(login/page.tsx)의 ERROR_MESSAGES에 빠뜨린 적이 있어(2026-08-07), 그 회귀를
        // 이 테스트가 잡는다.
        ReflectionTestUtils.setField(handler, "authorizedRedirectUri", "https://example.com/login");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException exception = new OAuth2AuthenticationException(new OAuth2Error("social_account_conflict"));

        handler.onAuthenticationFailure(request, response, exception);

        assertEquals("https://example.com/login?error=social_account_conflict", response.getRedirectedUrl());
    }
}
