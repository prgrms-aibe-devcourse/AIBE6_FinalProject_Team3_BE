package com.algogyeyak.auth.handler;

import com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
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

        String targetUrl = UriComponentsBuilder.fromUriString(authorizedRedirectUri)
                .queryParam("error", GENERIC_ERROR_CODE)
                .build().toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
