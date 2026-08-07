package com.algogyeyak.auth.oauth;

import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

@Component
public class CookieUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    // 이 서비스가 쿠키에 직렬화하는 타입(OAuth2AuthorizationRequest 및 그 구성 요소)만 역직렬화를 허용한다.
    // ObjectInputStream은 바이트를 그대로 읽으므로, 허용 목록 없이는 역직렬화 가젯 체인을 통한
    // 원격 코드 실행에 노출될 수 있다. (서명 검증과는 별개의 방어선)
    private static final ObjectInputFilter DESERIALIZATION_FILTER = ObjectInputFilter.Config.createFilter(
            "org.springframework.security.oauth2.core.**;"
                    + "java.util.*;"
                    + "java.lang.*;"
                    + "!*"
    );

    private final boolean secureCookie;
    private final String sameSite;
    private final String cookieDomain;
    private final SecretKey signingKey;

    public CookieUtils(
            @Value("${app.cookie.secure:false}") boolean secureCookie,
            @Value("${app.cookie.same-site:Lax}") String sameSite,
            @Value("${app.cookie.domain:}") String cookieDomain,
            @Value("${app.oauth2.state-signing-key}") String stateSigningKey) {
        // COOKIE_SAME_SITE 오타/공백("NONEE" 등)이 있으면 ResponseCookie가 이를 검증 없이 그대로
        // Set-Cookie 헤더에 실어 보내는데, 브라우저는 인식 못 하는 SameSite 값을 사양에 따라 각기
        // 다르게(대개 기본값 취급 또는 무시) 처리해 환경별로 쿠키 동작이 애매해진다 - 배포 자체는
        // 성공해버려 원인 파악이 어려우므로, 셋 중 하나가 아니면 기동을 막는다.
        if (!"strict".equalsIgnoreCase(sameSite) && !"lax".equalsIgnoreCase(sameSite) && !"none".equalsIgnoreCase(sameSite)) {
            throw new IllegalStateException(
                    "app.cookie.same-site must be one of Strict/Lax/None (got: \"" + sameSite + "\")");
        }
        // 브라우저는 SameSite=None인데 Secure가 없는 쿠키는 아예 거부한다 — 이 조합으로 기동되면
        // access/refresh 쿠키가 Set-Cookie로는 내려가지만 브라우저가 조용히 버려서 로그인이
        // 전부 깨지는데, 그 원인이 겉으로 드러나지 않는다. 배포 시 COOKIE_SECURE를 함께 켜는 걸
        // 잊었을 때 이 상태로 조용히 뜨는 대신 기동 자체를 막는다.
        if ("none".equalsIgnoreCase(sameSite) && !secureCookie) {
            throw new IllegalStateException(
                    "app.cookie.same-site=None requires app.cookie.secure=true (browsers reject SameSite=None cookies without Secure)");
        }
        this.secureCookie = secureCookie;
        this.sameSite = sameSite;
        this.cookieDomain = cookieDomain;
        // JwtProvider와 동일하게, HS256에 필요한 최소 키 길이(32바이트)를 만족하지 않으면
        // 여기서 바로 기동 실패(WeakKeyException)하도록 한다 — 조용히 약한 서명 키로 뜨는 것을 방지.
        this.signingKey = Keys.hmacShaKeyFor(stateSigningKey.getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<Cookie> getCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (cookie.getName().equals(name)) {
                return Optional.of(cookie);
            }
        }
        return Optional.empty();
    }

    public void addCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path("/")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .maxAge(maxAgeSeconds);
        applyDomain(builder);
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    // 쿠키 삭제도 결국 Set-Cookie 응답이다 - 저장할 때와 동일한 path/domain은 물론 secure/sameSite도
    // 맞춰야 한다. 특히 SameSite=None(크로스오리진 배포)에서 이 속성이 빠지면 브라우저가 기본값
    // Lax/Strict로 취급해 삭제 응답 자체를 다른 쿠키로 보거나 거부할 수 있어, 로그아웃/refresh
    // 발급 실패 후 access 쿠키 정리 등에서 stale 쿠키가 브라우저에 그대로 남을 수 있다.
    public void deleteCookie(HttpServletResponse response, String name) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, "")
                .path("/")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .maxAge(0);
        applyDomain(builder);
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    // domain이 비어있으면(로컬 개발 등) 아예 지정하지 않아 host-only 쿠키로 남긴다 —
    // 프론트/백엔드를 커스텀 서브도메인(app.example.com/api.example.com)으로 배포할 때만
    // COOKIE_DOMAIN=.example.com 형태로 설정해 서브도메인 간 쿠키 공유를 켠다.
    private void applyDomain(ResponseCookie.ResponseCookieBuilder builder) {
        if (StringUtils.hasText(cookieDomain)) {
            builder.domain(cookieDomain);
        }
    }

    /**
     * 객체를 직렬화한 뒤 HMAC 서명을 붙여 {@code payload.signature} 형태의 쿠키 값으로 인코딩한다.
     * 서명이 있어야 클라이언트가 쿠키 값을 조작해도(state 고정 등) 감지할 수 있다.
     */
    public String serialize(Serializable object) {
        byte[] payload = toBytes(object);
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        String signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        return payloadB64 + "." + signatureB64;
    }

    /**
     * 서명을 검증한 뒤 역직렬화한다. 쿠키가 손상되었거나 서명이 일치하지 않으면
     * {@link CookieTamperedException}을 던진다 — 호출부는 이를 "저장된 값 없음"으로 처리해야 한다.
     */
    public <T> T deserialize(Cookie cookie, Class<T> cls) {
        String[] parts = cookie.getValue().split("\\.", 2);
        if (parts.length != 2) {
            throw new CookieTamperedException("Malformed signed cookie: " + cookie.getName());
        }

        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[0]);
            signature = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new CookieTamperedException("Malformed signed cookie: " + cookie.getName(), e);
        }

        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw new CookieTamperedException("Signature mismatch for cookie: " + cookie.getName());
        }

        try (ObjectInputStream inputStream = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            inputStream.setObjectInputFilter(DESERIALIZATION_FILTER);
            return cls.cast(inputStream.readObject());
        } catch (IOException | ClassNotFoundException e) {
            throw new CookieTamperedException("Failed to deserialize cookie: " + cookie.getName(), e);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(signingKey);
            return mac.doFinal(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to sign cookie payload", e);
        }
    }

    private static byte[] toBytes(Serializable object) {
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             ObjectOutputStream objectStream = new ObjectOutputStream(byteStream)) {
            objectStream.writeObject(object);
            return byteStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize object for cookie", e);
        }
    }
}
