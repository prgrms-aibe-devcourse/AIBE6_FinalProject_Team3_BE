package com.algogyeyak.auth.jwt;

import com.algogyeyak.user.entity.User;
import com.algogyeyak.user.enums.Role;
import com.algogyeyak.user.enums.UserStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link JwtAuthenticationFilter}가 매 요청 DB에서 다시 읽던 User 상태(role/status/닉네임 등)를
 * 짧은 TTL로 Redis에 캐싱한다 - 인증된 요청마다 Redis(revocation 조회) + DB(User 조회)가 각각 한 번씩
 * 나가던 것을, 캐시 적중 시 DB 조회 없이 끝나도록 줄인다.
 *
 * <p>이 캐시는 순수 성능 최적화이므로 {@link AccessTokenRevocationService}(fail-closed)와 다르게
 * 의도적으로 fail-open이다 - 읽기/쓰기 중 Redis 장애가 나도 예외를 던지지 않고 그냥 DB를 그대로
 * 쓰게 둔다. 캐시가 없어도 인증 자체는 항상 DB로 완결될 수 있어야 하기 때문이다.
 *
 * <p>role/status/비밀번호를 바꾸는 쪽(AdminUserService.updateRole/updateStatus,
 * UserService.withdraw, LocalAuthService.setPassword, PasswordResetService.confirmReset)이
 * 성공 후 {@link #evictAfterCommit}으로 이 캐시를 직접 지운다 - 그래서 TTL 30초는 "이 무효화
 * 호출조차 실패했을 때의 최후 보루"이지, 정상 경로에서 매번 기다려야 하는 지연이 아니다.
 */
@Service
public class UserAuthStatusCacheService {

    private static final Logger log = LoggerFactory.getLogger(UserAuthStatusCacheService.class);
    private static final String KEY_PREFIX = "auth:user-status:";
    private static final Duration TTL = Duration.ofSeconds(30);

    // 이 클래스 내부에서만 쓰는 단순 직렬화 용도라 SecurityConfig와 동일한 이유로 별도 인스턴스를 둔다.
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final StringRedisTemplate redisTemplate;

    public UserAuthStatusCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<CachedUserStatus> find(Long userId) {
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(key(userId));
        } catch (DataAccessException e) {
            log.warn("user-status 캐시 조회 실패 - DB로 폴백합니다. userId={}", userId, e);
            return Optional.empty();
        }

        if (raw == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(raw, CachedUserStatus.class));
        } catch (JsonProcessingException e) {
            log.warn("user-status 캐시 역직렬화 실패 - DB로 폴백합니다. userId={}", userId, e);
            return Optional.empty();
        }
    }

    public void save(Long userId, User user) {
        CachedUserStatus status = CachedUserStatus.from(user);
        try {
            redisTemplate.opsForValue().set(key(userId), objectMapper.writeValueAsString(status), TTL);
        } catch (DataAccessException | JsonProcessingException e) {
            // 캐시 저장 실패는 다음 요청이 그냥 DB를 다시 읽는 것으로 자연히 만회되므로 조용히 넘어간다.
            log.warn("user-status 캐시 저장 실패 - 무시하고 계속합니다. userId={}", userId, e);
        }
    }

    /**
     * userId 캐시를 지운다. 트랜잭션이 진행 중이면(호출부가 전부 @Transactional 메서드다)
     * DB commit 이후로 미룬다 - commit 전에 지우면, 그 사이 들어온 다른 요청이 캐시 미스로
     * DB를 다시 읽으면서 아직 커밋 전인 이전(stale) 값을 읽어 그대로 재캐싱해버릴 수 있다
     * (MySQL REPEATABLE READ에서 이 경합이 실제로 재현된다 - 결과적으로 캐시를 지운 의미가
     * 없어지고 오히려 무효화 시도가 stale 값의 TTL을 그 시점부터 다시 30초 연장하는 역효과가
     * 난다). 트랜잭션이 없으면(방어적 폴백) 즉시 지운다.
     */
    public void evictAfterCommit(Long userId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(userId);
                }
            });
        } else {
            evict(userId);
        }
    }

    private void evict(Long userId) {
        try {
            redisTemplate.delete(key(userId));
        } catch (DataAccessException e) {
            // 무효화 실패는 fail-open이다 - 최악의 경우 다음 요청까지 최대 30초(TTL) 동안만
            // 이전 상태가 보인다. revokeAllForUser()의 best-effort 정리와 같은 성격이다.
            log.warn("user-status 캐시 무효화 실패 - TTL(30초)로 자연 만료됩니다. userId={}", userId, e);
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    public record CachedUserStatus(
            String email,
            Role role,
            UserStatus status,
            String nickname,
            String profileImageUrl,
            LocalDateTime passwordChangedAt) {

        public static CachedUserStatus from(User user) {
            return new CachedUserStatus(
                    user.getEmail(),
                    user.getRole(),
                    user.getStatus(),
                    user.getNickname(),
                    user.getProfileImageUrl(),
                    user.getPasswordChangedAt());
        }

        // record 컴포넌트가 아닌 파생 메서드지만 isXxx() 패턴 때문에 Jackson이 기본적으로
        // "withdrawn"/"suspended" 필드로 함께 직렬화해버려, 캐노니컬 생성자에 없는 그 필드를
        // 역직렬화 시점에 UnrecognizedPropertyException으로 거부하는 문제가 있었다 - 직렬화
        // 대상에서 명시적으로 제외한다.
        @JsonIgnore
        public boolean isWithdrawn() {
            return status == UserStatus.WITHDRAWN;
        }

        @JsonIgnore
        public boolean isSuspended() {
            return status == UserStatus.SUSPENDED;
        }
    }
}
