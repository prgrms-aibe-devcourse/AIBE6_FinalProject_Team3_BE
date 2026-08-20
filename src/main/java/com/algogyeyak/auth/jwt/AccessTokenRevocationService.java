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
            // 이 클래스의 나머지 설계는 철저히 fail-closed(Redis 장애 시 로그아웃 자체를 실패시킴)인데,
            // jti가 없는 경우만 예외적으로 무음 fail-open이었다 - 로그아웃은 여전히 성공해야 하지만
            // (jti 없는 access token은 애초에 블랙리스트에 올릴 수단이 없으므로 실패시킬 이유가
            // 없음), 이 access token이 결국 자연 만료 전까지 계속 유효하게 남는다는 사실 자체는
            // 운영에서 관측 가능해야 한다(2026-08-20 전수조사에서 지적 - 롤링 배포 중 jti 없는
            // 구버전 토큰이 섞이는 좁은 창에서만 발생).
            log.warn("access token에 jti가 없어 블랙리스트에 등록할 수 없습니다 - 이 토큰은 자연 만료 전까지 계속 유효합니다");
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

    // Redis 장애 시에는 fail-closed로 인증을 거부해야 하지만(무효화 여부를 확신할 수 없으므로
    // "유효하다"로 오인하면 안 됨), 예전엔 그 장애를 "블랙리스트에 있음"(true)과 똑같이 취급했다 -
    // 그러면 JwtAuthenticationFilter가 이 결과를 AUTH_TOKEN_INVALID(401)로만 처리해, Redis가
    // 잠깐 다운된 것뿐인데 사용자에게는 "로그아웃됨"으로 보였다(revoke()/DB 조회 실패는 이미
    // AUTH_TOKEN_STORE_UNAVAILABLE(503)로 구분해 응답하는데 이것만 어긋나 있었다). 장애는 예외로
    // 던져 호출부가 진짜 블랙리스트 여부와 구분해 503으로 응답할 수 있게 한다.
    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(jti)));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
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
