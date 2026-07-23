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
        addCookie(response, name, value, maxAgeSeconds, "/");
    }

    // Refresh Token처럼 더 민감하고 수명이 긴 쿠키는 path를 좁혀 노출 범위를 줄일 수 있게 오버로드한다.
    public void addCookie(HttpServletResponse response, String name, String value, int maxAgeSeconds, String path) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path(path)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .maxAge(maxAgeSeconds);
        applyDomain(builder);
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    public void deleteCookie(HttpServletResponse response, String name) {
        deleteCookie(response, name, "/");
    }

    // 쿠키 삭제는 저장할 때와 동일한 path/domain으로 Set-Cookie를 내려줘야 브라우저가 실제로 지운다.
    public void deleteCookie(HttpServletResponse response, String name, String path) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, "")
                .path(path)
                .httpOnly(true)
                .secure(secureCookie)
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
