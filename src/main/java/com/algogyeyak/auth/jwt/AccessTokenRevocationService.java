package com.algogyeyak.auth.jwt;

import com.algogyeyak.global.error.ErrorCode;
import com.algogyeyak.global.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 로그아웃된 access token(jti)을 자연 만료 시각까지만 블랙리스트로 들고 있는다. Redis 키의 TTL을
 * 만료 시각과 똑같이 맞춰두면 그 시점에 키가 자동으로 사라지므로, 예전 DB 버전처럼 별도 청소
 * 로직(deleteByExpiresAtBefore)이 필요 없다.
 */
@Service
public class AccessTokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenRevocationService.class);
    private static final String KEY_PREFIX = "auth:revoked-access-token:";
    private static final String REVOKED_MARKER = "1";

    private final StringRedisTemplate redisTemplate;

    public AccessTokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void revoke(String jti, LocalDateTime expiresAt) {
        if (jti == null) {
            return;
        }

        Duration ttl = Duration.between(LocalDateTime.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            // 이미 만료된 access token은 블랙리스트에 올릴 이유가 없다 — 만료 검증에서 이미 걸러진다.
            return;
        }

        try {
            redisTemplate.opsForValue().set(key(jti), REVOKED_MARKER, ttl);
        } catch (DataAccessException e) {
            // 로그아웃 자체를 fail-closed로 실패시킨다 — 여기서 조용히 넘어가면 클라이언트는
            // "로그아웃 성공"으로 알지만 실제로는 access token이 남은 유효기간 동안 계속 살아있게 된다.
            throw redisUnavailable(e);
        }
    }

    // JwtAuthenticationFilter는 예외를 던지지 않는 설계이므로(클래스 주석 참고), 여기서도 절대
    // 예외를 던지지 않는다 — Redis 장애 시에는 "무효화된 것으로 간주"(true)해 인증을 거부하는
    // fail-closed로 처리한다. 즉, 이 메서드가 false를 반환한다는 것은 "블랙리스트에 없음이 확인됨"을
    // 뜻하지 "확인이 안 됐지만 일단 통과"를 뜻하지 않는다.
    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
        } catch (DataAccessException e) {
            log.error("Redis 장애로 access token 블랙리스트 확인 불가 — fail-closed로 인증을 거부합니다. jti={}", jti, e);
            return true;
        }
    }

    private static String key(String jti) {
        return KEY_PREFIX + jti;
    }

    private static BusinessException redisUnavailable(DataAccessException cause) {
        log.error("Redis 장애로 access token revoke 실패", cause);
        return new BusinessException(ErrorCode.AUTH_TOKEN_STORE_UNAVAILABLE);
    }
}
