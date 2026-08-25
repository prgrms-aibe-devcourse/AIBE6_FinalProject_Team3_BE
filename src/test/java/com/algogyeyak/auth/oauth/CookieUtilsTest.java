package com.algogyeyak.auth.oauth;

import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieUtilsTest {

    private final CookieUtils cookieUtils =
            new CookieUtils(false, "Lax", "", "test-state-signing-key-must-be-at-least-32-bytes");

    @Test
    void serializeThenDeserializeRoundTrips() {
        String serialized = cookieUtils.serialize("hello-world");

        Cookie cookie = new Cookie("test", serialized);
        String result = cookieUtils.deserialize(cookie, String.class);

        assertEquals("hello-world", result);
    }

    @Test
    void tamperedPayloadFailsSignatureCheck() {
        String serialized = cookieUtils.serialize("hello-world");
        String[] parts = serialized.split("\\.", 2);
        // payload를 다른 값으로 바꿔치기 (서명은 원래 값 그대로) — 위변조 시나리오
        String tampered = cookieUtils.serialize("tampered-value").split("\\.", 2)[0] + "." + parts[1];

        Cookie cookie = new Cookie("test", tampered);

        assertThrows(CookieTamperedException.class, () -> cookieUtils.deserialize(cookie, String.class));
    }

    @Test
    void tamperedSignatureFailsSignatureCheck() {
        String serialized = cookieUtils.serialize("hello-world");
        String[] parts = serialized.split("\\.", 2);
        String tampered = parts[0] + ".not-a-real-signature";

        Cookie cookie = new Cookie("test", tampered);

        assertThrows(CookieTamperedException.class, () -> cookieUtils.deserialize(cookie, String.class));
    }

    @Test
    void malformedValueWithoutSeparatorIsRejected() {
        Cookie cookie = new Cookie("test", "no-separator-here");

        assertThrows(CookieTamperedException.class, () -> cookieUtils.deserialize(cookie, String.class));
    }

    @Test
    void differentSigningKeyFailsSignatureCheck() {
        CookieUtils otherCookieUtils =
                new CookieUtils(false, "Lax", "", "different-state-signing-key-also-at-least-32-bytes");
        String serialized = otherCookieUtils.serialize("hello-world");

        Cookie cookie = new Cookie("test", serialized);

        assertThrows(CookieTamperedException.class, () -> cookieUtils.deserialize(cookie, String.class));
    }

    // 회귀 테스트 - 지금까지 이 클래스의 유일한 왕복 테스트가 String만 다뤄서(java.lang.*라
    // 필터 유무와 무관하게 항상 통과), DESERIALIZATION_FILTER의 allowlist 패턴
    // ("org.springframework.security.oauth2.core.**")이 실제 프로덕션 페이로드(OAuth2AuthorizationRequest)의
    // 실제 형태를 admit하는지 한 번도 검증된 적이 없었다.
    @Test
    void serializeThenDeserializeRoundTripsRealOAuth2AuthorizationRequest() {
        OAuth2AuthorizationRequest original = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .clientId("test-client-id")
                .redirectUri("https://example.com/login/oauth2/code/kakao")
                .authorizationRequestUri("https://kauth.kakao.com/oauth/authorize?client_id=test-client-id")
                .state("test-state")
                .build();

        String serialized = cookieUtils.serialize(original);
        Cookie cookie = new Cookie("test", serialized);
        OAuth2AuthorizationRequest result = cookieUtils.deserialize(cookie, OAuth2AuthorizationRequest.class);

        assertEquals(original.getAuthorizationUri(), result.getAuthorizationUri());
        assertEquals(original.getClientId(), result.getClientId());
        assertEquals(original.getRedirectUri(), result.getRedirectUri());
        assertEquals(original.getState(), result.getState());
    }

    // 회귀 테스트 - 이 필터가 존재하는 유일한 이유(역직렬화 가젯 체인을 통한 RCE 방지)를 실제로
    // 검증하는 테스트가 없었다. java.util.concurrent.atomic.AtomicInteger는 Serializable이지만
    // allowlist(java.util.* — 하위 패키지 미포함)에 속하지 않으므로 반드시 거부되어야 한다.
    @Test
    void deserializeRejectsClassNotInAllowlist() {
        String serialized = cookieUtils.serialize(new AtomicInteger(42));
        Cookie cookie = new Cookie("test", serialized);

        assertThrows(CookieTamperedException.class, () -> cookieUtils.deserialize(cookie, AtomicInteger.class));
    }

    @Test
    void addCookieOmitsDomainAttributeWhenNotConfigured() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookieUtils.addCookie(response, "test", "value", 3600);

        String setCookie = response.getHeader("Set-Cookie");
        assertFalse(setCookie.contains("Domain"));
    }

    @Test
    void addCookieIncludesDomainAttributeWhenConfigured() {
        CookieUtils scopedCookieUtils =
                new CookieUtils(false, "Lax", ".localhost", "test-state-signing-key-must-be-at-least-32-bytes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        scopedCookieUtils.addCookie(response, "test", "value", 3600);

        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("Domain=.localhost"));
    }

    @Test
    void constructorRejectsSameSiteNoneWithoutSecure() {
        assertThrows(IllegalStateException.class, () ->
                new CookieUtils(false, "None", "", "test-state-signing-key-must-be-at-least-32-bytes"));
    }

    // 회귀 테스트 - SameSite=Strict는 형식상 유효한 값(Strict/Lax/None 중 하나)이라 위 검증은
    // 통과하지만, OAuth2 인가요청 쿠키가 IdP에서 돌아오는 크로스사이트 리다이렉트에서 브라우저에
    // 의해 거부돼 소셜 로그인이 전부 깨진다 - None+Secure 조합과 동일하게 기동 시점에 막아야 한다.
    @Test
    void constructorRejectsSameSiteStrict() {
        assertThrows(IllegalStateException.class, () ->
                new CookieUtils(true, "Strict", "", "test-state-signing-key-must-be-at-least-32-bytes"));
    }

    @Test
    void constructorRejectsInvalidSameSiteValue() {
        assertThrows(IllegalStateException.class, () ->
                new CookieUtils(true, "NONEE", "", "test-state-signing-key-must-be-at-least-32-bytes"));
    }

    @Test
    void constructorRejectsBlankSameSiteValue() {
        assertThrows(IllegalStateException.class, () ->
                new CookieUtils(false, "  ", "", "test-state-signing-key-must-be-at-least-32-bytes"));
    }

    @Test
    void constructorAllowsSameSiteNoneWithSecure() {
        CookieUtils secureNoneCookieUtils =
                new CookieUtils(true, "None", "", "test-state-signing-key-must-be-at-least-32-bytes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        secureNoneCookieUtils.addCookie(response, "test", "value", 3600);

        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("SameSite=None"));
        assertTrue(setCookie.contains("Secure"));
    }

    // 회귀 테스트 - deleteCookie()가 SameSite를 빼먹으면, 삭제 응답의 Set-Cookie 속성 조합이
    // addCookie()가 발급한 것과 달라진다. SameSite=None 배포에서는 이 속성이 빠진 삭제 응답을
    // 브라우저가 발급 때와 다른 쿠키로 취급하거나 거부할 수 있어, 로그아웃/refresh 발급 실패 후
    // access 쿠키 정리 등에서 stale 쿠키가 남을 수 있었다.
    @Test
    void deleteCookieIncludesSameSameSiteAttributeAsAddCookie() {
        CookieUtils secureNoneCookieUtils =
                new CookieUtils(true, "None", "", "test-state-signing-key-must-be-at-least-32-bytes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        secureNoneCookieUtils.deleteCookie(response, "test");

        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("SameSite=None"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("Max-Age=0"));
    }
}
