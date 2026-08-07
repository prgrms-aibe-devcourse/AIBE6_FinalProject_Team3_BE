package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final String GENERIC_ERROR_CODE = "oauth_login_failed";

    // CustomOAuth2UserService가 계정 상태/이메일·소셜 계정 충돌별로 만들어두는 구체적인
    // OAuth2Error 코드 - frontend(login/page.tsx의 ERROR_MESSAGES)가 이 코드들에 맞는 안내
    // 문구를 이미 준비해뒀으므로, 이 코드들만 그대로 통과시킨다. Spring Security 자체가 만드는
    // 임의의 OAuth2 스펙 코드(access_denied 등)까지 그대로 노출하면 우리가 의도하지 않은 문구
    // 없는 코드가 화면에 그대로 남을 수 있어, 화이트리스트에 없는 코드는 계속 generic으로 뭉갠다.
    private static final Set<String> DOMAIN_SPECIFIC_ERROR_CODES =
            Set.of("account_blocked", "email_conflict", "social_account_conflict");

    private final CookieAuthorizationRequestRepository authorizationRequestRepository;

    @Value("${app.oauth2.authorized-redirect-uri}")
    private String authorizedRedirectUri;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.warn("OAuth2 login failed", exception);

        authorizationRequestRepository.removeAuthorizationRequest(request, response);

        String targetUrl = UriComponentsBuilder.fromUriString(authorizedRedirectUri)
                .queryParam("error", resolveErrorCode(exception))
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private String resolveErrorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauthException) {
            String code = oauthException.getError().getErrorCode();
            if (DOMAIN_SPECIFIC_ERROR_CODES.contains(code)) {
                return code;
            }
        }
        return GENERIC_ERROR_CODE;
    }
}
