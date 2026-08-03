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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

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
 *
 * <p>두 키를 읽고/지우고/쓰는 시퀀스는 전부 Lua script(EVAL)로 묶어 원자적으로 실행한다 — GET 다음에
 * SET을 별도 명령으로 보내면 그 사이에 다른 요청이 끼어들 수 있다(예: issue()를 동시에 두 번 호출하면
 * 둘 다 같은 이전 hash를 읽고 각자 새 by-hash를 만들어, "유저당 1개"가 깨지고 두 raw token이 동시에
 * 살아있게 된다). Redis는 스크립트 실행 중에는 다른 명령을 끼워넣지 않으므로, 스크립트 하나 = 원자적
 * 트랜잭션이 된다.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String BY_HASH_KEY_PREFIX = "auth:refresh-token:by-hash:";
    private static final String BY_USER_KEY_PREFIX = "auth:refresh-token:by-user:";

    // KEYS[1] = by-user:{userId}, ARGV[1] = newHash, ARGV[2] = ttlSeconds, ARGV[3] = userId.
    // 이전 세션(by-user가 가리키던 hash)의 by-hash를 지우고 새 by-hash/by-user를 같은 스크립트
    // 안에서 갱신한다 — 그래야 동시 issue() 두 번이 각자 다른 by-hash를 살려두는 일이 없다.
    private static final RedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
            local oldHash = redis.call('GET', KEYS[1])
            if oldHash then
              redis.call('DEL', '%s' .. oldHash)
            end
            redis.call('SET', '%s' .. ARGV[1], ARGV[3], 'EX', ARGV[2])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
            """.formatted(BY_HASH_KEY_PREFIX, BY_HASH_KEY_PREFIX), Long.class);

    // KEYS[1] = by-hash:{presentedHash}, ARGV[1] = newHash, ARGV[2] = ttlSeconds.
    // 제시된 토큰의 by-hash를 원자적으로 소비(GET+DEL)하고, 성공했을 때만 새 by-hash/by-user를
    // 같은 스크립트 안에서 씀 — 소비와 재발급 사이에 다른 요청이 끼어들 틈이 없다.
    private static final RedisScript<String> ROTATE_SCRIPT = new DefaultRedisScript<>("""
            local userId = redis.call('GET', KEYS[1])
            if not userId then
              return nil
            end
            redis.call('DEL', KEYS[1])
            redis.call('SET', '%s' .. ARGV[1], userId, 'EX', ARGV[2])
            redis.call('SET', '%s' .. userId, ARGV[1], 'EX', ARGV[2])
            return userId
            """.formatted(BY_HASH_KEY_PREFIX, BY_USER_KEY_PREFIX), String.class);

    // KEYS[1] = by-hash:{presentedHash}, ARGV[1] = presentedHash.
    // by-user의 현재 값이 지금 소비한 hash와 같을 때만 지운다 — 이미 회전되어 더 최신 세션을
    // 가리키고 있는 by-user를, 오래된(rotate로 이미 무효화됐어야 할) 토큰의 revoke가 잘못 지우는
    // 것을 막는다.
    private static final RedisScript<String> REVOKE_SCRIPT = new DefaultRedisScript<>("""
            local userId = redis.call('GET', KEYS[1])
            if not userId then
              return nil
            end
            redis.call('DEL', KEYS[1])
            local byUserKey = '%s' .. userId
            if redis.call('GET', byUserKey) == ARGV[1] then
              redis.call('DEL', byUserKey)
            end
            return userId
            """.formatted(BY_USER_KEY_PREFIX), String.class);

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
        String userId = String.valueOf(user.getId());

        try {
            redisTemplate.execute(ISSUE_SCRIPT, List.of(byUserKey(userId)),
                    tokenHash, String.valueOf(refreshTokenValiditySeconds), userId);
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        return rawToken;
    }

    public RotationResult rotate(String rawToken) {
        String tokenHash = hash(rawToken);
        String newRawToken = generateRawToken();
        String newHash = hash(newRawToken);

        String userId;
        try {
            // ROTATE_SCRIPT가 "소비 + 재발급"을 하나의 원자적 스크립트로 처리한다 - 예전 DB의
            // PESSIMISTIC_WRITE 락과 같은 역할을 한다(같은 raw token으로 동시에 rotate()가 들어와도
            // 정확히 하나만 성공).
            userId = redisTemplate.execute(ROTATE_SCRIPT, List.of(byHashKey(tokenHash)),
                    newHash, String.valueOf(refreshTokenValiditySeconds));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID);
        }

        User user = userRepository.findById(Long.valueOf(userId)).orElse(null);
        if (user == null || user.isWithdrawn() || user.isSuspended()) {
            deleteByUserKey(userId);
            throw new BusinessException(ErrorCode.AUTH_REFRESH_TOKEN_INVALID, "존재하지 않거나 탈퇴한 사용자입니다.");
        }

        return new RotationResult(user, newRawToken);
    }

    public void revoke(String rawToken) {
        String tokenHash = hash(rawToken);
        try {
            redisTemplate.execute(REVOKE_SCRIPT, List.of(byHashKey(tokenHash)), tokenHash);
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
