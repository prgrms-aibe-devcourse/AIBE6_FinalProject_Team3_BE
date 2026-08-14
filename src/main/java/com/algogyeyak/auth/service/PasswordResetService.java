package com.algogyeyak.auth.service;

import com.algogyeyak.auth.token.RefreshTokenService;
import com.algogyeyak.auth.util.EmailNormalizer;
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
import org.springframework.mail.MailException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * 로그아웃 상태의 비밀번호 재설정("비밀번호를 잊으셨나요?"). RefreshTokenService와 동일한 패턴 —
 * 원문 토큰은 저장하지 않고 SHA-256 해시만 Redis에 저장하며, 유저당 최신 토큰 하나만 유효하다
 * (재요청 시 이전 토큰은 즉시 무효화).
 *
 * <p>이메일 존재 여부를 노출하지 않기 위해 {@link #requestReset}은 계정이 없거나 소셜 전용 계정이어도
 * 항상 같은 방식으로(예외 없이) 리턴한다 - 실제 메일 발송 여부와 무관하게 컨트롤러는 항상 동일한
 * 성공 응답을 내려준다.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String BY_HASH_KEY_PREFIX = "auth:password-reset:by-hash:";
    private static final String BY_USER_KEY_PREFIX = "auth:password-reset:by-user:";
    private static final String COOLDOWN_KEY_PREFIX = "auth:password-reset:cooldown:";

    // RefreshTokenService.ISSUE_SCRIPT와 동일한 구조 - 이전 세션(by-user가 가리키던 hash)의 by-hash를
    // 지우고 새 by-hash/by-user를 같은 스크립트 안에서 원자적으로 갱신한다.
    private static final RedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>("""
            local oldHash = redis.call('GET', KEYS[1])
            if oldHash then
              redis.call('DEL', '%s' .. oldHash)
            end
            redis.call('SET', '%s' .. ARGV[1], ARGV[3], 'EX', ARGV[2])
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
            """.formatted(BY_HASH_KEY_PREFIX, BY_HASH_KEY_PREFIX), Long.class);

    // 제시된 토큰을 원자적으로 소비(GET+DEL)한다 - by-user가 지금도 이 토큰을 가리키고 있으면 그
    // 역인덱스도 함께 지운다(이미 회전되어 최신이 아니면 손대지 않는다 - RefreshTokenService.REVOKE_SCRIPT
    // 참고).
    private static final RedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
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
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    @Value("${app.password-reset.token-validity-seconds}")
    private long tokenValiditySeconds;

    @Value("${app.password-reset.request-cooldown-seconds}")
    private long requestCooldownSeconds;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    public PasswordResetService(
            StringRedisTemplate redisTemplate,
            UserRepository userRepository,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService) {
        this.redisTemplate = redisTemplate;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
    }

    public void requestReset(String email) {
        String normalizedEmail = EmailNormalizer.normalize(email);

        // 계정 존재 여부와 무관하게 동일한 쿨다운을 적용한다 - 존재하는 이메일만 쿨다운에 걸린다면
        // 그 자체로 계정 존재 여부가 새어나간다.
        String cooldownKey = COOLDOWN_KEY_PREFIX + normalizedEmail;
        Boolean cooldownSet;
        try {
            cooldownSet = redisTemplate.opsForValue()
                    .setIfAbsent(cooldownKey, "1", Duration.ofSeconds(requestCooldownSeconds));
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }
        if (!Boolean.TRUE.equals(cooldownSet)) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_TOO_MANY_REQUESTS);
        }

        Optional<User> eligibleUser = userRepository.findByEmail(normalizedEmail)
                .filter(user -> user.getPasswordHash() != null)
                .filter(user -> !user.isWithdrawn() && !user.isSuspended());
        if (eligibleUser.isEmpty()) {
            // 소셜 전용 계정/탈퇴·정지 계정/존재하지 않는 이메일 - 아무 것도 하지 않고 조용히
            // 리턴한다(design decision: "이미 비밀번호가 있었던 계정" 전용으로 재설정 플로우를 좁힌다,
            // 2026-07-24-password-reset-design.md 결정 필요 사항 5).
            return;
        }
        User user = eligibleUser.get();

        String rawToken = generateRawToken();
        String tokenHash = hash(rawToken);
        String userId = String.valueOf(user.getId());
        try {
            redisTemplate.execute(ISSUE_SCRIPT, List.of(byUserKey(userId)),
                    tokenHash, String.valueOf(tokenValiditySeconds), userId);
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }

        String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
        try {
            emailService.sendPasswordResetLink(normalizedEmail, resetLink);
        } catch (MailException e) {
            log.error("비밀번호 재설정 메일 발송 실패 email={}", normalizedEmail, e);
            throw new BusinessException(ErrorCode.EMAIL_SEND_FAILED);
        }
    }

    @Transactional
    public void confirmReset(String rawToken, String newPassword) {
        String tokenHash = hash(rawToken);

        String userId;
        try {
            userId = redisTemplate.execute(CONSUME_SCRIPT, List.of(byHashKey(tokenHash)), tokenHash);
        } catch (DataAccessException e) {
            throw redisUnavailable(e);
        }
        if (userId == null) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID);
        }

        User user = userRepository.findById(Long.valueOf(userId))
                .filter(found -> !found.isWithdrawn() && !found.isSuspended())
                .filter(found -> found.getPasswordHash() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID));

        user.updatePasswordHash(passwordEncoder.encode(newPassword));

        // 재설정 성공 - 탈취된 세션이 있었을 수 있으므로 기존 refresh token(전체 세션)을 끊어낸다.
        refreshTokenService.revokeAllForUser(user.getId());
    }

    private static String byHashKey(String tokenHash) {
        return BY_HASH_KEY_PREFIX + tokenHash;
    }

    private static String byUserKey(String userId) {
        return BY_USER_KEY_PREFIX + userId;
    }

    private static BusinessException redisUnavailable(DataAccessException cause) {
        log.error("Redis 장애로 비밀번호 재설정 토큰 처리 실패", cause);
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
}
