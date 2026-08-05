package com.algogyeyak.auth.oauth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

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
}
