package com.algogyeyak.auth.token;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Refresh Token은 Redis에 유저당 1개(단일 세션)만 유지한다. 원문 토큰은 저장하지 않고 SHA-256 해시만
 * 저장하며, 매 회전(rotate)마다 새 값으로 덮어써 이전 토큰은 즉시 무효화된다.
 *
 * <p>키 두 개를 함께 유지한다 — {@code by-hash:{tokenHash}} → userId(정방향 검증용), {@code
 * by-user:{userId}} → tokenHash(재로그인 시 이전 세션을 즉시 무효화하기 위한 역인덱스). 두 키의 TTL은
 * 항상 refresh token의 남은 유효기간과 같게 맞춰 Redis가 자연 만료를 자동으로 처리하게 한다 — 그래서
 * DB 버전에 있던 {@code expiresAt} 컬럼/수동 만료 검사가 필요 없다. 그 대신, 자연 만료(evict)와
 * "애초에 모르는 토큰"이 둘 다 "키 없음"으로만 관측되어 더 이상 구분되지 않는다 — AUTH_REFRESH_TOKEN_EXPIRED는
 * 이제 던져지지 않고 둘 다 AUTH_REFRESH_TOKEN_INVALID로 처리한다(frontend는 애초에 이 둘을 구분하지 않았다).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String BY_HASH_KEY_PREFIX = "auth:refresh-token:by-hash:";
    private static final String BY_USER_KEY_PREFIX = "auth:refresh-token:by-user:";

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;

    @Value("${app.jwt.refresh-token-validity-seconds}")
    private long refreshTokenValiditySeconds;

    public RefreshTokenService(StringRedisTemplate redisTemplate, UserRepository userRepository) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
    }

    public long getValiditySeconds() {
        return refreshTokenValiditySeconds;
    }

    public String issue(User user) {
        String rawToken = generateRawToken();
        String tokenHash = hash(rawToken);
        Duration ttl = Duration.ofSeconds(refreshTokenValiditySeconds);
        String userId = String.valueOf(user.getId());

        try {
            String oldHash = redisTemplate.opsForValue().get(byUserKey(userId));
            if (oldHash != null) {
                // 재로그인/재발급 시 이전 세션을 즉시 무효화한다 — 안 지우면 이전 raw token이
                // 자기 TTL이 다 될 때까지(최대 refreshTokenValiditySeconds) 계속 유효하게 남는다.
                redisTemplate.delete(byHashKey(oldHash));
            }
            redisTemplate.opsForValue().set(byHashKey(tokenHash), userId, ttl);
            redisTemplate.opsForValue().set(byUserKey(userId), tokenHash, ttl);
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        return rawToken;
    }

    public RotationResult rotate(String rawToken) {
        String tokenHash = hash(rawToken);
        String userId;
        try {
            // getAndDelete는 원자적이라, 같은 raw token으로 동시에 rotate()가 들어와도 정확히 하나만
            // 성공하고 나머지는 즉시 실패한다 — 예전 DB의 PESSIMISTIC_WRITE 락과 같은 역할을 한다.
            userId = redisTemplate.opsForValue().getAndDelete(byHashKey(tokenHash));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        User user = userRepository.findById(Long.valueOf(userId)).orElse(null);
        if (user == null || user.isWithdrawn()) {
            deleteByUserKey(userId);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "존재하지 않거나 탈퇴한 사용자입니다.");
        }

        String newRawToken = generateRawToken();
        String newHash = hash(newRawToken);
        Duration ttl = Duration.ofSeconds(refreshTokenValiditySeconds);
        try {
            redisTemplate.opsForValue().set(byHashKey(newHash), userId, ttl);
            redisTemplate.opsForValue().set(byUserKey(userId), newHash, ttl);
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        return new RotationResult(user, newRawToken);
    }

    public void revoke(String rawToken) {
        String tokenHash = hash(rawToken);
        try {
            String userId = redisTemplate.opsForValue().getAndDelete(byHashKey(tokenHash));
            if (userId != null) {
                // by-hash가 이 hash로 살아있었다는 것 자체가 by-user가 정확히 같은 hash를 가리키고
                // 있었다는 뜻이다(issue/rotate가 항상 두 키를 함께 갱신) — 그래서 값을 다시 비교하지
                // 않고 바로 지워도 안전하다.
                redisTemplate.delete(byUserKey(userId));
            }
        } catch (DataAccessException e) {
            // 로그아웃을 fail-closed로 실패시킨다 — 여기서 조용히 넘어가면 클라이언트는 로그아웃에
            // 성공한 줄 알지만 refresh token은 실제로는 계속 살아있게 된다.
            throw redisUnavailable(e);
        }
    }

    private void deleteByUserKey(String userId) {
        try {
            redisTemplate.delete(byUserKey(userId));
        } catch (DataAccessException e) {
            log.warn("탈퇴/미존재 사용자의 refresh token 역인덱스 정리 실패 (TTL로 자연 정리됨) userId={}", userId, e);
        }
    }

    private static String byHashKey(String tokenHash) {
        return BY_HASH_KEY_PREFIX + tokenHash;
    }

    private static String byUserKey(String userId) {
        return BY_USER_KEY_PREFIX + userId;
    }

    private static BusinessException redisUnavailable(DataAccessException cause) {
        log.error("Redis 장애로 refresh token 처리 실패", cause);
        return new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE);
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " algorithm not available", e);
        }
    }

    public record RotationResult(User user, String rawToken) {
    }
}
