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

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final String GENERIC_ERROR_CODE = "oauth_login_failed";

    private final CookieAuthorizationRequestRepository authorizationRequestRepository;

    @Value("${app.oauth2.authorized-redirect-uri}")
    private String authorizedRedirectUri;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        log.warn("OAuth2 login failed", exception);

        authorizationRequestRepository.removeAuthorizationRequest(request, response);

        // CustomOAuth2UserService가 email_conflict/social_account_conflict처럼 구체적인 OAuth2Error
        // 코드를 이미 만들어서 던지는데, 여기서 항상 GENERIC_ERROR_CODE로 덮어써버리면 닉네임/이메일
        // 충돌이나 진짜 알 수 없는 오류나 프론트에서 전부 똑같은 문구로 보였다. 실제 코드가 있으면
        // 그대로 전달하고, Spring Security 자체 오류(제공자 응답 거부 등 OAuth2AuthenticationException이
        // 아닌 경우) 등 코드가 없을 때만 일반 코드로 대체한다. (참고: 정지/탈퇴 계정은 예외로,
        // CustomOAuth2UserService.rejectIfBlocked()가 계정 존재 비노출을 위해 이 GENERIC_ERROR_CODE와
        // 같은 값("oauth_login_failed")을 의도적으로 던진다 - 코드가 있어도 구분되지 않는 유일한 케이스)
        String errorCode = exception instanceof OAuth2AuthenticationException oauth2Exception
                ? oauth2Exception.getError().getErrorCode()
                : GENERIC_ERROR_CODE;

        // errorCode는 CustomOAuth2UserService가 내부에서 만든 고정 코드뿐 아니라, provider가
        // OAuth2 콜백(/login/oauth2/code/{registrationId})에 실어 보낸 error 쿼리 파라미터에서도
        // 그대로 올 수 있어(OAuth2LoginAuthenticationFilter) 외부 입력이다 - queryParam(String, Object)
        // 뒤 build().toUriString()은 값을 percent-encode하지 않으므로, 그대로 이어붙이면 리다이렉트
        // URL에 추가 쿼리 파라미터를 주입할 수 있었다. URI 템플릿 변수로 넣고 expand() 이후
        // encode()해서 값만 안전하게 percent-encode한다.
        String targetUrl = UriComponentsBuilder.fromUriString(authorizedRedirectUri)
                .queryParam("error", "{error}")
                .build()
                .expand(errorCode)
                .encode()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
